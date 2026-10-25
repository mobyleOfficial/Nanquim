#include "canvas/compositor.h"

#include <android/trace.h>

#include <algorithm>
#include <functional>
#include <utility>

#include "canvas/color_conversion.h"
#include "canvas/level_selection.h"
#include "core/log.h"
#include "embedded_shaders.h"

namespace nanquim::canvas {

namespace {

constexpr uint8_t kDirtyAgeFresh = 255;
constexpr uint8_t kDirtyAgeDecayPerFrame = 8;

constexpr const char* kActiveStrokePipeline = "activeLayerStroke";
constexpr const char* kEraseDefine = "ERASE";
constexpr const char* kMaskTargetDefine = "MASK_TARGET";
constexpr const char* kSelectionDefine = "SELECTION";

constexpr uint32_t kSourceBinding = 1;
constexpr uint32_t kLayersBinding = 1;
constexpr uint32_t kBackdropBinding = 1;
constexpr uint32_t kBlendSourceBinding = 2;
constexpr uint32_t kMaskBinding = 3;
constexpr uint32_t kBelowCacheBinding = 1;
constexpr uint32_t kAboveCacheBinding = 2;
constexpr uint32_t kActiveLayerBinding = 3;

struct Std140Int2 {
  std::array<int32_t, 2> value;
  std::array<int32_t, 2> padding;
};
struct Std140Float {
  float value;
  std::array<float, 3> padding;
};

struct CompositeLayersParameters {
  int32_t layerCount;
  std::array<int32_t, 3> padding0;
  std::array<Std140Int2, Compositor::kMaxLayersPerPass> layerSlotOrigin;
  std::array<Std140Float, Compositor::kMaxLayersPerPass> layerOpacity;
  std::array<std::array<float, 4>, Compositor::kMaxLayersPerPass> layerTint;
  std::array<int32_t, 2> targetOrigin;
  int32_t sampleStride;
  int32_t padding1;
};
static_assert(offsetof(CompositeLayersParameters, layerSlotOrigin) == 16);
static_assert(offsetof(CompositeLayersParameters, layerOpacity) == 272);
static_assert(offsetof(CompositeLayersParameters, layerTint) == 528);
static_assert(offsetof(CompositeLayersParameters, targetOrigin) == 784);
static_assert(sizeof(CompositeLayersParameters) == 800);

struct CompositeRegionCopyParameters {
  std::array<int32_t, 2> targetOrigin;
  std::array<int32_t, 2> sourceOrigin;
};
static_assert(sizeof(CompositeRegionCopyParameters) == 16);

struct CompositeBlendParameters {
  std::array<int32_t, 2> targetOrigin;
  std::array<int32_t, 2> backdropOrigin;
  int32_t hasSource;
  int32_t padding0;
  std::array<int32_t, 2> sourceOrigin;
  int32_t sourceStride;
  int32_t maskMode;
  int32_t maskInverted;
  int32_t padding1;
  std::array<int32_t, 2> maskOrigin;
  int32_t maskStride;
  float opacity;
  int32_t blendMode;
  int32_t clipToBackdrop;
  std::array<int32_t, 2> padding2;
  std::array<float, 4> sourceTint;
};
static_assert(offsetof(CompositeBlendParameters, sourceOrigin) == 24);
static_assert(offsetof(CompositeBlendParameters, maskOrigin) == 48);
static_assert(offsetof(CompositeBlendParameters, clipToBackdrop) == 68);
static_assert(offsetof(CompositeBlendParameters, sourceTint) == 80);
static_assert(sizeof(CompositeBlendParameters) == 96);

struct CompositeLevelPreviewParameters {
  int32_t sampleStride;
  float layerOpacity;
  std::array<int32_t, 2> padding;
};
static_assert(sizeof(CompositeLevelPreviewParameters) == 16);

struct ProgramSource {
  const char* name;
  const char* fragment;
};
const std::array<ProgramSource, 6> kProgramSources{{
    {"composite_layers", shaders::composite_layers_fragment},
    {"composite_copy", shaders::composite_copy_fragment},
    {"composite_region_copy", shaders::composite_region_copy_fragment},
    {"composite_blend", shaders::composite_blend_fragment},
    {"pyramid_downsample", shaders::pyramid_downsample_fragment},
    {"composite_level_preview", shaders::composite_level_preview_fragment},
}};

gpu::rhi::BlendState normalBlend() {
  gpu::rhi::BlendState blend;
  blend.enabled = true;
  blend.sourceColor = gpu::rhi::BlendFactor::One;
  blend.destinationColor = gpu::rhi::BlendFactor::OneMinusSourceAlpha;
  blend.sourceAlpha = gpu::rhi::BlendFactor::One;
  blend.destinationAlpha = gpu::rhi::BlendFactor::OneMinusSourceAlpha;
  return blend;
}

gpu::rhi::Rect tileRect(int32_t x, int32_t y, int32_t size) {
  return gpu::rhi::Rect{x, y, size, size};
}

bool isPastDeadline(std::chrono::steady_clock::time_point deadline) {
  return std::chrono::steady_clock::now() >= deadline;
}

TileRange scaleRangeToLevel(const TileRange& levelZeroRange, int32_t level) {
  const int32_t factor = 1 << level;
  return TileRange{levelZeroRange.firstColumn / factor, levelZeroRange.firstRow / factor,
                   (levelZeroRange.endColumn + factor - 1) / factor,
                   (levelZeroRange.endRow + factor - 1) / factor};
}

bool sameStructure(const std::vector<LayerTreeNode>& left, const std::vector<LayerTreeNode>& right) {
  if (left.size() != right.size()) {
    return false;
  }
  for (size_t index = 0; index < left.size(); ++index) {
    const LayerTreeNode& a = left[index];
    const LayerTreeNode& b = right[index];
    if (a.id != b.id || a.type != b.type || a.mask.has_value() != b.mask.has_value() ||
        (a.mask && a.mask->id != b.mask->id) || a.clipping != b.clipping ||
        !sameStructure(a.children, b.children)) {
      return false;
    }
  }
  return true;
}

void collectSurfaces(const LayerTreeNode& node, std::unordered_set<LayerId>& surfaces) {
  if (node.isGroup()) {
    for (const LayerTreeNode& child : node.children) {
      collectSurfaces(child, surfaces);
    }
    return;
  }
  surfaces.insert(node.id);
  if (node.mask) {
    surfaces.insert(node.mask->id);
  }
}

void collectChangedSurfaces(const std::vector<LayerTreeNode>& before,
                            const std::vector<LayerTreeNode>& after,
                            std::unordered_set<LayerId>& surfaces) {
  for (size_t index = 0; index < before.size(); ++index) {
    const LayerTreeNode& old = before[index];
    const LayerTreeNode& now = after[index];
    const bool maskChanged =
        old.mask && (old.mask->enabled != now.mask->enabled || old.mask->inverted != now.mask->inverted);
    if (old.opacity != now.opacity || old.blend != now.blend || old.hidden != now.hidden ||
        old.tint != now.tint || maskChanged) {
      collectSurfaces(now, surfaces);
    } else if (now.isGroup()) {
      collectChangedSurfaces(old.children, now.children, surfaces);
    }
  }
}

}  // namespace

Compositor::Compositor(gpu::rhi::Device& device, LayerTileStore& tileStore)
    : device_(device), tileStore_(tileStore) {}

bool Compositor::initialize(std::string& errorMessage) {
  for (const ProgramSource& program : kProgramSources) {
    device_.registerProgram(program.name, shaders::fullscreen_vertex, program.fragment);
  }
  for (size_t program = 0; program < kProgramCount; ++program) {
    for (const bool blend : {false, true}) {
      gpu::rhi::PipelineDescription description;
      description.program = kProgramSources[program].name;
      if (blend) {
        description.blend = normalBlend();
      }
      const gpu::rhi::Pipeline* pipeline = device_.pipeline(description, errorMessage);
      if (pipeline == nullptr) {
        return false;
      }
      pipelines_[program][blend ? 1 : 0][0] = pipeline;
    }
  }
  device_.registerProgram(kActiveStrokePipeline, shaders::fullscreen_vertex,
                          shaders::composite_active_stroke_fragment);
  for (const std::vector<std::string>& defines :
       {std::vector<std::string>{}, std::vector<std::string>{kEraseDefine},
        std::vector<std::string>{kMaskTargetDefine}}) {
    gpu::rhi::PipelineDescription description;
    description.program = kActiveStrokePipeline;
    description.defines = defines;
    if (device_.pipeline(description, errorMessage) == nullptr) {
      return false;
    }
  }
  scratchFormat_ = device_.isRenderable(gpu::rhi::Format::Rgba16f) ? gpu::rhi::Format::Rgba16f
                                                                   : gpu::rhi::Format::Rgba8;
  return true;
}

const gpu::rhi::Pipeline* Compositor::pipeline(Program program, bool blend) {
  const bool halfFloat =
      target_ != nullptr && target_->description().format == gpu::rhi::Format::Rgba16f;
  const auto index = static_cast<size_t>(program);
  const gpu::rhi::Pipeline*& cached = pipelines_[index][blend ? 1 : 0][halfFloat ? 1 : 0];
  if (cached == nullptr) {
    gpu::rhi::PipelineDescription description;
    description.program = kProgramSources[index].name;
    if (blend) {
      description.blend = normalBlend();
    }
    description.targetFormat = halfFloat ? gpu::rhi::Format::Rgba16f : gpu::rhi::Format::Rgba8;
    std::string errorMessage;
    cached = device_.pipeline(description, errorMessage);
    if (cached == nullptr) {
      NANQUIM_LOG_ERROR("%s", errorMessage.c_str());
    }
  }
  return cached;
}

std::unique_ptr<gpu::rhi::Texture> Compositor::createTarget(gpu::rhi::Format format, int32_t width,
                                                            int32_t height) {
  gpu::rhi::TextureDescription description;
  description.format = format;
  description.width = width;
  description.height = height;
  description.usage =
      gpu::rhi::kUsageSampled | gpu::rhi::kUsageRenderTarget | gpu::rhi::kUsageReadback;
  return device_.createTexture(description);
}

bool Compositor::configure(int32_t widthPixels, int32_t heightPixels, std::string& errorMessage) {
  release();
  const int32_t maxTextureSize = device_.maxTextureSize();
  if (widthPixels <= 0 || heightPixels <= 0 || widthPixels > maxTextureSize ||
      heightPixels > maxTextureSize) {
    errorMessage = "Canvas " + std::to_string(widthPixels) + "×" + std::to_string(heightPixels) +
                   " exceeds the GPU limit of " + std::to_string(maxTextureSize) + " pixels";
    return false;
  }
  widthPixels_ = widthPixels;
  heightPixels_ = heightPixels;
  canvasTiles_ = tileRangeForCanvas(widthPixels, heightPixels);

  const int32_t levelCount = pyramidLevelCount(widthPixels, heightPixels);
  for (int32_t level = 0; level < levelCount; ++level) {
    Level entry;
    entry.widthPixels = levelDimension(widthPixels, level);
    entry.heightPixels = levelDimension(heightPixels, level);
    entry.tiles = tileRangeForCanvas(entry.widthPixels, entry.heightPixels);
    entry.texture = createTarget(compositeFormat(), entry.widthPixels, entry.heightPixels);
    if (!entry.texture) {
      release();
      errorMessage = "Not enough GPU memory for the composite of this canvas";
      return false;
    }
    if (level > 0) {
      entry.dirty.assign(static_cast<size_t>(entry.tiles.count()), 1);
      entry.showingPreview.assign(static_cast<size_t>(entry.tiles.count()), 0);
    }
    levels_.push_back(std::move(entry));
  }
  gpu::rhi::CommandContext& commands = device_.commands();
  for (const Level& level : levels_) {
    commands.beginPass(gpu::rhi::RenderTarget::of(*level.texture), gpu::rhi::LoadAction::Clear);
    commands.endPass();
  }

  const auto tileCount = static_cast<size_t>(canvasTiles_.count());
  compositeDirty_.assign(tileCount, 0);
  previewed_.assign(tileCount, 0);
  selectedStamp_.assign(tileCount, 0);
  dirtyCount_ = 0;
  dirtyAges_.assign(tileCount, 0);
  gpu::rhi::TextureDescription ages;
  ages.format = gpu::rhi::Format::R8;
  ages.width = canvasTiles_.endColumn;
  ages.height = canvasTiles_.endRow;
  ages.usage = gpu::rhi::kUsageSampled | gpu::rhi::kUsageUpload;
  dirtyAgeTexture_ = device_.createTexture(ages);
  if (activeLayer_) {
    allocateCaches();
  }
  markAllDirty();
  return true;
}

gpu::rhi::Format Compositor::compositeFormat() const {
  return textureFormat(tileStore_.pixelFormat());
}

void Compositor::release() {
  levels_.clear();
  belowCache_.reset();
  aboveCache_.reset();
  dirtyAgeTexture_.reset();
  compositeDirty_.clear();
  previewed_.clear();
  previewTilesShowing_ = 0;
  selectedStamp_.clear();
  belowDirty_.clear();
  aboveDirty_.clear();
  dirtyAges_.clear();
  scratch_.clear();
  strokeOverride_.reset();
  dirtyCount_ = 0;
  settleStart_.reset();
  widthPixels_ = 0;
  heightPixels_ = 0;
  canvasTiles_ = TileRange{};
}

void Compositor::allocateCaches() {
  if (!isConfigured()) {
    return;
  }
  if (!belowCache_ || !aboveCache_) {
    belowCache_ = createTarget(compositeFormat(), widthPixels_, heightPixels_);
    aboveCache_ = createTarget(compositeFormat(), widthPixels_, heightPixels_);
    if (!belowCache_ || !aboveCache_) {
      NANQUIM_LOG_ERROR("No GPU memory for the below/above caches");
      belowCache_.reset();
      aboveCache_.reset();
      belowDirty_.clear();
      aboveDirty_.clear();
      return;
    }
  }
  belowDirty_.assign(static_cast<size_t>(canvasTiles_.count()), 1);
  aboveDirty_.assign(static_cast<size_t>(canvasTiles_.count()), 1);
}

void Compositor::rebuildPlan() { plan_ = buildCompositePlan(tree_, activeLayer_); }

void Compositor::setLayerTree(LayerTree tree) {
  const bool structural = !sameStructure(tree_.layers, tree.layers) ||
                          tree_.background != tree.background ||
                          tree_.backgroundHidden != tree.backgroundHidden || tree_.solo != tree.solo;
  std::unordered_set<LayerId> changed;
  if (!structural) {
    collectChangedSurfaces(tree_.layers, tree.layers, changed);
  }
  tree_ = std::move(tree);
  if (activeLayer_ && tree_.find(*activeLayer_) == nullptr) {
    activeLayer_.reset();
    belowCache_.reset();
    aboveCache_.reset();
    belowDirty_.clear();
    aboveDirty_.clear();
  }
  rebuildPlan();
  if (structural) {
    markAllCachesDirty();
    markAllDirty();
    return;
  }
  for (const LayerId surface : changed) {
    tileStore_.forEachTile(surface, [this](TileCoordinate coordinate) {
      markPositionDirty(coordinate);
      if (!belowDirty_.empty() && canvasTiles_.contains(coordinate)) {
        belowDirty_[tileIndex(coordinate)] = 1;
        aboveDirty_[tileIndex(coordinate)] = 1;
      }
    });
  }
}

void Compositor::setActiveLayer(std::optional<LayerId> layer) {
  if (layer && (tree_.find(*layer) == nullptr || tree_.find(*layer)->isGroup())) {
    layer.reset();
  }
  if (layer == activeLayer_) {
    return;
  }
  activeLayer_ = layer;
  rebuildPlan();
  if (activeLayer_) {
    allocateCaches();
  } else {
    belowCache_.reset();
    aboveCache_.reset();
    belowDirty_.clear();
    aboveDirty_.clear();
  }
}

void Compositor::markAllCachesDirty() {
  if (!belowDirty_.empty()) {
    std::fill(belowDirty_.begin(), belowDirty_.end(), 1);
    std::fill(aboveDirty_.begin(), aboveDirty_.end(), 1);
  }
}

void Compositor::markPositionDirty(TileCoordinate coordinate) {
  if (!isConfigured() || !canvasTiles_.contains(coordinate)) {
    return;
  }
  uint8_t& dirty = compositeDirty_[tileIndex(coordinate)];
  if (dirty == 0) {
    dirty = 1;
    ++dirtyCount_;
    if (!settleStart_) {
      settleStart_ = std::chrono::steady_clock::now();
      visibleResultRecorded_ = false;
    }
  }
}

void Compositor::markAllDirty() {
  if (!isConfigured()) {
    return;
  }
  canvasTiles_.forEach([this](TileCoordinate coordinate) { markPositionDirty(coordinate); });
}

void Compositor::markSurfaceChanged(LayerId surface) {
  tileStore_.forEachTile(surface, [this, surface](TileCoordinate coordinate) {
    markTileChanged(LayerTileKey{surface, coordinate});
  });
}

void Compositor::markTileChanged(const LayerTileKey& key) {
  if (!isConfigured() || !canvasTiles_.contains(key.coordinate)) {
    return;
  }
  markPositionDirty(key.coordinate);
  if (belowDirty_.empty()) {
    return;
  }
  const size_t index = tileIndex(key.coordinate);
  const auto activeUnit = plan_.activeUnit();
  auto found = plan_.unitOfSurface.find(key.layer);
  if (!activeUnit || found == plan_.unitOfSurface.end()) {
    belowDirty_[index] = 1;
    aboveDirty_[index] = 1;
    return;
  }
  if (found->second < *activeUnit) {
    belowDirty_[index] = 1;
  } else if (found->second > *activeUnit) {
    aboveDirty_[index] = 1;
  }
}

void Compositor::setLayerOverride(std::optional<LayerOverride> override, bool reducedPreviews,
                                  std::optional<LevelOverride> levelOverride) {
  layerOverride_ = std::move(override);
  overridePreviews_ = layerOverride_.has_value() && reducedPreviews;
  levelOverride_ = overridePreviews_ ? std::move(levelOverride) : std::nullopt;
}

void Compositor::invalidatePreviews() { std::fill(previewed_.begin(), previewed_.end(), 0); }

void Compositor::invalidatePreviews(const std::vector<TileCoordinate>& coordinates) {
  for (const TileCoordinate coordinate : coordinates) {
    if (canvasTiles_.contains(coordinate)) {
      previewed_[tileIndex(coordinate)] = 0;
    }
  }
}

void Compositor::setStrokePreview(std::optional<StrokePreview> preview) {
  strokePreview_ = preview;
}

void Compositor::markStrokeTilesChanged(const std::vector<TileCoordinate>& coordinates) {
  for (const TileCoordinate coordinate : coordinates) {
    markPositionDirty(coordinate);
  }
}

void Compositor::markPyramidAncestorsDirty(TileCoordinate coordinate) {
  for (size_t level = 1; level < levels_.size(); ++level) {
    coordinate = TileCoordinate{coordinate.column / 2, coordinate.row / 2};
    Level& entry = levels_[level];
    entry.dirty[static_cast<size_t>(coordinate.row) * static_cast<size_t>(entry.tiles.endColumn) +
                static_cast<size_t>(coordinate.column)] = 1;
  }
}

bool Compositor::update(const TileRange& visibleTiles, int32_t coarsestLevel, int32_t previewLevel,
                        std::chrono::steady_clock::time_point deadline,
                        int64_t maxLayerTilesPerUpdate) {
  tilesComposedLastUpdate_ = 0;
  changedRegion_.reset();
  pyramidTilesBuiltLastUpdate_ = 0;
  if (!isConfigured()) {
    return false;
  }
  viewport_.reset();
  scissor_.reset();
  const TileRange visible = visibleTiles.intersection(canvasTiles_);

  if (dirtyCount_ > 0 && !strokePreview_ && (!layerOverride_ || overridePreviews_)) {
    ATrace_beginSection("NanquimCompositePreviews");
    drawPreviews(visible, previewLevel);
    ATrace_endSection();
  }

  std::vector<TileCoordinate> batch;
  bool overBudget = false;
  if (dirtyCount_ > 0) {
    ++selectionStamp_;
    int64_t layerTilesSelected = 0;
    auto consider = [&](TileCoordinate coordinate) {
      const size_t index = tileIndex(coordinate);
      if (overBudget || compositeDirty_[index] == 0 || selectedStamp_[index] == selectionStamp_) {
        return;
      }
      selectedStamp_[index] = selectionStamp_;
      int64_t layerTiles = 0;
      if (!prepareTile(coordinate, layerTiles)) {
        return;
      }
      if (maxLayerTilesPerUpdate <= 0 ||
          (!batch.empty() && layerTilesSelected + layerTiles > maxLayerTilesPerUpdate)) {
        overBudget = true;
        return;
      }
      batch.push_back(coordinate);
      layerTilesSelected += layerTiles;
    };
    visible.forEach(consider);
    canvasTiles_.forEach(consider);
  }

  if (!batch.empty()) {
    ATrace_beginSection("NanquimComposeBatch");
    composeBatch(batch);
    ATrace_endSection();
  }

  recordVisibleResult(visible);

  bool outOfTime = false;
  const int32_t lastLevel = std::min(coarsestLevel, levelCount() - 1);
  const int32_t alignment = 1 << std::max(lastLevel, 0);
  const TileRange alignedVisible{visible.firstColumn / alignment * alignment,
                                 visible.firstRow / alignment * alignment,
                                 (visible.endColumn + alignment - 1) / alignment * alignment,
                                 (visible.endRow + alignment - 1) / alignment * alignment};
  for (int32_t level = 1; level <= lastLevel && !outOfTime; ++level) {
    Level& entry = levels_[level];
    const TileRange region = scaleRangeToLevel(alignedVisible, level).intersection(entry.tiles);
    region.forEach([&](TileCoordinate coordinate) {
      const size_t index =
          static_cast<size_t>(coordinate.row) * static_cast<size_t>(entry.tiles.endColumn) +
          static_cast<size_t>(coordinate.column);
      if (outOfTime || entry.dirty[index] == 0 || !childrenClean(level, coordinate)) {
        return;
      }
      if (isPastDeadline(deadline)) {
        outOfTime = true;
        return;
      }
      buildPyramidTile(level, coordinate);
      entry.dirty[index] = 0;
    });
  }

  endTarget();
  const bool workRemains = dirtyCount_ > 0 || outOfTime;
  if (!workRemains && settleStart_) {
    lastSettleMilliseconds_ =
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - *settleStart_)
            .count();
    settleStart_.reset();
  }
  return workRemains;
}

void Compositor::bindTarget(const gpu::rhi::Texture& texture) {
  if (target_ == &texture) {
    return;
  }
  endTarget();
  gpu::rhi::CommandContext& commands = device_.commands();
  commands.beginPass(gpu::rhi::RenderTarget::of(texture), gpu::rhi::LoadAction::Load);
  target_ = &texture;
  if (viewport_) {
    commands.setViewport(*viewport_);
  }
  commands.setScissor(scissor_);
}

void Compositor::endTarget() {
  if (target_ != nullptr) {
    device_.commands().endPass();
    target_ = nullptr;
  }
}

void Compositor::setViewport(std::optional<gpu::rhi::Rect> rect) {
  viewport_ = rect;
  if (target_ != nullptr) {
    device_.commands().setViewport(
        rect.value_or(gpu::rhi::Rect{0, 0, target_->width(), target_->height()}));
  }
}

void Compositor::setScissor(std::optional<gpu::rhi::Rect> rect) {
  scissor_ = rect;
  if (target_ != nullptr) {
    device_.commands().setScissor(rect);
  }
}

void Compositor::clearTile(std::array<float, 4> premultipliedColor) {
  device_.commands().clear(premultipliedColor, scissor_);
}

std::array<float, 4> Compositor::premultipliedBackground() const {
  if (tree_.backgroundHidden) {
    return {0, 0, 0, 0};
  }
  const float alpha = tree_.background[3];
  std::array<float, 3> color{tree_.background[0], tree_.background[1], tree_.background[2]};
  if (isLinear(tileStore_.pixelFormat())) {
    for (float& channel : color) {
      channel = static_cast<float>(srgbToLinearValue(channel));
    }
  }
  return {color[0] * alpha, color[1] * alpha, color[2] * alpha, alpha};
}

void Compositor::setTileScissor(TileCoordinate coordinate) {
  setScissor(tileRect(coordinate.column * kTileSizePixels, coordinate.row * kTileSizePixels,
                      kTileSizePixels));
}

std::vector<Compositor::OverLayer> Compositor::overLayers(size_t first, size_t end) const {
  std::vector<OverLayer> layers;
  for (size_t index = first; index < end && index < plan_.operations.size(); ++index) {
    const PlanOperation& operation = plan_.operations[index];
    layers.push_back(OverLayer{operation.layer, operation.opacity, operation.tint});
  }
  return layers;
}

void Compositor::drawLayers(TileCoordinate coordinate, const std::vector<OverLayer>& layers,
                            int32_t sampleStride, std::array<int32_t, 2> targetOrigin) {
  const gpu::rhi::Pipeline* layersPipeline = pipeline(Program::Layers, true);
  if (layersPipeline == nullptr) {
    return;
  }
  gpu::rhi::CommandContext& commands = device_.commands();
  CompositeLayersParameters parameters{};
  parameters.targetOrigin = targetOrigin;
  parameters.sampleStride = sampleStride;
  int32_t passSize = 0;

  auto flush = [&]() {
    if (passSize == 0) {
      return;
    }
    parameters.layerCount = passSize;
    commands.bindPipeline(*layersPipeline);
    commands.setUniforms(parameters);
    commands.draw(3);
    passSize = 0;
  };

  for (const OverLayer& layer : layers) {
    if (layer.opacity <= 0.0F) {
      continue;
    }
    const auto slot = tileStore_.slotForRead(LayerTileKey{layer.layer, coordinate});
    if (!slot) {
      continue;
    }
    const auto index = static_cast<size_t>(passSize);
    commands.bindTexture(kLayersBinding + static_cast<uint32_t>(passSize),
                         &tileStore_.pageTexture(slot->page));
    parameters.layerSlotOrigin[index].value = {slotOriginX(*slot), slotOriginY(*slot)};
    parameters.layerOpacity[index].value = layer.opacity;
    parameters.layerTint[index] = documentTint(layer.tint);
    if (++passSize == static_cast<int32_t>(kMaxLayersPerPass)) {
      flush();
    }
  }
  flush();
}

std::array<float, 4> Compositor::documentTint(const std::array<float, 4>& tint) const {
  if (tint[3] <= 0 || !linearLight()) {
    return tint;
  }
  return {static_cast<float>(srgbToLinearValue(tint[0])), static_cast<float>(srgbToLinearValue(tint[1])),
          static_cast<float>(srgbToLinearValue(tint[2])), tint[3]};
}

std::optional<LayerId> Compositor::strokeSurface() const {
  if (layerOverride_ && activeLayer_) {
    return activeLayer_;
  }
  if (!strokePreview_ || !activeLayer_) {
    return std::nullopt;
  }
  if (!strokePreview_->maskTarget) {
    return activeLayer_;
  }
  const LayerTreeNode* layer = tree_.find(*activeLayer_);
  if (layer == nullptr || !layer->mask) {
    return std::nullopt;
  }
  return layer->mask->id;
}

bool Compositor::strokePreviewIsSimple() const {
  if (layerOverride_) {
    return false;
  }
  return !strokePreview_ ||
         (!strokePreview_->maskTarget && !strokePreview_->alphaLock &&
          (strokePreview_->erase || strokePreview_->brushBlend == BlendMode::Normal));
}

std::optional<std::pair<const StrokeBuffer*, StrokeBuffer::Slot>> Compositor::strokeTileAt(
    TileCoordinate coordinate) const {
  if (!strokePreview_) {
    return std::nullopt;
  }
  if (strokePreview_->provisional != nullptr) {
    if (const auto slot = strokePreview_->provisional->find(coordinate)) {
      return std::make_pair(strokePreview_->provisional, *slot);
    }
  }
  if (strokePreview_->buffer != nullptr) {
    if (const auto slot = strokePreview_->buffer->find(coordinate)) {
      return std::make_pair(strokePreview_->buffer, *slot);
    }
  }
  return std::nullopt;
}

void Compositor::drawActiveSurfaceWithStroke(TileCoordinate coordinate, const StrokeBuffer& source,
                                             const StrokeBuffer::Slot& strokeSlot,
                                             float layerOpacity,
                                             std::array<int32_t, 2> targetOrigin, bool blend) {
  const StrokePreview& preview = *strokePreview_;
  std::vector<std::string> defines;
  if (preview.maskTarget) {
    defines.emplace_back(kMaskTargetDefine);
  } else if (preview.erase) {
    defines.emplace_back(kEraseDefine);
  }
  if (preview.selection) {
    defines.emplace_back(kSelectionDefine);
  }
  if (preview.workingCopy != nullptr) {
    defines = {"WORKING"};
    if (linearLight()) {
      defines.emplace_back("WORKING_HALF");
    }
    if (preview.selection) {
      defines.emplace_back(kSelectionDefine);
    }
  } else {
    appendStrokeEffectDefines(preview.effects, !preview.erase && !preview.maskTarget, defines);
  }
  gpu::rhi::PipelineDescription description;
  description.program = kActiveStrokePipeline;
  description.defines = std::move(defines);
  if (blend) {
    description.blend = normalBlend();
  }
  description.targetFormat = target_ != nullptr ? target_->description().format : compositeFormat();
  std::string errorMessage;
  const gpu::rhi::Pipeline* strokePipeline = device_.pipeline(description, errorMessage);
  if (strokePipeline == nullptr) {
    NANQUIM_LOG_ERROR("%s", errorMessage.c_str());
    return;
  }
  const LayerId surface = *strokeSurface();
  gpu::rhi::CommandContext& commands = device_.commands();
  commands.bindPipeline(*strokePipeline);
  const auto surfaceSlot = tileStore_.slotForRead(LayerTileKey{surface, coordinate});
  commands.bindTexture(kActiveSurfaceBinding,
                       surfaceSlot ? &tileStore_.pageTexture(surfaceSlot->page) : nullptr);
  commands.bindTexture(kActiveStrokeBinding, &source.pageTexture(strokeSlot.page));
  ActiveStrokeParameters parameters{};
  parameters.hasSurfaceTile = surfaceSlot ? 1 : 0;
  parameters.surfaceSlotOrigin = {surfaceSlot ? slotOriginX(*surfaceSlot) : 0,
                                  surfaceSlot ? slotOriginY(*surfaceSlot) : 0};
  parameters.strokeSlotOrigin = strokeSlot.origin;
  parameters.targetOrigin = targetOrigin;
  parameters.strokeOpacity = preview.opacity;
  parameters.layerOpacity = layerOpacity;
  parameters.brushBlendMode = static_cast<int32_t>(preview.brushBlend);
  parameters.alphaLock = preview.alphaLock ? 1 : 0;
  parameters.maskLuminance = preview.maskLuminance;
  bindStrokeEffects(commands, preview.effects, coordinate, parameters);
  if (preview.workingCopy != nullptr) {
    commands.bindTexture(kActiveWorkingCopyBinding, preview.workingCopy);
    parameters.workingOrigin = tileOrigin(coordinate);
  }
  if (preview.selection) {
    const auto selectionSlot = tileStore_.slotForRead(LayerTileKey{*preview.selection, coordinate});
    commands.bindTexture(kActiveSelectionBinding,
                         selectionSlot ? &tileStore_.pageTexture(selectionSlot->page) : nullptr);
    parameters.hasSelectionTile = selectionSlot ? 1 : 0;
    parameters.selectionSlotOrigin = {selectionSlot ? slotOriginX(*selectionSlot) : 0,
                                      selectionSlot ? slotOriginY(*selectionSlot) : 0};
  }
  commands.setUniforms(parameters);
  commands.draw(3);
}

void Compositor::drawCopy(const gpu::rhi::Texture& sourceTexture, bool blend) {
  const gpu::rhi::Pipeline* copyPipeline = pipeline(Program::Copy, blend);
  if (copyPipeline == nullptr) {
    return;
  }
  gpu::rhi::CommandContext& commands = device_.commands();
  commands.bindPipeline(*copyPipeline);
  commands.bindTexture(kSourceBinding, &sourceTexture);
  commands.draw(3);
}

void Compositor::drawRegionCopy(const gpu::rhi::Texture& sourceTexture,
                                std::array<int32_t, 2> sourceOrigin,
                                std::array<int32_t, 2> targetOrigin, bool blend) {
  const gpu::rhi::Pipeline* copyPipeline = pipeline(Program::RegionCopy, blend);
  if (copyPipeline == nullptr) {
    return;
  }
  gpu::rhi::CommandContext& commands = device_.commands();
  commands.bindPipeline(*copyPipeline);
  commands.bindTexture(kSourceBinding, &sourceTexture);
  commands.setUniforms(CompositeRegionCopyParameters{targetOrigin, sourceOrigin});
  commands.draw(3);
}

int64_t Compositor::requireResident(TileCoordinate coordinate, size_t first, size_t end,
                                    bool& allResident) {
  int64_t existingTiles = 0;
  auto require = [&](LayerId surface) {
    switch (tileStore_.ensureResident(LayerTileKey{surface, coordinate})) {
      case LayerTileStore::Availability::Resident:
        ++existingTiles;
        break;
      case LayerTileStore::Availability::Loading:
        ++existingTiles;
        allResident = false;
        break;
      case LayerTileStore::Availability::Absent:
        break;
    }
  };
  for (size_t index = first; index < end && index < plan_.operations.size(); ++index) {
    const PlanOperation& operation = plan_.operations[index];
    if (operation.kind != PlanOperation::Kind::Draw) {
      continue;
    }
    require(operation.layer);
    if (operation.mask) {
      require(operation.mask->id);
    }
  }
  return existingTiles;
}

bool Compositor::prepareTile(TileCoordinate coordinate, int64_t& layerTiles) {
  const size_t index = tileIndex(coordinate);
  const size_t operationCount = plan_.operations.size();
  bool allResident = true;
  layerTiles = 0;
  if (plan_.activeOperation && !belowDirty_.empty()) {
    const size_t active = *plan_.activeOperation;
    if (belowDirty_[index] != 0) {
      layerTiles += requireResident(coordinate, 0, active, allResident);
    }
    if (aboveDirty_[index] != 0 || !plan_.aboveIsOver) {
      layerTiles += requireResident(coordinate, active + 1, operationCount, allResident);
    }
    layerTiles += requireResident(coordinate, active, active + 1, allResident) + 2;
  } else {
    layerTiles += requireResident(coordinate, 0, operationCount, allResident);
  }
  if (const auto surface = strokeSurface()) {
    if (tileStore_.ensureResident(LayerTileKey{*surface, coordinate}) ==
        LayerTileStore::Availability::Loading) {
      allResident = false;
    }
    if (strokePreview_->selection &&
        tileStore_.ensureResident(LayerTileKey{*strokePreview_->selection, coordinate}) ==
            LayerTileStore::Availability::Loading) {
      allResident = false;
    }
  }
  layerTiles = std::max<int64_t>(layerTiles, 1);
  return allResident;
}

void Compositor::composeBatch(const std::vector<TileCoordinate>& batch) {
  if (plan_.allPlainOver && strokePreviewIsSimple()) {
    composeBatchFast(batch);
  } else {
    for (const TileCoordinate coordinate : batch) {
      composeGeneral(coordinate);
    }
  }

  for (const TileCoordinate coordinate : batch) {
    for (const LayerId surface : plan_.surfaces) {
      if (!activeLayer_ || surface != *activeLayer_) {
        tileStore_.markReadOnce(LayerTileKey{surface, coordinate});
      }
    }
  }
  for (const TileCoordinate coordinate : batch) {
    const size_t index = tileIndex(coordinate);
    compositeDirty_[index] = 0;
    previewed_[index] = 0;
    --dirtyCount_;
    dirtyAges_[index] = kDirtyAgeFresh;
    markPyramidAncestorsDirty(coordinate);
    extendChangedRegion(0, coordinate);
  }
  tilesComposedLastUpdate_ = static_cast<int64_t>(batch.size());
}

void Compositor::composeBatchFast(const std::vector<TileCoordinate>& batch) {
  setViewport(gpu::rhi::Rect{0, 0, widthPixels_, heightPixels_});
  const size_t operationCount = plan_.operations.size();
  if (plan_.activeOperation && !belowDirty_.empty()) {
    const size_t active = *plan_.activeOperation;
    const std::vector<OverLayer> below = overLayers(0, active);
    const std::vector<OverLayer> above = overLayers(active + 1, operationCount);
    const std::vector<OverLayer> activeLayer = overLayers(active, active + 1);
    bindTarget(*belowCache_);
    for (const TileCoordinate coordinate : batch) {
      const size_t index = tileIndex(coordinate);
      if (belowDirty_[index] == 0) {
        continue;
      }
      setTileScissor(coordinate);
      clearTile(premultipliedBackground());
      drawLayers(coordinate, below, 1, tileOrigin(coordinate));
      belowDirty_[index] = 0;
    }
    bindTarget(*aboveCache_);
    for (const TileCoordinate coordinate : batch) {
      const size_t index = tileIndex(coordinate);
      if (aboveDirty_[index] == 0) {
        continue;
      }
      setTileScissor(coordinate);
      clearTile({0, 0, 0, 0});
      drawLayers(coordinate, above, 1, tileOrigin(coordinate));
      aboveDirty_[index] = 0;
    }
    bindTarget(*levels_[0].texture);
    for (const TileCoordinate coordinate : batch) {
      setTileScissor(coordinate);
      drawCopy(*belowCache_, false);
      const auto stroke = strokeTileAt(coordinate);
      if (stroke) {
        drawActiveSurfaceWithStroke(coordinate, *stroke->first, stroke->second,
                                    activeLayer.front().opacity, tileOrigin(coordinate), true);
      } else {
        drawLayers(coordinate, activeLayer, 1, tileOrigin(coordinate));
      }
      drawCopy(*aboveCache_, true);
    }
    return;
  }
  const std::vector<OverLayer> layers = overLayers(0, operationCount);
  bindTarget(*levels_[0].texture);
  for (const TileCoordinate coordinate : batch) {
    setTileScissor(coordinate);
    clearTile(premultipliedBackground());
    drawLayers(coordinate, layers, 1, tileOrigin(coordinate));
  }
}

void Compositor::composeGeneral(TileCoordinate coordinate) {
  const size_t index = tileIndex(coordinate);
  const size_t operationCount = plan_.operations.size();
  const bool withStroke = strokeSurface().has_value() && renderStrokeOverride(coordinate);
  if (!ensureScratch(plan_.levels)) {
    return;
  }
  const gpu::rhi::Rect canvas{0, 0, widthPixels_, heightPixels_};
  int32_t parity = 0;
  if (plan_.activeOperation && !belowDirty_.empty()) {
    const size_t active = *plan_.activeOperation;
    if (belowDirty_[index] != 0) {
      const int32_t belowParity = evaluate(plan_, coordinate, 0, active, Start::Background, 1, false);
      bindTarget(*belowCache_);
      setViewport(canvas);
      setTileScissor(coordinate);
      drawRegionCopy(scratch(0, belowParity), {0, 0}, tileOrigin(coordinate), false);
      belowDirty_[index] = 0;
    }
    if (plan_.aboveIsOver && aboveDirty_[index] != 0) {
      const int32_t aboveParity =
          evaluate(plan_, coordinate, active + 1, operationCount, Start::Transparent, 1, false);
      bindTarget(*aboveCache_);
      setViewport(canvas);
      setTileScissor(coordinate);
      drawRegionCopy(scratch(0, aboveParity), {0, 0}, tileOrigin(coordinate), false);
      aboveDirty_[index] = 0;
    }
    if (plan_.aboveIsOver) {
      parity = evaluate(plan_, coordinate, active, active + 1, Start::BelowCache, 1, withStroke);
      bindTarget(scratch(0, parity));
      setViewport(tileRect(0, 0, kTileSizePixels));
      setScissor(tileRect(0, 0, kTileSizePixels));
      drawRegionCopy(*aboveCache_, tileOrigin(coordinate), {0, 0}, true);
    } else {
      parity = evaluate(plan_, coordinate, active, operationCount, Start::BelowCache, 1, withStroke);
    }
  } else {
    parity = evaluate(plan_, coordinate, 0, operationCount, Start::Background, 1, withStroke);
  }
  bindTarget(*levels_[0].texture);
  setViewport(canvas);
  setTileScissor(coordinate);
  drawRegionCopy(scratch(0, parity), {0, 0}, tileOrigin(coordinate), false);
}

bool Compositor::ensureScratch(int32_t levels) {
  while (static_cast<int32_t>(scratch_.size()) < levels) {
    std::array<std::unique_ptr<gpu::rhi::Texture>, 2> pair{
        createTarget(scratchFormat_, kTileSizePixels, kTileSizePixels),
        createTarget(scratchFormat_, kTileSizePixels, kTileSizePixels)};
    if (!pair[0] || !pair[1]) {
      NANQUIM_LOG_ERROR("No GPU memory for the composite's scratch buffers");
      return false;
    }
    scratch_.push_back(std::move(pair));
  }
  if (!strokeOverride_) {
    strokeOverride_ = createTarget(compositeFormat(), kTileSizePixels, kTileSizePixels);
  }
  return strokeOverride_ != nullptr;
}

bool Compositor::renderStrokeOverride(TileCoordinate coordinate, int32_t sampleStride) {
  if (layerOverride_) {
    if (!ensureScratch(plan_.levels)) {
      return false;
    }
    bindTarget(*strokeOverride_);
    const int32_t size = std::max(kTileSizePixels / sampleStride, 1);
    setViewport(tileRect(0, 0, size));
    setScissor(tileRect(0, 0, size));
    clearTile({0, 0, 0, 0});
    endTarget();
    (*layerOverride_)(coordinate, sampleStride, *strokeOverride_);
    return true;
  }
  const auto stroke = strokeTileAt(coordinate);
  if (!stroke || !ensureScratch(plan_.levels)) {
    return false;
  }
  bindTarget(*strokeOverride_);
  setViewport(tileRect(0, 0, kTileSizePixels));
  setScissor(tileRect(0, 0, kTileSizePixels));
  drawActiveSurfaceWithStroke(coordinate, *stroke->first, stroke->second, 1, {0, 0}, false);
  return true;
}

int32_t Compositor::evaluate(const CompositePlan& plan, TileCoordinate coordinate, size_t first,
                             size_t end, Start start, int32_t sampleStride, bool withStroke) {
  if (!ensureScratch(plan.levels)) {
    return 0;
  }
  const int32_t regionSize = std::max(kTileSizePixels / sampleStride, 1);
  std::vector<int32_t> parity(static_cast<size_t>(plan.levels), 0);
  setViewport(tileRect(0, 0, kTileSizePixels));
  setScissor(tileRect(0, 0, regionSize));
  bindTarget(scratch(0, 0));
  switch (start) {
    case Start::Background:
      clearTile(premultipliedBackground());
      break;
    case Start::Transparent:
      clearTile({0, 0, 0, 0});
      break;
    case Start::BelowCache:
      drawRegionCopy(*belowCache_, tileOrigin(coordinate), {0, 0}, false);
      break;
  }
  size_t index = first;
  while (index < end) {
    const PlanOperation& operation = plan.operations[index];
    const auto level = static_cast<size_t>(operation.level);
    if (operation.kind == PlanOperation::Kind::Push) {
      bindTarget(scratch(operation.level, 0));
      clearTile({0, 0, 0, 0});
      parity[level] = 0;
      ++index;
      continue;
    }
    const bool overridden =
        withStroke && activeLayer_ && operation.kind == PlanOperation::Kind::Draw &&
        operation.layer == *activeLayer_;
    if (operation.isPlainOver() && !overridden) {
      std::vector<OverLayer> run;
      while (index < end) {
        const PlanOperation& next = plan.operations[index];
        const bool nextOverridden = withStroke && activeLayer_ && next.layer == *activeLayer_;
        if (!next.isPlainOver() || next.level != operation.level || nextOverridden) {
          break;
        }
        run.push_back(OverLayer{next.layer, next.opacity, next.tint});
        ++index;
      }
      bindTarget(scratch(operation.level, parity[level]));
      drawLayers(coordinate, run, sampleStride, {0, 0});
      continue;
    }
    const int32_t sourceParity =
        operation.kind == PlanOperation::Kind::Pop ? parity[level + 1] : 0;
    bindTarget(scratch(operation.level, 1 - parity[level]));
    drawBlendStep(operation, coordinate, sampleStride, regionSize, parity[level], sourceParity,
                  withStroke);
    parity[level] = 1 - parity[level];
    ++index;
  }
  return parity[0];
}

void Compositor::drawBlendStep(const PlanOperation& operation, TileCoordinate coordinate,
                               int32_t sampleStride, int32_t /*regionSize*/,
                               int32_t backdropParity, int32_t sourceParity, bool withStroke) {
  const gpu::rhi::Pipeline* blendPipeline = pipeline(Program::Blend, false);
  if (blendPipeline == nullptr) {
    return;
  }
  CompositeBlendParameters parameters{};
  parameters.opacity = operation.opacity;
  parameters.blendMode = static_cast<int32_t>(operation.mode);
  parameters.clipToBackdrop = operation.clip ? 1 : 0;
  if (operation.kind == PlanOperation::Kind::Draw) {
    parameters.sourceTint = documentTint(operation.tint);
  }
  parameters.sourceStride = 1;
  parameters.maskStride = 1;

  const gpu::rhi::Texture* sourceTexture = nullptr;
  bool hasSource = true;
  const gpu::rhi::Texture* maskTexture = nullptr;
  if (operation.kind == PlanOperation::Kind::Pop) {
    sourceTexture = &scratch(operation.level + 1, sourceParity);
  } else {
    const bool active = withStroke && activeLayer_ && operation.layer == *activeLayer_;
    const bool maskTarget = !layerOverride_ && strokePreview_ && strokePreview_->maskTarget;
    const bool layerOverridden = active && !maskTarget;
    const bool maskOverridden = active && maskTarget;
    if (layerOverridden) {
      sourceTexture = strokeOverride_.get();
    } else if (const auto slot = tileStore_.slotForRead(LayerTileKey{operation.layer, coordinate})) {
      sourceTexture = &tileStore_.pageTexture(slot->page);
      parameters.sourceOrigin = {slotOriginX(*slot), slotOriginY(*slot)};
      parameters.sourceStride = sampleStride;
    } else {
      hasSource = false;
    }
    if (operation.mask) {
      parameters.maskInverted = operation.mask->inverted ? 1 : 0;
      if (maskOverridden) {
        parameters.maskMode = 1;
        maskTexture = strokeOverride_.get();
      } else if (const auto slot =
                     tileStore_.slotForRead(LayerTileKey{operation.mask->id, coordinate})) {
        parameters.maskMode = 1;
        maskTexture = &tileStore_.pageTexture(slot->page);
        parameters.maskOrigin = {slotOriginX(*slot), slotOriginY(*slot)};
        parameters.maskStride = sampleStride;
      } else {
        parameters.maskMode = 2;
      }
    }
  }
  parameters.hasSource = hasSource ? 1 : 0;
  gpu::rhi::CommandContext& commands = device_.commands();
  commands.bindPipeline(*blendPipeline);
  commands.bindTexture(kBackdropBinding, &scratch(operation.level, backdropParity));
  commands.bindTexture(kBlendSourceBinding, sourceTexture);
  commands.bindTexture(kMaskBinding, maskTexture);
  commands.setUniforms(parameters);
  commands.draw(3);
}

bool Compositor::planIsResident(const CompositePlan& plan, TileCoordinate coordinate) {
  bool allResident = true;
  for (const PlanOperation& operation : plan.operations) {
    if (operation.kind != PlanOperation::Kind::Draw) {
      continue;
    }
    for (const LayerId surface : {operation.layer, operation.mask ? operation.mask->id : 0U}) {
      if (surface != 0 && tileStore_.ensureResident(LayerTileKey{surface, coordinate}) ==
                              LayerTileStore::Availability::Loading) {
        allResident = false;
      }
    }
  }
  return allResident;
}

void Compositor::renderPlanIntoSlot(const CompositePlan& plan, TileCoordinate coordinate,
                                    const gpu::rhi::Texture& pageTexture, const AtlasSlot& slot) {
  viewport_.reset();
  scissor_.reset();
  const int32_t parity =
      evaluate(plan, coordinate, 0, plan.operations.size(), Start::Transparent, 1, false);
  if (scratch_.empty()) {
    endTarget();
    return;
  }
  const std::array<int32_t, 2> origin{slotOriginX(slot), slotOriginY(slot)};
  setViewport(gpu::rhi::Rect{0, 0, kAtlasPageSizePixels, kAtlasPageSizePixels});
  setScissor(tileRect(origin[0], origin[1], kTileSizePixels));
  bindTarget(pageTexture);
  drawRegionCopy(scratch(0, parity), {0, 0}, origin, false);
  endTarget();
}

const gpu::rhi::Texture* Compositor::renderPlanAtLevel(const CompositePlan& plan, int32_t level,
                                                       int32_t width, int32_t height) {
  if (!frameTarget_ || frameTarget_->description().width != width ||
      frameTarget_->description().height != height ||
      frameTarget_->description().format != compositeFormat()) {
    frameTarget_ = createTarget(compositeFormat(), width, height);
    if (!frameTarget_) {
      return nullptr;
    }
  }
  const int32_t stride = 1 << level;
  const int32_t size = std::max(kTileSizePixels / stride, 1);
  bool ok = true;
  canvasTiles_.forEach([&](TileCoordinate coordinate) {
    if (!ok) {
      return;
    }
    viewport_.reset();
    scissor_.reset();
    const int32_t parity =
        evaluate(plan, coordinate, 0, plan.operations.size(), Start::Background, stride, false);
    if (scratch_.empty()) {
      ok = false;
      return;
    }
    const std::array<int32_t, 2> origin{coordinate.column * kTileSizePixels / stride,
                                        coordinate.row * kTileSizePixels / stride};
    bindTarget(*frameTarget_);
    setViewport(gpu::rhi::Rect{0, 0, width, height});
    setScissor(tileRect(origin[0], origin[1], size));
    drawRegionCopy(scratch(0, parity), {0, 0}, origin, false);
  });
  endTarget();
  return ok ? frameTarget_.get() : nullptr;
}

void Compositor::extendChangedRegion(int32_t level, TileCoordinate coordinate) {
  const double size = static_cast<double>(kTileSizePixels) * static_cast<double>(1 << level);
  const std::array<double, 4> rect{coordinate.column * size, coordinate.row * size,
                                   (coordinate.column + 1) * size, (coordinate.row + 1) * size};
  if (!changedRegion_) {
    changedRegion_ = rect;
    return;
  }
  auto& region = *changedRegion_;
  region = {std::min(region[0], rect[0]), std::min(region[1], rect[1]),
            std::max(region[2], rect[2]), std::max(region[3], rect[3])};
}

void Compositor::buildPyramidTile(int32_t level, TileCoordinate coordinate) {
  const Level& target = levels_[level];
  bindTarget(*target.texture);
  setViewport(gpu::rhi::Rect{0, 0, target.widthPixels, target.heightPixels});
  setTileScissor(coordinate);
  if (const gpu::rhi::Pipeline* pyramidPipeline = pipeline(Program::Pyramid, false)) {
    gpu::rhi::CommandContext& commands = device_.commands();
    commands.bindPipeline(*pyramidPipeline);
    commands.bindTexture(kSourceBinding, levels_[level - 1].texture.get());
    commands.draw(3);
  }
  ++pyramidTilesBuiltLastUpdate_;
  extendChangedRegion(level, coordinate);
  uint8_t& preview = levels_[level].showingPreview[levelTileIndex(level, coordinate)];
  if (preview != 0) {
    preview = 0;
    --previewTilesShowing_;
  }
}

size_t Compositor::levelTileIndex(int32_t level, TileCoordinate coordinate) const {
  const Level& entry = levels_[level];
  return static_cast<size_t>(coordinate.row) * static_cast<size_t>(entry.tiles.endColumn) +
         static_cast<size_t>(coordinate.column);
}

std::array<int32_t, 2> Compositor::tileOrigin(TileCoordinate coordinate) {
  return {coordinate.column * kTileSizePixels, coordinate.row * kTileSizePixels};
}

bool Compositor::childrenClean(int32_t level, TileCoordinate coordinate) const {
  const TileRange& childTiles = levels_[level - 1].tiles;
  for (int32_t row = coordinate.row * 2; row < coordinate.row * 2 + 2; ++row) {
    for (int32_t column = coordinate.column * 2; column < coordinate.column * 2 + 2; ++column) {
      const TileCoordinate child{column, row};
      if (!childTiles.contains(child)) {
        continue;
      }
      const bool dirty = level == 1
                             ? compositeDirty_[tileIndex(child)] != 0
                             : levels_[level - 1].dirty[levelTileIndex(level - 1, child)] != 0;
      if (dirty) {
        return false;
      }
    }
  }
  return true;
}

void Compositor::drawPreviews(const TileRange& visible, int32_t previewLevel) {
  if (previewLevel < 1 || previewLevel >= levelCount()) {
    return;
  }
  if (previewTilesShowing_ > 0 && previewLevel != previewLevel_) {
    return;
  }
  previewLevel_ = previewLevel;
  if (levelOverride_ && drawLevelOverridePreviews(visible, previewLevel)) {
    return;
  }
  Level& target = levels_[previewLevel];
  const int32_t stride = 1 << previewLevel;
  const int32_t size = std::max(kTileSizePixels / stride, 1);
  const size_t operationCount = plan_.operations.size();
  const std::vector<OverLayer> layers =
      plan_.allPlainOver ? overLayers(0, operationCount) : std::vector<OverLayer>{};
  visible.forEach([&](TileCoordinate coordinate) {
    const size_t index = tileIndex(coordinate);
    if (compositeDirty_[index] == 0 || previewed_[index] != 0) {
      return;
    }
    bool allResident = true;
    requireResident(coordinate, 0, operationCount, allResident);
    if (!allResident) {
      return;
    }
    const std::array<int32_t, 2> origin{coordinate.column * kTileSizePixels / stride,
                                        coordinate.row * kTileSizePixels / stride};
    if (plan_.allPlainOver && !layerOverride_) {
      bindTarget(*target.texture);
      setViewport(gpu::rhi::Rect{0, 0, target.widthPixels, target.heightPixels});
      setScissor(tileRect(origin[0], origin[1], size));
      clearTile(premultipliedBackground());
      drawLayers(coordinate, layers, stride, origin);
    } else {
      const bool withOverride = layerOverride_ && renderStrokeOverride(coordinate, stride);
      const int32_t parity =
          evaluate(plan_, coordinate, 0, operationCount, Start::Background, stride, withOverride);
      if (scratch_.empty()) {
        return;
      }
      bindTarget(*target.texture);
      setViewport(gpu::rhi::Rect{0, 0, target.widthPixels, target.heightPixels});
      setScissor(tileRect(origin[0], origin[1], size));
      drawRegionCopy(scratch(0, parity), {0, 0}, origin, false);
    }
    previewed_[index] = 1;
    extendChangedRegion(0, coordinate);
    const TileCoordinate previewTile{coordinate.column >> previewLevel,
                                     coordinate.row >> previewLevel};
    uint8_t& showing = target.showingPreview[levelTileIndex(previewLevel, previewTile)];
    if (showing == 0) {
      showing = 1;
      ++previewTilesShowing_;
    }
  });
}

bool Compositor::drawLevelOverridePreviews(const TileRange& visible, int32_t previewLevel) {
  if (!plan_.activeOperation || !plan_.aboveIsOver || belowDirty_.empty() ||
      !plan_.operations[*plan_.activeOperation].isPlainOver()) {
    return false;
  }
  std::vector<TileCoordinate> positions;
  bool cachesReady = true;
  visible.forEach([&](TileCoordinate coordinate) {
    const size_t index = tileIndex(coordinate);
    if (compositeDirty_[index] == 0 || previewed_[index] != 0) {
      return;
    }
    cachesReady = cachesReady && belowDirty_[index] == 0 && aboveDirty_[index] == 0;
    positions.push_back(coordinate);
  });
  if (!cachesReady) {
    return false;
  }
  if (positions.empty()) {
    return true;
  }
  endTarget();
  const gpu::rhi::Texture* layer = (*levelOverride_)(previewLevel);
  if (layer == nullptr) {
    return false;
  }
  Level& target = levels_[previewLevel];
  const int32_t stride = 1 << previewLevel;
  const int32_t size = std::max(kTileSizePixels / stride, 1);
  bindTarget(*target.texture);
  setViewport(gpu::rhi::Rect{0, 0, target.widthPixels, target.heightPixels});
  const gpu::rhi::Pipeline* previewPipeline = pipeline(Program::LevelPreview, false);
  if (previewPipeline == nullptr) {
    return false;
  }
  gpu::rhi::CommandContext& commands = device_.commands();
  commands.bindPipeline(*previewPipeline);
  commands.bindTexture(kBelowCacheBinding, belowCache_.get());
  commands.bindTexture(kAboveCacheBinding, aboveCache_.get());
  commands.bindTexture(kActiveLayerBinding, layer);
  commands.setUniforms(CompositeLevelPreviewParameters{
      stride, plan_.operations[*plan_.activeOperation].opacity, {0, 0}});
  for (const TileCoordinate coordinate : positions) {
    setScissor(tileRect(coordinate.column * size, coordinate.row * size, size));
    commands.draw(3);
    const size_t index = tileIndex(coordinate);
    previewed_[index] = 1;
    extendChangedRegion(0, coordinate);
    const TileCoordinate previewTile{coordinate.column >> previewLevel,
                                     coordinate.row >> previewLevel};
    uint8_t& showing = target.showingPreview[levelTileIndex(previewLevel, previewTile)];
    if (showing == 0) {
      showing = 1;
      ++previewTilesShowing_;
    }
  }
  return true;
}

void Compositor::recordVisibleResult(const TileRange& visible) {
  if (visibleResultRecorded_ || !settleStart_) {
    return;
  }
  bool everyVisibleShown = true;
  visible.forEach([&](TileCoordinate coordinate) {
    const size_t index = tileIndex(coordinate);
    if (compositeDirty_[index] != 0 && previewed_[index] == 0) {
      everyVisibleShown = false;
    }
  });
  if (everyVisibleShown) {
    lastVisibleResultMilliseconds_ =
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - *settleStart_)
            .count();
    visibleResultRecorded_ = true;
  }
}

std::optional<int32_t> Compositor::displayLevelOverride(const TileRange& visibleTiles) const {
  if (previewTilesShowing_ == 0 || previewLevel_ < 1 || previewLevel_ >= levelCount()) {
    return std::nullopt;
  }
  const Level& entry = levels_[previewLevel_];
  const TileRange region = scaleRangeToLevel(visibleTiles.intersection(canvasTiles_), previewLevel_)
                               .intersection(entry.tiles);
  bool previewInView = false;
  region.forEach([&](TileCoordinate coordinate) {
    if (entry.showingPreview[levelTileIndex(previewLevel_, coordinate)] != 0) {
      previewInView = true;
    }
  });
  return previewInView ? std::optional<int32_t>(previewLevel_) : std::nullopt;
}

const gpu::rhi::Texture* Compositor::updateDirtyAgeTexture() {
  if (!isConfigured() || !dirtyAgeTexture_) {
    return nullptr;
  }
  device_.uploadTexture(*dirtyAgeTexture_, dirtyAges_.data());
  for (uint8_t& age : dirtyAges_) {
    age = age > kDirtyAgeDecayPerFrame ? static_cast<uint8_t>(age - kDirtyAgeDecayPerFrame) : 0;
  }
  return dirtyAgeTexture_.get();
}

CompositorStats Compositor::stats() const {
  CompositorStats stats;
  stats.dirtyTiles = dirtyCount_;
  stats.tilesComposedLastUpdate = tilesComposedLastUpdate_;
  stats.pyramidTilesBuiltLastUpdate = pyramidTilesBuiltLastUpdate_;
  stats.levelCount = levelCount();
  stats.lastSettleMilliseconds = lastSettleMilliseconds_;
  stats.lastVisibleResultMilliseconds = lastVisibleResultMilliseconds_;
  const int64_t texelBytes = bytesPerPixel(tileStore_.pixelFormat());
  for (const Level& level : levels_) {
    stats.compositeBytes += static_cast<int64_t>(level.widthPixels) * level.heightPixels * texelBytes;
  }
  if (belowCache_) {
    stats.cacheBytes = static_cast<int64_t>(widthPixels_) * heightPixels_ * texelBytes * 2;
  }
  const int64_t scratchTexelBytes = scratchFormat_ == gpu::rhi::Format::Rgba16f ? 8 : 4;
  stats.cacheBytes += static_cast<int64_t>(scratch_.size()) * 2 * kTileSizePixels *
                      kTileSizePixels * scratchTexelBytes;
  return stats;
}

}  // namespace nanquim::canvas
