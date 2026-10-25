#pragma once

#include <cstdint>
#include <vector>

#include "stroke/brush_parameters.h"

namespace nanquim::stroke {

constexpr int32_t kEdgeMapScale = 4;
constexpr int32_t kMaximumEdgeBlurRadius = 12;

double edgeBlurRadiusPixels(double sizePixels);

std::vector<float> edgeBlurWeights(double sizePixels);

double edgeAmount(double coverage, double blurred);

RenderParameters effectiveRender(const RenderParameters& render, bool paintsLayerColors,
                                 bool clone, bool selectionTarget, bool linearDocument = false);

double dualCoverage(DualBlend blend, double primary, double secondary);

double srgbToLinear(double value);
double linearToSrgb(double value);

}  // namespace nanquim::stroke
