#include "stroke/smoothing.h"

#include <algorithm>
#include <cmath>

#include "stroke/smoothing_constants.h"

namespace nanquim::stroke {

namespace {

constexpr double kPi = 3.14159265358979323846;

double lerp(double from, double to, double weight) { return from + (to - from) * weight; }

double lowPassAlpha(double cutoffHz, double intervalSeconds) {
  const double timeConstant = 1.0 / (2.0 * kPi * cutoffHz);
  return 1.0 / (1.0 + timeConstant / intervalSeconds);
}

double streamlineAlpha(double amount, double intervalMilliseconds) {
  const double alpha = lerp(1.0, kStreamlineMinimumAlpha,
                            std::pow(std::clamp(amount, 0.0, 1.0), kStreamlineExponent));
  return 1.0 -
         std::pow(1.0 - alpha, intervalMilliseconds / kStreamlineReferenceIntervalMilliseconds);
}

}  // namespace

OneEuroFilter::OneEuroFilter(double minimumCutoffHz, double beta, double derivativeCutoffHz)
    : minimumCutoffHz_(minimumCutoffHz), beta_(beta), derivativeCutoffHz_(derivativeCutoffHz) {}

double OneEuroFilter::filter(double value, double timeMilliseconds) {
  if (!previous_) {
    previous_ = State{value, 0, timeMilliseconds};
    return value;
  }
  const double intervalSeconds =
      std::max(timeMilliseconds - previous_->timeMilliseconds, kMinimumSampleIntervalMilliseconds) /
      1000.0;
  const double rawDerivative = (value - previous_->value) / intervalSeconds;
  const double derivative = lerp(previous_->derivative, rawDerivative,
                                 lowPassAlpha(derivativeCutoffHz_, intervalSeconds));
  const double cutoff = minimumCutoffHz_ + beta_ * std::abs(derivative);
  const double filtered = lerp(previous_->value, value, lowPassAlpha(cutoff, intervalSeconds));
  previous_ = State{filtered, derivative, timeMilliseconds};
  return filtered;
}

StrokeSmoother::StrokeSmoother(const SmoothingParameters& parameters)
    : parameters_(parameters),
      filterX_(lerp(kOneEuroMinimumCutoffAtZeroHz, kOneEuroMinimumCutoffAtOneHz,
                    std::clamp(parameters.jitterFilter, 0.0, 1.0)),
               kOneEuroBeta, kOneEuroDerivativeCutoffHz),
      filterY_(lerp(kOneEuroMinimumCutoffAtZeroHz, kOneEuroMinimumCutoffAtOneHz,
                    std::clamp(parameters.jitterFilter, 0.0, 1.0)),
               kOneEuroBeta, kOneEuroDerivativeCutoffHz) {
  const double stabilization = std::clamp(parameters.stabilization, 0.0, 1.0);
  windowSamples_ = 1 + static_cast<size_t>(std::lround(kStabilizationMaximumExtraSamples *
                                                       stabilization * stabilization));
}

void StrokeSmoother::addPoint(const PathPoint& point, std::vector<PathPoint>& output) {
  lastRaw_ = point;
  PathPoint current = point;

  if (parameters_.jitterFilter > 0) {
    const double expression =
        std::clamp(parameters_.jitterFilterExpression, 0.0, 1.0) * kJitterExpressionScale;
    current.x = lerp(filterX_.filter(point.x, point.timeMilliseconds), point.x, expression);
    current.y = lerp(filterY_.filter(point.y, point.timeMilliseconds), point.y, expression);
  }

  if (parameters_.streamline > 0 || parameters_.pressureSmoothing > 0) {
    if (!streamlined_) {
      streamlined_ = current;
    } else {
      const double interval = std::max(current.timeMilliseconds - streamlined_->timeMilliseconds,
                                       kMinimumSampleIntervalMilliseconds);
      PathPoint next = current;
      if (parameters_.streamline > 0) {
        const double pull = streamlineAlpha(parameters_.streamline, interval);
        next.x = lerp(streamlined_->x, current.x, pull);
        next.y = lerp(streamlined_->y, current.y, pull);
      }
      if (parameters_.pressureSmoothing > 0) {
        next.pressure = lerp(streamlined_->pressure, current.pressure,
                             streamlineAlpha(parameters_.pressureSmoothing, interval));
      }
      streamlined_ = next;
    }
    current = *streamlined_;
  }

  if (windowSamples_ > 1) {
    window_.push_back(current);
    while (window_.size() > windowSamples_) {
      window_.pop_front();
    }
    current = stabilized();
  }
  emit(current, output);
}

void StrokeSmoother::emit(const PathPoint& point, std::vector<PathPoint>& output) {
  output.push_back(point);
  lastOutput_ = point;
}

PathPoint StrokeSmoother::stabilized() const {
  double totalWeight = 0;
  PathPoint mean;
  for (size_t index = 0; index < window_.size(); ++index) {
    const double weight = index == 0 ? 0.0
                                     : std::hypot(window_[index].x - window_[index - 1].x,
                                                  window_[index].y - window_[index - 1].y);
    totalWeight += weight;
    mean.x += window_[index].x * weight;
    mean.y += window_[index].y * weight;
    mean.pressure += window_[index].pressure * weight;
  }
  if (totalWeight <= 0) {
    mean = PathPoint{};
    for (const PathPoint& sample : window_) {
      mean.x += sample.x;
      mean.y += sample.y;
      mean.pressure += sample.pressure;
    }
    totalWeight = static_cast<double>(window_.size());
  }
  const PathPoint& newest = window_.back();
  mean.x /= totalWeight;
  mean.y /= totalWeight;
  mean.pressure /= totalWeight;
  mean.tilt = newest.tilt;
  mean.orientation = newest.orientation;
  mean.timeMilliseconds = newest.timeMilliseconds;
  return mean;
}

void StrokeSmoother::finish(double stepPixels, std::vector<PathPoint>& output) {
  if (!lastRaw_) {
    return;
  }
  if (windowSamples_ > 1) {
    while (window_.size() > 1) {
      window_.pop_front();
      emit(stabilized(), output);
    }
  }
  if (parameters_.streamlineCatchUp && parameters_.streamline > 0 && lastOutput_) {
    const PathPoint from = *lastOutput_;
    const PathPoint& to = *lastRaw_;
    const double distance = std::hypot(to.x - from.x, to.y - from.y);
    const double step = std::max(stepPixels, 0.5);
    const auto steps = static_cast<int>(std::ceil(distance / step));
    for (int index = 1; index <= steps; ++index) {
      const double weight = static_cast<double>(index) / steps;
      PathPoint point = to;
      point.x = lerp(from.x, to.x, weight);
      point.y = lerp(from.y, to.y, weight);
      point.pressure = lerp(from.pressure, to.pressure, weight);
      emit(point, output);
    }
  }
}

}  // namespace nanquim::stroke
