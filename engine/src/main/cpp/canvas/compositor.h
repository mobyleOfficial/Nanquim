#pragma once

#include <array>
#include <chrono>
#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#include "canvas/composite_plan.h"
#include "canvas/layer_tile_store.h"
#include "canvas/layer_tree.h"
#include "canvas/stroke_buffer.h"
#include "canvas/stroke_effects_binding.h"
#include "canvas/tile_coordinate.h"
#include "gpu/rhi/rhi.h"

namespace nanquim::canvas {

struct CompositorStats {
  int64_t dirtyTiles = 0;
  int64_t tilesComposedLastUpdate = 0;
  int64_t pyramidTilesBuiltLastUpdate = 0;
  int64_t compositeBytes = 0;
  int64_t cacheBytes = 0;
  int32_t levelCount = 0;
  double lastSettleMilliseconds = 0;
  double lastVisibleResultMilliseconds = 0;
};

class Compositor {
 public:
  Compositor(gpu::rhi::Device& device, LayerTileStore& tileStore);

  struct StrokePreview {
    const StrokeBuffer* buffer = nullptr;
    const StrokeBuffer* provisional = nullptr;
    bool erase = false;
    float opacity = 1;
    BlendMode brushBlend = BlendMode::Normal;
    bool alphaLock = false;
    bool maskTarget = false;
    float maskLuminance = 0;
    std::optional<LayerId> selection;
    StrokeEffectsBinding effects;
    const gpu::rhi::Texture* workingCopy = nullptr;
  };

  bool initialize(std::string& errorMessage);

  bool configure(int32_t widthPixels, int32_t heightPixels, std::string& errorMessage);
  void release();
  [[nodiscard]] bool isConfigured() const { return !levels_.empty(); }

  void setLayerTree(LayerTree tree);
  [[nodiscard]] const LayerTree& layerTree() const { return tree_; }
  void setActiveLayer(std::optional<LayerId> layer);
  void markTileChanged(const LayerTileKey& key);
  void markSurfaceChanged(LayerId surface);
  void invalidateAll() {
    markAllCachesDirty();
    markAllDirty();
  }

  void setStrokePreview(std::optional<StrokePreview> preview);

  using LayerOverride =
      std::function<void(TileCoordinate, int32_t sampleStride, const gpu::rhi::Texture& target)>;
  using LevelOverride = std::function<const gpu::rhi::Texture*(int32_t level)>;
  void setLayerOverride(std::optional<LayerOverride> override, bool reducedPreviews = false,
                        std::optional<LevelOverride> levelOverride = std::nullopt);
  void invalidatePreviews();
  void invalidatePreviews(const std::vector<TileCoordinate>& coordinates);
  void markStrokeTilesChanged(const std::vector<TileCoordinate>& coordinates);

  bool update(const TileRange& visibleTiles, int32_t coarsestLevel, int32_t previewLevel,
              std::chrono::steady_clock::time_point deadline, int64_t maxLayerTilesPerUpdate);

  [[nodiscard]] std::optional<int32_t> displayLevelOverride(const TileRange& visibleTiles) const;

  static constexpr size_t kMaxLayersPerPass = 16;

  [[nodiscard]] int32_t widthPixels() const { return widthPixels_; }
  [[nodiscard]] int32_t heightPixels() const { return heightPixels_; }
  [[nodiscard]] TileRange canvasTiles() const { return canvasTiles_; }
  [[nodiscard]] int32_t levelCount() const { return static_cast<int32_t>(levels_.size()); }
  [[nodiscard]] const gpu::rhi::Texture& levelTexture(int32_t level) const {
    return *levels_[level].texture;
  }
  [[nodiscard]] bool linearLight() const { return isLinear(tileStore_.pixelFormat()); }
  [[nodiscard]] int32_t levelWidth(int32_t level) const { return levels_[level].widthPixels; }
  [[nodiscard]] int32_t levelHeight(int32_t level) const { return levels_[level].heightPixels; }
  [[nodiscard]] bool backgroundHidden() const { return tree_.backgroundHidden; }

  const gpu::rhi::Texture* updateDirtyAgeTexture();

  [[nodiscard]] CompositorStats stats() const;

  void renderPlanIntoSlot(const CompositePlan& plan, TileCoordinate coordinate,
                          const gpu::rhi::Texture& pageTexture, const AtlasSlot& slot);
  bool planIsResident(const CompositePlan& plan, TileCoordinate coordinate);
  const gpu::rhi::Texture* renderPlanAtLevel(const CompositePlan& plan, int32_t level,
                                             int32_t width, int32_t height);

  [[nodiscard]] std::optional<std::array<double, 4>> lastUpdateChangedRegion() const {
    return changedRegion_;
  }

 private:
  struct Level {
    std::unique_ptr<gpu::rhi::Texture> texture;
    int32_t widthPixels = 0;
    int32_t heightPixels = 0;
    TileRange tiles;
    std::vector<uint8_t> dirty;
    std::vector<uint8_t> showingPreview;
  };

  enum class Start { Background, Transparent, BelowCache };

  enum class Program : uint8_t { Layers, Copy, RegionCopy, Blend, Pyramid, LevelPreview };
  static constexpr size_t kProgramCount = 6;

  [[nodiscard]] std::array<float, 4> documentTint(const std::array<float, 4>& tint) const;

  struct OverLayer {
    LayerId layer = 0;
    float opacity = 1;
    std::array<float, 4> tint{0, 0, 0, 0};
  };

  [[nodiscard]] size_t tileIndex(TileCoordinate coordinate) const {
    return static_cast<size_t>(coordinate.row) * static_cast<size_t>(canvasTiles_.endColumn) +
           static_cast<size_t>(coordinate.column);
  }
  void rebuildPlan();
  void markPositionDirty(TileCoordinate coordinate);
  void markAllDirty();
  void markAllCachesDirty();
  void markPyramidAncestorsDirty(TileCoordinate coordinate);
  int64_t requireResident(TileCoordinate coordinate, size_t first, size_t end, bool& allResident);
  bool prepareTile(TileCoordinate coordinate, int64_t& layerTiles);
  void composeBatch(const std::vector<TileCoordinate>& batch);
  void composeBatchFast(const std::vector<TileCoordinate>& batch);
  void composeGeneral(TileCoordinate coordinate);
  void buildPyramidTile(int32_t level, TileCoordinate coordinate);
  void bindTarget(const gpu::rhi::Texture& texture);
  void endTarget();
  void setViewport(std::optional<gpu::rhi::Rect> rect);
  void setScissor(std::optional<gpu::rhi::Rect> rect);
  void setTileScissor(TileCoordinate coordinate);
  const gpu::rhi::Pipeline* pipeline(Program program, bool blend);
  [[nodiscard]] std::vector<OverLayer> overLayers(size_t first, size_t end) const;
  void drawLayers(TileCoordinate coordinate, const std::vector<OverLayer>& layers,
                  int32_t sampleStride, std::array<int32_t, 2> targetOrigin);
  [[nodiscard]] static std::array<int32_t, 2> tileOrigin(TileCoordinate coordinate);
  [[nodiscard]] size_t levelTileIndex(int32_t level, TileCoordinate coordinate) const;
  [[nodiscard]] bool childrenClean(int32_t level, TileCoordinate coordinate) const;
  void drawPreviews(const TileRange& visible, int32_t previewLevel);
  bool drawLevelOverridePreviews(const TileRange& visible, int32_t previewLevel);
  void recordVisibleResult(const TileRange& visible);
  void drawCopy(const gpu::rhi::Texture& sourceTexture, bool blend);
  void drawRegionCopy(const gpu::rhi::Texture& sourceTexture, std::array<int32_t, 2> sourceOrigin,
                      std::array<int32_t, 2> targetOrigin, bool blend);
  void clearTile(std::array<float, 4> premultipliedColor);
  [[nodiscard]] std::array<float, 4> premultipliedBackground() const;
  [[nodiscard]] gpu::rhi::Format compositeFormat() const;
  std::unique_ptr<gpu::rhi::Texture> frameTarget_;
  [[nodiscard]] std::unique_ptr<gpu::rhi::Texture> createTarget(gpu::rhi::Format format,
                                                                int32_t width, int32_t height);
  void allocateCaches();

  int32_t evaluate(const CompositePlan& plan, TileCoordinate coordinate, size_t first, size_t end,
                   Start start, int32_t sampleStride, bool withStroke);
  bool ensureScratch(int32_t levels);
  [[nodiscard]] const gpu::rhi::Texture& scratch(int32_t level, int32_t parity) const {
    return *scratch_[static_cast<size_t>(level)][static_cast<size_t>(parity)];
  }
  void drawBlendStep(const PlanOperation& operation, TileCoordinate coordinate,
                     int32_t sampleStride, int32_t regionSize, int32_t backdropParity,
                     int32_t sourceParity, bool withStroke);
  bool renderStrokeOverride(TileCoordinate coordinate, int32_t sampleStride = 1);
  [[nodiscard]] std::optional<LayerId> strokeSurface() const;
  void drawActiveSurfaceWithStroke(TileCoordinate coordinate, const StrokeBuffer& source,
                                   const StrokeBuffer::Slot& strokeSlot, float layerOpacity,
                                   std::array<int32_t, 2> targetOrigin, bool blend);
  [[nodiscard]] std::optional<std::pair<const StrokeBuffer*, StrokeBuffer::Slot>> strokeTileAt(
      TileCoordinate coordinate) const;
  [[nodiscard]] bool strokePreviewIsSimple() const;

  gpu::rhi::Device& device_;
  LayerTileStore& tileStore_;
  std::optional<StrokePreview> strokePreview_;
  std::optional<LayerOverride> layerOverride_;
  bool overridePreviews_ = false;
  std::optional<LevelOverride> levelOverride_;
  std::array<std::array<std::array<const gpu::rhi::Pipeline*, 2>, 2>, kProgramCount> pipelines_{};
  const gpu::rhi::Texture* target_ = nullptr;
  std::optional<gpu::rhi::Rect> viewport_;
  std::optional<gpu::rhi::Rect> scissor_;

  int32_t widthPixels_ = 0;
  int32_t heightPixels_ = 0;
  TileRange canvasTiles_;
  std::vector<Level> levels_;
  std::vector<uint8_t> compositeDirty_;
  int64_t dirtyCount_ = 0;

 public:
  [[nodiscard]] int64_t dirtyTileCount() const { return dirtyCount_; }

 private:
  std::vector<uint32_t> selectedStamp_;
  std::vector<uint8_t> previewed_;
  int32_t previewLevel_ = 0;
  int64_t previewTilesShowing_ = 0;
  uint32_t selectionStamp_ = 0;

  LayerTree tree_;
  CompositePlan plan_;
  std::optional<LayerId> activeLayer_;
  std::unique_ptr<gpu::rhi::Texture> belowCache_;
  std::unique_ptr<gpu::rhi::Texture> aboveCache_;
  std::vector<uint8_t> belowDirty_;
  std::vector<uint8_t> aboveDirty_;

  std::vector<std::array<std::unique_ptr<gpu::rhi::Texture>, 2>> scratch_;
  gpu::rhi::Format scratchFormat_ = gpu::rhi::Format::Rgba8;
  std::unique_ptr<gpu::rhi::Texture> strokeOverride_;

  std::vector<uint8_t> dirtyAges_;
  std::unique_ptr<gpu::rhi::Texture> dirtyAgeTexture_;

  void extendChangedRegion(int32_t level, TileCoordinate coordinate);

  std::optional<std::array<double, 4>> changedRegion_;
  int64_t tilesComposedLastUpdate_ = 0;
  int64_t pyramidTilesBuiltLastUpdate_ = 0;
  std::optional<std::chrono::steady_clock::time_point> settleStart_;
  double lastSettleMilliseconds_ = 0;
  double lastVisibleResultMilliseconds_ = 0;
  bool visibleResultRecorded_ = false;
};

}  // namespace nanquim::canvas
