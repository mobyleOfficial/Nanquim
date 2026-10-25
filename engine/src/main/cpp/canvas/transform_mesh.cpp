#include "canvas/transform_mesh.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace nanquim::canvas {

ContentAnalysis analyzeContent(const uint8_t* pixels, int32_t width, int32_t height,
                               int32_t originX, int32_t originY) {
  ContentAnalysis analysis;
  int32_t minimumX = std::numeric_limits<int32_t>::max();
  int32_t minimumY = std::numeric_limits<int32_t>::max();
  int32_t maximumX = -1;
  int32_t maximumY = -1;
  double weight = 0;
  double sumX = 0;
  double sumY = 0;
  double sumXX = 0;
  double sumYY = 0;
  double sumXY = 0;
  for (int32_t y = 0; y < height; ++y) {
    const uint8_t* row = pixels + static_cast<size_t>(y) * width * 4;
    for (int32_t x = 0; x < width; ++x) {
      const uint8_t alpha = row[static_cast<size_t>(x) * 4 + 3];
      if (alpha == 0) {
        continue;
      }
      minimumX = std::min(minimumX, x);
      maximumX = std::max(maximumX, x);
      minimumY = std::min(minimumY, y);
      maximumY = std::max(maximumY, y);
      const double a = alpha / 255.0;
      const double px = x + 0.5;
      const double py = y + 0.5;
      weight += a;
      sumX += a * px;
      sumY += a * py;
      sumXX += a * px * px;
      sumYY += a * py * py;
      sumXY += a * px * py;
    }
  }
  if (maximumX < 0) {
    return analysis;
  }
  analysis.bounds = {originX + minimumX, originY + minimumY, maximumX - minimumX + 1,
                     maximumY - minimumY + 1};
  const double meanX = sumX / weight;
  const double meanY = sumY / weight;
  analysis.centroidX = originX + meanX;
  analysis.centroidY = originY + meanY;
  const double covarianceXX = sumXX / weight - meanX * meanX;
  const double covarianceYY = sumYY / weight - meanY * meanY;
  const double covarianceXY = sumXY / weight - meanX * meanY;
  analysis.principalAngle = 0.5 * std::atan2(2 * covarianceXY, covarianceXX - covarianceYY);
  return analysis;
}

std::array<float, 4> meshBounds(const std::vector<MeshVertex>& triangles) {
  if (triangles.empty()) {
    return {0, 0, 0, 0};
  }
  std::array<float, 4> bounds{triangles[0].x, triangles[0].y, triangles[0].x, triangles[0].y};
  for (const MeshVertex& vertex : triangles) {
    bounds[0] = std::min(bounds[0], vertex.x);
    bounds[1] = std::min(bounds[1], vertex.y);
    bounds[2] = std::max(bounds[2], vertex.x);
    bounds[3] = std::max(bounds[3], vertex.y);
  }
  return bounds;
}

namespace {

std::array<double, 4> texel(const std::vector<double>& source, int32_t width, int32_t height,
                            int32_t x, int32_t y) {
  if (x < 0 || y < 0 || x >= width || y >= height) {
    return {0, 0, 0, 0};
  }
  const size_t offset = (static_cast<size_t>(y) * width + x) * 4;
  return {source[offset], source[offset + 1], source[offset + 2], source[offset + 3]};
}

std::array<double, 4> cubicWeights(double t) {
  const double t2 = t * t;
  const double t3 = t2 * t;
  return {0.5 * (-t3 + 2 * t2 - t), 0.5 * (3 * t3 - 5 * t2 + 2), 0.5 * (-3 * t3 + 4 * t2 + t),
          0.5 * (t3 - t2)};
}

}  // namespace

std::array<double, 4> sampleSource(const std::vector<double>& source, int32_t width, int32_t height,
                                   double u, double v, TransformFilter filter) {
  if (filter == TransformFilter::Nearest) {
    return texel(source, width, height, static_cast<int32_t>(std::floor(u)),
                 static_cast<int32_t>(std::floor(v)));
  }
  const double tx = u - 0.5;
  const double ty = v - 0.5;
  const auto x0 = static_cast<int32_t>(std::floor(tx));
  const auto y0 = static_cast<int32_t>(std::floor(ty));
  const double fx = tx - x0;
  const double fy = ty - y0;
  std::array<double, 4> result{0, 0, 0, 0};
  if (filter == TransformFilter::Bilinear) {
    const std::array<std::pair<std::array<int32_t, 2>, double>, 4> taps{
        {{{x0, y0}, (1 - fx) * (1 - fy)},
         {{x0 + 1, y0}, fx * (1 - fy)},
         {{x0, y0 + 1}, (1 - fx) * fy},
         {{x0 + 1, y0 + 1}, fx * fy}}};
    for (const auto& [position, weight] : taps) {
      const auto color = texel(source, width, height, position[0], position[1]);
      for (size_t channel = 0; channel < 4; ++channel) {
        result[channel] += color[channel] * weight;
      }
    }
    return result;
  }
  const auto weightsX = cubicWeights(fx);
  const auto weightsY = cubicWeights(fy);
  for (int32_t j = 0; j < 4; ++j) {
    for (int32_t i = 0; i < 4; ++i) {
      const auto color = texel(source, width, height, x0 - 1 + i, y0 - 1 + j);
      const double weight = weightsX[static_cast<size_t>(i)] * weightsY[static_cast<size_t>(j)];
      for (size_t channel = 0; channel < 4; ++channel) {
        result[channel] += color[channel] * weight;
      }
    }
  }
  result[3] = std::clamp(result[3], 0.0, 1.0);
  for (size_t channel = 0; channel < 3; ++channel) {
    result[channel] = std::clamp(result[channel], 0.0, result[3]);
  }
  return result;
}

void rasterizeMesh(const std::vector<MeshVertex>& triangles, const std::vector<double>& source,
                   std::vector<double>& target, int32_t width, int32_t height,
                   TransformFilter filter) {
  for (size_t first = 0; first + 2 < triangles.size(); first += 3) {
    const MeshVertex& a = triangles[first];
    const MeshVertex& b = triangles[first + 1];
    const MeshVertex& c = triangles[first + 2];
    const double area = (b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y);
    if (area == 0) {
      continue;
    }
    const auto minimumX = std::max(0, static_cast<int32_t>(std::floor(std::min({a.x, b.x, c.x}))));
    const auto maximumX =
        std::min(width - 1, static_cast<int32_t>(std::ceil(std::max({a.x, b.x, c.x}))));
    const auto minimumY = std::max(0, static_cast<int32_t>(std::floor(std::min({a.y, b.y, c.y}))));
    const auto maximumY =
        std::min(height - 1, static_cast<int32_t>(std::ceil(std::max({a.y, b.y, c.y}))));
    auto edge = [area](const MeshVertex& p, const MeshVertex& q, double x, double y) {
      const double value = (q.x - p.x) * (y - p.y) - (q.y - p.y) * (x - p.x);
      return area > 0 ? value : -value;
    };
    auto topLeft = [area](const MeshVertex& p, const MeshVertex& q) {
      const double dx = q.x - p.x;
      const double dy = q.y - p.y;
      return area > 0 ? (dy < 0 || (dy == 0 && dx > 0)) : (dy > 0 || (dy == 0 && dx < 0));
    };
    for (int32_t y = minimumY; y <= maximumY; ++y) {
      for (int32_t x = minimumX; x <= maximumX; ++x) {
        const double px = x + 0.5;
        const double py = y + 0.5;
        const double wa = edge(b, c, px, py);
        const double wb = edge(c, a, px, py);
        const double wc = edge(a, b, px, py);
        const bool inside = (wa > 0 || (wa == 0 && topLeft(b, c))) &&
                            (wb > 0 || (wb == 0 && topLeft(c, a))) &&
                            (wc > 0 || (wc == 0 && topLeft(a, b)));
        if (!inside) {
          continue;
        }
        const double total = wa + wb + wc;
        const double la = wa / total / a.w;
        const double lb = wb / total / b.w;
        const double lc = wc / total / c.w;
        const double inverse = la + lb + lc;
        const double u = (la * a.u + lb * b.u + lc * c.u) / inverse;
        const double v = (la * a.v + lb * b.v + lc * c.v) / inverse;
        const auto color = sampleSource(source, width, height, u, v, filter);
        double* pixel = &target[(static_cast<size_t>(y) * width + x) * 4];
        for (size_t channel = 0; channel < 4; ++channel) {
          pixel[channel] = color[channel] + pixel[channel] * (1 - color[3]);
        }
      }
    }
  }
}

}  // namespace nanquim::canvas
