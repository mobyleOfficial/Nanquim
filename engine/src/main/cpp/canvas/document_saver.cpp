#include "canvas/document_saver.h"

#include <algorithm>
#include <cstring>
#include <utility>

#include "core/log.h"
#include "io/document_transaction.h"
#include "io/file_system.h"
#include "io/tile_codec.h"

namespace nanquim::canvas {

namespace {

constexpr size_t kMaxReadbacksInFlight = 64;
constexpr int64_t kMaxTileJobsInFlight = 128;

std::string tilePath(const std::string& surfaceFolder, TileCoordinate coordinate) {
  return surfaceFolder + "/" + io::documentTileRelativePath(coordinate);
}

}  // namespace

DocumentSaver::DocumentSaver(LayerTileStore& tileStore, core::WorkerPool& ioPool,
                             PostToRenderThread postToRenderThread)
    : tileStore_(tileStore), ioPool_(ioPool), postToRenderThread_(std::move(postToRenderThread)) {}

DocumentSaver::~DocumentSaver() = default;

void DocumentSaver::resetForDocument(std::string folder, int32_t canvasWidth,
                                     int32_t canvasHeight) {
  ++generation_;
  if (active_) {
    SaveResult result;
    result.requestId = active_->request.requestId;
    result.error = "document closed";
    active_->done(result);
    active_.reset();
  }
  for (auto& [request, done] : queue_) {
    SaveResult result;
    result.requestId = request.requestId;
    result.error = "document closed";
    done(result);
  }
  queue_.clear();
  savedSurfaces_.clear();
  orphanFolders_.clear();
  folder_ = std::move(folder);
  canvasWidth_ = canvasWidth;
  canvasHeight_ = canvasHeight;
}

void DocumentSaver::addSavedSurface(const SaveSurface& surface,
                                    const std::vector<TileCoordinate>& tilesOnDisk) {
  SavedSurface& saved = savedSurfaces_[surface.id];
  saved.folder = surface.folder;
  saved.format = surface.format;
  saved.tilesOnDisk = {tilesOnDisk.begin(), tilesOnDisk.end()};
}

void DocumentSaver::addOrphanFolder(std::string relativeFolder) {
  orphanFolders_.push_back(std::move(relativeFolder));
}

void DocumentSaver::save(SaveRequest request, std::function<void(const SaveResult&)> done) {
  if (folder_.empty()) {
    SaveResult result;
    result.requestId = request.requestId;
    result.error = "the document has no folder";
    done(result);
    return;
  }
  queue_.emplace_back(std::move(request), std::move(done));
}

bool DocumentSaver::blocksTileWrites() const {
  return active_ != nullptr && !active_->gpuCaptures.empty();
}

const DocumentSaver::SavedSurface* DocumentSaver::savedSurfaceFor(LayerId id) const {
  auto found = savedSurfaces_.find(id);
  return found == savedSurfaces_.end() ? nullptr : &found->second;
}

void DocumentSaver::poll() {
  if (!active_ && !queue_.empty()) {
    active_ = std::make_unique<ActiveSave>();
    active_->request = std::move(queue_.front().first);
    active_->done = std::move(queue_.front().second);
    queue_.pop_front();
    start();
  }
  if (!active_) {
    return;
  }
  pollReadbacks();
  issueReadbacks();
  commitIfReady();
}

void DocumentSaver::start() {
  ActiveSave& save = *active_;
  save.start = std::chrono::steady_clock::now();
  save.transaction = std::make_shared<io::DocumentTransaction>(folder_);
  const TileRange canvasTiles{0, 0, (canvasWidth_ + kTileSizePixels - 1) / kTileSizePixels,
                              (canvasHeight_ + kTileSizePixels - 1) / kTileSizePixels};
  std::unordered_map<LayerId, std::string> requestedFolders;
  for (const SaveSurface& surface : save.request.surfaces) {
    requestedFolders[surface.id] = surface.folder;
    const SavedSurface* saved = savedSurfaceFor(surface.id);
    if (saved != nullptr && saved->folder != surface.folder) {
      saved = nullptr;
    }
    tileStore_.setSurfaceFile(surface.id, io::joinPath(folder_, surface.folder), surface.format);
    auto& kept = save.kept[surface.id];
    std::unordered_set<TileCoordinate, TileCoordinateHash> present;
    for (const TileCoordinate coordinate : tileStore_.tilesOf(surface.id)) {
      if (!canvasTiles.contains(coordinate)) {
        continue;
      }
      present.insert(coordinate);
      const LayerTileKey key{surface.id, coordinate};
      const auto source = tileStore_.saveSource(key);
      const bool onDisk = saved != nullptr && saved->tilesOnDisk.count(coordinate) != 0;
      if (!source || (source->fileUpToDate && onDisk)) {
        kept.insert(coordinate);
        continue;
      }
      save.gpuCaptures.push_back({key, source->version});
    }
    if (saved != nullptr) {
      for (const TileCoordinate coordinate : saved->tilesOnDisk) {
        if (present.count(coordinate) == 0) {
          save.transaction->removeAfterCommit(tilePath(surface.folder, coordinate));
          save.removedTiles[surface.id].push_back(coordinate);
        }
      }
    }
  }
  for (const auto& [id, saved] : savedSurfaces_) {
    auto requestedFolder = requestedFolders.find(id);
    const bool moved = requestedFolder != requestedFolders.end() && requestedFolder->second != saved.folder;
    if ((requestedFolder == requestedFolders.end() && !tileStore_.hasLayer(id)) || moved) {
      save.transaction->removeAfterCommit(saved.folder);
      save.removedFolders.push_back(saved.folder);
      if (!moved) {
        save.forgottenSurfaces.push_back(id);
      }
    }
  }
  for (const std::string& orphan : orphanFolders_) {
    save.transaction->removeAfterCommit(orphan);
    save.removedFolders.push_back(orphan);
  }
  issueReadbacks();
}

void DocumentSaver::issueReadbacks() {
  ActiveSave& save = *active_;
  while (!save.gpuCaptures.empty() && save.readbacks.size() < kMaxReadbacksInFlight &&
         save.tileJobsInFlight < kMaxTileJobsInFlight) {
    const GpuCapture capture = save.gpuCaptures.front();
    save.gpuCaptures.pop_front();
    const auto source = tileStore_.saveSource(capture.key);
    if (!source) {
      continue;
    }
    const uint64_t version = source->version;
    if (source->compressed || source->disk) {
      ++save.tileJobsInFlight;
      ioPool_.post([this, job = jobFor(capture.key, version), compressed = source->compressed,
                    disk = source->disk, scratch = tileStore_.scratchFile(),
                    post = postToRenderThread_] {
        std::vector<uint8_t> pixels(static_cast<size_t>(canvas::tileBytes(job.engineFormat)));
        bool decoded = false;
        if (compressed) {
          decoded = io::decompressTile(*compressed, pixels.data(), pixels.size());
        } else if (const auto bytes = scratch->read(*disk)) {
          decoded = io::decompressTile(*bytes, pixels.data(), pixels.size());
        }
        const TileOutcome outcome = decoded ? stageTile(job, pixels) : TileOutcome::Failed;
        post([this, job, outcome] { finishTile(job.generation, job.key, job.version, outcome); });
      });
      continue;
    }
    if (!source->slot) {
      NANQUIM_LOG_ERROR("Save: tile (%d, %d) of surface %u has no readable copy",
                        capture.key.coordinate.column, capture.key.coordinate.row,
                        capture.key.layer);
      save.failed = true;
      continue;
    }
    PendingReadback readback;
    readback.key = capture.key;
    readback.version = version;
    readback.readback = tileStore_.readSlot(*source->slot);
    if (!readback.readback) {
      NANQUIM_LOG_ERROR("Save: tile (%d, %d) of surface %u cannot be read",
                        capture.key.coordinate.column, capture.key.coordinate.row,
                        capture.key.layer);
      save.failed = true;
      continue;
    }
    ++save.tileJobsInFlight;
    save.readbacks.push_back(std::move(readback));
  }
}

void DocumentSaver::pollReadbacks() {
  ActiveSave& save = *active_;
  for (auto iterator = save.readbacks.begin(); iterator != save.readbacks.end();) {
    if (!iterator->readback->isReady()) {
      ++iterator;
      continue;
    }
    std::vector<uint8_t> pixels(iterator->readback->size());
    const bool readOk = iterator->readback->read(pixels.data());
    const LayerTileKey key = iterator->key;
    const uint64_t version = iterator->version;
    iterator = save.readbacks.erase(iterator);
    if (!readOk) {
      finishTile(generation_, key, version, TileOutcome::Failed);
      continue;
    }
    ioPool_.post([this, job = jobFor(key, version), pixels = std::move(pixels),
                  post = postToRenderThread_] {
      const TileOutcome outcome = stageTile(job, pixels);
      post([this, job, outcome] { finishTile(job.generation, job.key, job.version, outcome); });
    });
  }
}

DocumentSaver::TileJob DocumentSaver::jobFor(const LayerTileKey& key, uint64_t version) const {
  TileJob job;
  job.generation = generation_;
  job.key = key;
  job.version = version;
  job.transaction = active_->transaction;
  for (const SaveSurface& surface : active_->request.surfaces) {
    if (surface.id == key.layer) {
      job.surfaceFolder = surface.folder;
      job.format = surface.format;
    }
  }
  job.placement = io::TilePlacement{key.coordinate, canvasWidth_, canvasHeight_};
  job.engineFormat = tileStore_.pixelFormat();
  return job;
}

DocumentSaver::TileOutcome DocumentSaver::stageTile(const TileJob& job,
                                                    const std::vector<uint8_t>& pixels) {
  if (job.surfaceFolder.empty()) {
    return TileOutcome::Failed;
  }
  const std::string path = tilePath(job.surfaceFolder, job.key.coordinate);
  if (io::isImplicitEngineTile(pixels.data(), job.format, job.placement, job.engineFormat)) {
    job.transaction->removeAfterCommit(path);
    return TileOutcome::Implicit;
  }
  std::vector<uint8_t> fileTile(io::documentTileBytes(job.format));
  io::engineTileToFile(pixels.data(), job.format, job.placement, fileTile.data(),
                       job.engineFormat);
  const std::vector<uint8_t> encoded = io::encodeDocumentTile(fileTile.data(), job.format);
  return !encoded.empty() && job.transaction->stageFile(path, encoded) ? TileOutcome::Written
                                                                       : TileOutcome::Failed;
}

void DocumentSaver::finishTile(uint64_t generation, LayerTileKey key, uint64_t version,
                               TileOutcome outcome) {
  if (generation != generation_ || !active_) {
    return;
  }
  ActiveSave& save = *active_;
  --save.tileJobsInFlight;
  if (outcome == TileOutcome::Written) {
    save.written.emplace_back(key, version);
  } else if (outcome == TileOutcome::Failed) {
    save.failed = true;
  }
  commitIfReady();
}

void DocumentSaver::commitIfReady() {
  ActiveSave& save = *active_;
  if (save.committing || !save.gpuCaptures.empty() || !save.readbacks.empty() ||
      save.tileJobsInFlight > 0) {
    return;
  }
  save.committing = true;
  if (save.failed) {
    finishSave(generation_, false, "a tile could not be written");
    return;
  }
  std::unordered_map<LayerId, std::unordered_set<TileCoordinate, TileCoordinateHash>> finalTiles =
      save.kept;
  for (const auto& [key, version] : save.written) {
    finalTiles[key.layer].insert(key.coordinate);
  }
  std::vector<SaveFile> files = save.request.files;
  for (const SaveSurface& surface : save.request.surfaces) {
    const auto& tiles = finalTiles[surface.id];
    const SavedSurface* saved = savedSurfaceFor(surface.id);
    if (saved != nullptr && saved->folder == surface.folder && saved->tilesOnDisk == tiles) {
      continue;
    }
    io::SurfaceIndex index;
    index.tiles.assign(tiles.begin(), tiles.end());
    std::sort(index.tiles.begin(), index.tiles.end(), [](TileCoordinate left, TileCoordinate right) {
      return left.row != right.row ? left.row < right.row : left.column < right.column;
    });
    index.revision = 1;
    files.push_back({surface.folder + "/index.json", io::writeSurfaceIndexJson(index)});
  }
  const uint64_t generation = generation_;
  ioPool_.post([this, generation, transaction = save.transaction, files = std::move(files),
                post = postToRenderThread_] {
    std::string error;
    bool ok = true;
    for (const SaveFile& file : files) {
      if (!transaction->stageFile(file.path, file.bytes)) {
        ok = false;
        error = "cannot write " + file.path;
        break;
      }
    }
    ok = ok && transaction->commit(error);
    post([this, generation, ok, error] { finishSave(generation, ok, error); });
  });
  for (auto& [id, tiles] : finalTiles) {
    save.kept[id] = std::move(tiles);
  }
}

void DocumentSaver::finishSave(uint64_t generation, bool ok, std::string error) {
  if (generation != generation_ || !active_) {
    return;
  }
  ActiveSave& save = *active_;
  SaveResult result;
  result.requestId = save.request.requestId;
  result.ok = ok;
  result.error = std::move(error);
  result.milliseconds = std::chrono::duration<double, std::milli>(
                            std::chrono::steady_clock::now() - save.start)
                            .count();
  if (ok) {
    for (const auto& [key, version] : save.written) {
      tileStore_.markSavedToFile(key, version);
    }
    result.tilesWritten = static_cast<int64_t>(save.written.size());
    for (const auto& [id, removed] : save.removedTiles) {
      result.tilesRemoved += static_cast<int64_t>(removed.size());
    }
    for (const SaveSurface& surface : save.request.surfaces) {
      SavedSurface& saved = savedSurfaces_[surface.id];
      saved.folder = surface.folder;
      saved.format = surface.format;
      saved.tilesOnDisk = std::move(save.kept[surface.id]);
    }
    for (const LayerId id : save.forgottenSurfaces) {
      savedSurfaces_.erase(id);
    }
    orphanFolders_.erase(std::remove_if(orphanFolders_.begin(), orphanFolders_.end(),
                                        [&save](const std::string& folder) {
                                          return std::find(save.removedFolders.begin(),
                                                           save.removedFolders.end(),
                                                           folder) != save.removedFolders.end();
                                        }),
                         orphanFolders_.end());
  } else {
    NANQUIM_LOG_ERROR("Save failed: %s", result.error.c_str());
  }
  auto done = std::move(save.done);
  active_.reset();
  done(result);
}

}  // namespace nanquim::canvas
