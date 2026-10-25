#pragma once

#include <array>
#include <vector>

#include "canvas/blend_mode.h"
#include "reference_image.h"
#include "stroke/brush_parameters.h"
#include "stroke/paint_mixing.h"
#include "stroke/dab.h"
#include "stroke/tip_coverage.h"

namespace nanquim::reference {

void renderDabs(Image& strokeBuffer, const std::vector<stroke::Dab>& dabs,
                const stroke::TipStyle& style, stroke::Accumulation accumulation,
                const Image* layer = nullptr, bool linearLight = false,
                bool linearDocument = false);

struct DualCoverage {
  const Image* coverage = nullptr;
  stroke::DualBlend blend = stroke::DualBlend::Multiply;
  std::array<double, 3> color{0, 0, 0};
};

void applyStrokeEffects(Image& strokeBuffer, const stroke::RenderParameters& render,
                        double sizePixels, const DualCoverage* dual = nullptr,
                        const Image* particles = nullptr);

void renderMixingDabs(Image& working, const std::vector<stroke::Dab>& dabs,
                      const stroke::TipStyle& style, stroke::PaintMixing mode,
                      const stroke::BrushParameters& brush, double strokeOpacity,
                      stroke::MixingState& state,
                      canvas::PixelFormat format = canvas::PixelFormat::Rgba8PremulSrgb);

void commitWorking(Image& layer, const Image& working, const Image* selection, bool alphaLock);

void renderCloneDabs(Image& strokeBuffer, const std::vector<stroke::Dab>& dabs,
                     const stroke::TipStyle& style, stroke::Accumulation accumulation,
                     const Image& source, std::array<int32_t, 2> offset);

void commitPaint(Image& layer, const Image& strokeBuffer, double strokeOpacity,
                 canvas::BlendMode blend = canvas::BlendMode::Normal, bool alphaLock = false,
                 bool luminanceBlending = false);

void commitErase(Image& layer, const Image& strokeBuffer, double eraseOpacity,
                 bool alphaLock = false);

void commitMask(Image& mask, const Image& strokeBuffer, double strokeOpacity, double luminance);

void applySelection(Image& strokeBuffer, const Image& selection);

void featherSelection(Image& selection, const std::vector<float>& weights,
                      canvas::PixelFormat format = canvas::PixelFormat::Rgba8PremulSrgb);

Image whiteMask(int32_t width, int32_t height);

struct CompositeLayer {
  const Image* image = nullptr;
  double opacity = 1;
  bool visible = true;
};

Image flatten(int32_t width, int32_t height, std::array<double, 4> background,
              const std::vector<CompositeLayer>& layers);

}  // namespace nanquim::reference
