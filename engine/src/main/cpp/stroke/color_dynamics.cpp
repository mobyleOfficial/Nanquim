#include "stroke/color_dynamics.h"

#include <algorithm>
#include <cmath>

namespace nanquim::stroke {

namespace {

constexpr double kHueTurnsPerUnit = 0.5;

}  // namespace

Hsb hsbFromRgb(const std::array<double, 3>& rgb) {
  const double maximum = std::max({rgb[0], rgb[1], rgb[2]});
  const double minimum = std::min({rgb[0], rgb[1], rgb[2]});
  const double chroma = maximum - minimum;
  Hsb hsb;
  hsb.brightness = maximum;
  hsb.saturation = maximum > 0 ? chroma / maximum : 0;
  if (chroma > 0) {
    double sector = 0;
    if (maximum == rgb[0]) {
      sector = std::fmod((rgb[1] - rgb[2]) / chroma + 6.0, 6.0);
    } else if (maximum == rgb[1]) {
      sector = (rgb[2] - rgb[0]) / chroma + 2.0;
    } else {
      sector = (rgb[0] - rgb[1]) / chroma + 4.0;
    }
    hsb.hue = sector / 6.0;
  }
  return hsb;
}

std::array<double, 3> rgbFromHsb(const Hsb& hsb) {
  const double hue = hsb.hue - std::floor(hsb.hue);
  const double sector = hue * 6.0;
  const double chroma = hsb.brightness * hsb.saturation;
  const double second = chroma * (1.0 - std::fabs(std::fmod(sector, 2.0) - 1.0));
  const double minimum = hsb.brightness - chroma;
  std::array<double, 3> rgb{};
  switch (std::min(static_cast<int>(sector), 5)) {
    case 0:
      rgb = {chroma, second, 0};
      break;
    case 1:
      rgb = {second, chroma, 0};
      break;
    case 2:
      rgb = {0, chroma, second};
      break;
    case 3:
      rgb = {0, second, chroma};
      break;
    case 4:
      rgb = {second, 0, chroma};
      break;
    default:
      rgb = {chroma, 0, second};
      break;
  }
  for (double& channel : rgb) {
    channel = std::clamp(channel + minimum, 0.0, 1.0);
  }
  return rgb;
}

std::array<double, 4> dynamicColor(const ColorDynamicsParameters& dynamics,
                                   const std::array<double, 4>& primary,
                                   const std::array<double, 3>& secondary, double pressure,
                                   double normalizedTilt, const ColorJitter& stroke,
                                   const ColorJitter& dab) {
  const double curvedPressure =
      dynamics.pressureCurve ? dynamics.pressureCurve->evaluate(pressure) : pressure;
  std::array<double, 4> color = primary;
  const double secondaryWeight = std::clamp(
      dynamics.secondaryColorByPressure * curvedPressure +
          dynamics.tiltSecondaryColor * normalizedTilt +
          dynamics.strokeSecondaryColorJitter * stroke.secondary +
          dynamics.secondaryColorJitter * dab.secondary,
      0.0, 1.0);
  if (secondaryWeight > 0) {
    for (int channel = 0; channel < 3; ++channel) {
      color[channel] += (secondary[channel] - color[channel]) * secondaryWeight;
    }
  }
  if (!dynamics.changesHsb()) {
    return color;
  }
  Hsb hsb = hsbFromRgb({color[0], color[1], color[2]});
  hsb.hue += kHueTurnsPerUnit *
             (dynamics.strokeHueJitter * stroke.hue + dynamics.pressureHue * curvedPressure +
              dynamics.tiltHue * normalizedTilt + dynamics.hueJitter * dab.hue);
  hsb.saturation +=
      dynamics.strokeSaturationJitter * stroke.saturation +
      dynamics.pressureSaturation * curvedPressure + dynamics.tiltSaturation * normalizedTilt +
      dynamics.saturationJitter * dab.saturation;
  hsb.brightness +=
      dynamics.strokeBrightnessJitter * stroke.brightness +
      dynamics.pressureBrightness * curvedPressure + dynamics.tiltBrightness * normalizedTilt +
      dynamics.brightnessJitter * dab.brightness;
  hsb.saturation = std::clamp(hsb.saturation, 0.0, 1.0);
  hsb.brightness = std::clamp(hsb.brightness, 0.0, 1.0) *
                   (1.0 - dynamics.strokeDarknessJitter * stroke.darkness) *
                   (1.0 - dynamics.darknessJitter * dab.darkness);
  const std::array<double, 3> rgb = rgbFromHsb(hsb);
  return {rgb[0], rgb[1], rgb[2], color[3]};
}

}  // namespace nanquim::stroke
