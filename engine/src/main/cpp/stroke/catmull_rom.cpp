#include "stroke/catmull_rom.h"

#include <algorithm>
#include <cmath>

namespace nanquim::stroke {

namespace {

constexpr double kArcLengthTolerancePixels = 0.25;
constexpr double kMaximumPieceLengthPixels = 2.0;
constexpr double kMaximumHalfImbalancePixels = 0.02;
constexpr int kMinimumSubdivisionDepth = 2;
constexpr int kMaximumSubdivisionDepth = 16;
constexpr double kMinimumKnotInterval = 1e-9;

using Vector = std::array<double, 2>;

double distanceBetween(const Vector& first, const Vector& second) {
  return std::hypot(second[0] - first[0], second[1] - first[1]);
}

Vector blend(const Vector& a, const Vector& b, double ta, double tb, double t) {
  const double interval = tb - ta;
  if (interval < kMinimumKnotInterval) {
    return b;
  }
  const double weight = (t - ta) / interval;
  return {a[0] + (b[0] - a[0]) * weight, a[1] + (b[1] - a[1]) * weight};
}

}  // namespace

CatmullRomSegment::CatmullRomSegment(const PathPoint& p0, const PathPoint& p1, const PathPoint& p2,
                                     const PathPoint& p3)
    : start_(p1), end_(p2) {
  const std::array<const PathPoint*, 4> points{&p0, &p1, &p2, &p3};
  for (size_t index = 0; index < 4; ++index) {
    controls_[index] = {points[index]->x, points[index]->y};
  }
  knots_[0] = 0;
  for (size_t index = 1; index < 4; ++index) {
    knots_[index] =
        knots_[index - 1] + std::sqrt(distanceBetween(controls_[index - 1], controls_[index]));
  }
  arcTable_.emplace_back(knots_[1], 0.0);
  if (knots_[2] - knots_[1] < kMinimumKnotInterval) {
    return;
  }
  subdivide(knots_[1], controls_[1], knots_[2], controls_[2], 0);
  length_ = arcTable_.back().second;
}

Vector CatmullRomSegment::positionAt(double parameter) const {
  const auto& [t0, t1, t2, t3] = knots_;
  const Vector a1 = blend(controls_[0], controls_[1], t0, t1, parameter);
  const Vector a2 = blend(controls_[1], controls_[2], t1, t2, parameter);
  const Vector a3 = blend(controls_[2], controls_[3], t2, t3, parameter);
  const Vector b1 = blend(a1, a2, t0, t2, parameter);
  const Vector b2 = blend(a2, a3, t1, t3, parameter);
  return blend(b1, b2, t1, t2, parameter);
}

void CatmullRomSegment::subdivide(double startParameter, Vector start, double endParameter,
                                  Vector end, int depth) {
  const double middleParameter = (startParameter + endParameter) / 2;
  const Vector middle = positionAt(middleParameter);
  const double chord = distanceBetween(start, end);
  const double polyline = distanceBetween(start, middle) + distanceBetween(middle, end);
  const double firstHalf = distanceBetween(start, middle);
  const double secondHalf = distanceBetween(middle, end);
  const bool flatEnough = polyline - chord < kArcLengthTolerancePixels &&
                          polyline <= kMaximumPieceLengthPixels &&
                          std::abs(firstHalf - secondHalf) <= kMaximumHalfImbalancePixels;
  if (depth >= kMaximumSubdivisionDepth || (depth >= kMinimumSubdivisionDepth && flatEnough)) {
    const double startLength = arcTable_.back().second;
    arcTable_.emplace_back(middleParameter, startLength + distanceBetween(start, middle));
    arcTable_.emplace_back(endParameter, startLength + polyline);
    return;
  }
  subdivide(startParameter, start, middleParameter, middle, depth + 1);
  subdivide(middleParameter, middle, endParameter, end, depth + 1);
}

double CatmullRomSegment::parameterAtDistance(double distance) const {
  if (arcTable_.size() < 2) {
    return knots_[1];
  }
  const auto upper = std::lower_bound(
      arcTable_.begin() + 1, arcTable_.end() - 1, distance,
      [](const std::pair<double, double>& entry, double value) { return entry.second < value; });
  const auto& [endParameter, endLength] = *upper;
  const auto& [startParameter, startLength] = *(upper - 1);
  const double span = endLength - startLength;
  const double weight = span > 0 ? (distance - startLength) / span : 0.0;
  return startParameter + (endParameter - startParameter) * weight;
}

PathPoint CatmullRomSegment::pointAtDistance(double distance) const {
  const double clamped = std::clamp(distance, 0.0, length_);
  const Vector position = positionAt(parameterAtDistance(clamped));
  const double weight = length_ > 0 ? clamped / length_ : 0.0;
  auto mix = [weight](double from, double to) { return from + (to - from) * weight; };
  PathPoint point;
  point.x = position[0];
  point.y = position[1];
  point.pressure = mix(start_.pressure, end_.pressure);
  point.tilt = mix(start_.tilt, end_.tilt);
  point.orientation = mix(start_.orientation, end_.orientation);
  point.timeMilliseconds = mix(start_.timeMilliseconds, end_.timeMilliseconds);
  point.speed = mix(start_.speed, end_.speed);
  point.finger = end_.finger;
  return point;
}

std::array<double, 2> CatmullRomSegment::tangentAtDistance(double distance) const {
  if (length_ <= 0) {
    return {1.0, 0.0};
  }
  constexpr double kHalfStepPixels = 0.25;
  const double before = std::clamp(distance - kHalfStepPixels, 0.0, length_);
  const double after = std::clamp(distance + kHalfStepPixels, 0.0, length_);
  const Vector from = positionAt(parameterAtDistance(before));
  const Vector to = positionAt(parameterAtDistance(after));
  const double norm = distanceBetween(from, to);
  if (norm <= 0) {
    return {1.0, 0.0};
  }
  return {(to[0] - from[0]) / norm, (to[1] - from[1]) / norm};
}

}  // namespace nanquim::stroke
