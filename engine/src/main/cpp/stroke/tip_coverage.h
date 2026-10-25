#pragma once

#include <array>

#include "stroke/brush_parameters.h"
#include "stroke/brush_texture.h"
#include "stroke/dab.h"

namespace nanquim::stroke {

struct TipStyle {
  double hardness = 1;
  const GrayTexture* shape = nullptr;
  bool shapeInvert = false;
  TextureFiltering shapeFiltering = TextureFiltering::Improved;
  const GrainParameters* grain = nullptr;
  const GrayTexture* grainTexture = nullptr;

  [[nodiscard]] bool textured() const { return shape != nullptr; }
  [[nodiscard]] bool colored() const { return shape != nullptr && shape->hasColor(); }
  [[nodiscard]] bool hasGrain() const { return grain != nullptr && grainTexture != nullptr; }
};

double dabCoverage(const Dab& dab, const TipStyle& style, double canvasX, double canvasY);

bool dabTipColor(const Dab& dab, const TipStyle& style, double canvasX, double canvasY,
                 std::array<double, 3>& color);

double adjustGrain(double grain, const GrainParameters& parameters);

double grainBlend(double coverage, double grain, double depth, GrainBlend blend);

inline double dabBoundingRadius(const Dab& dab, bool textured) {
  return (textured ? dab.diameter * 0.70710678118654752 : dab.diameter / 2) + 1.0;
}

}  // namespace nanquim::stroke
