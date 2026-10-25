#include "adjust/spatial.h"

#include <algorithm>
#include <cmath>

#include "canvas/level_selection.h"

namespace nanquim::adjust {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kSmallestSigma = 0.3;
constexpr double kShortestStreak = 0.5;
constexpr double kPerspectiveSpan = 0.7;
constexpr double kSharpenSigma = 1;
constexpr double kSharpenStrength = 2;
constexpr int32_t kCoarsestLevel = 8;

double blurPixels(double value, int32_t width, int32_t height) {
  return std::clamp(value, 0.0, 1.0) * kMaximumBlurPixels * std::max(width, height) / 4096.0;
}

std::vector<double> gaussianWeights(double sigma) {
  const int32_t radius =
      std::clamp(static_cast<int32_t>(std::ceil(3 * sigma)), 1, kMaximumGaussianRadius);
  std::vector<double> weights(static_cast<size_t>(radius) + 1);
  double total = 0;
  for (int32_t offset = 0; offset <= radius; ++offset) {
    const double weight = std::exp(-0.5 * offset * offset / (sigma * sigma));
    weights[static_cast<size_t>(offset)] = weight;
    total += offset == 0 ? weight : 2 * weight;
  }
  for (double& weight : weights) {
    weight /= total;
  }
  return weights;
}

void addGaussian(SpatialPlan& plan, double sigma) {
  const std::vector<double> weights = gaussianWeights(sigma);
  SpatialPass horizontal;
  horizontal.kind = SpatialPass::Kind::GaussianHorizontal;
  horizontal.weights = weights;
  SpatialPass vertical = horizontal;
  vertical.kind = SpatialPass::Kind::GaussianVertical;
  plan.passes.push_back(std::move(horizontal));
  plan.passes.push_back(std::move(vertical));
}

std::vector<int32_t> combTaps(int32_t samples, int32_t maximum) {
  std::vector<int32_t> taps;
  int32_t product = 1;
  while (product < samples) {
    const int32_t count = std::clamp((samples + product - 1) / product, 2, maximum);
    taps.push_back(count);
    product *= count;
  }
  return taps;
}

int32_t combProduct(const std::vector<int32_t>& taps) {
  int32_t product = 1;
  for (const int32_t count : taps) {
    product *= count;
  }
  return product;
}

SpatialPlan gaussianPlan(double sigma, int32_t width, int32_t height, int32_t minimumLevel) {
  SpatialPlan plan;
  plan.level = minimumLevel;
  if (sigma <= 0) {
    return plan;
  }
  while (sigma / std::ldexp(1.0, plan.level) > kMaximumLevelSigma && plan.level < kCoarsestLevel &&
         (canvas::levelDimension(width, plan.level) > 1 ||
          canvas::levelDimension(height, plan.level) > 1)) {
    ++plan.level;
  }
  plan.pyramid = plan.level > minimumLevel;
  const double scale = std::ldexp(1.0, plan.level);
  const double levelSigma = plan.pyramid
                                ? std::sqrt(std::max(sigma * sigma / (scale * scale) - 0.25, 0.0))
                                : sigma / scale;
  int32_t radius = 0;
  if (levelSigma >= kSmallestSigma) {
    addGaussian(plan, levelSigma);
    radius = static_cast<int32_t>(plan.passes.front().weights.size()) - 1;
  }
  plan.reach = plan.pyramid ? (radius + 2) * static_cast<int32_t>(scale) : radius;
  return plan;
}

SpatialPlan motionPlan(double length, double angle, int32_t minimumLevel) {
  SpatialPlan plan;
  plan.level = minimumLevel;
  const double scale = std::ldexp(1.0, plan.level);
  const double levelLength = length / scale;
  plan.reach = static_cast<int32_t>(std::ceil(length / 2)) + 2;
  if (levelLength < kShortestStreak) {
    plan.reach = 0;
    return plan;
  }
  const std::array<double, 2> direction{std::cos(angle * kPi), std::sin(angle * kPi)};
  const std::vector<int32_t> taps =
      combTaps(static_cast<int32_t>(std::ceil(levelLength)) + 1, kMaximumLineTaps);
  const double spacing = levelLength / (combProduct(taps) - 1);
  double passSpacing = spacing;
  for (const int32_t count : taps) {
    SpatialPass pass;
    pass.kind = SpatialPass::Kind::Line;
    pass.taps = count;
    pass.step = {direction[0] * passSpacing, direction[1] * passSpacing};
    plan.passes.push_back(pass);
    passSpacing *= count;
  }
  return plan;
}

SpatialPlan perspectivePlan(const Parameters& parameters, int32_t width, int32_t height,
                            int32_t minimumLevel) {
  const auto& values = parameters.values;
  SpatialPlan plan;
  plan.level = minimumLevel;
  if (minimumLevel > 0 && (canvas::levelDimension(width, minimumLevel) > 1 ||
                           canvas::levelDimension(height, minimumLevel) > 1)) {
    plan.level = minimumLevel + 1;
    plan.pyramid = true;
  }
  plan.reach = -1;
  const double span = std::clamp(static_cast<double>(values[0]), 0.0, 1.0) * kPerspectiveSpan;
  const bool directional = values[3] >= 0.5;
  const std::array<double, 2> center{
      std::clamp(static_cast<double>(values[1]), 0.0, 1.0) * width - 0.5,
      std::clamp(static_cast<double>(values[2]), 0.0, 1.0) * height - 0.5};
  double farthest = 0;
  for (const double x : {-0.5, width - 0.5}) {
    for (const double y : {-0.5, height - 0.5}) {
      farthest = std::max(farthest, std::hypot(x - center[0], y - center[1]));
    }
  }
  const double ray = farthest * (directional ? span : std::exp(span / 2) - std::exp(-span / 2)) /
                     std::ldexp(1.0, plan.level);
  if (ray < kShortestStreak) {
    plan = SpatialPlan{};
    plan.level = minimumLevel;
    return plan;
  }
  const int32_t samples = std::min(static_cast<int32_t>(std::ceil(ray)) + 1, kMaximumRaySamples);
  const std::vector<int32_t> taps = combTaps(samples, kMaximumRayTaps);
  double passSpacing = span / (combProduct(taps) - 1);
  const double angle = values[4] * kPi;
  for (const int32_t count : taps) {
    SpatialPass pass;
    pass.kind = directional ? SpatialPass::Kind::Directional : SpatialPass::Kind::Zoom;
    pass.taps = count;
    pass.center = center;
    pass.spacing = passSpacing;
    if (directional) {
      pass.step = {std::cos(angle), std::sin(angle)};
    }
    plan.passes.push_back(pass);
    passSpacing *= count;
  }
  return plan;
}

double farthestCorner(const std::array<double, 2>& center, int32_t width, int32_t height) {
  double farthest = 0;
  for (const double x : {-0.5, width - 0.5}) {
    for (const double y : {-0.5, height - 0.5}) {
      farthest = std::max(farthest, std::hypot(x - center[0], y - center[1]));
    }
  }
  return farthest;
}

std::array<double, 2> puck(const Parameters& parameters, int32_t width, int32_t height) {
  return {std::clamp(static_cast<double>(parameters.values[1]), 0.0, 1.0) * width - 0.5,
          std::clamp(static_cast<double>(parameters.values[2]), 0.0, 1.0) * height - 0.5};
}

SpatialPlan bloomPlan(const Parameters& parameters, int32_t width, int32_t height,
                      int32_t minimumLevel) {
  const auto& values = parameters.values;
  const double sigma = blurPixels(values[0], width, height) / 2;
  if (sigma <= 0) {
    SpatialPlan plan;
    plan.level = minimumLevel;
    return plan;
  }
  SpatialPlan plan = gaussianPlan(sigma, width, height, minimumLevel);
  SpatialPass threshold;
  threshold.kind = SpatialPass::Kind::Threshold;
  threshold.values[0] = std::clamp(static_cast<double>(values[1]), 0.0, 1.0);
  plan.passes.insert(plan.passes.begin(), threshold);
  plan.additive = true;
  plan.gain = 0.5 + 1.5 * std::clamp(static_cast<double>(values[2]), 0.0, 1.0);
  return plan;
}

SpatialPlan halftonePlan(const Parameters& parameters, int32_t width, int32_t height,
                         int32_t minimumLevel) {
  const auto& values = parameters.values;
  SpatialPlan plan;
  plan.level = minimumLevel;
  SpatialPass pass;
  pass.kind = SpatialPass::Kind::Halftone;
  pass.variant = std::clamp(static_cast<int32_t>(std::lround(values[1])), 0, 2);
  pass.values[0] = std::round((6 + 90 * std::clamp(static_cast<double>(values[0]), 0.0, 1.0)) *
                              std::max(1.0, std::max(width, height) / 2048.0) * 8) /
                   8;
  plan.passes.push_back(pass);
  plan.reach = static_cast<int32_t>(std::ceil(pass.values[0])) + 2;
  return plan;
}

SpatialPlan glitchPlan(const Parameters& parameters, int32_t width, int32_t height,
                       int32_t minimumLevel) {
  const auto& values = parameters.values;
  SpatialPlan plan;
  plan.level = minimumLevel;
  const double amount = std::clamp(static_cast<double>(values[0]), 0.0, 1.0);
  if (amount <= 0) {
    return plan;
  }
  const double size = std::max(width, height) / 4096.0;
  SpatialPass pass;
  pass.kind = SpatialPass::Kind::Glitch;
  pass.variant = std::clamp(static_cast<int32_t>(std::lround(values[4])), 0, 3);
  pass.values[0] = amount * 0.08 * std::max(width, height);
  pass.values[1] =
      std::round(
          std::max(4.0, (8 + 248 * std::clamp(static_cast<double>(values[1]), 0.0, 1.0)) *
                            (0.25 + 1.75 * std::clamp(static_cast<double>(values[2]), 0.0, 1.0)) *
                            size) *
          8) /
      8;
  pass.values[2] = std::clamp(static_cast<double>(values[3]), 0.0, 1.0);
  plan.passes.push_back(pass);
  plan.reach = static_cast<int32_t>(std::ceil(pass.values[0])) + 2;
  return plan;
}

SpatialPlan chromaticPlan(const Parameters& parameters, int32_t width, int32_t height,
                          int32_t minimumLevel) {
  const auto& values = parameters.values;
  SpatialPlan plan;
  plan.level = minimumLevel;
  const double amount = std::clamp(static_cast<double>(values[0]), 0.0, 1.0);
  if (amount <= 0) {
    return plan;
  }
  SpatialPass pass;
  pass.kind = SpatialPass::Kind::Chromatic;
  pass.center = puck(parameters, width, height);
  pass.variant = values[3] >= 0.5 ? 1 : 0;
  const double farthest = farthestCorner(pass.center, width, height);
  pass.values[0] = pass.variant == 0 ? amount * 0.03 : amount * 0.02 * std::max(width, height);
  pass.values[1] = std::clamp(static_cast<double>(values[5]), 0.0, 1.0);
  pass.values[2] = std::clamp(static_cast<double>(values[6]), 0.0, 1.0);
  pass.values[3] = std::max(farthest, 1.0);
  const double angle = values[4] * kPi;
  pass.step = {std::cos(angle), std::sin(angle)};
  plan.passes.push_back(pass);
  const double shift = pass.variant == 0 ? pass.values[0] * farthest : pass.values[0];
  plan.reach = static_cast<int32_t>(std::ceil(shift)) + 2;
  return plan;
}

SpatialPlan sharpenPlan(double amount, int32_t minimumLevel) {
  SpatialPlan plan;
  plan.level = minimumLevel;
  plan.unsharp = true;
  plan.sharpen = std::clamp(amount, 0.0, 1.0) * kSharpenStrength * (minimumLevel == 0 ? 1 : 0.5);
  addGaussian(plan, kSharpenSigma);
  plan.reach = static_cast<int32_t>(plan.passes.front().weights.size()) - 1;
  return plan;
}

}  // namespace

LevelMapping boxMapping(int32_t level) { return {level, (std::ldexp(1.0, level) - 1) / 2}; }

bool isSpatial(Filter filter) {
  return filter == Filter::GaussianBlur || filter == Filter::MotionBlur ||
         filter == Filter::PerspectiveBlur || filter == Filter::Sharpen ||
         filter == Filter::Bloom || filter == Filter::Glitch || filter == Filter::Halftone ||
         filter == Filter::ChromaticAberration || filter == Filter::Liquify;
}

SpatialPlan spatialPlan(const Parameters& parameters, int32_t width, int32_t height,
                        int32_t minimumLevel) {
  const auto& values = parameters.values;
  switch (parameters.filter) {
    case Filter::GaussianBlur:
      return gaussianPlan(blurPixels(values[0], width, height) / 2, width, height, minimumLevel);
    case Filter::MotionBlur:
      return motionPlan(blurPixels(values[0], width, height), values[1], minimumLevel);
    case Filter::PerspectiveBlur:
      return perspectivePlan(parameters, width, height, minimumLevel);
    case Filter::Sharpen:
      return sharpenPlan(values[0], minimumLevel);
    case Filter::Bloom:
      return bloomPlan(parameters, width, height, minimumLevel);
    case Filter::Halftone:
      return halftonePlan(parameters, width, height, minimumLevel);
    case Filter::Glitch:
      return glitchPlan(parameters, width, height, minimumLevel);
    case Filter::ChromaticAberration:
      return chromaticPlan(parameters, width, height, minimumLevel);
    case Filter::Liquify: {
      SpatialPlan plan;
      plan.level = minimumLevel;
      plan.reach = -1;
      return plan;
    }
    default:
      break;
  }
  SpatialPlan plan;
  plan.level = minimumLevel;
  return plan;
}

std::array<double, 4> WorkImage::texel(int32_t x, int32_t y) const {
  const size_t offset = (static_cast<size_t>(std::clamp(y, 0, height - 1)) * width +
                         static_cast<size_t>(std::clamp(x, 0, width - 1))) *
                        4;
  return {rgba[offset], rgba[offset + 1], rgba[offset + 2], rgba[offset + 3]};
}

std::array<double, 4> WorkImage::sample(double x, double y) const {
  const double left = std::floor(x);
  const double top = std::floor(y);
  const double fractionX = x - left;
  const double fractionY = y - top;
  const auto column = static_cast<int32_t>(left);
  const auto row = static_cast<int32_t>(top);
  const std::array<double, 4> a = texel(column, row);
  const std::array<double, 4> b = texel(column + 1, row);
  const std::array<double, 4> c = texel(column, row + 1);
  const std::array<double, 4> d = texel(column + 1, row + 1);
  std::array<double, 4> result{};
  for (size_t channel = 0; channel < 4; ++channel) {
    const double upper = a[channel] + (b[channel] - a[channel]) * fractionX;
    const double lower = c[channel] + (d[channel] - c[channel]) * fractionX;
    result[channel] = upper + (lower - upper) * fractionY;
  }
  return result;
}

WorkImage boxDownsample(const std::vector<double>& rgba, int32_t width, int32_t height,
                        int32_t level) {
  WorkImage image;
  image.width = canvas::levelDimension(width, level);
  image.height = canvas::levelDimension(height, level);
  image.rgba.assign(static_cast<size_t>(image.width) * image.height * 4, 0.0);
  const int32_t factor = 1 << level;
  for (int32_t row = 0; row < image.height; ++row) {
    for (int32_t column = 0; column < image.width; ++column) {
      std::array<double, 4> sum{};
      int32_t count = 0;
      for (int32_t y = row * factor; y < std::min((row + 1) * factor, height); ++y) {
        for (int32_t x = column * factor; x < std::min((column + 1) * factor, width); ++x) {
          const size_t offset = (static_cast<size_t>(y) * width + x) * 4;
          for (size_t channel = 0; channel < 4; ++channel) {
            sum[channel] += rgba[offset + channel];
          }
          ++count;
        }
      }
      const size_t offset = (static_cast<size_t>(row) * image.width + column) * 4;
      for (size_t channel = 0; channel < 4; ++channel) {
        image.rgba[offset + channel] = sum[channel] / count;
      }
    }
  }
  return image;
}

namespace {

double smoothStep(double edge0, double edge1, double value) {
  const double t = std::clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
  return t * t * (3 - 2 * t);
}

std::array<double, 3> unpremultiplied(const std::array<double, 4>& value) {
  if (value[3] <= 0) {
    return {0, 0, 0};
  }
  return {std::clamp(value[0] / value[3], 0.0, 1.0), std::clamp(value[1] / value[3], 0.0, 1.0),
          std::clamp(value[2] / value[3], 0.0, 1.0)};
}

}  // namespace

std::array<double, 4> pixelPass(const SpatialPass& pass, const WorkImage& image,
                                const LevelMapping& mapping, int32_t x, int32_t y) {
  const double pixelX = mapping.offset + x * mapping.scale();
  const double pixelY = mapping.offset + y * mapping.scale();
  auto read = [&](double canvasX, double canvasY) {
    return image.sample(mapping.toTexel(canvasX), mapping.toTexel(canvasY));
  };
  switch (pass.kind) {
    case SpatialPass::Kind::Threshold: {
      const std::array<double, 4> value = image.texel(x, y);
      const double weight =
          smoothStep(pass.values[0] - 0.1, pass.values[0] + 0.1, luminance(unpremultiplied(value)));
      return {value[0] * weight, value[1] * weight, value[2] * weight, value[3] * weight};
    }
    case SpatialPass::Kind::Halftone: {
      const double cell = pass.values[0];
      const double alpha = image.texel(x, y)[3];
      auto coverage = [&](double degrees, auto ink) {
        const double angle = degrees * kPi / 180;
        const double cosine = std::cos(angle);
        const double sine = std::sin(angle);
        const double u = cosine * (pixelX + 0.5) + sine * (pixelY + 0.5);
        const double v = -sine * (pixelX + 0.5) + cosine * (pixelY + 0.5);
        const double centerU = (std::floor(u / cell) + 0.5) * cell;
        const double centerV = (std::floor(v / cell) + 0.5) * cell;
        const std::array<double, 4> sample =
            read(cosine * centerU - sine * centerV - 0.5, sine * centerU + cosine * centerV - 0.5);
        const double amount = std::clamp(ink(unpremultiplied(sample)), 0.0, 1.0) * sample[3];
        const double radius = cell * 0.70710678 * std::sqrt(amount);
        const double distance = std::hypot(u - centerU, v - centerV);
        return amount <= 0 ? 0.0
                           : std::clamp((radius - distance) / mapping.scale() + 0.5, 0.0, 1.0);
      };
      std::array<double, 3> rgb{};
      if (pass.variant == 0) {
        auto black = [](const std::array<double, 3>& c) {
          return 1 - std::max({c[0], c[1], c[2]});
        };
        auto ink = [black](size_t channel) {
          return [black, channel](const std::array<double, 3>& c) {
            const double k = black(c);
            return k >= 1 ? 0.0 : (1 - c[channel] - k) / (1 - k);
          };
        };
        const double cyan = coverage(15, ink(0));
        const double magenta = coverage(75, ink(1));
        const double yellow = coverage(0, ink(2));
        const double key = coverage(45, black);
        rgb = {(1 - cyan) * (1 - key), (1 - magenta) * (1 - key), (1 - yellow) * (1 - key)};
      } else if (pass.variant == 1) {
        rgb = {coverage(15, [](const std::array<double, 3>& c) { return c[0]; }),
               coverage(75, [](const std::array<double, 3>& c) { return c[1]; }),
               coverage(0, [](const std::array<double, 3>& c) { return c[2]; })};
      } else {
        const double gray =
            1 - coverage(45, [](const std::array<double, 3>& c) { return 1 - luminance(c); });
        rgb = {gray, gray, gray};
      }
      return {rgb[0] * alpha, rgb[1] * alpha, rgb[2] * alpha, alpha};
    }
    case SpatialPass::Kind::Glitch: {
      const double shift = pass.values[0];
      const double block = pass.values[1];
      const double frequency = pass.values[2];
      const auto column = static_cast<int32_t>(std::floor((pixelX + 0.5) / block));
      const auto row = static_cast<int32_t>(std::floor((pixelY + 0.5) / block));
      std::array<double, 2> red{pixelX, pixelY};
      std::array<double, 2> green = red;
      std::array<double, 2> blue = red;
      if (pass.variant == 0) {
        if (hashUnit(column, row, 11) < frequency) {
          const double dx = (hashUnit(column, row, 12) - 0.5) * 2 * shift;
          const double dy = (hashUnit(column, row, 13) - 0.5) * 0.5 * shift;
          red = green = blue = {pixelX + dx, pixelY + dy};
        }
      } else if (pass.variant == 1) {
        const double sway =
            (valueNoise(pixelY / block * (0.5 + 2 * frequency), 0.5, 14) - 0.5) * 2 * shift;
        red = green = blue = {pixelX + sway, pixelY};
      } else if (pass.variant == 2) {
        const auto band = static_cast<int32_t>(std::floor((pixelY + 0.5) / (block * 0.25)));
        const double split =
            hashUnit(0, band, 15) < frequency ? (hashUnit(1, band, 16) - 0.5) * 2 * shift : 0;
        red = {pixelX + split, pixelY};
        blue = {pixelX - split, pixelY};
      } else {
        const double split =
            shift * (hashUnit(column, row, 17) < frequency ? 0.25 + 0.75 * hashUnit(column, row, 18)
                                                           : 0.25);
        red = {pixelX + split, pixelY};
        blue = {pixelX - split, pixelY};
      }
      const std::array<double, 4> r = read(red[0], red[1]);
      const std::array<double, 4> g = read(green[0], green[1]);
      const std::array<double, 4> b = read(blue[0], blue[1]);
      return {r[0], g[1], b[2], std::max({r[3], g[3], b[3]})};
    }
    case SpatialPass::Kind::Chromatic: {
      const std::array<double, 2>& center = pass.center;
      const double distance = std::hypot(pixelX - center[0], pixelY - center[1]);
      const double edge = std::clamp(distance / pass.values[3], 0.0, 1.0);
      const double strength = pass.values[0] * (1 + (edge * edge - 1) * pass.values[1]);
      std::array<double, 2> red{};
      std::array<double, 2> blue{};
      if (pass.variant == 0) {
        for (size_t axis = 0; axis < 2; ++axis) {
          const double offset = (axis == 0 ? pixelX : pixelY) - center[axis];
          red[axis] = center[axis] + offset * (1 + strength);
          blue[axis] = center[axis] + offset * (1 - strength);
        }
      } else {
        red = {pixelX + pass.step[0] * strength, pixelY + pass.step[1] * strength};
        blue = {pixelX - pass.step[0] * strength, pixelY - pass.step[1] * strength};
      }
      const std::array<double, 4> r = read(red[0], red[1]);
      const std::array<double, 4> g = image.texel(x, y);
      const std::array<double, 4> b = read(blue[0], blue[1]);
      const double alpha =
          std::max({r[3], g[3], b[3]}) + (g[3] - std::max({r[3], g[3], b[3]})) * pass.values[2];
      return {std::min(r[0], alpha), std::min(g[1], alpha), std::min(b[2], alpha), alpha};
    }
    default:
      return image.texel(x, y);
  }
}

WorkImage runPass(const SpatialPass& pass, const WorkImage& image, const LevelMapping& mapping) {
  WorkImage result = image;
  const std::array<double, 2> center{mapping.toTexel(pass.center[0]),
                                     mapping.toTexel(pass.center[1])};
  const double middle = (pass.taps - 1) / 2.0;
  for (int32_t y = 0; y < image.height; ++y) {
    for (int32_t x = 0; x < image.width; ++x) {
      std::array<double, 4> sum{};
      auto add = [&sum](const std::array<double, 4>& value, double weight) {
        for (size_t channel = 0; channel < 4; ++channel) {
          sum[channel] += value[channel] * weight;
        }
      };
      switch (pass.kind) {
        case SpatialPass::Kind::GaussianHorizontal:
        case SpatialPass::Kind::GaussianVertical: {
          const bool horizontal = pass.kind == SpatialPass::Kind::GaussianHorizontal;
          const auto radius = static_cast<int32_t>(pass.weights.size()) - 1;
          for (int32_t offset = -radius; offset <= radius; ++offset) {
            add(horizontal ? image.texel(x + offset, y) : image.texel(x, y + offset),
                pass.weights[static_cast<size_t>(std::abs(offset))]);
          }
          break;
        }
        case SpatialPass::Kind::Line:
          for (int32_t tap = 0; tap < pass.taps; ++tap) {
            const double t = tap - middle;
            add(image.sample(x + pass.step[0] * t, y + pass.step[1] * t), 1.0 / pass.taps);
          }
          break;
        case SpatialPass::Kind::Zoom:
          for (int32_t tap = 0; tap < pass.taps; ++tap) {
            const double scale = std::exp(pass.spacing * (tap - middle));
            add(image.sample(center[0] + (x - center[0]) * scale,
                             center[1] + (y - center[1]) * scale),
                1.0 / pass.taps);
          }
          break;
        case SpatialPass::Kind::Directional: {
          const double distance = std::hypot(x - center[0], y - center[1]) * pass.spacing;
          for (int32_t tap = 0; tap < pass.taps; ++tap) {
            const double t = distance * (tap - middle);
            add(image.sample(x + pass.step[0] * t, y + pass.step[1] * t), 1.0 / pass.taps);
          }
          break;
        }
        default:
          sum = pixelPass(pass, image, mapping, x, y);
          break;
      }
      const size_t offset = (static_cast<size_t>(y) * image.width + x) * 4;
      std::copy(sum.begin(), sum.end(), result.rgba.begin() + static_cast<ptrdiff_t>(offset));
    }
  }
  return result;
}

std::array<double, 4> resultAt(const WorkImage& result, const LevelMapping& mapping, int32_t x,
                               int32_t y) {
  return result.sample(mapping.toTexel(x), mapping.toTexel(y));
}

std::array<double, 4> combineSpatial(const SpatialPlan& plan, const std::array<double, 4>& original,
                                     const std::array<double, 4>& filtered, double weight) {
  std::array<double, 4> value = filtered;
  if (plan.unsharp) {
    for (size_t channel = 0; channel < 4; ++channel) {
      value[channel] = original[channel] + plan.sharpen * (original[channel] - filtered[channel]);
    }
  } else if (plan.additive) {
    for (size_t channel = 0; channel < 3; ++channel) {
      value[channel] = original[channel] + plan.gain * filtered[channel];
    }
    value[3] = original[3] + plan.gain * filtered[3] * (1 - original[3]);
  }
  value[3] = std::clamp(value[3], 0.0, 1.0);
  for (size_t channel = 0; channel < 3; ++channel) {
    value[channel] = std::clamp(value[channel], 0.0, value[3]);
  }
  std::array<double, 4> result{};
  for (size_t channel = 0; channel < 4; ++channel) {
    result[channel] = original[channel] + (value[channel] - original[channel]) * weight;
  }
  return result;
}

std::vector<double> filterImage(const Parameters& parameters, const std::vector<double>& rgba,
                                int32_t width, int32_t height) {
  const SpatialPlan plan = spatialPlan(parameters, width, height, 0);
  const LevelMapping mapping = boxMapping(plan.level);
  WorkImage image = boxDownsample(rgba, width, height, plan.level);
  for (const SpatialPass& pass : plan.passes) {
    image = runPass(pass, image, mapping);
  }
  std::vector<double> result(rgba.size());
  for (int32_t y = 0; y < height; ++y) {
    for (int32_t x = 0; x < width; ++x) {
      const size_t offset = (static_cast<size_t>(y) * width + x) * 4;
      const std::array<double, 4> original{rgba[offset], rgba[offset + 1], rgba[offset + 2],
                                           rgba[offset + 3]};
      const std::array<double, 4> value =
          combineSpatial(plan, original, resultAt(image, mapping, x, y), 1);
      std::copy(value.begin(), value.end(), result.begin() + static_cast<ptrdiff_t>(offset));
    }
  }
  return result;
}

}  // namespace nanquim::adjust
