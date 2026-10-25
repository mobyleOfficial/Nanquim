#pragma once

#include <array>
#include <cstdint>

#include "stroke/brush_parameters.h"
#include "stroke/dab.h"

namespace nanquim::stroke {

enum class PaintMixing : int32_t {
  None = 0,
  Smudge = 1,
  WetMix = 2,
};

constexpr int32_t kMixSampleGrid = 16;

std::array<double, 2> mixSamplePoint(const Dab& dab, double boundingRadius, int32_t column,
                                     int32_t row);

struct DabMixing {
  PaintMixing mode = PaintMixing::None;
  std::array<double, 4> brushPaint{0, 0, 0, 0};
  double pickUp = 0;
  double useBrushColor = 0;
  double pull = 0;
  double blur = 0;
  double amount = 0;
};

DabMixing dabMixing(PaintMixing mode, const BrushParameters& brush, double strokeOpacity,
                    const Dab& dab, double previousDistance);

struct MixingState {
  std::array<double, 4> carried{0, 0, 0, 0};
  bool started = false;
  bool hasPrevious = false;
  double previousDistance = 0;
};

struct MixedPaint {
  std::array<double, 4> paint{0, 0, 0, 0};
  std::array<double, 4> sample{0, 0, 0, 0};
  bool apply = false;
};
MixedPaint mixPaint(const DabMixing& mixing, const std::array<double, 4>& sample, bool hasSample,
                    MixingState& state);

void applyMixedPaint(const DabMixing& mixing, const MixedPaint& paint, double tip, double* pixel);

}  // namespace nanquim::stroke
