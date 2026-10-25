#pragma once

#include <deque>
#include <optional>
#include <vector>

#include "stroke/catmull_rom.h"

namespace nanquim::stroke {

class OneEuroFilter {
 public:
  OneEuroFilter(double minimumCutoffHz, double beta, double derivativeCutoffHz);

  double filter(double value, double timeMilliseconds);
  void reset() { previous_.reset(); }

 private:
  struct State {
    double value = 0;
    double derivative = 0;
    double timeMilliseconds = 0;
  };

  double minimumCutoffHz_;
  double beta_;
  double derivativeCutoffHz_;
  std::optional<State> previous_;
};

struct SmoothingParameters {
  double jitterFilter = 0;
  double jitterFilterExpression = 0;
  double streamline = 0;
  double pressureSmoothing = 0;
  double stabilization = 0;
  bool streamlineCatchUp = true;

  [[nodiscard]] bool isIdentity() const {
    return jitterFilter == 0 && streamline == 0 && pressureSmoothing == 0 && stabilization == 0;
  }
};

class StrokeSmoother {
 public:
  explicit StrokeSmoother(const SmoothingParameters& parameters);

  void addPoint(const PathPoint& point, std::vector<PathPoint>& output);

  void finish(double stepPixels, std::vector<PathPoint>& output);

 private:
  [[nodiscard]] PathPoint stabilized() const;
  void emit(const PathPoint& point, std::vector<PathPoint>& output);

  SmoothingParameters parameters_;
  OneEuroFilter filterX_;
  OneEuroFilter filterY_;
  std::optional<PathPoint> streamlined_;
  std::optional<PathPoint> lastRaw_;
  std::optional<PathPoint> lastOutput_;
  std::deque<PathPoint> window_;
  size_t windowSamples_ = 1;
};

}  // namespace nanquim::stroke
