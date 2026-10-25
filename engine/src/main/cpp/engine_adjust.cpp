#include <android/trace.h>

#include <algorithm>
#include <string_view>

#include "canvas/level_selection.h"
#include "engine_context.h"
#include "engine_programs.h"

namespace nanquim {

namespace {

constexpr int64_t kLargestHalfFloatImage = 2048LL * 2048;

bool contains(const canvas::TileRange& outer, const canvas::TileRange& inner) {
  return inner.isEmpty() ||
         (inner.firstColumn >= outer.firstColumn && inner.firstRow >= outer.firstRow &&
          inner.endColumn <= outer.endColumn && inner.endRow <= outer.endRow);
}

int32_t levelOfStride(int32_t stride) {
  int32_t level = 0;
  while ((1 << level) < stride) {
    ++level;
  }
  return level;
}

}  // namespace

void EngineContext::beginAdjustment(canvas::LayerId layer, std::optional<canvas::LayerId> selection,
                                    std::optional<canvas::LayerId> mask,
                                    adjust::Parameters parameters, float amount) {
  post([this, layer, selection, mask, parameters, amount] {
    runStrokeTask([this, layer, selection, mask, parameters, amount] {
      if (!document_) {
        return;
      }
      AdjustmentSession& session = adjustmentSession_.emplace();
      session.layer = layer;
      session.selection = selection;
      session.mask = mask;
      session.parameters = parameters;
      session.amount = amount;
      if (parameters.filter == adjust::Filter::Liquify) {
        session.liquifyField.emplace(compositor_->widthPixels(), compositor_->heightPixels());
      }
      frameRequested_ = true;
    });
  });
}

void EngineContext::setAdjustment(adjust::Parameters parameters, float amount) {
  post([this, parameters, amount] {
    if (!adjustmentSession_ || adjustmentSession_->endRequested) {
      return;
    }
    AdjustmentSession& session = *adjustmentSession_;
    session.parameters = parameters;
    session.amount = amount;
    ++session.version;
    session.reducedResultOutdated = true;
    session.lastChange = std::chrono::steady_clock::now();
    if (!session.shown) {
      return;
    }
    std::vector<canvas::TileCoordinate> tiles = adjustmentOutputTiles();
    for (const canvas::TileCoordinate coordinate : session.shownTiles) {
      markTileChanged({session.layer, coordinate});
    }
    for (const canvas::TileCoordinate coordinate : tiles) {
      markTileChanged({session.layer, coordinate});
    }
    session.shownTiles = std::move(tiles);
    compositor_->invalidatePreviews();
    frameRequested_ = true;
  });
}

void EngineContext::endAdjustment(bool commit, int64_t historyEntry) {
  post([this, commit, historyEntry] {
    if (!adjustmentSession_ || adjustmentSession_->endRequested) {
      return;
    }
    adjustmentSession_->endRequested = true;
    adjustmentSession_->commit = commit;
    adjustmentSession_->historyEntry = historyEntry;
    frameRequested_ = true;
  });
}

std::vector<canvas::TileCoordinate> EngineContext::adjustmentTiles() const {
  std::vector<canvas::TileCoordinate> tiles;
  const canvas::TileRange canvasTiles = compositor_->canvasTiles();
  for (const canvas::TileCoordinate coordinate : tileStore_->tilesOf(adjustmentSession_->layer)) {
    if (canvasTiles.contains(coordinate)) {
      tiles.push_back(coordinate);
    }
  }
  return tiles;
}

std::vector<canvas::TileCoordinate> EngineContext::adjustmentOutputTiles() const {
  const AdjustmentSession& session = *adjustmentSession_;
  std::vector<canvas::TileCoordinate> layerTiles = adjustmentTiles();
  if (!adjust::isSpatial(session.parameters.filter)) {
    return layerTiles;
  }
  const canvas::TileRange canvasTiles = compositor_->canvasTiles();
  if (session.liquifyField) {
    std::vector<canvas::TileCoordinate> tiles;
    const adjust::FieldRegion& bounds = session.liquifyField->bounds();
    if (bounds.isEmpty()) {
      return tiles;
    }
    const int32_t spacing = adjust::kLiquifyFieldSpacing;
    const int32_t size = canvas::kTileSizePixels;
    const canvas::TileRange inTiles{std::max(bounds.first[0] - 1, 0) * spacing / size,
                                    std::max(bounds.first[1] - 1, 0) * spacing / size,
                                    bounds.end[0] * spacing / size + 1,
                                    bounds.end[1] * spacing / size + 1};
    inTiles.intersection(canvasTiles).forEach([&tiles](canvas::TileCoordinate coordinate) {
      tiles.push_back(coordinate);
    });
    return tiles;
  }
  const adjust::SpatialPlan plan = adjust::spatialPlan(
      session.parameters, compositor_->widthPixels(), compositor_->heightPixels(), 0);
  std::vector<canvas::TileCoordinate> tiles;
  if (plan.reach < 0) {
    if (!layerTiles.empty()) {
      canvasTiles.forEach(
          [&tiles](canvas::TileCoordinate coordinate) { tiles.push_back(coordinate); });
    }
    return tiles;
  }
  const int32_t rings = (plan.reach + canvas::kTileSizePixels - 1) / canvas::kTileSizePixels;
  std::vector<uint8_t> marked(static_cast<size_t>(canvasTiles.count()), 0);
  for (const canvas::TileCoordinate coordinate : layerTiles) {
    for (int32_t row = coordinate.row - rings; row <= coordinate.row + rings; ++row) {
      for (int32_t column = coordinate.column - rings; column <= coordinate.column + rings;
           ++column) {
        if (canvasTiles.contains({column, row})) {
          marked[static_cast<size_t>(row) * static_cast<size_t>(canvasTiles.endColumn) +
                 static_cast<size_t>(column)] = 1;
        }
      }
    }
  }
  canvasTiles.forEach([&](canvas::TileCoordinate coordinate) {
    if (marked[static_cast<size_t>(coordinate.row) * static_cast<size_t>(canvasTiles.endColumn) +
               static_cast<size_t>(coordinate.column)] != 0) {
      tiles.push_back(coordinate);
    }
  });
  return tiles;
}

bool EngineContext::paintsAdjustmentMask() const {
  return paintSession_ && adjustmentSession_ && adjustmentSession_->mask &&
         paintSession_->commit.selectionTarget &&
         paintSession_->commit.surface == *adjustmentSession_->mask;
}

void EngineContext::adjustmentMaskChanged(const std::vector<canvas::TileCoordinate>& coordinates) {
  AdjustmentSession& session = *adjustmentSession_;
  session.reducedMaskOutdated.insert(coordinates.begin(), coordinates.end());
  session.reducedResultOutdated = true;
  compositor_->markStrokeTilesChanged(coordinates);
  compositor_->invalidatePreviews();
  frameRequested_ = true;
}

void EngineContext::processAdjustment() {
  if (!adjustmentSession_ || !document_) {
    return;
  }
  if (!adjustmentSession_->shown && !showAdjustment()) {
    return;
  }
  AdjustmentSession& session = *adjustmentSession_;
  if (session.spatialRetry) {
    session.spatialRetry = false;
    for (const canvas::TileCoordinate coordinate : session.shownTiles) {
      markTileChanged({session.layer, coordinate});
    }
    compositor_->invalidatePreviews();
    frameRequested_ = true;
  }
  if (session.liquifyStroke && session.liquifyStroke->ended()) {
    const double elapsed = std::chrono::duration<double, std::milli>(
                               std::chrono::steady_clock::now() - session.liquifyStrokeEnd)
                               .count();
    const bool going = session.liquifyStroke->advanceMomentum(elapsed);
    liquifyFieldChanged();
    if (going) {
      frameRequested_ = true;
    } else {
      finishLiquifyStroke();
    }
  }
  if (session.liquifyField) {
    uploadLiquifyField();
  }
  if (session.spatialDraftShown && !adjustmentChanging()) {
    session.spatialDraftShown = false;
    for (const canvas::TileCoordinate coordinate : session.shownTiles) {
      markTileChanged({session.layer, coordinate});
    }
    frameRequested_ = true;
  }
  if (session.endRequested && !paintSession_) {
    finishAdjustment();
  }
}

bool EngineContext::showAdjustment() {
  AdjustmentSession& session = *adjustmentSession_;
  bool resident = true;
  auto require = [&](canvas::LayerId surface, canvas::TileCoordinate coordinate) {
    resident = tileStore_->ensureResident({surface, coordinate}) !=
                   canvas::LayerTileStore::Availability::Loading &&
               resident;
  };
  for (const canvas::TileCoordinate coordinate : adjustmentTiles()) {
    require(session.layer, coordinate);
  }
  std::vector<canvas::TileCoordinate> tiles = adjustmentOutputTiles();
  for (const canvas::TileCoordinate coordinate : tiles) {
    for (const auto surface : {session.selection, session.mask}) {
      if (surface) {
        require(*surface, coordinate);
      }
    }
  }
  if (!resident) {
    return false;
  }
  session.shown = true;
  compositor_->setActiveLayer(session.layer);
  compositor_->setLayerOverride(
      [this](canvas::TileCoordinate coordinate, int32_t sampleStride,
             const gpu::rhi::Texture& target) {
        renderAdjustmentTile(coordinate, sampleStride, target);
      },
      true, [this](int32_t level) { return renderAdjustmentLevel(level); });
  for (const canvas::TileCoordinate coordinate : tiles) {
    markTileChanged({session.layer, coordinate});
  }
  session.shownTiles = std::move(tiles);
  frameRequested_ = true;
  return true;
}

void EngineContext::renderAdjustmentTile(canvas::TileCoordinate coordinate, int32_t sampleStride,
                                         const gpu::rhi::Texture& target) {
  AdjustmentSession& session = *adjustmentSession_;
  const bool spatial = adjust::isSpatial(session.parameters.filter);
  const auto source = tileStore_->slotForRead({session.layer, coordinate});
  if (!source && !spatial) {
    return;
  }
  auto slotTexture = [this](const canvas::AtlasSlot& slot) {
    return AdjustmentTexture{&tileStore_->pageTexture(slot.page),
                             {canvas::slotOriginX(slot), canvas::slotOriginY(slot)}};
  };
  AdjustmentInputs inputs;
  if (source) {
    inputs.source = slotTexture(*source);
  } else {
    inputs.hasSource = false;
  }
  inputs.amount = session.amount;
  inputs.encodeSource = compositor_->linearLight();
  inputs.decodeResult = compositor_->linearLight();
  inputs.sampleStride = sampleStride;
  inputs.canvasOrigin = {coordinate.column * canvas::kTileSizePixels,
                         coordinate.row * canvas::kTileSizePixels};
  inputs.canvasStride = sampleStride;
  if (session.selection) {
    if (const auto selected = tileStore_->slotForRead({*session.selection, coordinate})) {
      inputs.selection = slotTexture(*selected);
    } else {
      inputs.amount = 0;
    }
  }
  if (session.mask) {
    inputs.masked = true;
    if (const auto painted = tileStore_->slotForRead({*session.mask, coordinate})) {
      inputs.mask = slotTexture(*painted);
    }
    if (paintsAdjustmentMask()) {
      const std::array<const canvas::StrokeBuffer*, 2> buffers{&strokeRenderer_->provisional(),
                                                               &strokeRenderer_->buffer()};
      for (const canvas::StrokeBuffer* buffer : buffers) {
        if (const auto slot = buffer->find(coordinate)) {
          inputs.stroke = AdjustmentStroke{{&buffer->pageTexture(slot->page), slot->origin},
                                           paintSession_->commit.opacity,
                                           paintSession_->commit.maskLuminance};
          break;
        }
      }
    }
  }
  if (spatial) {
    const int32_t level = levelOfStride(sampleStride);
    SpatialResult& result = level == 0 ? session.spatialFull : session.spatialPreview;
    bool ready = false;
    if (level > 0) {
      const canvas::TileRange canvasTiles = compositor_->canvasTiles();
      const canvas::TileRange visible = visibleTiles().intersection(canvasTiles);
      const canvas::TileRange covered = visible.contains(coordinate) ? visible : canvasTiles;
      ready = result.valid && result.version == session.version && result.outputLevel == level &&
              contains(result.covered, covered);
      if (!ready) {
        ready = computeSpatial(result, level, -1, covered);
      }
    } else {
      const canvas::TileRange canvasTiles = compositor_->canvasTiles();
      const bool changing = adjustmentChanging() && !session.endRequested;
      const bool current = result.valid && result.version == session.version &&
                           result.outputLevel == 0 && result.covered.contains(coordinate);
      ready = current && (changing || result.minimumLevel == 0);
      if (!ready) {
        if (changing) {
          ready = computeSpatial(result, 0, draftLevel(), canvasTiles);
        } else {
          const canvas::TileRange visible = visibleTiles().intersection(canvasTiles);
          ready =
              computeSpatial(result, 0, 0, visible.contains(coordinate) ? visible : canvasTiles);
        }
      }
      if (ready && result.minimumLevel > 0) {
        session.spatialDraftShown = true;
      }
    }
    if (!ready) {
      session.spatialRetry = true;
      return;
    }
    inputs.spatial = &result;
  }
  prepareAdjustmentTextures();
  const int32_t size = std::max(canvas::kTileSizePixels / sampleStride, 1);
  gpu::rhi::CommandContext& commands = device_->commands();
  commands.beginPass(gpu::rhi::RenderTarget::of(target), gpu::rhi::LoadAction::Load);
  commands.setViewport(gpu::rhi::Rect{0, 0, size, size});
  commands.setScissor(gpu::rhi::Rect{0, 0, size, size});
  drawAdjustment(inputs, target.description().format);
  commands.endPass();
}

void EngineContext::prepareAdjustmentTextures() {
  AdjustmentSession& session = *adjustmentSession_;
  const auto& table = session.parameters.table;
  if (table && table != session.uploadedTable) {
    if (!session.tableTexture) {
      gpu::rhi::TextureDescription description;
      description.format = gpu::rhi::Format::Rgba32f;
      description.width = static_cast<int32_t>(adjust::kTableSize);
      description.height = 1;
      description.usage = gpu::rhi::kUsageSampled | gpu::rhi::kUsageUpload;
      session.tableTexture = device_->createTexture(description);
    }
    if (session.tableTexture) {
      device_->uploadTexture(*session.tableTexture, table->data());
      session.uploadedTable = table;
    }
  }
  if (session.liquifyField && !session.fieldTexture) {
    const adjust::LiquifyField& field = *session.liquifyField;
    gpu::rhi::TextureDescription description;
    description.format = gpu::rhi::Format::Rg32f;
    description.width = field.width();
    description.height = field.height();
    description.usage = gpu::rhi::kUsageSampled | gpu::rhi::kUsageUpload;
    session.fieldTexture = device_->createTexture(description);
    if (session.fieldTexture) {
      device_->uploadTexture(*session.fieldTexture, field.data().data());
    }
  }
}

void EngineContext::drawAdjustment(const AdjustmentInputs& inputs, gpu::rhi::Format targetFormat) {
  AdjustmentSession& session = *adjustmentSession_;
  const EngineProgram program = inputs.spatial == nullptr ? EngineProgram::AdjustTile
                                : session.liquifyField    ? EngineProgram::AdjustTileLiquify
                                                          : EngineProgram::AdjustTileSpatial;
  const gpu::rhi::Pipeline* pipeline = enginePipeline(program, targetFormat);
  if (pipeline == nullptr) {
    return;
  }
  gpu::rhi::CommandContext& commands = device_->commands();
  commands.bindPipeline(*pipeline);
  const SpatialResult* spatial = inputs.spatial;
  commands.bindTexture(kAdjustSourceBinding, inputs.hasSource ? inputs.source.texture : nullptr);
  commands.bindTexture(kAdjustSelectionBinding,
                       inputs.selection ? inputs.selection->texture : nullptr);
  commands.bindTexture(kAdjustTableBinding,
                       session.parameters.table ? session.tableTexture.get() : nullptr);
  commands.bindTexture(kAdjustMaskBinding, inputs.mask ? inputs.mask->texture : nullptr);
  commands.bindTexture(kAdjustStrokeBinding, inputs.stroke ? inputs.stroke->tile.texture : nullptr);
  commands.bindTexture(kAdjustFilteredBinding, spatial != nullptr ? spatial->result : nullptr);
  commands.bindTexture(kAdjustFieldBinding, session.fieldTexture.get());
  auto origin = [](const std::optional<AdjustmentTexture>& texture) {
    return texture ? texture->origin : std::array<int32_t, 2>{0, 0};
  };
  AdjustTileParameters parameters{};
  parameters.sourceSlotOrigin = inputs.source.origin;
  parameters.selectionSlotOrigin = origin(inputs.selection);
  parameters.maskSlotOrigin = origin(inputs.mask);
  parameters.strokeSlotOrigin =
      inputs.stroke ? inputs.stroke->tile.origin : std::array<int32_t, 2>{0, 0};
  parameters.targetOrigin = inputs.targetOrigin;
  parameters.sampleStride = inputs.sampleStride;
  parameters.hasSource = inputs.hasSource ? 1 : 0;
  parameters.encodeSource = inputs.encodeSource ? 1 : 0;
  parameters.decodeResult = inputs.decodeResult ? 1 : 0;
  parameters.hasSelection = inputs.selection ? 1 : 0;
  parameters.maskMode = !inputs.masked ? 0 : inputs.mask ? 1 : 2;
  parameters.hasStroke = inputs.stroke ? 1 : 0;
  parameters.strokeOpacity = inputs.stroke ? inputs.stroke->opacity : 0;
  parameters.strokeLuminance = inputs.stroke ? inputs.stroke->luminance : 0;
  parameters.amount = inputs.amount;
  parameters.filterKind = static_cast<int32_t>(session.parameters.filter);
  for (size_t index = 0; index < session.parameters.values.size(); ++index) {
    parameters.parameters[index][0] = session.parameters.values[index];
  }
  parameters.spatialMode = spatial == nullptr       ? 0
                           : spatial->plan.unsharp  ? 2
                           : spatial->plan.additive ? 3
                                                    : 1;
  parameters.sharpenStrength =
      spatial == nullptr
          ? 0
          : static_cast<float>(spatial->plan.additive ? spatial->plan.gain : spatial->plan.sharpen);
  parameters.canvasSize = {compositor_->widthPixels(), compositor_->heightPixels()};
  if (session.liquifyField && spatial != nullptr) {
    parameters.spatialMode = 4;
    parameters.fieldSize = {session.liquifyField->width(), session.liquifyField->height()};
    parameters.fieldScale = std::clamp(session.parameters.values[4], 0.0F, 1.0F);
    parameters.liquifyCubic = spatial->outputLevel == 0 && inputs.sampleStride == 1 ? 1 : 0;
  }
  parameters.canvasOrigin = inputs.canvasOrigin;
  parameters.canvasStride = inputs.canvasStride;
  if (spatial != nullptr) {
    parameters.filteredScale = static_cast<float>(spatial->mapping.scale());
    parameters.filteredOffset = static_cast<float>(spatial->mapping.offset);
    parameters.filteredRectOrigin = spatial->rectOrigin;
    parameters.filteredRectSize = spatial->rectSize;
    parameters.filteredLevelSize = spatial->levelSize;
  }
  commands.setUniforms(parameters);
  commands.draw(3);
}

bool EngineContext::ensureReducedCopies(int32_t level) {
  AdjustmentSession& session = *adjustmentSession_;
  const int32_t width = compositor_->levelWidth(level);
  const int32_t height = compositor_->levelHeight(level);
  const int32_t stride = 1 << level;
  const int32_t size = std::max(canvas::kTileSizePixels / stride, 1);
  const canvas::TileRange canvasTiles = compositor_->canvasTiles();
  auto tilesOf = [&](canvas::LayerId surface) {
    std::vector<canvas::TileCoordinate> tiles;
    for (const canvas::TileCoordinate coordinate : tileStore_->tilesOf(surface)) {
      if (canvasTiles.contains(coordinate)) {
        tiles.push_back(coordinate);
      }
    }
    return tiles;
  };
  struct Copy {
    canvas::LayerId surface = 0;
    std::unique_ptr<gpu::rhi::Texture>* texture = nullptr;
    std::vector<canvas::TileCoordinate> positions;
    bool clearAll = true;
    bool layer = false;
  };
  std::vector<Copy> copies;
  const bool rebuild = session.reducedLevel != level;
  if (rebuild) {
    copies.push_back({session.layer, &session.reducedLayer, tilesOf(session.layer), true, true});
    if (session.selection) {
      copies.push_back(
          {*session.selection, &session.reducedSelection, tilesOf(*session.selection), true});
    }
    if (session.mask) {
      copies.push_back({*session.mask, &session.reducedMask, tilesOf(*session.mask), true});
    }
  } else if (session.mask && !session.reducedMaskOutdated.empty()) {
    copies.push_back({*session.mask,
                      &session.reducedMask,
                      {session.reducedMaskOutdated.begin(), session.reducedMaskOutdated.end()},
                      false});
  }
  for (const Copy& copy : copies) {
    for (const canvas::TileCoordinate coordinate : copy.positions) {
      if (tileStore_->hasTile({copy.surface, coordinate}) &&
          !tileStore_->slotForRead({copy.surface, coordinate})) {
        tileStore_->ensureResident({copy.surface, coordinate});
        return false;
      }
    }
  }
  if (rebuild) {
    session.reducedLayer.reset();
    session.reducedSelection.reset();
    session.reducedMask.reset();
    session.spatialPreview.valid = false;
  }
  prepareAdjustmentTextures();
  gpu::rhi::CommandContext& commands = device_->commands();
  for (Copy& copy : copies) {
    std::unique_ptr<gpu::rhi::Texture>& texture = *copy.texture;
    if (!texture) {
      texture = createSurfaceTexture(width, height, gpu::rhi::Format::Rgba8);
      if (!texture) {
        session.reducedLevel = -1;
        return false;
      }
    }
    commands.beginPass(gpu::rhi::RenderTarget::of(*texture),
                       copy.clearAll ? gpu::rhi::LoadAction::Clear : gpu::rhi::LoadAction::Load);
    for (const canvas::TileCoordinate coordinate : copy.positions) {
      const std::array<int32_t, 2> origin{coordinate.column * size, coordinate.row * size};
      if (!copy.clearAll) {
        commands.clear({0, 0, 0, 0}, gpu::rhi::Rect{origin[0], origin[1], size, size});
      }
      const auto slot = tileStore_->slotForRead({copy.surface, coordinate});
      if (!slot) {
        continue;
      }
      commands.setViewport(gpu::rhi::Rect{origin[0], origin[1], size, size});
      AdjustmentInputs inputs;
      inputs.source = {&tileStore_->pageTexture(slot->page),
                       {canvas::slotOriginX(*slot), canvas::slotOriginY(*slot)}};
      inputs.targetOrigin = origin;
      inputs.sampleStride = stride;
      inputs.amount = 0;
      inputs.encodeSource = copy.layer && compositor_->linearLight();
      drawAdjustment(inputs, gpu::rhi::Format::Rgba8);
    }
    commands.endPass();
  }
  if (rebuild) {
    session.reducedResult = createSurfaceTexture(width, height);
    if (!session.reducedResult) {
      return false;
    }
    session.reducedLevel = level;
    session.reducedResultOutdated = true;
  }
  session.reducedMaskOutdated.clear();
  return true;
}

const gpu::rhi::Texture* EngineContext::renderAdjustmentLevel(int32_t level) {
  AdjustmentSession& session = *adjustmentSession_;
  if (paintsAdjustmentMask()) {
    return nullptr;
  }
  if (!ensureReducedCopies(level)) {
    return nullptr;
  }
  const bool spatialFilter = adjust::isSpatial(session.parameters.filter);
  const canvas::TileRange visible = visibleTiles().intersection(compositor_->canvasTiles());
  if (spatialFilter && !contains(session.spatialPreview.covered, visible)) {
    session.reducedResultOutdated = true;
  }
  const std::array<int32_t, 4> liquifyDirty = session.liquifyLevelDirty;
  session.liquifyLevelDirty = {};
  if (session.reducedResultOutdated || liquifyDirty[2] > liquifyDirty[0]) {
    const SpatialResult* spatial = nullptr;
    if (spatialFilter) {
      if (!computeSpatial(session.spatialPreview, level, -1, visible)) {
        return nullptr;
      }
      spatial = &session.spatialPreview;
    }
    prepareAdjustmentTextures();
    gpu::rhi::CommandContext& commands = device_->commands();
    commands.beginPass(gpu::rhi::RenderTarget::of(*session.reducedResult),
                       gpu::rhi::LoadAction::Load);
    AdjustmentInputs inputs;
    inputs.source = {session.reducedLayer.get(), {0, 0}};
    if (session.selection) {
      inputs.selection = AdjustmentTexture{session.reducedSelection.get(), {0, 0}};
    }
    if (session.mask) {
      inputs.masked = true;
      inputs.mask = AdjustmentTexture{session.reducedMask.get(), {0, 0}};
    }
    inputs.amount = session.amount;
    inputs.decodeResult = compositor_->linearLight();
    inputs.spatial = spatial;
    inputs.canvasStride = 1 << level;
    if (!session.reducedResultOutdated) {
      commands.setScissor(gpu::rhi::Rect{liquifyDirty[0] >> level, liquifyDirty[1] >> level,
                                         ((liquifyDirty[2] - liquifyDirty[0]) >> level) + 2,
                                         ((liquifyDirty[3] - liquifyDirty[1]) >> level) + 2});
    } else if (spatial != nullptr) {
      const int32_t size = std::max(canvas::kTileSizePixels >> level, 1);
      const canvas::TileRange& covered = spatial->covered;
      commands.setScissor(gpu::rhi::Rect{covered.firstColumn * size, covered.firstRow * size,
                                         (covered.endColumn - covered.firstColumn) * size,
                                         (covered.endRow - covered.firstRow) * size});
    }
    drawAdjustment(inputs, session.reducedResult->description().format);
    commands.endPass();
    session.reducedResultOutdated = false;
  }
  return session.reducedResult.get();
}

int32_t EngineContext::draftLevel() const {
  constexpr int64_t kDraftTexels = 1024LL * 1024;
  int32_t level = 0;
  while (static_cast<int64_t>(canvas::levelDimension(compositor_->widthPixels(), level)) *
             canvas::levelDimension(compositor_->heightPixels(), level) >
         kDraftTexels) {
    ++level;
  }
  return level;
}

bool EngineContext::computeSpatial(SpatialResult& result, int32_t outputLevel, int32_t minimumLevel,
                                   std::optional<canvas::TileRange> covered) {
  AdjustmentSession& session = *adjustmentSession_;
  const canvas::TileRange canvasTiles = compositor_->canvasTiles();
  if (minimumLevel < 0 || outputLevel > 0) {
    minimumLevel = outputLevel;
  }
  const canvas::TileRange wanted = covered.value_or(canvasTiles);
  if (result.valid && result.version == session.version && result.outputLevel == outputLevel &&
      result.minimumLevel == minimumLevel && contains(result.covered, wanted)) {
    return true;
  }
  const int32_t width = compositor_->widthPixels();
  const int32_t height = compositor_->heightPixels();
  adjust::SpatialPlan plan = adjust::spatialPlan(session.parameters, width, height, minimumLevel);
  const int32_t level = plan.level;
  const int32_t factor = 1 << level;
  const std::array<int32_t, 2> levelSize{canvas::levelDimension(width, level),
                                         canvas::levelDimension(height, level)};
  std::array<int32_t, 2> rectOrigin{0, 0};
  std::array<int32_t, 2> rectSize = levelSize;
  const std::vector<canvas::TileCoordinate> layerTiles = adjustmentTiles();
  if (outputLevel == 0) {
    std::array<int32_t, 2> first{width, height};
    std::array<int32_t, 2> end{0, 0};
    for (const canvas::TileCoordinate coordinate : layerTiles) {
      first = {std::min(first[0], coordinate.column * canvas::kTileSizePixels),
               std::min(first[1], coordinate.row * canvas::kTileSizePixels)};
      end = {std::max(end[0], (coordinate.column + 1) * canvas::kTileSizePixels),
             std::max(end[1], (coordinate.row + 1) * canvas::kTileSizePixels)};
    }
    if (plan.reach >= 0) {
      const int32_t margin = plan.reach + factor;
      const std::array<int32_t, 2> coveredFirst{wanted.firstColumn * canvas::kTileSizePixels,
                                                wanted.firstRow * canvas::kTileSizePixels};
      const std::array<int32_t, 2> coveredEnd{wanted.endColumn * canvas::kTileSizePixels,
                                              wanted.endRow * canvas::kTileSizePixels};
      for (size_t axis = 0; axis < 2; ++axis) {
        first[axis] = std::max(first[axis], coveredFirst[axis] - margin);
        end[axis] = std::min(end[axis], coveredEnd[axis] + margin);
      }
    }
    if (end[0] <= first[0] || end[1] <= first[1]) {
      rectSize = {1, 1};
    } else if (plan.reach >= 0) {
      const int32_t margin = plan.reach + factor;
      for (size_t axis = 0; axis < 2; ++axis) {
        const int32_t firstTexel = std::max(first[axis] - margin, 0) / factor - 2;
        const int32_t endTexel = (end[axis] + margin + factor - 1) / factor + 2;
        rectOrigin[axis] = std::max(firstTexel, 0);
        rectSize[axis] = std::min(endTexel, levelSize[axis]) - rectOrigin[axis];
      }
    }
    for (const canvas::TileCoordinate coordinate : layerTiles) {
      const int32_t texelsPerTile = std::max(canvas::kTileSizePixels / factor, 1);
      const int32_t column = coordinate.column * canvas::kTileSizePixels / factor;
      const int32_t row = coordinate.row * canvas::kTileSizePixels / factor;
      const bool read = column < rectOrigin[0] + rectSize[0] &&
                        column + texelsPerTile > rectOrigin[0] &&
                        row < rectOrigin[1] + rectSize[1] && row + texelsPerTile > rectOrigin[1];
      if (read && !tileStore_->slotForRead({session.layer, coordinate})) {
        tileStore_->ensureResident({session.layer, coordinate});
        return false;
      }
    }
  } else {
    if (!ensureReducedCopies(outputLevel)) {
      return false;
    }
    if (plan.reach >= 0) {
      const int32_t margin = plan.reach + 8 * factor;
      const std::array<int32_t, 2> first{wanted.firstColumn * canvas::kTileSizePixels - margin,
                                         wanted.firstRow * canvas::kTileSizePixels - margin};
      const std::array<int32_t, 2> end{wanted.endColumn * canvas::kTileSizePixels + margin,
                                       wanted.endRow * canvas::kTileSizePixels + margin};
      for (size_t axis = 0; axis < 2; ++axis) {
        rectOrigin[axis] = std::max(first[axis], 0) / factor;
        rectSize[axis] = std::max(
            std::min((end[axis] + factor - 1) / factor, levelSize[axis]) - rectOrigin[axis], 1);
      }
    }
  }
  auto image = [&](size_t index) -> gpu::rhi::Texture* {
    const gpu::rhi::Format format =
        device_->isRenderable(gpu::rhi::Format::Rgba16f) &&
                static_cast<int64_t>(rectSize[0]) * rectSize[1] <= kLargestHalfFloatImage
            ? gpu::rhi::Format::Rgba16f
            : gpu::rhi::Format::Rgba8;
    if (!result.images[index] || result.imageSizes[index] != rectSize ||
        result.imageFormats[index] != format) {
      constexpr size_t kPooledImages = 6;
      auto& pool = session.imagePool;
      if (result.images[index]) {
        pool.push_back({result.imageSizes[index], result.imageFormats[index],
                        std::move(result.images[index])});
      }
      const auto found = std::find_if(pool.begin(), pool.end(), [&](const auto& pooled) {
        return pooled.size == rectSize && pooled.format == format;
      });
      if (found != pool.end()) {
        result.images[index] = std::move(found->texture);
        pool.erase(found);
      } else {
        result.images[index] = createSurfaceTexture(rectSize[0], rectSize[1], format);
        if (!result.images[index]) {
          return nullptr;
        }
      }
      while (pool.size() > kPooledImages) {
        pool.erase(pool.begin());
      }
      result.imageSizes[index] = rectSize;
      result.imageFormats[index] = format;
    }
    return result.images[index].get();
  };
  result.valid = false;
  gpu::rhi::CommandContext& commands = device_->commands();
  SpatialPassInputs box;
  box.rectOrigin = rectOrigin;
  box.rectSize = rectSize;
  box.levelSize = levelSize;
  box.outputOrigin = rectOrigin;
  const gpu::rhi::Texture* input = nullptr;
  size_t next = 0;
  adjust::LevelMapping mapping;
  if (outputLevel == 0) {
    mapping = adjust::boxMapping(level);
    gpu::rhi::Texture* target = image(0);
    if (target == nullptr) {
      return false;
    }
    const int32_t texelsPerTile = canvas::kTileSizePixels / factor;
    struct BoxTile {
      canvas::AtlasSlot slot;
      std::array<int32_t, 2> pixel;
      std::array<int32_t, 2> first;
      std::array<int32_t, 2> end;
    };
    std::vector<BoxTile> boxTiles;
    for (const canvas::TileCoordinate coordinate : layerTiles) {
      const auto slot = tileStore_->slotForRead({session.layer, coordinate});
      const std::array<int32_t, 2> pixel{coordinate.column * canvas::kTileSizePixels,
                                         coordinate.row * canvas::kTileSizePixels};
      std::array<int32_t, 2> first{};
      std::array<int32_t, 2> end{};
      for (size_t axis = 0; axis < 2; ++axis) {
        first[axis] = std::max(pixel[axis] / factor, rectOrigin[axis]);
        end[axis] =
            std::min(pixel[axis] / factor + texelsPerTile, rectOrigin[axis] + rectSize[axis]);
      }
      if (slot && end[0] > first[0] && end[1] > first[1]) {
        boxTiles.push_back({*slot, pixel, first, end});
      }
    }
    commands.beginPass(gpu::rhi::RenderTarget::of(*target), gpu::rhi::LoadAction::Clear);
    for (const BoxTile& tile : boxTiles) {
      commands.setViewport(
          gpu::rhi::Rect{tile.first[0] - rectOrigin[0], tile.first[1] - rectOrigin[1],
                         tile.end[0] - tile.first[0], tile.end[1] - tile.first[1]});
      box.texture = &tileStore_->pageTexture(tile.slot.page);
      box.inputTexelOrigin = {canvas::slotOriginX(tile.slot), canvas::slotOriginY(tile.slot)};
      box.inputBase = tile.pixel;
      box.inputLimit = {std::min(tile.pixel[0] + canvas::kTileSizePixels, width),
                        std::min(tile.pixel[1] + canvas::kTileSizePixels, height)};
      box.boxFactor = factor;
      box.encodeInput = compositor_->linearLight();
      drawSpatialPass(box, target->description().format);
    }
    commands.endPass();
    input = target;
    next = 1;
  } else if (level == outputLevel) {
    mapping = {level, 0};
    input = session.reducedLayer.get();
  } else {
    const int32_t ratio = 1 << (level - outputLevel);
    mapping = {level, (ratio - 1) * std::ldexp(1.0, outputLevel - 1)};
    gpu::rhi::Texture* target = image(0);
    if (target == nullptr) {
      return false;
    }
    box.texture = session.reducedLayer.get();
    box.inputLimit = {compositor_->levelWidth(outputLevel), compositor_->levelHeight(outputLevel)};
    box.boxFactor = ratio;
    commands.beginPass(gpu::rhi::RenderTarget::of(*target), gpu::rhi::LoadAction::Load);
    drawSpatialPass(box, target->description().format);
    commands.endPass();
    input = target;
    next = 1;
  }
  for (const adjust::SpatialPass& pass : plan.passes) {
    gpu::rhi::Texture* output = image(next);
    if (output == nullptr) {
      return false;
    }
    SpatialPassInputs inputs;
    inputs.texture = input;
    inputs.kind = static_cast<int32_t>(pass.kind);
    inputs.pass = &pass;
    inputs.outputOrigin = rectOrigin;
    const bool wholeLevel = input == session.reducedLayer.get();
    inputs.rectOrigin = wholeLevel ? std::array<int32_t, 2>{0, 0} : rectOrigin;
    inputs.rectSize = wholeLevel ? levelSize : rectSize;
    inputs.levelSize = levelSize;
    const bool canvasPixels = pass.kind == adjust::SpatialPass::Kind::Chromatic;
    inputs.center = {
        static_cast<float>(canvasPixels ? pass.center[0] : mapping.toTexel(pass.center[0])),
        static_cast<float>(canvasPixels ? pass.center[1] : mapping.toTexel(pass.center[1]))};
    inputs.mapping = mapping;
    inputs.exact = outputLevel == 0 && minimumLevel == 0;
    commands.beginPass(gpu::rhi::RenderTarget::of(*output), gpu::rhi::LoadAction::Load);
    drawSpatialPass(inputs, output->description().format);
    commands.endPass();
    input = output;
    next ^= 1;
  }
  result.valid = true;
  result.version = session.version;
  result.outputLevel = outputLevel;
  result.minimumLevel = minimumLevel;
  result.covered = wanted;
  result.plan = std::move(plan);
  result.mapping = mapping;
  const bool wholeLevel = input == session.reducedLayer.get();
  result.rectOrigin = wholeLevel ? std::array<int32_t, 2>{0, 0} : rectOrigin;
  result.rectSize = wholeLevel ? levelSize : rectSize;
  result.levelSize = levelSize;
  result.result = input;
  return true;
}

void EngineContext::drawSpatialPass(const SpatialPassInputs& inputs,
                                    gpu::rhi::Format targetFormat) {
  const bool ray = inputs.kind >= static_cast<int32_t>(adjust::SpatialPass::Kind::Line);
  const bool texels = inputs.kind >= static_cast<int32_t>(adjust::SpatialPass::Kind::Threshold);
  const gpu::rhi::Pipeline* pipeline = enginePipeline(texels ? EngineProgram::AdjustPassTexels
                                                      : ray  ? EngineProgram::AdjustPassRays
                                                             : EngineProgram::AdjustPass,
                                                      targetFormat);
  if (pipeline == nullptr) {
    return;
  }
  gpu::rhi::CommandContext& commands = device_->commands();
  commands.bindPipeline(*pipeline);
  commands.bindTexture(kAdjustPassInputBinding, inputs.texture,
                       ray ? linearSampler_.get() : nullptr);
  AdjustPassParameters parameters{};
  parameters.passKind = inputs.kind;
  parameters.outputOrigin = inputs.outputOrigin;
  parameters.rectOrigin = inputs.rectOrigin;
  parameters.rectSize = inputs.rectSize;
  parameters.levelSize = inputs.levelSize;
  parameters.inputTexelOrigin = inputs.inputTexelOrigin;
  parameters.inputBase = inputs.inputBase;
  parameters.inputLimit = inputs.inputLimit;
  parameters.boxFactor = inputs.boxFactor;
  parameters.encodeInput = inputs.encodeInput ? 1 : 0;
  if (inputs.pass != nullptr) {
    const adjust::SpatialPass& pass = *inputs.pass;
    for (size_t index = 0; index < pass.weights.size() && index < parameters.weights.size();
         ++index) {
      parameters.weights[index][0] = static_cast<float>(pass.weights[index]);
    }
    parameters.radius = std::max(static_cast<int32_t>(pass.weights.size()) - 1, 0);
    parameters.taps = pass.taps;
    parameters.tapStep = {static_cast<float>(pass.step[0]), static_cast<float>(pass.step[1])};
    parameters.center = inputs.center;
    parameters.spacing = static_cast<float>(pass.spacing);
    parameters.values = {static_cast<float>(pass.values[0]), static_cast<float>(pass.values[1]),
                         static_cast<float>(pass.values[2]), static_cast<float>(pass.values[3])};
    parameters.variant = pass.variant;
    parameters.mappingOffset = static_cast<float>(inputs.mapping.offset);
    parameters.mappingScale = static_cast<float>(inputs.mapping.scale());
    parameters.exactReads = inputs.exact ? 1 : 0;
  }
  commands.setUniforms(parameters);
  commands.draw(3);
}

bool EngineContext::adjustmentChanging() const {
  constexpr auto kSettleTime = std::chrono::milliseconds(150);
  return adjustmentSession_ && adjustmentSession_->shown && !adjustmentSession_->endRequested &&
         std::chrono::steady_clock::now() - adjustmentSession_->lastChange < kSettleTime;
}

bool EngineContext::finishAdjustment() {
  AdjustmentSession& session = *adjustmentSession_;
  if (session.liquifyField) {
    finishLiquifyStroke();
    uploadLiquifyField();
  }
  const std::vector<canvas::TileCoordinate> tiles = adjustmentOutputTiles();
  const bool spatial = adjust::isSpatial(session.parameters.filter);
  if (session.commit) {
    if (session.historyEntry != 0 && history_->isBackedUp()) {
      return false;
    }
    bool resident = true;
    auto require = [&](canvas::LayerId surface, canvas::TileCoordinate coordinate) {
      resident = tileStore_->ensureResident({surface, coordinate}) !=
                     canvas::LayerTileStore::Availability::Loading &&
                 resident;
    };
    for (const canvas::TileCoordinate coordinate : adjustmentTiles()) {
      require(session.layer, coordinate);
    }
    for (const canvas::TileCoordinate coordinate : tiles) {
      for (const auto surface : {std::optional(session.layer), session.selection, session.mask}) {
        if (surface) {
          require(*surface, coordinate);
        }
      }
    }
    if (!resident) {
      return false;
    }
    ATrace_beginSection("NanquimAdjustmentApply");
    if (spatial && !computeSpatial(session.spatialFull, 0)) {
      ATrace_endSection();
      return false;
    }
    if (!ensureOperationScratch()) {
      ATrace_endSection();
      return false;
    }
    for (const canvas::TileCoordinate coordinate : tiles) {
      const canvas::LayerTileKey key{session.layer, coordinate};
      if (session.selection && !tileStore_->slotForRead({*session.selection, coordinate})) {
        continue;
      }
      if (session.mask && !tileStore_->slotForRead({*session.mask, coordinate})) {
        continue;
      }
      clearTexture(*operationScratch_, {0, 0, 0, 0});
      renderAdjustmentTile(coordinate, 1, *operationScratch_);
      if (session.historyEntry != 0) {
        history_->captureBefore(session.historyEntry, key);
      }
      copyOperationScratchTo(key);
      if (session.historyEntry != 0) {
        history_->captureAfter(session.historyEntry, key);
      }
    }
    if (session.historyEntry != 0) {
      history_->finishEntry(session.historyEntry);
    }
    if (session.parameters.filter == adjust::Filter::Curves || spatial) {
      std::vector<canvas::LayerTileKey> written;
      for (const canvas::TileCoordinate coordinate : tiles) {
        written.push_back({session.layer, coordinate});
      }
      strokeRenderer_->checkForEmptyTiles(written);
    }
    waitForGpu();
    ATrace_endSection();
  }
  if (session.shown) {
    compositor_->setLayerOverride(std::nullopt);
  }
  for (const canvas::TileCoordinate coordinate : session.shownTiles) {
    markTileChanged({session.layer, coordinate});
  }
  for (const canvas::TileCoordinate coordinate : tiles) {
    markTileChanged({session.layer, coordinate});
  }
  adjustmentSession_.reset();
  frameRequested_ = true;
  runDeferredTasks();
  return true;
}

EngineContext::AdjustmentSession* EngineContext::liquifySession() {
  if (!adjustmentSession_ || adjustmentSession_->endRequested ||
      !adjustmentSession_->liquifyField) {
    return nullptr;
  }
  return &*adjustmentSession_;
}

void EngineContext::beginLiquifyStroke(adjust::LiquifyBrush brush) {
  post([this, brush] {
    AdjustmentSession* session = liquifySession();
    if (session == nullptr) {
      return;
    }
    finishLiquifyStroke();
    session->liquifyStroke = std::make_unique<adjust::LiquifyStroke>(*session->liquifyField, brush);
  });
}

void EngineContext::addLiquifySamples(std::vector<adjust::LiquifySample> samples) {
  post([this, samples = std::move(samples)] {
    AdjustmentSession* session = liquifySession();
    if (session == nullptr || !session->liquifyStroke) {
      return;
    }
    for (const adjust::LiquifySample& sample : samples) {
      session->liquifyStroke->add(sample);
    }
    liquifyFieldChanged();
  });
}

void EngineContext::endLiquifyStroke() {
  post([this] {
    AdjustmentSession* session = liquifySession();
    if (session == nullptr || !session->liquifyStroke) {
      return;
    }
    session->liquifyStroke->end();
    session->liquifyStrokeEnd = std::chrono::steady_clock::now();
    frameRequested_ = true;
  });
}

void EngineContext::cancelLiquifyStroke() {
  post([this] {
    AdjustmentSession* session = liquifySession();
    if (session == nullptr || !session->liquifyStroke) {
      return;
    }
    const adjust::LiquifyField::Change change = session->liquifyStroke->finish();
    session->liquifyStroke.reset();
    session->liquifyField->apply(change, true);
    liquifyFieldChanged();
  });
}

void EngineContext::finishLiquifyStroke() {
  AdjustmentSession& session = *adjustmentSession_;
  if (!session.liquifyStroke) {
    return;
  }
  session.liquifyStroke->end();
  session.liquifyStroke->finishMomentum();
  liquifyFieldChanged();
  adjust::LiquifyField::Change change = session.liquifyStroke->finish();
  session.liquifyStroke.reset();
  pushLiquifyStep(std::move(change));
}

void EngineContext::pushLiquifyStep(adjust::LiquifyField::Change change) {
  AdjustmentSession& session = *adjustmentSession_;
  session.liquifyUndo.push_back(std::move(change));
  session.liquifyRedo.clear();
}

void EngineContext::liquifyStep(bool undo) {
  post([this, undo] {
    AdjustmentSession* session = liquifySession();
    if (session == nullptr) {
      return;
    }
    finishLiquifyStroke();
    auto& from = undo ? session->liquifyUndo : session->liquifyRedo;
    auto& to = undo ? session->liquifyRedo : session->liquifyUndo;
    if (from.empty()) {
      return;
    }
    session->liquifyField->apply(from.back(), undo);
    to.push_back(std::move(from.back()));
    from.pop_back();
    liquifyFieldChanged();
  });
}

void EngineContext::resetLiquify() { scaleLiquify(0); }

void EngineContext::scaleLiquify(float factor) {
  post([this, factor] {
    AdjustmentSession* session = liquifySession();
    if (session == nullptr) {
      return;
    }
    finishLiquifyStroke();
    adjust::LiquifyField::Change change;
    session->liquifyField->beginRecording(&change);
    session->liquifyField->scale(std::clamp(static_cast<double>(factor), 0.0, 1.0));
    session->liquifyField->endRecording();
    pushLiquifyStep(std::move(change));
    liquifyFieldChanged();
  });
}

void EngineContext::liquifyFieldChanged() { frameRequested_ = true; }

void EngineContext::uploadLiquifyField() {
  AdjustmentSession& session = *adjustmentSession_;
  adjust::LiquifyField& field = *session.liquifyField;
  const adjust::FieldRegion region = field.takeChanged();
  if (region.isEmpty()) {
    return;
  }
  if (session.fieldTexture) {
    device_->uploadTextureRegion(
        *session.fieldTexture,
        gpu::rhi::Rect{region.first[0], region.first[1], region.end[0] - region.first[0],
                       region.end[1] - region.first[1]},
        field.data().data() +
            (static_cast<size_t>(region.first[1]) * field.width() + region.first[0]) * 2,
        field.width());
  }
  if (!session.shown) {
    return;
  }
  const int32_t spacing = adjust::kLiquifyFieldSpacing;
  const canvas::TileRange changed{
      std::max((region.first[0] - 1) * spacing, 0) / canvas::kTileSizePixels,
      std::max((region.first[1] - 1) * spacing, 0) / canvas::kTileSizePixels,
      region.end[0] * spacing / canvas::kTileSizePixels + 1,
      region.end[1] * spacing / canvas::kTileSizePixels + 1};
  std::vector<canvas::TileCoordinate> coordinates;
  changed.intersection(compositor_->canvasTiles()).forEach([&](canvas::TileCoordinate coordinate) {
    coordinates.push_back(coordinate);
    markTileChanged({session.layer, coordinate});
  });
  for (const canvas::TileCoordinate coordinate : coordinates) {
    if (std::find(session.shownTiles.begin(), session.shownTiles.end(), coordinate) ==
        session.shownTiles.end()) {
      session.shownTiles.push_back(coordinate);
    }
  }
  const std::array<int32_t, 4> pixels{std::max(region.first[0] - 1, 0) * spacing,
                                      std::max(region.first[1] - 1, 0) * spacing,
                                      (region.end[0] + 1) * spacing, (region.end[1] + 1) * spacing};
  std::array<int32_t, 4>& dirty = session.liquifyLevelDirty;
  if (dirty[2] <= dirty[0]) {
    dirty = pixels;
  } else {
    dirty = {std::min(dirty[0], pixels[0]), std::min(dirty[1], pixels[1]),
             std::max(dirty[2], pixels[2]), std::max(dirty[3], pixels[3])};
  }
  compositor_->invalidatePreviews(coordinates);
  frameRequested_ = true;
}

}  // namespace nanquim
