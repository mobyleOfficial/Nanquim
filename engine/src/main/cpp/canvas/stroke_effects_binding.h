#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "canvas/stroke_buffer.h"
#include "canvas/tile_coordinate.h"
#include "gpu/rhi/rhi.h"
#include "stroke/brush_parameters.h"

namespace nanquim::canvas {

struct StrokeEffectsBinding {
  stroke::RenderParameters render;
  const gpu::rhi::Texture* edgeMap = nullptr;
  std::array<int32_t, 2> edgeMapSize{};
  const StrokeBuffer* dual = nullptr;
  stroke::DualBlend dualBlend = stroke::DualBlend::Multiply;
  std::array<float, 3> dualColor{0, 0, 0};
  const StrokeBuffer* particles = nullptr;

  [[nodiscard]] bool edges() const { return render.hasEdges() && edgeMap != nullptr; }
  [[nodiscard]] bool any() const {
    return edges() || render.gammaCorrect || render.luminanceBlending || dual != nullptr ||
           particles != nullptr;
  }
};

struct ActiveStrokeParameters {
  int32_t hasSurfaceTile;
  int32_t padding0;
  std::array<int32_t, 2> surfaceSlotOrigin;
  std::array<int32_t, 2> strokeSlotOrigin;
  std::array<int32_t, 2> targetOrigin;
  float strokeOpacity;
  float layerOpacity;
  int32_t brushBlendMode;
  int32_t alphaLock;
  float maskLuminance;
  int32_t hasSelectionTile;
  std::array<int32_t, 2> selectionSlotOrigin;
  int32_t hasDualTile;
  int32_t padding1;
  std::array<int32_t, 2> dualSlotOrigin;
  int32_t dualBlend;
  std::array<int32_t, 3> padding2;
  std::array<float, 3> dualColor;
  int32_t hasParticleTile;
  std::array<int32_t, 2> particleSlotOrigin;
  std::array<int32_t, 2> workingOrigin;
  std::array<int32_t, 2> edgeMapSize;
  std::array<int32_t, 2> tileCanvasOrigin;
  float wetEdges;
  float burntEdges;
  int32_t burntBlendMode;
  int32_t padding3;
};
static_assert(offsetof(ActiveStrokeParameters, surfaceSlotOrigin) == 8);
static_assert(offsetof(ActiveStrokeParameters, maskLuminance) == 48);
static_assert(offsetof(ActiveStrokeParameters, selectionSlotOrigin) == 56);
static_assert(offsetof(ActiveStrokeParameters, dualSlotOrigin) == 72);
static_assert(offsetof(ActiveStrokeParameters, dualColor) == 96);
static_assert(offsetof(ActiveStrokeParameters, hasParticleTile) == 108);
static_assert(offsetof(ActiveStrokeParameters, workingOrigin) == 120);
static_assert(offsetof(ActiveStrokeParameters, wetEdges) == 144);
static_assert(sizeof(ActiveStrokeParameters) == 160);

inline constexpr uint32_t kActiveSurfaceBinding = 1;
inline constexpr uint32_t kActiveStrokeBinding = 2;
inline constexpr uint32_t kActiveSelectionBinding = 3;
inline constexpr uint32_t kActiveEdgeMapBinding = 4;
inline constexpr uint32_t kActiveWorkingCopyBinding = 5;
inline constexpr uint32_t kActiveDualBinding = 6;
inline constexpr uint32_t kActiveParticleBinding = 7;

inline void appendStrokeEffectDefines(const StrokeEffectsBinding& effects, bool paint,
                                      std::vector<std::string>& defines) {
  if (effects.render.gammaCorrect && paint) {
    defines.emplace_back("STROKE_GAMMA");
  }
  if (effects.edges()) {
    defines.emplace_back("STROKE_EDGES");
  }
  if (effects.dual != nullptr) {
    defines.emplace_back("DUAL");
  }
  if (effects.particles != nullptr) {
    defines.emplace_back("PARTICLES");
  }
  if (effects.render.luminanceBlending && paint) {
    defines.emplace_back("LUMINANCE_BLEND");
  }
}

inline void bindStrokeEffects(gpu::rhi::CommandContext& commands,
                              const StrokeEffectsBinding& effects, TileCoordinate coordinate,
                              ActiveStrokeParameters& parameters) {
  if (effects.particles != nullptr) {
    const auto slot = effects.particles->find(coordinate);
    commands.bindTexture(kActiveParticleBinding,
                         slot ? &effects.particles->pageTexture(slot->page) : nullptr);
    parameters.hasParticleTile = slot ? 1 : 0;
    parameters.particleSlotOrigin = slot ? slot->origin : std::array<int32_t, 2>{0, 0};
  }
  if (effects.dual != nullptr) {
    const auto slot = effects.dual->find(coordinate);
    commands.bindTexture(kActiveDualBinding,
                         slot ? &effects.dual->pageTexture(slot->page) : nullptr);
    parameters.hasDualTile = slot ? 1 : 0;
    parameters.dualSlotOrigin = slot ? slot->origin : std::array<int32_t, 2>{0, 0};
    parameters.dualBlend = static_cast<int32_t>(effects.dualBlend);
    parameters.dualColor = effects.dualColor;
  }
  if (!effects.edges()) {
    return;
  }
  commands.bindTexture(kActiveEdgeMapBinding, effects.edgeMap);
  parameters.edgeMapSize = effects.edgeMapSize;
  parameters.tileCanvasOrigin = {coordinate.column * kTileSizePixels,
                                 coordinate.row * kTileSizePixels};
  parameters.wetEdges = static_cast<float>(effects.render.wetEdges);
  parameters.burntEdges = static_cast<float>(effects.render.burntEdges);
  parameters.burntBlendMode = static_cast<int32_t>(effects.render.burntEdgesBlend);
}

}  // namespace nanquim::canvas
