#include "stroke/paint_mixing.h"

#include <algorithm>
#include <cmath>

namespace nanquim::stroke {

namespace {

constexpr double kShortestDragPixels = 20;
constexpr double kDragPixelsPerLength = 2000;
constexpr double kChargePixels = 1000;

double lerp(double from, double to, double weight) { return from + (to - from) * weight; }

}  // namespace

std::array<double, 2> mixSamplePoint(const Dab& dab, double boundingRadius, int32_t column,
                                     int32_t row) {
  const auto offset = [boundingRadius](int32_t index) {
    return ((index + 0.5) / kMixSampleGrid * 2.0 - 1.0) * boundingRadius;
  };
  return {dab.x + offset(column), dab.y + offset(row)};
}

DabMixing dabMixing(PaintMixing mode, const BrushParameters& brush, double strokeOpacity,
                    const Dab& dab, double previousDistance) {
  DabMixing mixing;
  mixing.mode = mode;
  const double opacity = std::clamp(strokeOpacity, 0.0, 1.0) * std::clamp(dab.opacity, 0.0, 1.0) *
                         std::clamp(dab.flow, 0.0, 1.0);
  if (mode == PaintMixing::Smudge) {
    const SmudgeParameters& smudge = brush.smudge;
    mixing.brushPaint = {dab.color[0], dab.color[1], dab.color[2], 1};
    const double dragPixels = kShortestDragPixels + kDragPixelsPerLength * smudge.length;
    const double step = std::max(0.0, dab.distance - previousDistance);
    mixing.pickUp = 1.0 - (1.0 - smudge.pickUp) * std::exp(-step / dragPixels);
    mixing.useBrushColor = smudge.useBrushColor;
    mixing.amount = smudge.strength * opacity;
  } else if (mode == PaintMixing::WetMix) {
    const WetMixParameters& wet = brush.wetMix;
    const double grade = lerp(1.0, std::clamp(dab.pressure, 0.0, 1.0), wet.grade);
    const double charge =
        wet.charge <= 0 ? 0.0 : std::clamp(1.0 - dab.distance / (wet.charge * kChargePixels), 0.0, 1.0);
    mixing.brushPaint = {dab.color[0] * charge, dab.color[1] * charge, dab.color[2] * charge,
                         charge};
    mixing.pull = std::clamp(wet.pull * grade, 0.0, 1.0);
    mixing.blur = std::clamp(wet.blur + wet.blurJitter * dab.wetJitter[1], 0.0, 1.0);
    const double dilution =
        std::clamp(wet.dilution + wet.wetnessJitter * dab.wetJitter[0], 0.0, 1.0);
    mixing.amount = opacity * wet.attack * grade * (1.0 - dilution);
  }
  return mixing;
}

MixedPaint mixPaint(const DabMixing& mixing, const std::array<double, 4>& sample, bool hasSample,
                    MixingState& state) {
  MixedPaint result;
  result.sample = sample;
  if (!hasSample) {
    return result;
  }
  result.apply = true;
  if (mixing.mode == PaintMixing::Smudge) {
    for (size_t channel = 0; channel < 4; ++channel) {
      state.carried[channel] = state.started
                                   ? lerp(state.carried[channel], sample[channel], mixing.pickUp)
                                   : sample[channel];
      result.paint[channel] =
          lerp(state.carried[channel], mixing.brushPaint[channel], mixing.useBrushColor);
    }
    state.started = true;
  } else {
    for (size_t channel = 0; channel < 4; ++channel) {
      result.paint[channel] = lerp(mixing.brushPaint[channel], sample[channel], mixing.pull);
    }
  }
  return result;
}

void applyMixedPaint(const DabMixing& mixing, const MixedPaint& paint, double tip, double* pixel) {
  if (!paint.apply || tip <= 0) {
    return;
  }
  if (mixing.mode == PaintMixing::Smudge) {
    const double weight = std::clamp(tip * mixing.amount, 0.0, 1.0);
    for (size_t channel = 0; channel < 4; ++channel) {
      pixel[channel] = lerp(pixel[channel], paint.paint[channel], weight);
    }
    return;
  }
  const double blur = std::clamp(tip * mixing.blur, 0.0, 1.0);
  const double deposit = std::clamp(tip * mixing.amount, 0.0, 1.0);
  for (size_t channel = 0; channel < 4; ++channel) {
    const double softened = lerp(pixel[channel], paint.sample[channel], blur);
    pixel[channel] = paint.paint[channel] * deposit + softened * (1.0 - paint.paint[3] * deposit);
  }
}

}  // namespace nanquim::stroke
