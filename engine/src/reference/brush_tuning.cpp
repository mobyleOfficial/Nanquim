#include "brush_tuning.h"

#include <algorithm>
#include <cmath>
#include <numeric>
#include <stdexcept>

#include "reference_scene.h"
#include "stroke/brush_json.h"
#include "test_sheet.h"

namespace nanquim::reference {

namespace {

nlohmann::json::json_pointer pointerOf(const std::string& path) {
  std::string pointer;
  size_t start = 0;
  while (start <= path.size()) {
    const size_t dot = path.find('.', start);
    pointer += "/" + path.substr(start, dot == std::string::npos ? std::string::npos : dot - start);
    if (dot == std::string::npos) {
      break;
    }
    start = dot + 1;
  }
  return nlohmann::json::json_pointer(pointer);
}

nlohmann::json withValues(const nlohmann::json& document, const std::vector<TunableParameter>& parameters,
                          const std::vector<double>& normalized) {
  nlohmann::json result = document;
  for (size_t index = 0; index < parameters.size(); ++index) {
    const TunableParameter& parameter = parameters[index];
    const double value =
        parameter.minimum + (parameter.maximum - parameter.minimum) * std::clamp(normalized[index], 0.0, 1.0);
    result[pointerOf(parameter.path)] = value;
  }
  return result;
}

std::vector<double> normalizedValues(const nlohmann::json& document, const std::vector<TunableParameter>& parameters) {
  std::vector<double> values;
  for (const TunableParameter& parameter : parameters) {
    double value = parameter.minimum;
    const auto pointer = pointerOf(parameter.path);
    if (document.contains(pointer) && document.at(pointer).is_number()) {
      value = document.at(pointer).get<double>();
    }
    values.push_back(std::clamp((value - parameter.minimum) / (parameter.maximum - parameter.minimum), 0.0, 1.0));
  }
  return values;
}

}  // namespace

std::vector<TunableParameter> standardTunableParameters() {
  return {
      {"path.spacing", 0.01, 1.0},
      {"path.jitterLateral", 0, 1},
      {"taper.stylus.start", 0, 300},
      {"taper.stylus.end", 0, 300},
      {"taper.stylus.minSize", 0, 1},
      {"taper.stylus.minOpacity", 0, 1},
      {"dynamics.size.min", 0, 1},
      {"dynamics.opacity.min", 0, 1},
      {"dynamics.size.jitter", 0, 1},
      {"dynamics.opacity.jitter", 0, 1},
      {"shape.scatter", 0, 1},
      {"shape.hardness", 0, 1},
      {"grain.scale", 0.1, 8},
      {"grain.depth", 0, 1},
      {"render.flow", 0.05, 1},
  };
}

Rgba8Image renderTestSheet(const nlohmann::json& brushDocument, const stroke::LoadedBrush& textures,
                           double scale, double sizePixels, double opacity) {
  std::string error;
  auto parameters = stroke::parseBrush(brushDocument, error);
  if (!parameters) {
    throw std::invalid_argument("invalid brush: " + error);
  }
  stroke::LoadedBrush brush;
  brush.parameters = std::move(*parameters);
  brush.shapeTexture = brush.parameters.shape.texture.empty() ? nullptr : textures.shapeTexture;
  brush.grainTexture = brush.parameters.grain.enabled() ? textures.grainTexture : nullptr;
  const Image image = renderScene(testSheetScene(brush, scale, sizePixels, opacity));
  return Rgba8Image{image.width, image.height, toUnpremultipliedRgba8(image)};
}

std::vector<double> minimizeNelderMead(const std::function<double(const std::vector<double>&)>& f,
                                       std::vector<double> start, int32_t maximumEvaluations,
                                       double initialStep, int32_t* evaluations) {
  const size_t dimensions = start.size();
  int32_t count = 0;
  auto clampAll = [](std::vector<double> point) {
    for (double& value : point) {
      value = std::clamp(value, 0.0, 1.0);
    }
    return point;
  };
  auto evaluate = [&](const std::vector<double>& point) {
    ++count;
    return f(point);
  };
  std::vector<std::vector<double>> simplex{clampAll(start)};
  for (size_t dimension = 0; dimension < dimensions; ++dimension) {
    std::vector<double> vertex = simplex.front();
    vertex[dimension] += vertex[dimension] + initialStep <= 1 ? initialStep : -initialStep;
    simplex.push_back(clampAll(vertex));
  }
  std::vector<double> values;
  for (const auto& vertex : simplex) {
    values.push_back(evaluate(vertex));
  }
  std::vector<size_t> order(simplex.size());
  while (count < maximumEvaluations) {
    std::iota(order.begin(), order.end(), 0);
    std::sort(order.begin(), order.end(), [&](size_t a, size_t b) { return values[a] < values[b]; });
    const size_t best = order.front();
    const size_t worst = order.back();
    const size_t secondWorst = order[order.size() - 2];
    if (std::abs(values[worst] - values[best]) < 1e-9) {
      break;
    }
    std::vector<double> centroid(dimensions, 0);
    for (size_t index : order) {
      if (index == worst) {
        continue;
      }
      for (size_t d = 0; d < dimensions; ++d) {
        centroid[d] += simplex[index][d] / static_cast<double>(dimensions);
      }
    }
    auto along = [&](double coefficient) {
      std::vector<double> point(dimensions);
      for (size_t d = 0; d < dimensions; ++d) {
        point[d] = centroid[d] + coefficient * (simplex[worst][d] - centroid[d]);
      }
      return clampAll(point);
    };
    const auto reflected = along(-1);
    const double reflectedValue = evaluate(reflected);
    if (reflectedValue < values[best]) {
      const auto expanded = along(-2);
      const double expandedValue = evaluate(expanded);
      if (expandedValue < reflectedValue) {
        simplex[worst] = expanded;
        values[worst] = expandedValue;
      } else {
        simplex[worst] = reflected;
        values[worst] = reflectedValue;
      }
      continue;
    }
    if (reflectedValue < values[secondWorst]) {
      simplex[worst] = reflected;
      values[worst] = reflectedValue;
      continue;
    }
    const auto contracted = along(0.5);
    const double contractedValue = evaluate(contracted);
    if (contractedValue < values[worst]) {
      simplex[worst] = contracted;
      values[worst] = contractedValue;
      continue;
    }
    for (size_t index = 0; index < simplex.size(); ++index) {
      if (index == best) {
        continue;
      }
      for (size_t d = 0; d < dimensions; ++d) {
        simplex[index][d] = simplex[best][d] + 0.5 * (simplex[index][d] - simplex[best][d]);
      }
      values[index] = evaluate(simplex[index]);
    }
  }
  if (evaluations != nullptr) {
    *evaluations = count;
  }
  const auto best = std::min_element(values.begin(), values.end()) - values.begin();
  return simplex[static_cast<size_t>(best)];
}

TuningResult tuneBrush(const nlohmann::json& brushDocument, const stroke::LoadedBrush& textures,
                       const std::vector<StrokeStatistic>& target,
                       const std::vector<TunableParameter>& parameters, int32_t maximumEvaluations,
                       double scale, const std::function<void(int32_t, double)>& progress) {
  double bestDistance = INFINITY;
  int32_t evaluation = 0;
  auto distanceOf = [&](const std::vector<double>& normalized) {
    const auto image = renderTestSheet(withValues(brushDocument, parameters, normalized), textures, scale);
    const double distance = compareStatistics(target, measureSheet(image)).distance;
    bestDistance = std::min(bestDistance, distance);
    if (progress) {
      progress(++evaluation, bestDistance);
    }
    return distance;
  };
  const auto start = normalizedValues(brushDocument, parameters);
  TuningResult result;
  result.initialDistance =
      compareStatistics(target, measureSheet(renderTestSheet(brushDocument, textures, scale))).distance;
  const auto best = minimizeNelderMead(distanceOf, start, maximumEvaluations, 0.15, &result.evaluations);
  result.brush = withValues(brushDocument, parameters, best);
  result.distance = compareStatistics(target, measureSheet(renderTestSheet(result.brush, textures, scale))).distance;
  if (result.distance > result.initialDistance) {
    result.brush = brushDocument;
    result.distance = result.initialDistance;
  }
  return result;
}

}  // namespace nanquim::reference
