#include "canvas/viewport_renderer.h"

#include <cmath>
#include <cstddef>

#include "canvas/level_selection.h"
#include "color/display_lut.h"
#include "embedded_shaders.h"

namespace nanquim::canvas {
namespace {

struct ViewportParameters {
  std::array<float, 4> screenToCanvasLinear;
  std::array<float, 4> shadowColor;
  std::array<float, 3> surroundColor;
  float fineLevelScale;
  std::array<float, 2> fineLevelSize;
  std::array<float, 2> coarseLevelSize;
  std::array<float, 2> screenToCanvasOffset;
  std::array<float, 2> canvasSize;
  std::array<float, 2> selectionSize;
  float coarseLevelScale;
  float coarseWeight;
  float surfaceHeight;
  float zoom;
  float shadowRadiusPixels;
  float selectionReduction;
  float antsPhase;
  int32_t showCheckerboard;
  int32_t linearComposite;
  int32_t useDisplayLut;
  int32_t showDirtyOverlay;
  int32_t showSelection;
  int32_t dimOutsideSelection;
  int32_t rowsFromBottom;
};
static_assert(offsetof(ViewportParameters, surroundColor) == 32);
static_assert(offsetof(ViewportParameters, fineLevelSize) == 48);
static_assert(offsetof(ViewportParameters, coarseLevelScale) == 88);
static_assert(offsetof(ViewportParameters, showCheckerboard) == 116);
static_assert(sizeof(ViewportParameters) == 144);

constexpr uint32_t kFineLevelBinding = 1;
constexpr uint32_t kCoarseLevelBinding = 2;
constexpr uint32_t kDirtyAgesBinding = 3;
constexpr uint32_t kSelectionBinding = 4;
constexpr uint32_t kDisplayLutBinding = 5;

}  // namespace

bool ViewportRenderer::initialize(gpu::rhi::Device& device, std::string& errorMessage) {
  device_ = &device;
  device.registerProgram("viewport", shaders::fullscreen_vertex, shaders::viewport_fragment);
  gpu::rhi::PipelineDescription description;
  description.program = "viewport";
  pipeline_ = device.pipeline(description, errorMessage);
  if (pipeline_ == nullptr) {
    return false;
  }
  linearSampler_ = device.createSampler(gpu::rhi::Filter::Linear);
  nearestSampler_ = device.createSampler(gpu::rhi::Filter::Nearest);
  return true;
}

void ViewportRenderer::setDisplayLut(const std::vector<uint16_t>& lut) {
  constexpr int32_t kSize = color::kDisplayLutSize;
  hasDisplayLut_ = lut.size() == static_cast<size_t>(kSize) * kSize * kSize * 4;
  if (!hasDisplayLut_) {
    return;
  }
  if (!displayLut_) {
    gpu::rhi::TextureDescription description;
    description.type = gpu::rhi::TextureType::Texture3D;
    description.format = gpu::rhi::Format::Rgba16f;
    description.width = kSize;
    description.height = kSize;
    description.depth = kSize;
    description.usage = gpu::rhi::kUsageSampled | gpu::rhi::kUsageUpload;
    description.filter = gpu::rhi::Filter::Linear;
    displayLut_ = device_->createTexture(description);
  }
  device_->uploadTexture(*displayLut_, lut.data());
}

void ViewportRenderer::render(const Compositor& compositor,
                              const core::AffineTransform& canvasToScreen,
                              const gpu::rhi::RenderTarget& target, const ViewportStyle& style,
                              const gpu::rhi::Texture* dirtyAges,
                              std::optional<int32_t> levelOverride,
                              std::optional<std::array<int32_t, 4>> screenRegion,
                              std::optional<SelectionDisplay> selection) {
  const auto screenToCanvas = canvasToScreen.inverted();
  if (!compositor.isConfigured() || !screenToCanvas) {
    return;
  }
  const double zoom = canvasToScreen.scaleFactor();
  const LevelChoice levels = levelOverride
                                 ? LevelChoice{*levelOverride, *levelOverride, 0.0F, false}
                                 : chooseLevels(zoom, compositor.levelCount(), style.showPixels);

  gpu::rhi::CommandContext& commands = device_->commands();
  commands.beginPass(target, gpu::rhi::LoadAction::Load);
  if (screenRegion) {
    const auto& [minX, minY, maxX, maxY] = *screenRegion;
    const int32_t firstRow = target.rowsFromBottom ? target.height - maxY : minY;
    commands.setScissor(gpu::rhi::Rect{minX, firstRow, maxX - minX, maxY - minY});
  }
  commands.bindPipeline(*pipeline_);

  commands.bindTexture(kFineLevelBinding, &compositor.levelTexture(levels.fineLevel),
                       levels.nearest ? nearestSampler_.get() : linearSampler_.get());
  commands.bindTexture(kCoarseLevelBinding, &compositor.levelTexture(levels.coarseLevel),
                       linearSampler_.get());
  commands.bindTexture(kDirtyAgesBinding, dirtyAges, nearestSampler_.get());
  commands.bindTexture(kSelectionBinding, selection ? selection->texture : nullptr,
                       linearSampler_.get());
  commands.bindTexture(kDisplayLutBinding, hasDisplayLut_ ? displayLut_.get() : nullptr);

  ViewportParameters parameters{};
  parameters.fineLevelScale = static_cast<float>(std::ldexp(1.0, levels.fineLevel));
  parameters.coarseLevelScale = static_cast<float>(std::ldexp(1.0, levels.coarseLevel));
  parameters.fineLevelSize = {static_cast<float>(compositor.levelWidth(levels.fineLevel)),
                              static_cast<float>(compositor.levelHeight(levels.fineLevel))};
  parameters.coarseLevelSize = {static_cast<float>(compositor.levelWidth(levels.coarseLevel)),
                                static_cast<float>(compositor.levelHeight(levels.coarseLevel))};
  parameters.coarseWeight = levels.coarseWeight;
  parameters.screenToCanvasLinear = {
      static_cast<float>(screenToCanvas->a), static_cast<float>(screenToCanvas->b),
      static_cast<float>(screenToCanvas->c), static_cast<float>(screenToCanvas->d)};
  parameters.screenToCanvasOffset = {static_cast<float>(screenToCanvas->tx),
                                     static_cast<float>(screenToCanvas->ty)};
  parameters.surfaceHeight = static_cast<float>(target.height);
  parameters.canvasSize = {static_cast<float>(compositor.widthPixels()),
                           static_cast<float>(compositor.heightPixels())};
  parameters.zoom = static_cast<float>(zoom);
  parameters.surroundColor = style.surroundColor;
  parameters.shadowColor = style.shadowColor;
  parameters.shadowRadiusPixels = style.shadowRadiusPixels;
  parameters.showCheckerboard = compositor.backgroundHidden() ? 1 : 0;
  parameters.linearComposite = compositor.linearLight() ? 1 : 0;
  parameters.showDirtyOverlay = dirtyAges != nullptr ? 1 : 0;
  parameters.showSelection = selection ? 1 : 0;
  parameters.useDisplayLut = hasDisplayLut_ ? 1 : 0;
  parameters.rowsFromBottom = target.rowsFromBottom ? 1 : 0;
  if (selection) {
    parameters.selectionReduction = static_cast<float>(selection->reduction);
    parameters.selectionSize = {static_cast<float>(selection->widthTexels),
                                static_cast<float>(selection->heightTexels)};
    parameters.dimOutsideSelection = selection->dimOutside ? 1 : 0;
    parameters.antsPhase = selection->antsPhasePixels;
  }
  commands.setUniforms(parameters);
  commands.draw(3);
  commands.endPass();
}

}  // namespace nanquim::canvas
