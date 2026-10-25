#include "canvas/layer_tile_store.h"

#include <cstring>
#include <utility>

#include "core/log.h"
#include "io/tile_codec.h"

namespace nanquim::canvas {

namespace {

constexpr size_t kMaxReadbacksInFlight = 64;
constexpr int kMaxNewReadbacksPerFrame = 16;
constexpr int64_t kMaxLoadsInFlight = 64;
constexpr int64_t kMaxSpillsInFlight = 64;
constexpr size_t kStagingTextureCount = 32;

}  // namespace

LayerTileStore::LayerTileStore(gpu::rhi::Device& device, core::WorkerPool& ioPool,
                               PostToRenderThread postToRenderThread, std::string scratchFilePath)
    : device_(device),
      ioPool_(ioPool),
      postToRenderThread_(std::move(postToRenderThread)),
      scratchFile_(std::make_shared<io::ScratchFile>(std::move(scratchFilePath))) {}

LayerTileStore::~LayerTileStore() = default;

void LayerTileStore::setPixelFormat(PixelFormat format) {
  if (format == format_) {
    return;
  }
  if (!tilesByLayer_.empty()) {
    NANQUIM_LOG_ERROR("Pixel format changed while the tile store holds tiles; ignored");
    return;
  }
  format_ = format;
  tileBytes_ = canvas::tileBytes(format);
  pages_.clear();
  freeSlots_.clear();
  stagingTextures_.clear();
  nextStagingTexture_ = 0;
}

std::unique_ptr<gpu::rhi::Readback> LayerTileStore::readSlot(const AtlasSlot& slot) {
  return device_.commands().readTextureAsync(
      pageTexture(slot.page),
      gpu::rhi::Rect{slotOriginX(slot), slotOriginY(slot), kTileSizePixels, kTileSizePixels});
}

void LayerTileStore::setBudgets(int64_t gpuBytes, int64_t ramBytes) {
  gpuBudgetBytes_ = gpuBytes;
  ramBudgetBytes_ = ramBytes;
}

LayerTileStore::TileRecord* LayerTileStore::find(const LayerTileKey& key) {
  auto layer = tilesByLayer_.find(key.layer);
  if (layer == tilesByLayer_.end()) {
    return nullptr;
  }
  auto tile = layer->second.find(key.coordinate);
  return tile == layer->second.end() ? nullptr : &tile->second;
}

bool LayerTileStore::hasTile(const LayerTileKey& key) const {
  auto layer = tilesByLayer_.find(key.layer);
  return layer != tilesByLayer_.end() && layer->second.count(key.coordinate) != 0;
}

std::optional<AtlasSlot> LayerTileStore::allocateSlot() {
  if (freeSlots_.empty()) {
    gpu::rhi::TextureDescription description;
    description.format = textureFormat(format_);
    description.width = kAtlasPageSizePixels;
    description.height = kAtlasPageSizePixels;
    description.usage = gpu::rhi::kUsageSampled | gpu::rhi::kUsageRenderTarget |
                        gpu::rhi::kUsageUpload | gpu::rhi::kUsageReadback;
    std::unique_ptr<gpu::rhi::Texture> page = device_.createTexture(description);
    if (!page) {
      NANQUIM_LOG_ERROR("Atlas page allocation failed (out of GPU memory)");
      return std::nullopt;
    }
    const auto pageIndex = static_cast<uint32_t>(pages_.size());
    pages_.push_back(std::move(page));
    for (int32_t index = kAtlasSlotsPerPage - 1; index >= 0; --index) {
      freeSlots_.push_back(AtlasSlot{pageIndex, static_cast<uint32_t>(index)});
    }
  }
  const AtlasSlot slot = freeSlots_.back();
  freeSlots_.pop_back();
  return slot;
}

void LayerTileStore::releaseGpuCopy(const LayerTileKey& key, TileRecord& record) {
  if (record.slot) {
    freeSlots_.push_back(*record.slot);
    record.slot.reset();
  }
  gpuLru_.remove(key);
}

void LayerTileStore::dropRamCopy(const LayerTileKey& key, TileRecord& record) {
  record.compressed.reset();
  ramLru_.remove(key);
}

LayerTileStore::Availability LayerTileStore::ensureResident(const LayerTileKey& key) {
  TileRecord* record = find(key);
  if (record == nullptr) {
    return Availability::Absent;
  }
  if (record->slot) {
    gpuLru_.touch(key, tileBytes_, frame_);
    return Availability::Resident;
  }
  requestLoad(key, *record);
  return find(key) == nullptr ? Availability::Absent : Availability::Loading;
}

std::optional<AtlasSlot> LayerTileStore::slotForRead(const LayerTileKey& key) {
  TileRecord* record = find(key);
  if (record == nullptr) {
    return std::nullopt;
  }
  if (record->slot) {
    gpuLru_.touch(key, tileBytes_, frame_);
    return record->slot;
  }
  requestLoad(key, *record);
  return std::nullopt;
}

std::optional<AtlasSlot> LayerTileStore::slotForWrite(const LayerTileKey& key) {
  TileRecord& record = tilesByLayer_[key.layer][key.coordinate];
  if (record.hasValidDiskCopy()) {
    --validDiskCopies_;
  }
  ++record.version;
  if (record.compressed) {
    dropRamCopy(key, record);
  }
  if (!record.slot) {
    record.slot = allocateSlot();
    if (!record.slot) {
      return std::nullopt;
    }
  }
  gpuLru_.touch(key, tileBytes_, frame_);
  return record.slot;
}

std::optional<LayerTileStore::UpdateSlot> LayerTileStore::slotForUpdate(const LayerTileKey& key) {
  TileRecord* record = find(key);
  if (record == nullptr) {
    const auto slot = slotForWrite(key);
    return slot ? std::optional<UpdateSlot>(UpdateSlot{*slot, true}) : std::nullopt;
  }
  if (!record->slot) {
    return std::nullopt;
  }
  if (record->hasValidDiskCopy()) {
    --validDiskCopies_;
  }
  ++record->version;
  if (record->compressed) {
    dropRamCopy(key, *record);
  }
  gpuLru_.touch(key, tileBytes_, frame_);
  return UpdateSlot{*record->slot, false};
}

std::optional<uint64_t> LayerTileStore::version(const LayerTileKey& key) const {
  auto layer = tilesByLayer_.find(key.layer);
  if (layer == tilesByLayer_.end()) {
    return std::nullopt;
  }
  auto tile = layer->second.find(key.coordinate);
  return tile == layer->second.end() ? std::nullopt : std::optional<uint64_t>(tile->second.version);
}

bool LayerTileStore::removeTileIfUnchanged(const LayerTileKey& key, uint64_t expectedVersion) {
  TileRecord* record = find(key);
  if (record == nullptr || record->version != expectedVersion) {
    return false;
  }
  releaseGpuCopy(key, *record);
  dropRamCopy(key, *record);
  if (record->hasValidDiskCopy()) {
    --validDiskCopies_;
  }
  tilesByLayer_[key.layer].erase(key.coordinate);
  return true;
}

void LayerTileStore::removeTile(const LayerTileKey& key) {
  TileRecord* record = find(key);
  if (record != nullptr) {
    removeTileIfUnchanged(key, record->version);
  }
}

bool LayerTileStore::writeTile(const LayerTileKey& key, const uint8_t* pixels) {
  const auto slot = slotForWrite(key);
  if (!slot) {
    return false;
  }
  uploadTile(*slot, pixels);
  return true;
}

std::shared_ptr<const std::vector<uint8_t>> LayerTileStore::validCompressedCopy(
    const LayerTileKey& key) const {
  auto layer = tilesByLayer_.find(key.layer);
  if (layer == tilesByLayer_.end()) {
    return nullptr;
  }
  auto found = layer->second.find(key.coordinate);
  if (found == layer->second.end() || !found->second.hasValidRamCopy()) {
    return nullptr;
  }
  return found->second.compressed;
}

std::vector<TileCoordinate> LayerTileStore::tilesOf(LayerId layer) const {
  std::vector<TileCoordinate> coordinates;
  forEachTile(layer, [&coordinates](TileCoordinate coordinate) { coordinates.push_back(coordinate); });
  return coordinates;
}

void LayerTileStore::requestLoad(const LayerTileKey& key, TileRecord& record) {
  if (record.loading || loadsInFlight_ >= kMaxLoadsInFlight) {
    return;
  }
  const uint64_t generation = generation_;
  const uint64_t version = record.version;
  auto deliver = [post = postToRenderThread_, this, key, generation, version](
                     std::vector<uint8_t> pixels, bool decoded) {
    post([this, key, generation, version, pixels = std::move(pixels), decoded]() mutable {
      finishLoad(key, generation, version, std::move(pixels), decoded);
    });
  };

  if (record.hasValidRamCopy()) {
    record.loading = true;
    ++loadsInFlight_;
    ioPool_.post([compressed = record.compressed, deliver, bytes = tileBytes_]() {
      std::vector<uint8_t> pixels(static_cast<size_t>(bytes));
      const bool decoded = io::decompressTile(*compressed, pixels.data(), pixels.size());
      deliver(std::move(pixels), decoded);
    });
  } else if (record.hasValidDiskCopy()) {
    record.loading = true;
    ++loadsInFlight_;
    ioPool_.post([scratchFile = scratchFile_,
                  location = record.disk.value_or(io::ScratchLocation{}), deliver,
                  bytes = tileBytes_]() {
      std::vector<uint8_t> pixels(static_cast<size_t>(bytes));
      const auto compressed = scratchFile->read(location);
      const bool decoded =
          compressed && io::decompressTile(*compressed, pixels.data(), pixels.size());
      deliver(std::move(pixels), decoded);
    });
  } else if (auto file = surfaceFiles_.find(key.layer);
             record.hasValidFileCopy() && file != surfaceFiles_.end()) {
    record.loading = true;
    ++loadsInFlight_;
    ioPool_.post([folder = file->second.folder, format = file->second.format, key, deliver,
                  engineFormat = format_]() {
      std::vector<uint8_t> pixels(static_cast<size_t>(canvas::tileBytes(engineFormat)));
      const io::TileReadResult result =
          io::readDocumentTile(folder, key.coordinate, format, pixels.data(), engineFormat);
      if (result != io::TileReadResult::Ok) {
        io::fillEmptyEngineTile(pixels.data(), format, engineFormat);
      }
      deliver(std::move(pixels), result == io::TileReadResult::Ok);
    });
  } else {
    NANQUIM_LOG_ERROR("Tile (%d, %d) of layer %u has no stored copy; treating it as empty",
                      key.coordinate.column, key.coordinate.row, key.layer);
    tilesByLayer_[key.layer].erase(key.coordinate);
  }
}

void LayerTileStore::finishLoad(const LayerTileKey& key, uint64_t generation, uint64_t version,
                                std::vector<uint8_t> pixels, bool decoded) {
  if (generation != generation_) {
    return;
  }
  --loadsInFlight_;
  TileRecord* record = find(key);
  if (record == nullptr) {
    return;
  }
  record->loading = false;
  if (record->slot || record->version != version) {
    return;
  }
  if (!decoded) {
    NANQUIM_LOG_ERROR("Tile (%d, %d) of layer %u failed to decode; treating it as empty",
                      key.coordinate.column, key.coordinate.row, key.layer);
    if (record->hasValidFileCopy()) {
      ++corruptFileTiles_;
      record->fileVersion = 0;
    } else {
      std::memset(pixels.data(), 0, pixels.size());
    }
  }
  const std::optional<AtlasSlot> slot = allocateSlot();
  if (!slot) {
    return;
  }
  record->slot = slot;
  uploadTile(*slot, pixels.data());
  gpuLru_.touch(key, tileBytes_, frame_);
  if (onTileLoaded_) {
    onTileLoaded_(key);
  }
}

void LayerTileStore::uploadTile(const AtlasSlot& slot, const uint8_t* pixels) {
  if (stagingTextures_.empty()) {
    gpu::rhi::TextureDescription description;
    description.format = textureFormat(format_);
    description.width = kTileSizePixels;
    description.height = kTileSizePixels;
    description.usage = gpu::rhi::kUsageUpload | gpu::rhi::kUsageReadback;
    for (size_t index = 0; index < kStagingTextureCount; ++index) {
      std::unique_ptr<gpu::rhi::Texture> staging = device_.createTexture(description);
      if (!staging) {
        break;
      }
      stagingTextures_.push_back(std::move(staging));
    }
    if (stagingTextures_.empty()) {
      NANQUIM_LOG_ERROR("No GPU memory for tile uploads");
      return;
    }
  }
  gpu::rhi::Texture& staging = *stagingTextures_[nextStagingTexture_];
  nextStagingTexture_ = (nextStagingTexture_ + 1) % stagingTextures_.size();
  device_.uploadTexture(staging, pixels);
  device_.commands().copyTexture(staging, gpu::rhi::Rect{0, 0, kTileSizePixels, kTileSizePixels},
                                 pageTexture(slot.page), slotOriginX(slot), slotOriginY(slot));
}

void LayerTileStore::startReadback(const LayerTileKey& key, TileRecord& record) {
  if (!record.slot) {
    return;
  }
  PendingReadback readback;
  readback.key = key;
  readback.version = record.version;
  readback.readback = readSlot(*record.slot);
  if (!readback.readback) {
    return;
  }
  record.evicting = true;
  pendingReadbacks_.push_back(std::move(readback));
}

void LayerTileStore::pollReadbacks() {
  for (auto iterator = pendingReadbacks_.begin(); iterator != pendingReadbacks_.end();) {
    if (!iterator->readback->isReady()) {
      ++iterator;
      continue;
    }
    std::vector<uint8_t> pixels(static_cast<size_t>(tileBytes_));
    const bool readOk = iterator->readback->read(pixels.data());

    const LayerTileKey key = iterator->key;
    const uint64_t version = iterator->version;
    const uint64_t generation = generation_;
    iterator = pendingReadbacks_.erase(iterator);
    if (!readOk) {
      NANQUIM_LOG_ERROR("Readback failed; tile stays on the GPU");
      if (TileRecord* record = find(key)) {
        record->evicting = false;
      }
      continue;
    }
    ioPool_.post(
        [post = postToRenderThread_, this, key, version, generation, pixels = std::move(pixels)]() {
          auto compressed = std::make_shared<const std::vector<uint8_t>>(
              io::compressTile(pixels.data(), pixels.size()));
          post([this, key, version, generation, compressed]() {
            finishEviction(key, generation, version, compressed);
          });
        });
  }
}

void LayerTileStore::finishEviction(const LayerTileKey& key, uint64_t generation, uint64_t version,
                                    std::shared_ptr<const std::vector<uint8_t>> compressed) {
  if (generation != generation_) {
    return;
  }
  TileRecord* record = find(key);
  if (record == nullptr) {
    return;
  }
  record->evicting = false;
  if (record->version != version || !record->slot || compressed->empty()) {
    return;
  }
  record->compressed = std::move(compressed);
  record->compressedVersion = version;
  ramLru_.touch(key, static_cast<int64_t>(record->compressed->size()), frame_);
  releaseGpuCopy(key, *record);
}

void LayerTileStore::enforceGpuBudget() {
  const auto readbackBytes = static_cast<int64_t>(pendingReadbacks_.size()) * tileBytes_;
  int64_t excess = gpuLru_.totalBytes() - readbackBytes - gpuBudgetBytes_;
  if (excess <= 0) {
    return;
  }
  std::vector<LayerTileKey> candidates;
  gpuLru_.visitLeastRecentFirst([&](const auto& entry) {
    if (entry.lastUseFrame >= frame_ - 1 || excess <= 0) {
      return false;
    }
    candidates.push_back(entry.key);
    excess -= entry.bytes;
    return true;
  });
  int newReadbacks = 0;
  for (const LayerTileKey& key : candidates) {
    TileRecord* record = find(key);
    if (record == nullptr || !record->slot || record->evicting) {
      continue;
    }
    if (record->hasValidRamCopy() || record->hasValidDiskCopy() || record->hasValidFileCopy()) {
      releaseGpuCopy(key, *record);
    } else if (pendingReadbacks_.size() < kMaxReadbacksInFlight &&
               newReadbacks < kMaxNewReadbacksPerFrame) {
      startReadback(key, *record);
      ++newReadbacks;
    }
  }
}

void LayerTileStore::enforceRamBudget() {
  int64_t excess = ramLru_.totalBytes() - spillBytesInFlight_ - ramBudgetBytes_;
  if (excess <= 0) {
    return;
  }
  std::vector<LayerTileKey> candidates;
  ramLru_.visitLeastRecentFirst([&](const auto& entry) {
    if (excess <= 0) {
      return false;
    }
    candidates.push_back(entry.key);
    excess -= entry.bytes;
    return true;
  });
  for (const LayerTileKey& key : candidates) {
    TileRecord* record = find(key);
    if (record == nullptr || record->spilling || !record->compressed) {
      continue;
    }
    if (record->hasValidDiskCopy() || record->hasValidFileCopy() || !record->hasValidRamCopy()) {
      dropRamCopy(key, *record);
      continue;
    }
    if (spillsInFlight_ >= kMaxSpillsInFlight) {
      continue;
    }
    record->spilling = true;
    ++spillsInFlight_;
    spillBytesInFlight_ += static_cast<int64_t>(record->compressed->size());
    const uint64_t generation = generation_;
    const uint64_t version = record->compressedVersion;
    ioPool_.post([post = postToRenderThread_, this, key, generation, version,
                  scratchFile = scratchFile_, compressed = record->compressed]() {
      auto location = scratchFile->append(*compressed);
      const auto bytes = static_cast<int64_t>(compressed->size());
      post([this, key, generation, version, location, bytes]() {
        if (generation == generation_) {
          --spillsInFlight_;
          spillBytesInFlight_ -= bytes;
        }
        finishSpill(key, generation, version, location);
      });
    });
  }
}

void LayerTileStore::finishSpill(const LayerTileKey& key, uint64_t generation, uint64_t version,
                                 std::optional<io::ScratchLocation> location) {
  if (generation != generation_) {
    return;
  }
  TileRecord* record = find(key);
  if (record == nullptr) {
    return;
  }
  record->spilling = false;
  if (!location) {
    NANQUIM_LOG_ERROR("Spilling a tile to the scratch file failed; it stays in RAM");
    return;
  }
  if (record->version != version) {
    return;
  }
  if (!record->hasValidDiskCopy()) {
    ++validDiskCopies_;
  }
  record->disk = location;
  record->diskVersion = version;
  dropRamCopy(key, *record);
}

bool LayerTileStore::isOverGpuBudget() const {
  return gpuLru_.totalBytes() >
         gpuBudgetBytes_ + static_cast<int64_t>(kMaxReadbacksInFlight) * tileBytes_;
}

void LayerTileStore::maintain() {
  pollReadbacks();
  enforceGpuBudget();
  enforceRamBudget();
}

void LayerTileStore::removeLayer(LayerId layer) {
  auto found = tilesByLayer_.find(layer);
  if (found == tilesByLayer_.end()) {
    return;
  }
  for (auto& [coordinate, record] : found->second) {
    const LayerTileKey key{layer, coordinate};
    releaseGpuCopy(key, record);
    dropRamCopy(key, record);
    if (record.hasValidDiskCopy()) {
      --validDiskCopies_;
    }
  }
  tilesByLayer_.erase(found);
}

void LayerTileStore::clear() {
  for (auto& [layer, tiles] : tilesByLayer_) {
    for (auto& [coordinate, record] : tiles) {
      const LayerTileKey key{layer, coordinate};
      releaseGpuCopy(key, record);
      dropRamCopy(key, record);
    }
  }
  tilesByLayer_.clear();
  surfaceFiles_.clear();
  corruptFileTiles_ = 0;
  pendingReadbacks_.clear();
  ++generation_;
  validDiskCopies_ = 0;
  loadsInFlight_ = 0;
  spillsInFlight_ = 0;
  spillBytesInFlight_ = 0;
  ioPool_.post([scratchFile = scratchFile_] { scratchFile->truncate(); });
}

void LayerTileStore::setSurfaceFile(LayerId surface, std::string folder,
                                    io::DocumentTileFormat format) {
  surfaceFiles_[surface] = SurfaceFile{std::move(folder), format};
}

void LayerTileStore::addFileTile(const LayerTileKey& key) {
  TileRecord& record = tilesByLayer_[key.layer][key.coordinate];
  record.fileVersion = record.version;
}

void LayerTileStore::markSavedToFile(const LayerTileKey& key, uint64_t version) {
  if (TileRecord* record = find(key); record != nullptr && record->version == version) {
    record->fileVersion = version;
  }
}

std::optional<LayerTileStore::SaveSource> LayerTileStore::saveSource(const LayerTileKey& key) const {
  auto layer = tilesByLayer_.find(key.layer);
  if (layer == tilesByLayer_.end()) {
    return std::nullopt;
  }
  auto found = layer->second.find(key.coordinate);
  if (found == layer->second.end()) {
    return std::nullopt;
  }
  const TileRecord& record = found->second;
  SaveSource source;
  source.version = record.version;
  source.fileUpToDate = record.hasValidFileCopy();
  if (record.hasValidRamCopy()) {
    source.compressed = record.compressed;
  }
  if (record.hasValidDiskCopy()) {
    source.disk = record.disk;
  }
  source.slot = record.slot;
  return source;
}

LayerTileStoreStats LayerTileStore::stats() const {
  LayerTileStoreStats stats;
  for (const auto& [layer, tiles] : tilesByLayer_) {
    stats.tileCount += static_cast<int64_t>(tiles.size());
    if (surfaceFiles_.count(layer) != 0) {
      for (const auto& [coordinate, record] : tiles) {
        stats.fileTiles += record.hasValidFileCopy() ? 1 : 0;
      }
    }
  }
  stats.diskTiles = validDiskCopies_;
  stats.corruptFileTiles = corruptFileTiles_;
  stats.gpuTiles = static_cast<int64_t>(gpuLru_.size());
  stats.gpuTileBytes = gpuLru_.totalBytes();
  stats.atlasPages = static_cast<int64_t>(pages_.size());
  stats.ramTiles = static_cast<int64_t>(ramLru_.size());
  stats.ramBytes = ramLru_.totalBytes();
  stats.scratchFileBytes = scratchFile_->sizeBytes();
  stats.loadsInFlight = loadsInFlight_;
  stats.readbacksInFlight = static_cast<int64_t>(pendingReadbacks_.size());
  stats.spillsInFlight = spillsInFlight_;
  stats.gpuBudgetBytes = gpuBudgetBytes_;
  stats.ramBudgetBytes = ramBudgetBytes_;
  return stats;
}

}  // namespace nanquim::canvas
