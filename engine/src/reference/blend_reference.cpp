#include "blend_reference.h"

#include <algorithm>
#include <cmath>

namespace nanquim::reference {

using canvas::BlendMode;

namespace {

constexpr double kHardMixThreshold = 1 - 1.0 / 1024;

double screen(double backdrop, double source) { return backdrop + source - backdrop * source; }

double hardLight(double backdrop, double source) {
  return source <= 0.5 ? backdrop * (2 * source) : screen(backdrop, 2 * source - 1);
}

double colorDodge(double backdrop, double source) {
  if (backdrop == 0) {
    return 0;
  }
  if (source == 1) {
    return 1;
  }
  return std::min(1.0, backdrop / (1 - source));
}

double colorBurn(double backdrop, double source) {
  if (backdrop == 1) {
    return 1;
  }
  if (source == 0) {
    return 0;
  }
  return 1 - std::min(1.0, (1 - backdrop) / source);
}

double softLight(double backdrop, double source) {
  if (source <= 0.5) {
    return backdrop - (1 - 2 * source) * backdrop * (1 - backdrop);
  }
  const double d =
      backdrop <= 0.25 ? ((16 * backdrop - 12) * backdrop + 4) * backdrop : std::sqrt(backdrop);
  return backdrop + (2 * source - 1) * (d - backdrop);
}

Rgb clipColor(Rgb color) {
  const double lum = luminosity(color);
  const double lowest = std::min({color[0], color[1], color[2]});
  const double highest = std::max({color[0], color[1], color[2]});
  if (lowest < 0) {
    for (double& channel : color) {
      channel = lum <= 0 ? 0 : lum + (channel - lum) * lum / (lum - lowest);
    }
  }
  if (highest > 1) {
    for (double& channel : color) {
      channel = lum >= 1 ? 1 : lum + (channel - lum) * (1 - lum) / (highest - lum);
    }
  }
  return color;
}

Rgb setLuminosity(const Rgb& color, double lum) {
  const double delta = lum - luminosity(color);
  return clipColor({color[0] + delta, color[1] + delta, color[2] + delta});
}

double saturation(const Rgb& color) {
  return std::max({color[0], color[1], color[2]}) - std::min({color[0], color[1], color[2]});
}

Rgb setSaturation(const Rgb& color, double saturationValue) {
  const double highest = std::max({color[0], color[1], color[2]});
  const double lowest = std::min({color[0], color[1], color[2]});
  if (highest <= lowest) {
    return {0, 0, 0};
  }
  Rgb result{};
  for (size_t channel = 0; channel < 3; ++channel) {
    result[channel] = (color[channel] - lowest) * saturationValue / (highest - lowest);
  }
  return result;
}

Rgb unpremultiplied(const Rgba& color) {
  if (color.alpha <= 0) {
    return {0, 0, 0};
  }
  return {color.red / color.alpha, color.green / color.alpha, color.blue / color.alpha};
}

}  // namespace

double luminosity(const Rgb& color) { return 0.3 * color[0] + 0.59 * color[1] + 0.11 * color[2]; }

double blendSeparable(BlendMode mode, double backdrop, double source) {
  switch (mode) {
    case BlendMode::Multiply:
      return backdrop * source;
    case BlendMode::Screen:
      return screen(backdrop, source);
    case BlendMode::Overlay:
      return hardLight(source, backdrop);
    case BlendMode::Darken:
      return std::min(backdrop, source);
    case BlendMode::Lighten:
      return std::max(backdrop, source);
    case BlendMode::ColorDodge:
      return colorDodge(backdrop, source);
    case BlendMode::ColorBurn:
      return colorBurn(backdrop, source);
    case BlendMode::HardLight:
      return hardLight(backdrop, source);
    case BlendMode::SoftLight:
      return softLight(backdrop, source);
    case BlendMode::Difference:
      return std::abs(backdrop - source);
    case BlendMode::Exclusion:
      return backdrop + source - 2 * backdrop * source;
    case BlendMode::Add:
      return std::min(1.0, backdrop + source);
    case BlendMode::Subtract:
      return std::max(0.0, backdrop - source);
    case BlendMode::Divide:
      if (source == 0) {
        return backdrop == 0 ? 0 : 1;
      }
      return std::min(1.0, backdrop / source);
    case BlendMode::LinearBurn:
      return std::max(0.0, backdrop + source - 1);
    case BlendMode::VividLight:
      return source <= 0.5 ? colorBurn(backdrop, 2 * source) : colorDodge(backdrop, 2 * source - 1);
    case BlendMode::LinearLight:
      return std::clamp(backdrop + 2 * source - 1, 0.0, 1.0);
    case BlendMode::PinLight:
      return source <= 0.5 ? std::min(backdrop, 2 * source) : std::max(backdrop, 2 * source - 1);
    case BlendMode::HardMix:
      return backdrop + source >= kHardMixThreshold ? 1 : 0;
    default:
      return source;
  }
}

Rgb blendColor(BlendMode mode, const Rgb& backdrop, const Rgb& source) {
  switch (mode) {
    case BlendMode::Hue:
      return setLuminosity(setSaturation(source, saturation(backdrop)), luminosity(backdrop));
    case BlendMode::Saturation:
      return setLuminosity(setSaturation(backdrop, saturation(source)), luminosity(backdrop));
    case BlendMode::Color:
      return setLuminosity(source, luminosity(backdrop));
    case BlendMode::Luminosity:
      return setLuminosity(backdrop, luminosity(source));
    case BlendMode::DarkerColor:
      return luminosity(source) < luminosity(backdrop) ? source : backdrop;
    case BlendMode::LighterColor:
      return luminosity(source) > luminosity(backdrop) ? source : backdrop;
    default:
      return {blendSeparable(mode, backdrop[0], source[0]),
              blendSeparable(mode, backdrop[1], source[1]),
              blendSeparable(mode, backdrop[2], source[2])};
  }
}

Rgba compositePixel(BlendMode mode, const Rgba& backdrop, const Rgba& source) {
  if (mode == BlendMode::Behind) {
    const double transmit = 1 - backdrop.alpha;
    return {backdrop.red + source.red * transmit, backdrop.green + source.green * transmit,
            backdrop.blue + source.blue * transmit, backdrop.alpha + source.alpha * transmit};
  }
  const double sourceAlpha = source.alpha;
  const double backdropAlpha = backdrop.alpha;
  if (mode == BlendMode::Normal || mode == BlendMode::PassThrough) {
    const double transmit = 1 - sourceAlpha;
    return {source.red + backdrop.red * transmit, source.green + backdrop.green * transmit,
            source.blue + backdrop.blue * transmit, sourceAlpha + backdropAlpha * transmit};
  }
  const Rgb cs = unpremultiplied(source);
  const Rgb cb = unpremultiplied(backdrop);
  const Rgb blended = blendColor(mode, cb, cs);
  Rgba result;
  double* channels[3] = {&result.red, &result.green, &result.blue};
  for (size_t channel = 0; channel < 3; ++channel) {
    *channels[channel] = sourceAlpha * (1 - backdropAlpha) * cs[channel] +
                         sourceAlpha * backdropAlpha * blended[channel] +
                         (1 - sourceAlpha) * backdropAlpha * cb[channel];
  }
  result.alpha = sourceAlpha + backdropAlpha * (1 - sourceAlpha);
  return result;
}

Rgba clipCompositePixel(BlendMode mode, const Rgba& backdrop, const Rgba& source) {
  const double sourceAlpha = source.alpha;
  const double backdropAlpha = backdrop.alpha;
  const Rgb cs = unpremultiplied(source);
  const Rgb cb = unpremultiplied(backdrop);
  const Rgb blended = blendColor(mode, cb, cs);
  Rgba result;
  double* channels[3] = {&result.red, &result.green, &result.blue};
  for (size_t channel = 0; channel < 3; ++channel) {
    *channels[channel] = sourceAlpha * backdropAlpha * blended[channel] +
                         (1 - sourceAlpha) * backdropAlpha * cb[channel];
  }
  result.alpha = backdropAlpha;
  return result;
}

}  // namespace nanquim::reference
