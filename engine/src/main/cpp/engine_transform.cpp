#include <algorithm>
#include <cmath>
#include <unordered_set>

#include "core/json_writer.h"
#include "core/log.h"
#include "engine_context.h"
#include "engine_programs.h"

namespace nanquim {

namespace {

constexpr int32_t kFloatingMargin = 2;

constexpr int64_t kCloneCompositeTileBudget = 8192;

}  // namespace

void EngineContext::beginTransform(canvas::LayerId layer, std::optional<canvas::LayerId> selection,
                                   int32_t requestId) {
  post([this, layer, selection, requestId] {
    runStrokeTask([this, layer, selection, requestId] {
      if (!document_) {
        return;
      }
      TransformSession& session = transformSession_.emplace();
      session.layer = layer;
      session.selection = selection;
      session.requestId = requestId;
      frameRequested_ = true;
    });
  });
}

void EngineContext::setTransformMesh(std::vector<canvas::MeshVertex> triangles,
                                     canvas::TransformFilter filter) {
  post([this, triangles = std::move(triangles), filter]() mutable {
    if (!transformSession_ || transformSession_->endRequested) {
      return;
    }
    TransformSession& session = *transformSession_;
    session.triangles = std::move(triangles);
    session.filter = filter;
    session.vertexBufferOutdated = true;
    if (!session.lifted) {
      return;
    }
    const canvas::TileRange before = session.shownTiles;
    session.shownTiles = transformMeshTiles();
    for (const canvas::TileRange& range : {before, session.shownTiles}) {
      range.forEach([this, &session](canvas::TileCoordinate coordinate) {
        markTileChanged({session.layer, coordinate});
      });
    }
    frameRequested_ = true;
  });
}

void EngineContext::endTransform(bool commit, int64_t historyEntry) {
  post([this, commit, historyEntry] {
    if (!transformSession_ || transformSession_->endRequested) {
      return;
    }
    transformSession_->endRequested = true;
    transformSession_->commit = commit;
    transformSession_->historyEntry = historyEntry;
    frameRequested_ = true;
  });
}

canvas::TileRange EngineContext::transformMeshTiles() const {
  const auto& triangles = transformSession_->triangles;
  if (triangles.empty()) {
    return {};
  }
  const auto bounds = canvas::meshBounds(triangles);
  return canvas::tileRangeCoveringRect(static_cast<int32_t>(std::floor(bounds[0])),
                                       static_cast<int32_t>(std::floor(bounds[1])),
                                       static_cast<int32_t>(std::ceil(bounds[2])) + 1,
                                       static_cast<int32_t>(std::ceil(bounds[3])) + 1)
      .intersection(compositor_->canvasTiles());
}

void EngineContext::processTransform() {
  if (!transformSession_ || !document_) {
    return;
  }
  TransformSession& session = *transformSession_;
  if (!session.lifted) {
    if (!liftTransformContent()) {
      return;
    }
  }
  if (session.endRequested) {
    finishTransform();
  }
}

bool EngineContext::liftTransformContent() {
  TransformSession& session = *transformSession_;
  const std::vector<canvas::TileCoordinate> tiles = tileStore_->tilesOf(session.layer);
  bool resident = true;
  for (const canvas::TileCoordinate coordinate : tiles) {
    resident = tileStore_->ensureResident({session.layer, coordinate}) !=
                   canvas::LayerTileStore::Availability::Loading &&
               resident;
    if (session.selection) {
      resident = tileStore_->ensureResident({*session.selection, coordinate}) !=
                     canvas::LayerTileStore::Availability::Loading &&
                 resident;
    }
  }
  if (!resident) {
    return false;
  }
  const canvas::TileRange canvasTiles = compositor_->canvasTiles();
  canvas::TileRange covered{canvasTiles.endColumn, canvasTiles.endRow, 0, 0};
  for (const canvas::TileCoordinate coordinate : tiles) {
    if (!canvasTiles.contains(coordinate)) {
      continue;
    }
    covered.firstColumn = std::min(covered.firstColumn, coordinate.column);
    covered.firstRow = std::min(covered.firstRow, coordinate.row);
    covered.endColumn = std::max(covered.endColumn, coordinate.column + 1);
    covered.endRow = std::max(covered.endRow, coordinate.row + 1);
  }
  canvas::ContentAnalysis analysis;
  if (!covered.isEmpty()) {
    const int32_t left = covered.firstColumn * canvas::kTileSizePixels;
    const int32_t top = covered.firstRow * canvas::kTileSizePixels;
    const int32_t width =
        std::min(covered.endColumn * canvas::kTileSizePixels, document_->widthPixels) - left;
    const int32_t height =
        std::min(covered.endRow * canvas::kTileSizePixels, document_->heightPixels) - top;
    session.floatingOrigin = {left - kFloatingMargin, top - kFloatingMargin};
    session.floatingSize = {width + 2 * kFloatingMargin, height + 2 * kFloatingMargin};
    session.floating = createSurfaceTexture(session.floatingSize[0], session.floatingSize[1]);
    const gpu::rhi::Pipeline* pipeline =
        enginePipeline(EngineProgram::MaskedCopy, documentFormat());
    if (!session.floating || pipeline == nullptr) {
      session.floating.reset();
    }
    std::vector<uint8_t> pixels;
    if (session.floating) {
      gpu::rhi::CommandContext& commands = device_->commands();
      commands.beginPass(gpu::rhi::RenderTarget::of(*session.floating),
                         gpu::rhi::LoadAction::Clear);
      commands.bindPipeline(*pipeline);
      for (const canvas::TileCoordinate coordinate : tiles) {
        if (!canvasTiles.contains(coordinate)) {
          continue;
        }
        const auto source = tileStore_->slotForRead({session.layer, coordinate});
        std::optional<canvas::AtlasSlot> selected;
        if (session.selection) {
          selected = tileStore_->slotForRead({*session.selection, coordinate});
          if (!selected) {
            continue;
          }
        }
        if (!source) {
          continue;
        }
        const int32_t x = coordinate.column * canvas::kTileSizePixels - session.floatingOrigin[0];
        const int32_t y = coordinate.row * canvas::kTileSizePixels - session.floatingOrigin[1];
        commands.setViewport(
            gpu::rhi::Rect{x, y, canvas::kTileSizePixels, canvas::kTileSizePixels});
        commands.setScissor(gpu::rhi::Rect{
            x, y,
            std::min(canvas::kTileSizePixels,
                     document_->widthPixels - coordinate.column * canvas::kTileSizePixels),
            std::min(canvas::kTileSizePixels,
                     document_->heightPixels - coordinate.row * canvas::kTileSizePixels)});
        commands.bindTexture(kMaskedCopySourceBinding, &tileStore_->pageTexture(source->page));
        commands.bindTexture(kMaskedCopySelectionBinding,
                             selected ? &tileStore_->pageTexture(selected->page) : nullptr);
        commands.setUniforms(
            MaskedCopyParameters{{canvas::slotOriginX(*source), canvas::slotOriginY(*source)},
                                 {x, y},
                                 {selected ? canvas::slotOriginX(*selected) : 0,
                                  selected ? canvas::slotOriginY(*selected) : 0},
                                 selected ? 1 : 0,
                                 0});
        commands.draw(3);
      }
      commands.endPass();
      pixels.resize(static_cast<size_t>(session.floatingSize[0]) * session.floatingSize[1] * 4);
      readRegionRgba8(*session.floating,
                      gpu::rhi::Rect{0, 0, session.floatingSize[0], session.floatingSize[1]},
                      pixels.data());
    }
    if (!pixels.empty()) {
      analysis =
          canvas::analyzeContent(pixels.data(), session.floatingSize[0], session.floatingSize[1],
                                 session.floatingOrigin[0], session.floatingOrigin[1]);
    }
  }
  session.lifted = true;
  if (session.triangles.empty() && analysis.bounds[2] > 0) {
    const auto x0 = static_cast<float>(session.floatingOrigin[0]);
    const auto y0 = static_cast<float>(session.floatingOrigin[1]);
    const auto x1 = x0 + static_cast<float>(session.floatingSize[0]);
    const auto y1 = y0 + static_cast<float>(session.floatingSize[1]);
    session.triangles = {{x0, y0, 1, x0, y0}, {x1, y0, 1, x1, y0}, {x1, y1, 1, x1, y1},
                         {x0, y0, 1, x0, y0}, {x1, y1, 1, x1, y1}, {x0, y1, 1, x0, y1}};
  }
  session.shownTiles = transformMeshTiles();
  compositor_->setActiveLayer(session.layer);
  compositor_->setLayerOverride(
      [this](canvas::TileCoordinate coordinate, int32_t /*sampleStride*/,
             const gpu::rhi::Texture& target) { renderTransformTile(coordinate, target); });
  for (const canvas::TileCoordinate coordinate : tiles) {
    markTileChanged({session.layer, coordinate});
  }
  session.shownTiles.forEach([this, &session](canvas::TileCoordinate coordinate) {
    markTileChanged({session.layer, coordinate});
  });
  core::JsonWriter writer;
  writer.beginObject();
  writer.key("type");
  writer.stringValue("transformReady");
  writer.key("requestId");
  writer.integerValue(session.requestId);
  writer.key("bounds");
  writer.beginArray();
  for (const int32_t value : analysis.bounds) {
    writer.integerValue(value);
  }
  writer.endArray();
  writer.key("centroid");
  writer.beginArray();
  writer.numberValue(analysis.centroidX);
  writer.numberValue(analysis.centroidY);
  writer.endArray();
  writer.key("principalAngle");
  writer.numberValue(analysis.principalAngle);
  writer.endObject();
  callbacks_->onEvent(writer.text());
  frameRequested_ = true;
  return true;
}

void EngineContext::renderTransformTile(canvas::TileCoordinate coordinate,
                                        const gpu::rhi::Texture& target) {
  TransformSession& session = *transformSession_;
  const canvas::LayerTileKey key{session.layer, coordinate};
  const bool mesh =
      !session.triangles.empty() && session.floating && session.shownTiles.contains(coordinate);
  if (mesh && session.vertexBufferOutdated) {
    const size_t bytes = session.triangles.size() * sizeof(canvas::MeshVertex);
    if (!session.vertexBuffer) {
      session.vertexBuffer = device_->createBuffer(bytes, gpu::rhi::kBufferVertex);
    }
    if (session.vertexBuffer) {
      device_->uploadBuffer(*session.vertexBuffer, session.triangles.data(), bytes);
      session.vertexBufferOutdated = false;
    }
  }
  const gpu::rhi::Format format = target.description().format;
  gpu::rhi::CommandContext& commands = device_->commands();
  commands.beginPass(gpu::rhi::RenderTarget::of(target), gpu::rhi::LoadAction::Load);
  if (session.selection) {
    const auto source = tileStore_->slotForRead(key);
    const gpu::rhi::Pipeline* pipeline = enginePipeline(EngineProgram::MaskedCopy, format);
    if (source && pipeline != nullptr) {
      const auto selected = tileStore_->slotForRead({*session.selection, coordinate});
      commands.bindPipeline(*pipeline);
      commands.bindTexture(kMaskedCopySourceBinding, &tileStore_->pageTexture(source->page));
      commands.bindTexture(kMaskedCopySelectionBinding,
                           selected ? &tileStore_->pageTexture(selected->page) : nullptr);
      commands.setUniforms(
          MaskedCopyParameters{{canvas::slotOriginX(*source), canvas::slotOriginY(*source)},
                               {0, 0},
                               {selected ? canvas::slotOriginX(*selected) : 0,
                                selected ? canvas::slotOriginY(*selected) : 0},
                               selected ? 2 : 0,
                               0});
      commands.draw(3);
    }
  }
  if (mesh && !session.vertexBufferOutdated) {
    drawMesh(*session.vertexBuffer, static_cast<uint32_t>(session.triangles.size()),
             *session.floating, session.floatingOrigin, session.floatingSize, session.filter,
             coordinate, format, true);
  }
  commands.endPass();
}

void EngineContext::prepareCloneSource(const StrokeStyle::Clone& clone) {
  snapshotSource(clone.composite, clone.layer);
  strokeRenderer_->setCloneSource(canvas::StrokeRenderer::CloneSource{
      cloneSnapshotTexture(), clone.offset, cloneSnapshotSize_});
}

void EngineContext::snapshotSource(bool composite, canvas::LayerId layer) {
  const int32_t width = document_->widthPixels;
  const int32_t height = document_->heightPixels;
  if (!cloneSnapshot_ || cloneSnapshotSize_ != std::array<int32_t, 2>{width, height}) {
    cloneSnapshot_ = createSurfaceTexture(width, height);
    cloneSnapshotSize_ = {width, height};
  }
  if (!cloneSnapshot_) {
    return;
  }
  gpu::rhi::CommandContext& commands = device_->commands();
  if (composite) {
    compositor_->update(compositor_->canvasTiles(), 0, 0,
                        std::chrono::steady_clock::now() + std::chrono::hours(1),
                        kCloneCompositeTileBudget);
    commands.copyTexture(compositor_->levelTexture(0), gpu::rhi::Rect{0, 0, width, height},
                         *cloneSnapshot_, 0, 0);
    return;
  }
  std::vector<std::pair<canvas::TileCoordinate, canvas::AtlasSlot>> slots;
  for (const canvas::TileCoordinate coordinate : tileStore_->tilesOf(layer)) {
    tileStore_->ensureResident({layer, coordinate});
    const auto slot = tileStore_->slotForRead({layer, coordinate});
    if (!slot || !compositor_->canvasTiles().contains(coordinate)) {
      continue;
    }
    slots.emplace_back(coordinate, *slot);
  }
  commands.beginPass(gpu::rhi::RenderTarget::of(*cloneSnapshot_), gpu::rhi::LoadAction::Clear);
  drawMaskedCopies(slots, cloneSnapshot_->description().format);
  commands.endPass();
}

bool EngineContext::snapshotForRemap(DocumentOperation& operation) {
  const std::vector<canvas::TileCoordinate> tiles = tileStore_->tilesOf(operation.surface);
  bool resident = true;
  for (const canvas::TileCoordinate coordinate : tiles) {
    resident = tileStore_->ensureResident({operation.surface, coordinate}) !=
                   canvas::LayerTileStore::Availability::Loading &&
               resident;
  }
  if (!resident) {
    return false;
  }
  const int32_t width = document_->widthPixels;
  const int32_t height = document_->heightPixels;
  operation.snapshot = createSurfaceTexture(width, height);
  if (!operation.snapshot) {
    return false;
  }
  std::vector<std::pair<canvas::TileCoordinate, canvas::AtlasSlot>> slots;
  for (const canvas::TileCoordinate coordinate : tiles) {
    const auto slot = tileStore_->slotForRead({operation.surface, coordinate});
    if (slot && compositor_->canvasTiles().contains(coordinate)) {
      slots.emplace_back(coordinate, *slot);
    }
  }
  const float base = operation.surfaceKind == 1 ? 1.0F : 0.0F;
  gpu::rhi::CommandContext& commands = device_->commands();
  commands.beginPass(gpu::rhi::RenderTarget::of(*operation.snapshot), gpu::rhi::LoadAction::Clear,
                     {base, base, base, operation.surfaceKind == 0 ? 0.0F : 1.0F});
  drawMaskedCopies(slots, operation.snapshot->description().format);
  commands.endPass();
  const size_t bytes = operation.triangles.size() * sizeof(canvas::MeshVertex);
  if (bytes > 0) {
    operation.meshBuffer = device_->createBuffer(bytes, gpu::rhi::kBufferVertex);
    if (!operation.meshBuffer) {
      operation.snapshot.reset();
      return false;
    }
    device_->uploadBuffer(*operation.meshBuffer, operation.triangles.data(), bytes);
  }
  return true;
}

void EngineContext::drawMaskedCopies(
    const std::vector<std::pair<canvas::TileCoordinate, canvas::AtlasSlot>>& slots,
    gpu::rhi::Format targetFormat) {
  const gpu::rhi::Pipeline* pipeline = enginePipeline(EngineProgram::MaskedCopy, targetFormat);
  if (pipeline == nullptr) {
    return;
  }
  gpu::rhi::CommandContext& commands = device_->commands();
  commands.bindPipeline(*pipeline);
  commands.bindTexture(kMaskedCopySelectionBinding, nullptr);
  for (const auto& [coordinate, slot] : slots) {
    const int32_t x = coordinate.column * canvas::kTileSizePixels;
    const int32_t y = coordinate.row * canvas::kTileSizePixels;
    commands.setViewport(gpu::rhi::Rect{x, y, canvas::kTileSizePixels, canvas::kTileSizePixels});
    commands.bindTexture(kMaskedCopySourceBinding, &tileStore_->pageTexture(slot.page));
    commands.setUniforms(MaskedCopyParameters{
        {canvas::slotOriginX(slot), canvas::slotOriginY(slot)}, {x, y}, {0, 0}, 0, 0});
    commands.draw(3);
  }
}

bool EngineContext::operateOnRemapTile(DocumentOperation& operation,
                                       canvas::TileCoordinate coordinate) {
  const canvas::LayerTileKey key{operation.surface, coordinate};
  if (tileStore_->ensureResident(key) == canvas::LayerTileStore::Availability::Loading) {
    return false;
  }
  if (operation.historyEntry != 0) {
    history_->captureBefore(operation.historyEntry, key);
  }
  const canvas::TileRange newTiles =
      canvas::tileRangeForCanvas(operation.newSize[0], operation.newSize[1]);
  if (!newTiles.contains(coordinate)) {
    tileStore_->removeTile(key);
  } else {
    if (!ensureOperationScratch()) {
      return false;
    }
    const float base = operation.surfaceKind == 1 ? 1.0F : 0.0F;
    gpu::rhi::CommandContext& commands = device_->commands();
    commands.beginPass(gpu::rhi::RenderTarget::of(*operationScratch_), gpu::rhi::LoadAction::Clear,
                       {base, base, base, operation.surfaceKind == 0 ? 0.0F : 1.0F});
    if (operation.meshBuffer) {
      drawMesh(*operation.meshBuffer, static_cast<uint32_t>(operation.triangles.size()),
               *operation.snapshot, {0, 0}, {document_->widthPixels, document_->heightPixels},
               operation.filter, coordinate, operationScratch_->description().format, false);
    }
    commands.endPass();
    copyOperationScratchTo(key);
  }
  if (operation.historyEntry != 0) {
    history_->captureAfter(operation.historyEntry, key);
  }
  return true;
}

void EngineContext::drawMesh(const gpu::rhi::Buffer& vertices, uint32_t vertexCount,
                             const gpu::rhi::Texture& texture, std::array<int32_t, 2> textureOrigin,
                             std::array<int32_t, 2> textureSize, canvas::TransformFilter filter,
                             canvas::TileCoordinate coordinate, gpu::rhi::Format targetFormat,
                             bool blend) {
  const gpu::rhi::Pipeline* pipeline =
      enginePipeline(EngineProgram::TransformMesh, targetFormat, blend);
  if (pipeline == nullptr) {
    return;
  }
  gpu::rhi::CommandContext& commands = device_->commands();
  commands.bindPipeline(*pipeline);
  commands.bindVertexBuffer(vertices, 0);
  commands.bindTexture(kTransformFloatingBinding, &texture, linearSampler_.get());
  commands.setUniforms(TransformMeshParameters{
      {static_cast<float>(coordinate.column * canvas::kTileSizePixels),
       static_cast<float>(coordinate.row * canvas::kTileSizePixels)},
      {static_cast<float>(textureOrigin[0]), static_cast<float>(textureOrigin[1])},
      {static_cast<float>(textureSize[0]), static_cast<float>(textureSize[1])},
      static_cast<int32_t>(filter),
      0});
  commands.draw(vertexCount);
}

bool EngineContext::finishTransform() {
  TransformSession& session = *transformSession_;
  std::vector<canvas::TileCoordinate> positions;
  const canvas::TileRange canvasTiles = compositor_->canvasTiles();
  std::unordered_set<canvas::TileCoordinate, canvas::TileCoordinateHash> unique;
  for (const canvas::TileCoordinate coordinate : tileStore_->tilesOf(session.layer)) {
    if (canvasTiles.contains(coordinate) && unique.insert(coordinate).second) {
      positions.push_back(coordinate);
    }
  }
  session.shownTiles.forEach([&](canvas::TileCoordinate coordinate) {
    if (unique.insert(coordinate).second) {
      positions.push_back(coordinate);
    }
  });
  if (session.commit) {
    if (session.historyEntry != 0 && history_->isBackedUp()) {
      return false;
    }
    bool resident = true;
    for (const canvas::TileCoordinate coordinate : positions) {
      resident = tileStore_->ensureResident({session.layer, coordinate}) !=
                     canvas::LayerTileStore::Availability::Loading &&
                 resident;
      if (session.selection) {
        resident = tileStore_->ensureResident({*session.selection, coordinate}) !=
                       canvas::LayerTileStore::Availability::Loading &&
                   resident;
      }
    }
    if (!resident) {
      return false;
    }
    if (!ensureOperationScratch()) {
      return false;
    }
    for (const canvas::TileCoordinate coordinate : positions) {
      clearTexture(*operationScratch_, {0, 0, 0, 0});
      renderTransformTile(coordinate, *operationScratch_);
      const canvas::LayerTileKey key{session.layer, coordinate};
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
    std::vector<canvas::LayerTileKey> written;
    written.reserve(positions.size());
    for (const canvas::TileCoordinate coordinate : positions) {
      written.push_back({session.layer, coordinate});
    }
    strokeRenderer_->checkForEmptyTiles(written);
  }
  compositor_->setLayerOverride(std::nullopt);
  for (const canvas::TileCoordinate coordinate : positions) {
    markTileChanged({session.layer, coordinate});
  }
  transformSession_.reset();
  frameRequested_ = true;
  runDeferredTasks();
  return true;
}

}  // namespace nanquim
