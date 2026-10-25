#pragma once

#include <array>
#include <deque>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <unordered_set>
#include <vector>

#include "canvas/blend_mode.h"
#include "canvas/layer_tile_store.h"
#include "canvas/stroke_buffer.h"
#include "canvas/stroke_effects_binding.h"
#include "canvas/tile_coordinate.h"
#include "gpu/rhi/rhi.h"
#include "stroke/brush_parameters.h"
#include "stroke/brush_texture.h"
#include "stroke/dab.h"
#include "stroke/paint_mixing.h"

namespace nanquim::canvas {

class StrokeRenderer {
 public:
  StrokeRenderer(gpu::rhi::Device& device, LayerTileStore& tileStore);

  bool initialize(std::string& errorMessage);

  [[nodiscard]] StrokeBuffer& buffer() { return buffer_; }
  [[nodiscard]] const StrokeBuffer& buffer() const { return buffer_; }
  [[nodiscard]] const StrokeBuffer& provisional() const { return provisional_; }

  void setBrush(const stroke::BrushParameters& brush,
                std::shared_ptr<const stroke::GrayTexture> shapeTexture,
                std::shared_ptr<const stroke::GrayTexture> grainTexture);

  struct CloneSource {
    const gpu::rhi::Texture* texture = nullptr;
    std::array<int32_t, 2> offset{};
    std::array<int32_t, 2> size{};
  };
  void setCloneSource(std::optional<CloneSource> source) { cloneSource_ = source; }

  void setLayerSource(std::optional<CloneSource> source) { layerSource_ = source; }

  void setStrokeEffects(const stroke::RenderParameters& render, double sizePixels,
                        int32_t canvasWidth, int32_t canvasHeight);
  [[nodiscard]] const StrokeEffectsBinding& effects() const { return effects_; }

  struct DualBrush {
    stroke::BrushParameters brush;
    std::shared_ptr<const stroke::GrayTexture> shapeTexture;
    std::shared_ptr<const stroke::GrayTexture> grainTexture;
    stroke::DualBlend blend = stroke::DualBlend::Multiply;
    std::array<float, 3> color{0, 0, 0};
  };
  void setDualBrush(std::optional<DualBrush> dual);
  void renderDualDabs(const std::vector<stroke::Dab>& dabs, int32_t canvasWidth,
                      int32_t canvasHeight, std::vector<TileCoordinate>& touchedTiles);

  void setParticles(bool enabled, bool useShape);
  void renderParticleDabs(const std::vector<stroke::Dab>& dabs, int32_t canvasWidth,
                          int32_t canvasHeight, std::vector<TileCoordinate>& touchedTiles);

  bool beginMixing(stroke::PaintMixing mode, LayerId layer, double strokeOpacity,
                   int32_t canvasWidth, int32_t canvasHeight);
  void endMixing() { mixing_ = stroke::PaintMixing::None; }
  [[nodiscard]] bool mixing() const { return mixing_ != stroke::PaintMixing::None; }
  [[nodiscard]] const gpu::rhi::Texture* workingCopy() const {
    return mixing() ? workingCopy_.get() : nullptr;
  }
  void releaseWorkingCopy();
  void restartStroke(LayerId layer, int32_t canvasWidth, int32_t canvasHeight);

  [[nodiscard]] bool readsDestination() const { return destinationRead_; }

  void renderDabs(const std::vector<stroke::Dab>& dabs, int32_t canvasWidth, int32_t canvasHeight,
                  std::vector<TileCoordinate>& touchedTiles);

  void renderProvisionalDabs(const std::vector<stroke::Dab>& dabs, int32_t canvasWidth,
                             int32_t canvasHeight, std::vector<TileCoordinate>& changedTiles);
  void clearProvisional(std::vector<TileCoordinate>& changedTiles);
  [[nodiscard]] int64_t provisionalTilesRendered() const { return provisionalTilesRendered_; }

  enum class CommitResult { Done, Waiting };

  struct CommitOptions {
    LayerId surface = 0;
    bool erase = false;
    float opacity = 1;
    BlendMode blend = BlendMode::Normal;
    bool alphaLock = false;
    bool maskTarget = false;
    float maskLuminance = 0;
    std::optional<LayerId> selection;
    bool selectionTarget = false;
  };

  CommitResult commit(const CommitOptions& options, std::vector<LayerTileKey>& changedTiles,
                      const std::function<void(const LayerTileKey&)>& beforeWrite = {});

  void checkForEmptyTiles(const std::vector<LayerTileKey>& tiles);
  int32_t poll();
  [[nodiscard]] bool hasPendingWork() const {
    return !emptinessQueue_.empty() || !pendingReductions_.empty();
  }

  void releaseMemory();

 private:
  struct PendingReduction {
    std::vector<std::pair<LayerTileKey, uint64_t>> tiles;
    std::unique_ptr<gpu::rhi::Readback> readback;
    bool halfFloat = false;
    int32_t rows = 0;
  };

  struct CachedTexture {
    std::shared_ptr<const stroke::GrayTexture> source;
    std::unique_ptr<gpu::rhi::Texture> texture;
    uint64_t lastUse = 0;
  };
  static constexpr size_t kTextureCacheSize = 8;

  void startReduction();
  const gpu::rhi::Texture* textureFor(const std::shared_ptr<const stroke::GrayTexture>& source);
  const gpu::rhi::Pipeline* pipeline(const char* program, const std::vector<std::string>& defines,
                                     const gpu::rhi::BlendState& blend, gpu::rhi::Format target,
                                     bool dabs = false);
  std::unique_ptr<gpu::rhi::Texture> createTarget(gpu::rhi::Format format, int32_t width,
                                                  int32_t height, uint32_t extraUsage = 0);
  [[nodiscard]] std::vector<std::string> dabDefines() const;
  [[nodiscard]] std::vector<std::string> tipDefines() const;
  [[nodiscard]] bool workingHalf() const;
  [[nodiscard]] bool linearDocument() const;
  [[nodiscard]] std::vector<std::string> mixingDefines() const;
  [[nodiscard]] std::vector<std::string> workingDefines() const;
 public:
  struct TipUniforms {
    float hardness;
    float shapeSize;
    int32_t shapeInvert;
    int32_t shapeFiltering;
    float grainSize;
    float grainScale;
    float grainRotation;
    int32_t grainRotateWithDab;
    float grainBrightness;
    float grainContrast;
    int32_t grainInvert;
    int32_t grainImproved;
  };

 private:
  TipUniforms bindTip();
  [[nodiscard]] bool glazesByCompute() const;
  const gpu::rhi::Pipeline* glazePipeline(bool halfFloat);
  const gpu::rhi::Texture* glazeScratchTile(bool halfFloat);
  void renderMixingDabs(const std::vector<stroke::Dab>& dabs, int32_t canvasWidth,
                        int32_t canvasHeight, std::vector<TileCoordinate>& touchedTiles);
  void updateEdgeMap(const std::vector<TileCoordinate>& tiles, bool strokeOnly);
  void clearEdgeMap();
  void renderDabsInto(StrokeBuffer& target, bool startFromStroke,
                      const std::vector<stroke::Dab>& dabs, int32_t canvasWidth,
                      int32_t canvasHeight, std::vector<TileCoordinate>& touchedTiles);

  gpu::rhi::Device& device_;
  LayerTileStore& tileStore_;
  StrokeBuffer buffer_;
  StrokeBuffer provisional_;
  StrokeBuffer dualBuffer_;
  StrokeBuffer particleBuffer_;
  bool particlesUseShape_ = true;
  std::optional<DualBrush> dual_;
  std::unique_ptr<gpu::rhi::Buffer> instanceBuffer_;
  std::unique_ptr<gpu::rhi::Sampler> nearestClampSampler_;
  std::unique_ptr<gpu::rhi::Sampler> mipmapClampSampler_;
  std::unique_ptr<gpu::rhi::Sampler> nearestRepeatSampler_;
  std::unique_ptr<gpu::rhi::Sampler> mipmapRepeatSampler_;
  std::unique_ptr<gpu::rhi::Texture> reductionTexture_;
  PixelFormat reductionFormat_ = PixelFormat::Rgba8PremulSrgb;
  PixelFormat commitScratchFormat_ = PixelFormat::Rgba8PremulSrgb;
  std::unique_ptr<gpu::rhi::Texture> intermediateTexture_;
  std::unique_ptr<gpu::rhi::Texture> commitScratch_;
  std::array<std::unique_ptr<gpu::rhi::Texture>, 2> glazeScratch_;
  std::vector<float> instanceData_;
  std::deque<LayerTileKey> emptinessQueue_;
  std::vector<PendingReduction> pendingReductions_;
  int32_t pollsBeforeReduction_ = 0;
  int64_t provisionalTilesRendered_ = 0;
  stroke::BrushParameters brush_;
  stroke::Accumulation primitive_ = stroke::Accumulation::Wash;
  bool destinationRead_ = false;
  std::shared_ptr<const stroke::GrayTexture> shapeTexture_;
  std::shared_ptr<const stroke::GrayTexture> grainTexture_;
  std::vector<CachedTexture> textureCache_;
  std::optional<CloneSource> cloneSource_;
  std::optional<CloneSource> layerSource_;
  StrokeEffectsBinding effects_;
  std::unique_ptr<gpu::rhi::Texture> edgeMap_;
  std::unique_ptr<gpu::rhi::Texture> edgeRows_;
  std::unique_ptr<gpu::rhi::Texture> edgeBlurred_;
  std::array<int32_t, 2> edgeMapSize_{};
  std::vector<float> edgeWeights_;
  stroke::PaintMixing mixing_ = stroke::PaintMixing::None;
  double mixingOpacity_ = 1;
  bool mixingStarted_ = false;
  double mixingPreviousDistance_ = 0;
  std::unique_ptr<gpu::rhi::Texture> workingCopy_;
  std::array<int32_t, 2> workingCopySize_{};
  std::unique_ptr<gpu::rhi::Buffer> mixingState_;
  std::unordered_set<TileCoordinate, TileCoordinateHash> workingMissing_;
  uint64_t textureUseCounter_ = 0;
};

}  // namespace nanquim::canvas
