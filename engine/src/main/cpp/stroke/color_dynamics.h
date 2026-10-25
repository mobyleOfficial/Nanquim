#pragma once

#include <array>

#include "stroke/brush_parameters.h"

namespace nanquim::stroke {

struct Hsb {
  double hue = 0;
  double saturation = 0;
  double brightness = 0;
};

Hsb hsbFromRgb(const std::array<double, 3>& rgb);
std::array<double, 3> rgbFromHsb(const Hsb& hsb);

struct ColorJitter {
  double hue = 0;
  double saturation = 0;
  double brightness = 0;
  double darkness = 0;
  double secondary = 0;
};

std::array<double, 4> dynamicColor(const ColorDynamicsParameters& dynamics,
                                   const std::array<double, 4>& primary,
                                   const std::array<double, 3>& secondary, double pressure,
                                   double normalizedTilt, const ColorJitter& stroke,
                                   const ColorJitter& dab);

}  // namespace nanquim::stroke
