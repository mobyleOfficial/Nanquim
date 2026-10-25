#include <algorithm>
#include <cmath>
#include <map>

#include "canvas/color_conversion.h"
#include "canvas/flood_fill.h"
#include "core/log.h"
#include "engine_context.h"
#include "engine_programs.h"

namespace nanquim {

namespace {

constexpr canvas::LayerId kFillOriginalSurface = 0xFFFFFFE0U;

constexpr int32_t kSurfaceReadBandRows = 4;

}  // namespace

void EngineContext::beginFill(FillRequest request) {
  const uint64_t id = ++fillCounter_;
  post([this, request = std::move(request), id] {
    runStrokeTask([this, request, id] {
      if (!document_) {
        pendingFillControls_.erase(id);
        return;
      }
      FillSession& session = fillSession_.emplace();
      session.request = request;
      session.id = id;
      session.started = std::chrono::steady_clock::now();
      session.thresholdDirty = true;
      const auto pending = pendingFillControls_.find(id);
      if (pending != pendingFillControls_.end()) {
        if (pending->second.threshold) {
          session.request.threshold = *pending->second.threshold;
        }
        if (pending->second.end) {
          session.endRequested = true;
          session.commit = pending->second.end->first;
          session.historyEntry = pending->second.end->second;
        }
        pendingFillControls_.erase(pending);
      }
      frameRequested_ = true;
    });
  });
}

void EngineContext::setFillThreshold(double threshold) {
  const uint64_t id = fillCounter_.load();
  threshold = std::clamp(threshold, 0.0, 1.0);
  post([this, threshold, id] {
    if (!fillSession_ || fillSession_->id != id) {
      pendingFillControls_[id].threshold = threshold;
      return;
    }
    if (fillSession_->endRequested) {
      return;
    }
    fillSession_->request.threshold = threshold;
    fillSession_->thresholdDirty = true;
    frameRequested_ = true;
  });
}

void EngineContext::endFill(bool commit, int64_t historyEntry) {
  const uint64_t id = fillCounter_.load();
  post([this, commit, historyEntry, id] {
    if (!fillSession_ || fillSession_->id != id) {
      pendingFillControls_[id].end = std::make_pair(commit, historyEntry);
      return;
    }
    if (fillSession_->endRequested) {
      return;
    }
    fillSession_->endRequested = true;
    fillSession_->commit = commit;
    fillSession_->historyEntry = historyEntry;
    frameRequested_ = true;
  });
}

bool EngineContext::readSurfacePixels(canvas::LayerId surface, std::vector<uint8_t>& pixels) {
  const int32_t width = document_->widthPixels;
  const int32_t height = document_->heightPixels;
  const std::vector<canvas::TileCoordinate> tiles = tileStore_->tilesOf(surface);
  bool resident = true;
  for (const canvas::TileCoordinate coordinate : tiles) {
    resident = tileStore_->ensureResident({surface, coordinate}) !=
                   canvas::LayerTileStore::Availability::Loading &&
               resident;
  }
  if (!resident) {
    return false;
  }
  pixels.assign(static_cast<size_t>(width) * height * 4, 0);
  const canvas::TileRange canvasTiles = compositor_->canvasTiles();
  std::map<int32_t, std::vector<std::pair<canvas::TileCoordinate, canvas::AtlasSlot>>> byBand;
  for (const canvas::TileCoordinate coordinate : tiles) {
    if (!canvasTiles.contains(coordinate)) {
      continue;
    }
    if (const auto slot = tileStore_->slotForRead({surface, coordinate})) {
      byBand[coordinate.row / kSurfaceReadBandRows].emplace_back(coordinate, *slot);
    }
  }
  if (byBand.empty()) {
    return true;
  }
  const int32_t bandPixels = kSurfaceReadBandRows * canvas::kTileSizePixels;
  gpu::rhi::TextureDescription description;
  description.width = width;
  description.height = std::min(bandPixels, height);
  description.usage = gpu::rhi::kUsageRenderTarget | gpu::rhi::kUsageReadback;
  const std::unique_ptr<gpu::rhi::Texture> band = device_->createTexture(description);
  if (!band) {
    return false;
  }
  gpu::rhi::CommandContext& commands = device_->commands();
  ThumbnailTileParameters parameters{};
  parameters.sampleStride = 1;
  parameters.encode = compositor_->linearLight() ? 1 : 0;
  for (const auto& [bandIndex, slots] : byBand) {
    const int32_t bandTop = bandIndex * bandPixels;
    const int32_t rows = std::min(bandPixels, height - bandTop);
    commands.beginPass(gpu::rhi::RenderTarget::of(*band), gpu::rhi::LoadAction::DontCare);
    commands.clear({0.0F, 0.0F, 0.0F, 0.0F}, gpu::rhi::Rect{0, 0, width, rows});
    commands.bindPipeline(*thumbnailPipeline_);
    for (const auto& [coordinate, slot] : slots) {
      const int32_t originX = coordinate.column * canvas::kTileSizePixels;
      const int32_t originY = coordinate.row * canvas::kTileSizePixels - bandTop;
      commands.setScissor(gpu::rhi::Rect{originX, originY,
                                         std::min(canvas::kTileSizePixels, width - originX),
                                         std::min(canvas::kTileSizePixels, rows - originY)});
      commands.bindTexture(kThumbnailSourceBinding, &tileStore_->pageTexture(slot.page));
      parameters.slotOrigin = {canvas::slotOriginX(slot), canvas::slotOriginY(slot)};
      parameters.targetOrigin = {originX, originY};
      commands.setUniforms(parameters);
      commands.draw(3);
    }
    commands.endPass();
    commands.readTexture(*band, gpu::rhi::Rect{0, 0, width, rows},
                         pixels.data() + static_cast<size_t>(bandTop) * width * 4);
  }
  return true;
}

void EngineContext::processFill() {
  if (!fillSession_ || !document_) {
    return;
  }
  FillSession& session = *fillSession_;
  const FillRequest& request = session.request;
  if (!request.whole && !session.sourcePixels) {
    auto pixels = std::make_shared<std::vector<uint8_t>>();
    const auto readStart = std::chrono::steady_clock::now();
    if (!readSurfacePixels(request.source, *pixels)) {
      return;
    }
    session.readMilliseconds =
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - readStart)
            .count();
    session.sourcePixels = std::move(pixels);
  }
  if (session.thresholdDirty && !session.computing) {
    session.thresholdDirty = false;
    const uint64_t generation = ++session.generation;
    const int32_t width = document_->widthPixels;
    const int32_t height = document_->heightPixels;
    if (request.whole) {
      session.result = std::make_shared<const canvas::TileCoverageMap>(canvas::coverageTiles(
          std::vector<uint8_t>(static_cast<size_t>(width) * height, 255), width, height));
    } else {
      session.computing = true;
      canvas::FloodFillOptions options;
      options.threshold = request.threshold;
      options.expand = request.expand;
      const auto seedX = static_cast<int32_t>(std::floor(request.seedX));
      const auto seedY = static_cast<int32_t>(std::floor(request.seedY));
      ioPool_.post(
          [this, source = session.sourcePixels, width, height, seedX, seedY, options, generation] {
            const auto regionStart = std::chrono::steady_clock::now();
            auto tiles = std::make_shared<const canvas::TileCoverageMap>(canvas::coverageTiles(
                canvas::floodFillCoverage(source->data(), width, height, seedX, seedY, options),
                width, height));
            const double regionMilliseconds = std::chrono::duration<double, std::milli>(
                                                  std::chrono::steady_clock::now() - regionStart)
                                                  .count();
            post([this, tiles = std::move(tiles), generation, regionMilliseconds] {
              if (!fillSession_) {
                return;
              }
              fillSession_->computing = false;
              fillSession_->regionMilliseconds = regionMilliseconds;
              if (generation == fillSession_->generation) {
                fillSession_->result = tiles;
              }
              frameRequested_ = true;
            });
          });
    }
  }
  if (session.result && !session.endRequested) {
    if (applyFillPreview(*session.result)) {
      waitForGpu();
      NANQUIM_LOG_INFO(
          "Fill %dx%d: read %.1f ms, region %.1f ms, %zu tiles, shown %.1f ms after the start",
          document_->widthPixels, document_->heightPixels, session.readMilliseconds,
          session.regionMilliseconds, session.result->size(),
          std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() -
                                                    session.started)
              .count());
      session.result.reset();
    }
    return;
  }
  if (session.endRequested && !session.computing && !session.thresholdDirty) {
    if (session.result) {
      session.applied = session.result;
      session.result.reset();
    }
    finishFill(session.commit);
  }
}

bool EngineContext::fillTilesResident(const std::vector<canvas::TileCoordinate>& positions) {
  const FillRequest& request = fillSession_->request;
  bool resident = true;
  for (const canvas::TileCoordinate coordinate : positions) {
    for (const canvas::LayerId surface : {request.surface, kFillOriginalSurface}) {
      resident = tileStore_->ensureResident({surface, coordinate}) !=
                     canvas::LayerTileStore::Availability::Loading &&
                 resident;
    }
    if (request.selection) {
      resident = tileStore_->ensureResident({*request.selection, coordinate}) !=
                     canvas::LayerTileStore::Availability::Loading &&
                 resident;
    }
  }
  return resident;
}

bool EngineContext::applyFillPreview(const canvas::TileCoverageMap& coverage) {
  FillSession& session = *fillSession_;
  std::vector<canvas::TileCoordinate> positions;
  if (session.applied) {
    for (const auto& [coordinate, tile] : *session.applied) {
      positions.push_back(coordinate);
    }
  }
  for (const auto& [coordinate, tile] : coverage) {
    if (!session.applied || session.applied->count(coordinate) == 0) {
      positions.push_back(coordinate);
    }
  }
  if (!fillTilesResident(positions)) {
    return false;
  }
  for (const canvas::TileCoordinate coordinate : positions) {
    restoreFillOriginal(coordinate);
    const auto found = coverage.find(coordinate);
    if (found != coverage.end()) {
      applyFillTile(coordinate, found->second, 0);
    }
  }
  session.applied = std::make_shared<const canvas::TileCoverageMap>(coverage);
  return true;
}

void EngineContext::restoreFillOriginal(canvas::TileCoordinate coordinate) {
  FillSession& session = *fillSession_;
  const canvas::LayerTileKey target{session.request.surface, coordinate};
  const auto original = session.originals.find(coordinate);
  if (original == session.originals.end()) {
    const bool had = tileStore_->hasTile(target);
    session.originals.emplace(coordinate, had);
    if (had) {
      copySurfaceTile(target, {kFillOriginalSurface, coordinate});
    }
    return;
  }
  if (original->second) {
    copySurfaceTile({kFillOriginalSurface, coordinate}, target);
  } else if (tileStore_->hasTile(target)) {
    tileStore_->removeTile(target);
  }
  markTileChanged(target);
}

void EngineContext::copySurfaceTile(const canvas::LayerTileKey& from,
                                    const canvas::LayerTileKey& to) {
  copyTileThroughScratch(from, to);
}

void EngineContext::applyFillTile(canvas::TileCoordinate coordinate,
                                  const canvas::TileCoverage& coverage, int64_t historyEntry) {
  const FillRequest& request = fillSession_->request;
  const canvas::LayerTileKey key{request.surface, coordinate};
  std::optional<canvas::AtlasSlot> selectionSlot;
  if (request.selection && request.target != FillRequest::Target::Selection) {
    selectionSlot = tileStore_->slotForRead({*request.selection, coordinate});
    if (!selectionSlot) {
      return;
    }
  }
  if (historyEntry != 0) {
    history_->captureBefore(historyEntry, key);
  }
  ensureOperationScratch();
  if (!coverageTexture_) {
    gpu::rhi::TextureDescription description;
    description.format = gpu::rhi::Format::R8;
    description.width = canvas::kTileSizePixels;
    description.height = canvas::kTileSizePixels;
    description.usage = gpu::rhi::kUsageSampled | gpu::rhi::kUsageUpload;
    coverageTexture_ = device_->createTexture(description);
  }
  if (!operationScratch_ || !coverageTexture_) {
    return;
  }
  device_->uploadTexture(*coverageTexture_, coverage.data());
  const auto slot = tileStore_->slotForRead(key);
  const gpu::rhi::Pipeline* pipeline = enginePipeline(
      request.target == FillRequest::Target::Selection ? EngineProgram::SelectionCombine
                                                       : EngineProgram::CoverageFill,
      operationScratch_->description().format);
  if (pipeline == nullptr) {
    return;
  }
  gpu::rhi::CommandContext& commands = device_->commands();
  commands.beginPass(gpu::rhi::RenderTarget::of(*operationScratch_),
                     gpu::rhi::LoadAction::DontCare);
  commands.bindPipeline(*pipeline);
  commands.bindTexture(kTilePageBinding, slot ? &tileStore_->pageTexture(slot->page) : nullptr);
  commands.bindTexture(kCoverageBinding, coverageTexture_.get());
  const std::array<int32_t, 2> slotOrigin{slot ? canvas::slotOriginX(*slot) : 0,
                                          slot ? canvas::slotOriginY(*slot) : 0};
  if (request.target == FillRequest::Target::Selection) {
    commands.setUniforms(SelectionCombineParameters{slotOrigin, slot ? 1 : 0,
                                                    static_cast<int32_t>(request.combine)});
  } else {
    commands.bindTexture(kCoverageSelectionBinding,
                         selectionSlot ? &tileStore_->pageTexture(selectionSlot->page) : nullptr);
    const bool maskTarget = request.target == FillRequest::Target::Mask;
    const bool linear = compositor_->linearLight();
    CoverageFillParameters parameters{};
    parameters.slotOrigin = slotOrigin;
    parameters.hasTile = slot ? 1 : 0;
    parameters.hasSelection = selectionSlot ? 1 : 0;
    parameters.selectionSlotOrigin = {selectionSlot ? canvas::slotOriginX(*selectionSlot) : 0,
                                      selectionSlot ? canvas::slotOriginY(*selectionSlot) : 0};
    parameters.color = {canvas::documentChannel(request.color[0], linear && !maskTarget),
                        canvas::documentChannel(request.color[1], linear && !maskTarget),
                        canvas::documentChannel(request.color[2], linear && !maskTarget),
                        request.color[3]};
    parameters.maskTarget = maskTarget ? 1 : 0;
    parameters.operation = static_cast<int32_t>(request.operation);
    parameters.linearDocument = linear ? 1 : 0;
    commands.setUniforms(parameters);
  }
  commands.draw(3);
  commands.endPass();
  copyOperationScratchTo(key);
  if (historyEntry != 0) {
    history_->captureAfter(historyEntry, key);
  }
  markTileChanged(key);
}

bool EngineContext::finishFill(bool commit) {
  FillSession& session = *fillSession_;
  std::vector<canvas::TileCoordinate> touched;
  touched.reserve(session.originals.size());
  for (const auto& [coordinate, had] : session.originals) {
    touched.push_back(coordinate);
  }
  if (commit && session.applied) {
    for (const auto& [coordinate, tile] : *session.applied) {
      if (session.originals.count(coordinate) == 0) {
        touched.push_back(coordinate);
      }
    }
  }
  if (!fillTilesResident(touched)) {
    return false;
  }
  for (const canvas::TileCoordinate coordinate : touched) {
    if (session.originals.count(coordinate) != 0) {
      restoreFillOriginal(coordinate);
    }
  }
  if (commit && session.applied) {
    for (const auto& [coordinate, tile] : *session.applied) {
      applyFillTile(coordinate, tile, session.historyEntry);
    }
  }
  if (commit && session.historyEntry != 0) {
    history_->finishEntry(session.historyEntry);
  }
  if (commit && session.applied && session.started != std::chrono::steady_clock::time_point{}) {
    waitForGpu();
    NANQUIM_LOG_INFO(
        "Fill %dx%d: read %.1f ms, region %.1f ms, %zu tiles, shown %.1f ms after the start",
        document_->widthPixels, document_->heightPixels, session.readMilliseconds,
        session.regionMilliseconds, session.applied->size(),
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() -
                                                  session.started)
            .count());
  }
  for (const canvas::TileCoordinate coordinate : tileStore_->tilesOf(kFillOriginalSurface)) {
    tileStore_->removeTile({kFillOriginalSurface, coordinate});
  }
  fillSession_.reset();
  frameRequested_ = true;
  runDeferredTasks();
  return true;
}

void EngineContext::runFillNow(const FillRequest& request) {
  FillSession& session = fillSession_.emplace();
  session.request = request;
  const int32_t width = document_->widthPixels;
  const int32_t height = document_->heightPixels;
  std::vector<uint8_t> coverage(static_cast<size_t>(width) * height, 255);
  if (!request.whole) {
    std::vector<uint8_t> pixels;
    readSurfacePixels(request.source, pixels);
    canvas::FloodFillOptions options;
    options.threshold = request.threshold;
    options.expand = request.expand;
    coverage = canvas::floodFillCoverage(pixels.data(), width, height,
                                         static_cast<int32_t>(std::floor(request.seedX)),
                                         static_cast<int32_t>(std::floor(request.seedY)), options);
  }
  session.applied = std::make_shared<const canvas::TileCoverageMap>(
      canvas::coverageTiles(coverage, width, height));
  finishFill(true);
}

}  // namespace nanquim
