#pragma once

#include <array>
#include <cstdint>
#include <deque>
#include <vector>

#include "stroke/brush_parameters.h"
#include "stroke/catmull_rom.h"
#include "stroke/color_dynamics.h"
#include "stroke/dab.h"
#include "stroke/input_sample.h"
#include "stroke/random.h"
#include "stroke/smoothing.h"

namespace nanquim::stroke {

inline StrokeSettings dualStrokeSettings(const StrokeSettings& settings) {
  StrokeSettings dual = settings;
  dual.seed = settings.seed ^ 0xA0761D6478BD642FULL;
  return dual;
}

inline PathPoint pathPointFrom(const InputSample& sample) {
  PathPoint point;
  point.x = sample.x;
  point.y = sample.y;
  point.pressure = sample.pressure;
  point.tilt = sample.tilt;
  point.orientation = sample.orientation;
  point.timeMilliseconds = static_cast<double>(sample.eventTimeNanos) / 1e6;
  point.finger = sample.toolType == kToolTypeFinger;
  return point;
}

class DabGenerator {
 public:
  static constexpr int kRandomNumbersPerDab = 16;
  static constexpr uint32_t kCheckpointInterval = 64;
  static constexpr double kSpeedSmoothingMilliseconds = 30;
  static constexpr double kGrainZoomReferencePixels = 256;
  static constexpr double kStampTurnDistancePixels = 8;
  static constexpr double kTipAnimationMilliseconds = 400;
  static constexpr double kTipAnimationStep = 0.05;

  DabGenerator(BrushParameters brush, StrokeSettings settings);

  void addPoint(const PathPoint& point, std::vector<Dab>& dabs);

  void finish(std::vector<Dab>& dabs);

  [[nodiscard]] uint32_t dabCount() const { return nextDabIndex_; }

  [[nodiscard]] double finalTimeMilliseconds() const;
  [[nodiscard]] const std::vector<Random::State>& randomCheckpoints() const { return checkpoints_; }
  [[nodiscard]] const BrushParameters& brush() const { return brush_; }
  [[nodiscard]] const StrokeSettings& settings() const { return settings_; }

  [[nodiscard]] DabGenerator replacement() const {
    StrokeSettings settings = settings_;
    settings.smoothPositions = false;
    return {brush_, std::move(settings)};
  }

 private:
  struct PendingDab {
    Dab dab;
    double distance = 0;
    double startProgress = 1;
    double sizeWithoutPressure = 1;
    double pressureSize = 1;
    double opacityWithoutTaper = 1;
    std::array<double, 2> center{};
    std::array<double, 2> tangent{1, 0};
    double scatterOffset = 0;
    double lateralOffset = 0;
    std::array<double, 2> grainRandom{};
  };

  void addPathPoint(const PathPoint& point, std::vector<Dab>& dabs);
  void finishPath(std::vector<Dab>& dabs);
  void addSmoothedPoint(PathPoint point, std::vector<Dab>& dabs);
  void addStampPoint(const PathPoint& point, std::vector<Dab>& dabs);
  void animateTip(const PathPoint& point, std::vector<Dab>& dabs);
  void walkSegment(const CatmullRomSegment& segment, std::vector<Dab>& dabs);
  double emitStep(const PathPoint& point, std::array<double, 2> tangent, double distance);
  void releasePending(std::vector<Dab>& dabs);
  [[nodiscard]] Dab finalize(const PendingDab& pending, double endProgress) const;
  [[nodiscard]] double taperedPressureSize(double pressureSize, double progress) const;
  [[nodiscard]] double taperProgress(double distance, double length) const;
  [[nodiscard]] double spacingFor(double diameter) const;
  [[nodiscard]] double clampDiameter(double diameter) const;

  BrushParameters brush_;
  StrokeSettings settings_;
  AccumulationPrimitive accumulation_;
  [[nodiscard]] const TaperSettings& taper() const {
    return finger_ ? brush_.taper.finger : brush_.taper.stylus;
  }
  double taperStartPixels_ = 0;
  double taperEndPixels_ = 0;
  double grainPeriodPixels_ = 256;
  Random random_;
  Random extraRandom_;
  double strokeAngle_ = 0;
  std::array<double, 2> strokeGrainOffset_{};
  ColorJitter strokeColorJitter_;
  StrokeSmoother smoother_;
  std::vector<PathPoint> smoothedPoints_;
  bool started_ = false;
  bool finger_ = false;
  std::optional<PathPoint> lastSmoothed_;
  std::array<double, 2> strokeStart_{};
  double lastBaseDiameter_ = 0;
  std::vector<PathPoint> points_;
  double walkedLength_ = 0;
  double walkedUntilMilliseconds_ = 0;
  double distanceToNextDab_ = 0;
  std::deque<PendingDab> pending_;
  uint32_t nextDabIndex_ = 0;
  std::vector<Random::State> checkpoints_;
  bool finished_ = false;
  double strokeStartMilliseconds_ = 0;
  double heldStartProgress_ = 0;
  bool stampPlaced_ = false;
  double stampAngle_ = 0;
};

struct TipFootprint {
  double diameter = 0;
  double angle = 0;
  double roundness = 1;
};
TipFootprint tipFootprint(BrushParameters brush, StrokeSettings settings, double pressure);

}  // namespace nanquim::stroke
