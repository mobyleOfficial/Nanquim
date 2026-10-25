#include "canvas/history_store.h"

#include <algorithm>
#include <cstring>

#include "core/log.h"
#include "io/tile_codec.h"

namespace nanquim::canvas {

namespace {


int64_t sizeOf(const std::shared_ptr<const std::vector<uint8_t>>& compressed) {
  return compressed ? static_cast<int64_t>(compressed->size()) : 0;
}

}  // namespace

HistoryStore::HistoryStore(LayerTileStore& tileStore, core::WorkerPool& ioPool,
                           PostToRenderThread postToRenderThread, std::string spillFilePath)
    : tileStore_(tileStore),
      ioPool_(ioPool),
      postToRenderThread_(std::move(postToRenderThread)),
      spillFile_(std::make_shared<io::ScratchFile>(std::move(spillFilePath))) {}

HistoryStore::~HistoryStore() = default;

HistoryStore::Entry& HistoryStore::entryFor(int64_t entry) {
  auto found = entries_.find(entry);
  if (found == entries_.end()) {
    found = entries_.emplace(entry, Entry{}).first;
    found->second.sequence = nextSequence_++;
  }
  return found->second;
}

void HistoryStore::captureBefore(int64_t entryId, const LayerTileKey& key) {
  Entry& entry = entryFor(entryId);
  if (entry.indexByKey.count(key) != 0) {
    return;
  }
  entry.indexByKey[key] = entry.tiles.size();
  entry.tiles.push_back(TileHistory{key, {}, {}});
  capture(entryId, entry, entry.tiles.size() - 1, true);
}

void HistoryStore::captureAfter(int64_t entryId, const LayerTileKey& key) {
  auto found = entries_.find(entryId);
  if (found == entries_.end()) {
    return;
  }
  Entry& entry = found->second;
  auto index = entry.indexByKey.find(key);
  if (index == entry.indexByKey.end() || entry.tiles[index->second].after.captured ||
      entry.tiles[index->second].after.loading) {
    return;
  }
  capture(entryId, entry, index->second, false);
}

std::vector<LayerTileKey> HistoryStore::capturedTiles(int64_t entry) const {
  std::vector<LayerTileKey> keys;
  auto found = entries_.find(entry);
  if (found != entries_.end()) {
    for (const TileHistory& tile : found->second.tiles) {
      keys.push_back(tile.key);
    }
  }
  return keys;
}

void HistoryStore::capture(int64_t entryId, Entry& entry, size_t tileIndex, bool before) {
  TileHistory& tile = entry.tiles[tileIndex];
  Payload& payload = payloadOf(tile, before);
  if (!tileStore_.hasTile(tile.key)) {
    payload.captured = true;
    payload.empty = true;
    return;
  }
  if (auto copy = tileStore_.validCompressedCopy(tile.key)) {
    payload.captured = true;
    payload.compressed = std::move(copy);
    const int64_t bytes = sizeOf(payload.compressed);
    entry.bytes += bytes;
    ramBytes_ += bytes;
    return;
  }
  const auto slot = tileStore_.slotForRead(tile.key);
  if (!slot) {
    NANQUIM_LOG_ERROR("History: tile (%d, %d) of surface %u is not resident; recorded as empty",
                      tile.key.coordinate.column, tile.key.coordinate.row, tile.key.layer);
    payload.captured = true;
    payload.empty = true;
    return;
  }
  PendingCapture pending;
  pending.entry = entryId;
  pending.tileIndex = tileIndex;
  pending.before = before;
  pending.readback = tileStore_.readSlot(*slot);
  if (!pending.readback) {
    NANQUIM_LOG_ERROR("History: tile (%d, %d) of surface %u cannot be read; recorded as empty",
                      tile.key.coordinate.column, tile.key.coordinate.row, tile.key.layer);
    payload.captured = true;
    payload.empty = true;
    return;
  }
  ++entry.pendingPayloads;
  payload.loading = true;
  pendingCaptures_.push_back(std::move(pending));
}

void HistoryStore::finishEntry(int64_t entryId) {
  auto found = entries_.find(entryId);
  if (found == entries_.end()) {
    return;
  }
  found->second.finished = true;
  payloadDone(entryId, found->second);
}

void HistoryStore::payloadDone(int64_t entryId, Entry& entry) {
  if (!entry.finished || entry.pendingPayloads > 0 || entry.readyReported) {
    return;
  }
  entry.readyReported = true;
  if (onEntryReady_) {
    onEntryReady_(entryId, entry.bytes);
  }
}

void HistoryStore::pollCaptures() {
  for (auto iterator = pendingCaptures_.begin(); iterator != pendingCaptures_.end();) {
    if (!iterator->readback->isReady()) {
      ++iterator;
      continue;
    }
    std::vector<uint8_t> pixels(iterator->readback->size());
    const bool readOk = iterator->readback->read(pixels.data());
    if (!readOk) {
      NANQUIM_LOG_ERROR("History readback failed; the tile state is recorded as empty");
    }
    const int64_t entryId = iterator->entry;
    const size_t tileIndex = iterator->tileIndex;
    const bool before = iterator->before;
    const uint64_t generation = generation_;
    iterator = pendingCaptures_.erase(iterator);
    ioPool_.post([this, post = postToRenderThread_, entryId, tileIndex, before, generation, readOk,
                  pixels = std::move(pixels)] {
      std::shared_ptr<const std::vector<uint8_t>> compressed =
          readOk ? std::make_shared<const std::vector<uint8_t>>(
                       io::compressTile(pixels.data(), pixels.size()))
                 : nullptr;
      post([this, entryId, generation, tileIndex, before, compressed = std::move(compressed)] {
        storeCompressed(entryId, generation, tileIndex, before, compressed);
      });
    });
  }
}

void HistoryStore::storeCompressed(int64_t entryId, uint64_t generation, size_t tileIndex,
                                   bool before,
                                   std::shared_ptr<const std::vector<uint8_t>> compressed) {
  if (generation != generation_) {
    return;
  }
  auto found = entries_.find(entryId);
  if (found == entries_.end()) {
    return;
  }
  Entry& entry = found->second;
  Payload& payload = payloadOf(entry.tiles[tileIndex], before);
  payload.loading = false;
  payload.captured = true;
  if (compressed) {
    payload.compressed = std::move(compressed);
    const int64_t bytes = sizeOf(payload.compressed);
    entry.bytes += bytes;
    ramBytes_ += bytes;
  } else {
    payload.empty = true;
  }
  --entry.pendingPayloads;
  payloadDone(entryId, entry);
}

HistoryStore::ApplyResult HistoryStore::apply(
    int64_t entryId, bool before, const std::function<void(const LayerTileKey&)>& onTileWritten) {
  auto found = entries_.find(entryId);
  if (found == entries_.end()) {
    return ApplyResult::Unknown;
  }
  Entry& entry = found->second;
  if (!entry.finished || entry.pendingPayloads > 0) {
    return ApplyResult::NotReady;
  }
  bool waiting = false;
  for (size_t index = 0; index < entry.tiles.size(); ++index) {
    Payload& payload = payloadOf(entry.tiles[index], before);
    if (payload.empty || payload.compressed) {
      continue;
    }
    waiting = true;
    if (payload.loading || !payload.disk) {
      continue;
    }
    payload.loading = true;
    const uint64_t generation = generation_;
    const io::ScratchLocation location = *payload.disk;
    ioPool_.post([this, post = postToRenderThread_, file = spillFile_, entryId, index, before,
                  generation, location] {
      auto bytes = file->read(location);
      post([this, entryId, index, before, generation, bytes = std::move(bytes)]() mutable {
        if (generation != generation_) {
          return;
        }
        auto entryFound = entries_.find(entryId);
        if (entryFound == entries_.end()) {
          return;
        }
        Payload& loaded = payloadOf(entryFound->second.tiles[index], before);
        loaded.loading = false;
        if (!bytes) {
          NANQUIM_LOG_ERROR("History: cannot read a spilled tile; it is restored as empty");
          loaded.empty = true;
          return;
        }
        loaded.compressed = std::make_shared<const std::vector<uint8_t>>(std::move(*bytes));
        ramBytes_ += sizeOf(loaded.compressed);
      });
    });
  }
  if (waiting) {
    entry.applying = true;
    return ApplyResult::NotReady;
  }
  entry.applying = false;
  std::vector<uint8_t> pixels(static_cast<size_t>(tileStore_.tileBytes()));
  for (TileHistory& tile : entry.tiles) {
    const Payload& payload = payloadOf(tile, before);
    if (payload.empty) {
      tileStore_.removeTile(tile.key);
    } else if (io::decompressTile(*payload.compressed, pixels.data(), pixels.size())) {
      if (!tileStore_.writeTile(tile.key, pixels.data())) {
        NANQUIM_LOG_ERROR("History: no GPU memory to restore a tile");
      }
    } else {
      NANQUIM_LOG_ERROR("History: corrupt tile payload");
      continue;
    }
    onTileWritten(tile.key);
  }
  return ApplyResult::Applied;
}

void HistoryStore::release(const std::vector<int64_t>& entries) {
  for (const int64_t entryId : entries) {
    auto found = entries_.find(entryId);
    if (found == entries_.end()) {
      continue;
    }
    for (TileHistory& tile : found->second.tiles) {
      for (Payload* payload : {&tile.before, &tile.after}) {
        ramBytes_ -= sizeOf(payload->compressed);
        if (payload->disk) {
          diskBytes_ -= payload->disk->length;
        }
      }
    }
    entries_.erase(found);
  }
}

void HistoryStore::clear() {
  pendingCaptures_.clear();
  entries_.clear();
  ramBytes_ = 0;
  diskBytes_ = 0;
  spillingBytes_ = 0;
  ++generation_;
  ioPool_.post([file = spillFile_] { file->truncate(); });
}

void HistoryStore::maintain() {
  pollCaptures();
  spillOverBudget();
}

void HistoryStore::spillOverBudget() {
  if (ramBytes_ - spillingBytes_ <= ramBudgetBytes_) {
    return;
  }
  Entry* oldest = nullptr;
  int64_t oldestId = 0;
  for (auto& [entryId, entry] : entries_) {
    if (entry.spilling || entry.applying || !entry.readyReported) {
      continue;
    }
    const bool hasRamPayload = std::any_of(entry.tiles.begin(), entry.tiles.end(),
                                           [](const TileHistory& tile) {
                                             return tile.before.compressed || tile.after.compressed;
                                           });
    if (hasRamPayload && (oldest == nullptr || entry.sequence < oldest->sequence)) {
      oldest = &entry;
      oldestId = entryId;
    }
  }
  if (oldest == nullptr) {
    return;
  }
  oldest->spilling = true;
  struct Blob {
    size_t tileIndex;
    bool before;
    std::shared_ptr<const std::vector<uint8_t>> compressed;
  };
  std::vector<Blob> blobs;
  for (size_t index = 0; index < oldest->tiles.size(); ++index) {
    for (const bool before : {true, false}) {
      Payload& payload = payloadOf(oldest->tiles[index], before);
      if (!payload.compressed) {
        continue;
      }
      if (payload.disk) {
        ramBytes_ -= sizeOf(payload.compressed);
        payload.compressed.reset();
        continue;
      }
      blobs.push_back(Blob{index, before, payload.compressed});
    }
  }
  int64_t blobBytes = 0;
  for (const Blob& blob : blobs) {
    blobBytes += sizeOf(blob.compressed);
  }
  spillingBytes_ += blobBytes;
  const uint64_t generation = generation_;
  ioPool_.post([this, post = postToRenderThread_, file = spillFile_, entryId = oldestId,
                generation, blobBytes, blobs = std::move(blobs)] {
    std::vector<std::optional<io::ScratchLocation>> locations;
    locations.reserve(blobs.size());
    for (const Blob& blob : blobs) {
      locations.push_back(file->append(*blob.compressed));
    }
    post([this, entryId, generation, blobBytes, blobs, locations = std::move(locations)] {
      if (generation != generation_) {
        return;
      }
      spillingBytes_ -= blobBytes;
      auto found = entries_.find(entryId);
      if (found == entries_.end()) {
        return;
      }
      Entry& entry = found->second;
      entry.spilling = false;
      for (size_t index = 0; index < blobs.size(); ++index) {
        if (!locations[index]) {
          NANQUIM_LOG_ERROR("History: spill write failed; the payload stays in RAM");
          continue;
        }
        Payload& payload = payloadOf(entry.tiles[blobs[index].tileIndex], blobs[index].before);
        payload.disk = locations[index];
        diskBytes_ += locations[index]->length;
        if (payload.compressed == blobs[index].compressed) {
          ramBytes_ -= sizeOf(payload.compressed);
          payload.compressed.reset();
        }
      }
    });
  });
}

HistoryStoreStats HistoryStore::stats() const {
  HistoryStoreStats stats;
  stats.entries = static_cast<int64_t>(entries_.size());
  stats.ramBytes = ramBytes_;
  stats.diskBytes = diskBytes_;
  stats.capturesInFlight = static_cast<int64_t>(pendingCaptures_.size());
  return stats;
}

}  // namespace nanquim::canvas
