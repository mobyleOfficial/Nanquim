#include "stroke/dab_generator.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <optional>
#include <utility>

#include "stroke/color_dynamics.h"
#include "stroke/symmetry.h"

namespace nanquim::stroke {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kMinimumPointDistancePixels = 0.05;
constexpr double kMinimumSpacingPixels = 0.5;
constexpr double kFalloffDistancePixels = 2000;
constexpr double kFingerPressureSpeedPixelsPerMillisecond = 3.0;
constexpr double kFullSpeedPixelsPerMillisecond = 2.0;
constexpr double kTapLengthPixels = 1.0;
constexpr uint64_t kStrokeStreamMix = 0x9E3779B97F4A7C15ULL;
constexpr uint64_t kExtraStreamMix = 0xD1B54A32D192ED03ULL;

double lerp(double from, double to, double weight) { return from + (to - from) * weight; }

double smoothstep(double value) {
  const double t = std::clamp(value, 0.0, 1.0);
  return t * t * (3.0 - 2.0 * t);
}

double normalizedTilt(double tilt) { return std::clamp(tilt / (kPi / 2), 0.0, 1.0); }

double pressureTerm(const Dynamics& dynamics, double pressure) {
  if (!dynamics.pressure) {
    return 1.0;
  }
  return lerp(1.0, lerp(dynamics.minimum, 1.0, dynamics.pressure->evaluate(pressure)),
              dynamics.pressureAmount);
}

double tiltTerm(const Dynamics& dynamics, double tilt) {
  if (!dynamics.tilt) {
    return 1.0;
  }
  return lerp(1.0, dynamics.tilt->evaluate(normalizedTilt(tilt)), dynamics.tiltAmount);
}

double speedTerm(const Dynamics& dynamics, double speed) {
  return 1.0 + dynamics.speed *
                   (std::clamp(speed / kFullSpeedPixelsPerMillisecond, 0.0, 1.0) - 0.5) * 2.0;
}

double bleedFor(const Dynamics& bleed, double pressure, double tilt) {
  double value = bleed.minimum;
  if (bleed.pressure) {
    value += bleed.pressureAmount * bleed.pressure->evaluate(pressure);
  }
  if (bleed.tilt) {
    value += bleed.tiltAmount * bleed.tilt->evaluate(normalizedTilt(tilt));
  }
  return std::clamp(value, 0.0, 1.0);
}

SmoothingParameters smoothingFor(const BrushParameters& brush, const StrokeSettings& settings) {
  if (!settings.smoothPositions) {
    return SmoothingParameters{
        0, 0, 0, brush.path.pressureSmoothing, 0, settings.streamlineCatchUp};
  }
  const double streamline = 1.0 - (1.0 - std::clamp(brush.path.streamline, 0.0, 1.0)) *
                                      (1.0 - std::clamp(settings.globalSmoothing, 0.0, 1.0));
  return SmoothingParameters{
      brush.path.jitterFilter,      brush.path.jitterFilterExpression, streamline,
      brush.path.pressureSmoothing, brush.path.stabilization,          settings.streamlineCatchUp};
}

}  // namespace

DabGenerator::DabGenerator(BrushParameters brush, StrokeSettings settings)
    : brush_(std::move(brush)),
      settings_(std::move(settings)),
      accumulation_(primitiveFor(brush_.render.accumulation)),
      random_(settings_.seed),
      extraRandom_(settings_.seed ^ kExtraStreamMix),
      smoother_(smoothingFor(brush_, settings_)) {
  brush_.shape.count = std::clamp(brush_.shape.count, 1, 16);
  if (brush_.stamp.enabled) {
    brush_.shape.count = 1;
  }
  const GrainParameters& grain = brush_.grain;
  grainPeriodPixels_ =
      256.0 / std::clamp(grain.scale, 0.05, 16.0) *
      lerp(1.0, settings_.sizePixels / kGrainZoomReferencePixels, std::clamp(grain.zoom, 0.0, 1.0));
  Random strokeRandom(settings_.seed ^ kStrokeStreamMix);
  strokeAngle_ = strokeRandom.uniform(0, 2 * kPi);
  strokeGrainOffset_ = {strokeRandom.nextUnit(), strokeRandom.nextUnit()};
  strokeColorJitter_.hue = strokeRandom.uniform(-1, 1);
  strokeColorJitter_.saturation = strokeRandom.uniform(-1, 1);
  strokeColorJitter_.brightness = strokeRandom.uniform(-1, 1);
  strokeColorJitter_.darkness = strokeRandom.nextUnit();
  strokeColorJitter_.secondary = strokeRandom.nextUnit();
}

double DabGenerator::spacingFor(double diameter) const {
  return std::max(kMinimumSpacingPixels, brush_.path.spacing * diameter);
}

double DabGenerator::clampDiameter(double diameter) const {
  return std::clamp(diameter, brush_.limits.minSize, brush_.limits.maxSize);
}

double DabGenerator::finalTimeMilliseconds() const {
  if (finished_) {
    return std::numeric_limits<double>::infinity();
  }
  if (!pending_.empty()) {
    return std::min(walkedUntilMilliseconds_, pending_.front().dab.timeMilliseconds);
  }
  return walkedUntilMilliseconds_;
}

void DabGenerator::addPoint(const PathPoint& point, std::vector<Dab>& dabs) {
  const size_t first = dabs.size();
  addPathPoint(point, dabs);
  replicateDabs(settings_.symmetry, dabs, first);
}

void DabGenerator::finish(std::vector<Dab>& dabs) {
  const size_t first = dabs.size();
  finishPath(dabs);
  replicateDabs(settings_.symmetry, dabs, first);
}

void DabGenerator::addPathPoint(const PathPoint& point, std::vector<Dab>& dabs) {
  if (finished_) {
    return;
  }
  PathPoint input = point;
  if (!started_) {
    started_ = true;
    finger_ = point.finger;
    const double scale = taper().relative ? settings_.sizePixels : 1.0;
    taperStartPixels_ = std::max(0.0, taper().start * scale);
    taperEndPixels_ = std::max(0.0, taper().end * scale);
  }
  if (!finger_ && settings_.pressureCurve) {
    input.pressure = settings_.pressureCurve->evaluate(std::clamp(input.pressure, 0.0, 1.0));
  }
  smoothedPoints_.clear();
  smoother_.addPoint(input, smoothedPoints_);
  for (const PathPoint& smoothed : smoothedPoints_) {
    addSmoothedPoint(smoothed, dabs);
  }
}

void DabGenerator::addSmoothedPoint(PathPoint point, std::vector<Dab>& dabs) {
  if (lastSmoothed_) {
    const double interval = point.timeMilliseconds - lastSmoothed_->timeMilliseconds;
    if (interval > 0) {
      const double instant =
          std::hypot(point.x - lastSmoothed_->x, point.y - lastSmoothed_->y) / interval;
      const double weight = 1.0 - std::exp(-interval / kSpeedSmoothingMilliseconds);
      point.speed = lerp(lastSmoothed_->speed, instant, weight);
    } else {
      point.speed = lastSmoothed_->speed;
    }
  }
  lastSmoothed_ = point;
  if (finger_) {
    point.pressure =
        brush_.dynamics.fingerPressureFromSpeed
            ? 1.0 - std::clamp(point.speed / kFingerPressureSpeedPixelsPerMillisecond, 0.0, 1.0)
            : 1.0;
  }

  if (brush_.stamp.enabled) {
    addStampPoint(point, dabs);
    return;
  }
  if (points_.empty()) {
    points_.push_back(point);
    strokeStart_ = {point.x, point.y};
    strokeStartMilliseconds_ = point.timeMilliseconds;
    const double diameter = emitStep(point, {1.0, 0.0}, 0.0);
    distanceToNextDab_ = spacingFor(diameter);
    walkedUntilMilliseconds_ = point.timeMilliseconds;
    releasePending(dabs);
    return;
  }
  PathPoint& last = points_.back();
  if (std::hypot(point.x - last.x, point.y - last.y) < kMinimumPointDistancePixels) {
    last.pressure = point.pressure;
    last.tilt = point.tilt;
    last.orientation = point.orientation;
    last.timeMilliseconds = point.timeMilliseconds;
    last.speed = point.speed;
    if (points_.size() == 1 && taper().tipAnimation && taperStartPixels_ > 0) {
      animateTip(last, dabs);
    }
    return;
  }
  points_.push_back(point);
  if (points_.size() > 4) {
    points_.erase(points_.begin());
  }
  const size_t count = points_.size();
  if (count < 3) {
    return;
  }
  const PathPoint& before = count >= 4 ? points_[count - 4] : points_[count - 3];
  walkSegment(CatmullRomSegment(before, points_[count - 3], points_[count - 2], points_[count - 1]),
              dabs);
}

void DabGenerator::animateTip(const PathPoint& point, std::vector<Dab>& dabs) {
  const double progress = std::clamp(
      (point.timeMilliseconds - strokeStartMilliseconds_) / kTipAnimationMilliseconds, 0.0, 1.0);
  if (progress < heldStartProgress_ + kTipAnimationStep &&
      !(progress >= 1.0 && heldStartProgress_ < 1.0)) {
    return;
  }
  heldStartProgress_ = progress;
  emitStep(point, {1.0, 0.0}, 0.0);
  releasePending(dabs);
}

void DabGenerator::addStampPoint(const PathPoint& point, std::vector<Dab>& dabs) {
  walkedUntilMilliseconds_ = point.timeMilliseconds;
  if (!stampPlaced_) {
    stampPlaced_ = true;
    strokeStart_ = {point.x, point.y};
    emitStep(point, {1.0, 0.0}, 0.0);
    PendingDab& stamp = pending_.front();
    stamp.startProgress = 1.0;
    stampAngle_ = stamp.dab.angle;
    if (!brush_.stamp.placeOnRelease) {
      dabs.push_back(finalize(stamp, 1.0));
      pending_.clear();
    }
    return;
  }
  if (!pending_.empty()) {
    const double dx = point.x - strokeStart_[0];
    const double dy = point.y - strokeStart_[1];
    if (std::hypot(dx, dy) >= kStampTurnDistancePixels) {
      pending_.front().dab.angle = stampAngle_ + std::atan2(dy, dx);
    }
  }
}

void DabGenerator::finishPath(std::vector<Dab>& dabs) {
  if (finished_) {
    return;
  }
  if (brush_.stamp.enabled) {
    smoothedPoints_.clear();
    smoother_.finish(spacingFor(lastBaseDiameter_), smoothedPoints_);
    for (const PathPoint& smoothed : smoothedPoints_) {
      addSmoothedPoint(smoothed, dabs);
    }
    finished_ = true;
    for (const PendingDab& pending : pending_) {
      dabs.push_back(finalize(pending, 1.0));
    }
    pending_.clear();
    return;
  }
  smoothedPoints_.clear();
  smoother_.finish(spacingFor(lastBaseDiameter_), smoothedPoints_);
  for (const PathPoint& smoothed : smoothedPoints_) {
    addSmoothedPoint(smoothed, dabs);
  }
  finished_ = true;
  const size_t count = points_.size();
  if (count >= 2) {
    const PathPoint& before = count >= 3 ? points_[count - 3] : points_[count - 2];
    walkSegment(
        CatmullRomSegment(before, points_[count - 2], points_[count - 1], points_[count - 1]),
        dabs);
  }
  const double length = walkedLength_;
  const bool tap = length < kTapLengthPixels;
  const bool animatedTap = tap && taper().tipAnimation && taperStartPixels_ > 0 && !points_.empty();
  const double heldProgress =
      animatedTap ? std::clamp((points_.back().timeMilliseconds - strokeStartMilliseconds_) /
                                   kTipAnimationMilliseconds,
                               0.0, 1.0)
                  : 1.0;
  for (const PendingDab& pending : pending_) {
    const double endProgress =
        tap || taperEndPixels_ <= 0
            ? 1.0
            : std::clamp((length - pending.distance) / taperEndPixels_, 0.0, 1.0);
    PendingDab untapered = pending;
    if (tap) {
      untapered.startProgress = animatedTap ? std::max(pending.startProgress, heldProgress) : 1.0;
    }
    dabs.push_back(finalize(untapered, endProgress));
  }
  pending_.clear();
}

void DabGenerator::walkSegment(const CatmullRomSegment& segment, std::vector<Dab>& dabs) {
  const double length = segment.length();
  double distance = distanceToNextDab_;
  while (distance <= length) {
    const double diameter = emitStep(segment.pointAtDistance(distance),
                                     segment.tangentAtDistance(distance), walkedLength_ + distance);
    distance += spacingFor(diameter);
  }
  distanceToNextDab_ = distance - length;
  walkedLength_ += length;
  walkedUntilMilliseconds_ = segment.end().timeMilliseconds;
  releasePending(dabs);
}

void DabGenerator::releasePending(std::vector<Dab>& dabs) {
  if ((taperStartPixels_ > 0 || taperEndPixels_ > 0) && walkedLength_ < kTapLengthPixels) {
    return;
  }
  while (!pending_.empty() && walkedLength_ - pending_.front().distance >= taperEndPixels_) {
    dabs.push_back(finalize(pending_.front(), 1.0));
    pending_.pop_front();
  }
}

double DabGenerator::taperProgress(double distance, double length) const {
  return length > 0 ? std::clamp(distance / length, 0.0, 1.0) : 1.0;
}

double DabGenerator::taperedPressureSize(double pressureSize, double progress) const {
  if (progress >= 1.0) {
    return pressureSize;
  }
  const double shaped =
      smoothstep(std::pow(progress, std::pow(4.0, -std::clamp(taper().shape, -1.0, 1.0))));
  const double tipSize = std::clamp(taper().minSize, 0.0, 1.0) * std::clamp(taper().tip, 0.0, 1.0);
  if (taper().pressureMode && !brush_.taper.classic) {
    return pressureSize * lerp(tipSize, 1.0, shaped);
  }
  return lerp(tipSize, pressureSize, shaped);
}

Dab DabGenerator::finalize(const PendingDab& pending, double endProgress) const {
  const double progress = std::min(pending.startProgress, endProgress);
  Dab dab = pending.dab;
  dab.diameter = clampDiameter(settings_.sizePixels * pending.sizeWithoutPressure *
                               taperedPressureSize(pending.pressureSize, progress));
  double opacityTaper = 1.0;
  if (progress < 1.0) {
    const double shaped =
        smoothstep(std::pow(progress, std::pow(4.0, -std::clamp(taper().shape, -1.0, 1.0))));
    opacityTaper = lerp(std::clamp(taper().minOpacity, 0.0, 1.0), 1.0, shaped);
  }
  dab.opacity = std::clamp(pending.opacityWithoutTaper * opacityTaper, 0.0, 1.0);
  const double normalX = -pending.tangent[1];
  const double normalY = pending.tangent[0];
  const double scatter = pending.scatterOffset * dab.diameter;
  const double lateral = pending.lateralOffset * dab.diameter;
  dab.x = pending.center[0] + normalX * scatter + pending.tangent[0] * lateral;
  dab.y = pending.center[1] + normalY * scatter + pending.tangent[1] * lateral;

  const GrainParameters& grain = brush_.grain;
  if (grain.enabled()) {
    if (grain.moving()) {
      dab.grainOffset = grain.offsetJitter ? pending.grainRandom : std::array<double, 2>{0, 0};
    } else {
      const double movement = std::clamp(grain.movement, 0.0, 1.0);
      dab.grainOffset = {movement * (dab.x - strokeStart_[0]),
                         movement * (dab.y - strokeStart_[1])};
      if (grain.perStroke) {
        dab.grainOffset[0] += strokeGrainOffset_[0] * grainPeriodPixels_;
        dab.grainOffset[1] += strokeGrainOffset_[1] * grainPeriodPixels_;
      }
      dab.grainPeriod = grainPeriodPixels_;
    }
  }
  return dab;
}

double DabGenerator::emitStep(const PathPoint& point, std::array<double, 2> tangent,
                              double distance) {
  const DynamicsParameters& dynamics = brush_.dynamics;
  const ShapeParameters& shape = brush_.shape;
  const double pressure = std::clamp(point.pressure, 0.0, 1.0);
  const double tilt = normalizedTilt(point.tilt);

  double sizeTilt = tiltTerm(dynamics.size, point.tilt);
  sizeTilt =
      1.0 + (sizeTilt - 1.0) * (1.0 - std::clamp(dynamics.tiltSizeCompression, 0.0, 1.0) * tilt);
  const double sizeOther = std::max(0.0, sizeTilt * speedTerm(dynamics.size, point.speed));
  const double pressureSize = std::max(0.0, pressureTerm(dynamics.size, pressure));
  const double startProgress =
      std::max(taperProgress(distance, taperStartPixels_), heldStartProgress_);
  const double baseDiameter = clampDiameter(settings_.sizePixels * sizeOther *
                                            taperedPressureSize(pressureSize, startProgress));
  lastBaseDiameter_ = baseDiameter;

  const double falloff = 1.0 - std::clamp(brush_.path.falloff, 0.0, 1.0) *
                                   std::clamp(distance / kFalloffDistancePixels, 0.0, 1.0);
  const double baseOpacity = pressureTerm(dynamics.opacity, pressure) *
                             tiltTerm(dynamics.opacity, point.tilt) *
                             speedTerm(dynamics.opacity, point.speed) * falloff;
  const double flow =
      std::clamp(brush_.render.flow * pressureTerm(dynamics.flow, pressure) *
                     tiltTerm(dynamics.flow, point.tilt) * speedTerm(dynamics.flow, point.speed) *
                     accumulation_.flowScale,
                 0.0, 1.0);
  const double tangentAngle = std::atan2(tangent[1], tangent[0]);
  double roundness = shape.roundness;
  if (shape.pressureRoundness) {
    roundness *=
        lerp(1.0, shape.pressureRoundness->evaluate(pressure), shape.pressureRoundnessAmount);
  }
  roundness *= 1.0 - std::clamp(shape.tiltRoundnessAmount, 0.0, 1.0) * tilt;
  roundness = std::clamp(roundness, 0.05, 1.0);
  const double bleed = bleedFor(dynamics.bleed, pressure, point.tilt);
  const double screenCorrection = brush_.orientToScreen ? -settings_.viewRotation : 0.0;
  const GrainParameters& grain = brush_.grain;
  double grainDepth = std::clamp(grain.depth, 0.0, 1.0);
  if (grain.pressureDepth) {
    grainDepth *= lerp(1.0, grain.pressureDepth->evaluate(pressure), grain.pressureDepthAmount);
  }

  int32_t stepCount = shape.count;
  for (int32_t step = 0; step < stepCount; ++step) {
    if (nextDabIndex_ % kCheckpointInterval == 0) {
      checkpoints_.push_back(random_.state());
    }
    const double sizeJitter = random_.uniform(-1, 1);
    const double opacityJitter = random_.nextUnit();
    const double randomAngle = random_.uniform(0, 2 * kPi);
    const double angleJitter = random_.uniform(-kPi, kPi);
    const double scatter = random_.uniform(-1, 1);
    const double lateral = random_.uniform(-1, 1);
    const double flipX = random_.nextUnit();
    const double flipY = random_.nextUnit();
    const double grainX = random_.nextUnit();
    const double grainY = random_.nextUnit();
    const double depthJitter = random_.nextUnit();
    const double countJitter = random_.nextUnit();
    ColorJitter colorJitter;
    colorJitter.hue = random_.uniform(-1, 1);
    colorJitter.saturation = random_.uniform(-1, 1);
    colorJitter.brightness = random_.uniform(-1, 1);
    colorJitter.darkness = random_.nextUnit();
    colorJitter.secondary = extraRandom_.nextUnit();
    const double wetnessJitter = extraRandom_.uniform(-1, 1);
    const double blurJitter = extraRandom_.uniform(-1, 1);
    if (step == 0 && shape.countJitter > 0) {
      stepCount = std::max(
          1, static_cast<int32_t>(std::lround(
                 shape.count * (1.0 - std::clamp(shape.countJitter, 0.0, 1.0) * countJitter))));
    }

    PendingDab pending;
    pending.distance = distance;
    pending.startProgress = startProgress;
    pending.sizeWithoutPressure = sizeOther * (1 + dynamics.size.jitter * sizeJitter);
    pending.pressureSize = pressureSize;
    pending.opacityWithoutTaper = baseOpacity * (1 - dynamics.opacity.jitter * opacityJitter);
    pending.center = {point.x, point.y};
    pending.tangent = tangent;
    pending.scatterOffset = shape.scatter * scatter;
    pending.lateralOffset = brush_.path.jitterLateral * lateral;
    pending.grainRandom = {grainX, grainY};

    Dab& dab = pending.dab;
    dab.flow = flow;
    switch (shape.rotationMode) {
      case RotationMode::Fixed:
        dab.angle = shape.rotation + screenCorrection;
        break;
      case RotationMode::FollowStroke:
        dab.angle = tangentAngle + shape.rotation;
        break;
      case RotationMode::PenAzimuth:
        dab.angle = point.orientation + shape.rotation + screenCorrection;
        break;
      case RotationMode::Random:
        dab.angle = randomAngle;
        break;
    }
    if (shape.randomized) {
      dab.angle += strokeAngle_;
    }
    dab.angle += shape.rotationJitter * angleJitter;
    if (brush_.roll.enabled && settings_.rollFromOrientation) {
      dab.angle += point.orientation * brush_.roll.amount;
    }
    dab.roundness = roundness;
    dab.color = dynamicColor(brush_.colorDynamics, settings_.color, settings_.secondaryColor,
                             pressure, tilt, strokeColorJitter_, colorJitter);
    dab.flipX = flipX < shape.flipXJitter;
    dab.flipY = flipY < shape.flipYJitter;
    dab.bleed = bleed;
    dab.gradation = std::clamp(dynamics.tiltGradation, 0.0, 1.0) * tilt;
    dab.gradationAngle = point.orientation + screenCorrection;
    dab.grainDepth =
        std::max(std::clamp(grain.depthMinimum, 0.0, 1.0),
                 grainDepth * (1.0 - std::clamp(grain.depthJitter, 0.0, 1.0) * depthJitter));
    dab.distance = distance;
    dab.pressure = pressure;
    dab.timeMilliseconds = point.timeMilliseconds;
    dab.wetJitter = {wetnessJitter, blurJitter};
    dab.index = nextDabIndex_++;
    pending_.push_back(pending);
  }
  return baseDiameter;
}

TipFootprint tipFootprint(BrushParameters brush, StrokeSettings settings, double pressure) {
  brush.dynamics.size.jitter = 0;
  brush.shape.rotationJitter = 0;
  brush.shape.randomized = false;
  brush.shape.scatter = 0;
  brush.shape.count = 1;
  brush.shape.countJitter = 0;
  brush.path.jitterLateral = 0;
  brush.taper.stylus.start = 0;
  brush.taper.stylus.end = 0;
  brush.stamp = StampParameters{};
  if (brush.shape.rotationMode == RotationMode::Random) {
    brush.shape.rotationMode = RotationMode::Fixed;
  }
  settings.pressureCurve.reset();
  DabGenerator generator(std::move(brush), std::move(settings));
  PathPoint point;
  point.pressure = std::clamp(pressure, 0.0, 1.0);
  std::vector<Dab> dabs;
  generator.addPoint(point, dabs);
  generator.finish(dabs);
  if (dabs.empty()) {
    return {};
  }
  return TipFootprint{dabs.front().diameter, dabs.front().angle, dabs.front().roundness};
}

}  // namespace nanquim::stroke
