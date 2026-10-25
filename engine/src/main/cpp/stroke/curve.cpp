#include "stroke/curve.h"

#include <algorithm>
#include <cmath>

namespace nanquim::stroke {

namespace {

bool isValid(const std::vector<std::pair<double, double>>& points) {
  if (points.size() < 2 || points.front().first != 0.0 || points.back().first != 1.0) {
    return false;
  }
  for (size_t index = 0; index < points.size(); ++index) {
    const auto [x, y] = points[index];
    if (!std::isfinite(x) || !std::isfinite(y) || y < 0.0 || y > 1.0) {
      return false;
    }
    if (index > 0 && x <= points[index - 1].first) {
      return false;
    }
  }
  return true;
}

}  // namespace

Curve::Curve(std::vector<std::pair<double, double>> points)
    : points_(isValid(points) ? std::move(points)
                              : std::vector<std::pair<double, double>>{{0.0, 0.0}, {1.0, 1.0}}) {
  const size_t count = points_.size();
  std::vector<double> secants(count - 1);
  for (size_t index = 0; index + 1 < count; ++index) {
    secants[index] = (points_[index + 1].second - points_[index].second) /
                     (points_[index + 1].first - points_[index].first);
  }
  tangents_.resize(count);
  tangents_.front() = secants.front();
  tangents_.back() = secants.back();
  for (size_t index = 1; index + 1 < count; ++index) {
    const double before = secants[index - 1];
    const double after = secants[index];
    tangents_[index] = before * after <= 0.0 ? 0.0 : (before + after) / 2.0;
  }
  for (size_t index = 0; index + 1 < count; ++index) {
    const double secant = secants[index];
    if (secant == 0.0) {
      tangents_[index] = 0.0;
      tangents_[index + 1] = 0.0;
      continue;
    }
    const double alpha = tangents_[index] / secant;
    const double beta = tangents_[index + 1] / secant;
    const double magnitude = alpha * alpha + beta * beta;
    if (magnitude > 9.0) {
      const double scale = 3.0 / std::sqrt(magnitude);
      tangents_[index] = scale * alpha * secant;
      tangents_[index + 1] = scale * beta * secant;
    }
  }
}

Curve Curve::linear() { return Curve({{0.0, 0.0}, {1.0, 1.0}}); }
Curve Curve::soft() { return Curve({{0.0, 0.0}, {0.25, 0.45}, {1.0, 1.0}}); }
Curve Curve::hard() { return Curve({{0.0, 0.0}, {0.6, 0.3}, {1.0, 1.0}}); }

double Curve::evaluate(double x) const {
  const double clamped = std::clamp(x, 0.0, 1.0);
  const auto upper = std::upper_bound(
      points_.begin() + 1, points_.end() - 1, clamped,
      [](double value, const std::pair<double, double>& point) { return value < point.first; });
  const auto index = static_cast<size_t>(upper - points_.begin()) - 1;
  const auto [x0, y0] = points_[index];
  const auto [x1, y1] = points_[index + 1];
  const double width = x1 - x0;
  const double t = (clamped - x0) / width;
  const double t2 = t * t;
  const double t3 = t2 * t;
  const double value = (2 * t3 - 3 * t2 + 1) * y0 + (t3 - 2 * t2 + t) * width * tangents_[index] +
                       (-2 * t3 + 3 * t2) * y1 + (t3 - t2) * width * tangents_[index + 1];
  return std::clamp(value, 0.0, 1.0);
}

}  // namespace nanquim::stroke
