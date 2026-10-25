#include "stroke/stroke_effects.h"

#include <algorithm>
#include <cmath>

namespace nanquim::stroke {

double edgeBlurRadiusPixels(double sizePixels) { return std::clamp(0.15 * sizePixels, 2.0, 32.0); }

std::vector<float> edgeBlurWeights(double sizePixels) {
  const double sigma = std::max(0.5, edgeBlurRadiusPixels(sizePixels) / 8.0);
  const auto radius = std::clamp(static_cast<int32_t>(std::ceil(3.0 * sigma)), 1,
                                 kMaximumEdgeBlurRadius);
  std::vector<double> weights(static_cast<size_t>(radius) + 1);
  double total = 0;
  for (int32_t tap = 0; tap <= radius; ++tap) {
    weights[static_cast<size_t>(tap)] = std::exp(-0.5 * tap * tap / (sigma * sigma));
    total += weights[static_cast<size_t>(tap)] * (tap == 0 ? 1 : 2);
  }
  std::vector<float> result;
  result.reserve(weights.size());
  for (const double weight : weights) {
    result.push_back(static_cast<float>(weight / total));
  }
  return result;
}

double edgeAmount(double coverage, double blurred) {
  return coverage > 0 ? std::clamp(1.0 - blurred / coverage, 0.0, 1.0) : 0.0;
}

RenderParameters effectiveRender(const RenderParameters& render, bool paintsLayerColors,
                                 bool clone, bool selectionTarget, bool linearDocument) {
  RenderParameters result = render;
  result.gammaCorrect = render.gammaCorrect && paintsLayerColors && !clone && !linearDocument;
  result.luminanceBlending = render.luminanceBlending && paintsLayerColors;
  if (!paintsLayerColors) {
    result.burntEdges = 0;
  }
  if (selectionTarget) {
    result.wetEdges = 0;
  }
  return result;
}

double dualCoverage(DualBlend blend, double primary, double secondary) {
  const double c = std::clamp(primary, 0.0, 1.0);
  const double g = std::clamp(secondary, 0.0, 1.0);
  switch (blend) {
    case DualBlend::Multiply:
      return c * g;
    case DualBlend::Subtract:
      return std::max(0.0, c - (1.0 - g));
    case DualBlend::Darken:
      return std::min(c, g);
    case DualBlend::Lighten:
      return std::max(c, g);
    case DualBlend::Overlay:
      return c < 0.5 ? 2.0 * c * g : 1.0 - 2.0 * (1.0 - c) * (1.0 - g);
    case DualBlend::Height:
      return std::clamp((c - (1.0 - g)) / 0.01, 0.0, 1.0);
  }
  return c;
}

double srgbToLinear(double value) {
  return value <= 0.04045 ? value / 12.92 : std::pow((value + 0.055) / 1.055, 2.4);
}

double linearToSrgb(double value) {
  return value <= 0.0031308 ? value * 12.92 : 1.055 * std::pow(value, 1.0 / 2.4) - 0.055;
}

}  // namespace nanquim::stroke
