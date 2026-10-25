#include "stroke/tip_coverage.h"

#include <algorithm>
#include <cmath>
#include <optional>

namespace nanquim::stroke {

namespace {

double lerp(double from, double to, double weight) { return from + (to - from) * weight; }

double roundCoverage(const Dab& dab, double hardness, double along, double across) {
  const double radius = dab.diameter / 2;
  const double distance = std::sqrt(along * along + across * across);
  const double edge = std::clamp(radius - distance + 0.5, 0.0, 1.0);
  const double blur = dab.bleed * dab.diameter * 0.25;
  const double inner = std::max(0.0, radius * std::clamp(hardness, 0.0, 1.0) - blur);
  if (distance <= inner || inner >= radius) {
    return edge;
  }
  const double t = std::clamp((distance - inner) / (radius - inner), 0.0, 1.0);
  return edge * (1.0 - t * t * (3.0 - 2.0 * t));
}

struct TipTexel {
  double u = 0;
  double v = 0;
  double lod = 0;
};

std::optional<TipTexel> tipTexel(const Dab& dab, const TipStyle& style, double along,
                                 double across) {
  double u = along / dab.diameter + 0.5;
  double v = across / dab.diameter + 0.5;
  if (u < 0 || u > 1 || v < 0 || v > 1) {
    return std::nullopt;
  }
  if (dab.flipX) {
    u = 1 - u;
  }
  if (dab.flipY) {
    v = 1 - v;
  }
  const GrayTexture& shape = *style.shape;
  const double size = std::max(shape.width(), shape.height());
  const double bleedLod = std::log2(1.0 + dab.bleed * 0.25 * size);
  double lod = 0;
  if (style.shapeFiltering == TextureFiltering::Improved) {
    lod = std::max(std::log2(size / (dab.diameter * std::max(dab.roundness, 0.05))), bleedLod);
  } else if (style.shapeFiltering == TextureFiltering::Classic) {
    lod = bleedLod;
  }
  return TipTexel{u, v, lod};
}

std::array<double, 2> tipFrame(const Dab& dab, double canvasX, double canvasY) {
  const double cosine = std::cos(dab.angle);
  const double sine = std::sin(dab.angle);
  const double offsetX = canvasX - dab.x;
  const double offsetY = canvasY - dab.y;
  return {offsetX * cosine + offsetY * sine,
          (-offsetX * sine + offsetY * cosine) / std::max(dab.roundness, 0.05)};
}

double textureCoverage(const Dab& dab, const TipStyle& style, double along, double across) {
  const auto texel = tipTexel(dab, style, along, across);
  if (!texel) {
    return 0;
  }
  const double value =
      style.shape->sample(texel->u, texel->v, texel->lod, style.shapeFiltering, false);
  return style.shapeInvert ? 1 - value : value;
}

double grainSample(const Dab& dab, const TipStyle& style, double offsetX, double offsetY,
                   double canvasX, double canvasY) {
  const GrainParameters& grain = *style.grain;
  const GrayTexture& texture = *style.grainTexture;
  const double size = std::max(texture.width(), texture.height());
  double u = 0;
  double v = 0;
  double texelsPerPixel = 1;
  if (grain.moving()) {
    const double angle = grain.rotation + (grain.rotateWithDab ? dab.angle : 0.0);
    const double cosine = std::cos(angle);
    const double sine = std::sin(angle);
    const double scale = grain.scale / dab.diameter;
    u = (offsetX * cosine + offsetY * sine) * scale + dab.grainOffset[0];
    v = (-offsetX * sine + offsetY * cosine) * scale + dab.grainOffset[1];
    texelsPerPixel = size * scale;
  } else {
    const double cosine = std::cos(grain.rotation);
    const double sine = std::sin(grain.rotation);
    const double x = canvasX - dab.grainOffset[0];
    const double y = canvasY - dab.grainOffset[1];
    u = (x * cosine + y * sine) / dab.grainPeriod;
    v = (-x * sine + y * cosine) / dab.grainPeriod;
    texelsPerPixel = size / dab.grainPeriod;
  }
  const double lod =
      grain.filtering == TextureFiltering::Improved ? std::log2(texelsPerPixel) : 0.0;
  return adjustGrain(texture.sample(u, v, lod, grain.filtering, true), grain);
}

}  // namespace

double adjustGrain(double grain, const GrainParameters& parameters) {
  double value = parameters.invert ? 1 - grain : grain;
  value += parameters.brightness;
  const double contrast = parameters.contrast;
  const double factor = contrast >= 0 ? 1.0 / (1.0 - 0.99 * contrast) : 1.0 + contrast;
  value = (value - 0.5) * factor + 0.5;
  return std::clamp(value, 0.0, 1.0);
}

double grainBlend(double coverage, double grain, double depth, GrainBlend blend) {
  const double c = coverage;
  const double g = grain;
  const double d = std::clamp(depth, 0.0, 1.0);
  switch (blend) {
    case GrainBlend::Multiply:
      return c * lerp(1.0, g, d);
    case GrainBlend::Subtract:
      return std::max(0.0, c - (1 - g) * d);
    case GrainBlend::LinearBurn:
      return lerp(c, std::max(0.0, c + g - 1), d);
    case GrainBlend::ColorBurn:
      return lerp(c, g > 0 ? std::clamp(1 - (1 - c) / g, 0.0, 1.0) : 0.0, d);
    case GrainBlend::Overlay:
      return lerp(c, c < 0.5 ? 2 * c * g : 1 - 2 * (1 - c) * (1 - g), d);
    case GrainBlend::Height:
      return std::clamp((c - (1 - g) * d) / (1 - 0.99 * d), 0.0, 1.0);
  }
  return c;
}

double dabCoverage(const Dab& dab, const TipStyle& style, double canvasX, double canvasY) {
  if (dab.diameter <= 0) {
    return 0;
  }
  const double offsetX = canvasX - dab.x;
  const double offsetY = canvasY - dab.y;
  const auto [along, across] = tipFrame(dab, canvasX, canvasY);
  double coverage = style.textured() ? textureCoverage(dab, style, along, across)
                                     : roundCoverage(dab, style.hardness, along, across);
  if (coverage <= 0) {
    return 0;
  }
  if (dab.gradation > 0) {
    const double projection =
        (offsetX * std::cos(dab.gradationAngle) + offsetY * std::sin(dab.gradationAngle)) /
        (dab.diameter / 2);
    coverage *= 1 - dab.gradation * (std::clamp(projection, -1.0, 1.0) + 1) / 2;
  }
  if (style.hasGrain()) {
    const double grain = grainSample(dab, style, offsetX, offsetY, canvasX, canvasY);
    coverage = grainBlend(coverage, grain, dab.grainDepth, style.grain->blend);
  }
  return coverage;
}

bool dabTipColor(const Dab& dab, const TipStyle& style, double canvasX, double canvasY,
                 std::array<double, 3>& color) {
  if (!style.colored() || dab.diameter <= 0) {
    return false;
  }
  const auto [along, across] = tipFrame(dab, canvasX, canvasY);
  const auto texel = tipTexel(dab, style, along, across);
  if (!texel) {
    return false;
  }
  const GrayTexture& shape = *style.shape;
  const double alpha =
      shape.sampleChannel(3, texel->u, texel->v, texel->lod, style.shapeFiltering, false);
  if (alpha <= 0) {
    return false;
  }
  for (int32_t channel = 0; channel < 3; ++channel) {
    color[static_cast<size_t>(channel)] = std::min(
        1.0,
        shape.sampleChannel(channel, texel->u, texel->v, texel->lod, style.shapeFiltering, false) /
            alpha);
  }
  return true;
}

}  // namespace nanquim::stroke
