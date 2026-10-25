#pragma once

#include <array>

#include "canvas/blend_mode.h"

namespace nanquim::reference {

struct Rgba {
  double red = 0;
  double green = 0;
  double blue = 0;
  double alpha = 0;
};

using Rgb = std::array<double, 3>;

double blendSeparable(canvas::BlendMode mode, double backdrop, double source);

Rgb blendColor(canvas::BlendMode mode, const Rgb& backdrop, const Rgb& source);

Rgba compositePixel(canvas::BlendMode mode, const Rgba& backdrop, const Rgba& source);

Rgba clipCompositePixel(canvas::BlendMode mode, const Rgba& backdrop, const Rgba& source);

double luminosity(const Rgb& color);

}  // namespace nanquim::reference
