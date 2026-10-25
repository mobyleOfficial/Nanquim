#pragma once

#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "canvas/layer_tile_store.h"
#include "core/worker_pool.h"
#include "gpu/rhi/rhi.h"
#include "io/scratch_file.h"

namespace nanquim::canvas {

struct HistoryStoreStats {
  int64_t entries = 0;
  int64_t ramBytes = 0;
  int64_t diskBytes = 0;
  int64_t capturesInFlight = 0;
};

class HistoryStore {
 public:
  using PostToRenderThread = std::function<void(std::function<void()>)>;
  using EntryReadyCallback = std::function<void(int64_t entry, int64_t bytes)>;

  HistoryStore(LayerTileStore& tileStore, core::WorkerPool& ioPool,
               PostToRenderThread postToRenderThread, std::string spillFilePath);
  HistoryStore(const HistoryStore&) = delete;
  HistoryStore& operator=(const HistoryStore&) = delete;
  ~HistoryStore();

  void setRamBudget(int64_t bytes) { ramBudgetBytes_ = bytes; }
  void setOnEntryReady(EntryReadyCallback callback) { onEntryReady_ = std::move(callback); }

  void captureBefore(int64_t entry, const LayerTileKey& key);
  void captureAfter(int64_t entry, const LayerTileKey& key);
  [[nodiscard]] std::vector<LayerTileKey> capturedTiles(int64_t entry) const;
  void finishEntry(int64_t entry);

  [[nodiscard]] bool hasEntry(int64_t entry) const { return entries_.count(entry) != 0; }

  enum class ApplyResult { Applied, NotReady, Unknown };

  ApplyResult apply(int64_t entry, bool before,
                    const std::function<void(const LayerTileKey&)>& onTileWritten);

  void release(const std::vector<int64_t>& entries);
  void clear();

  void maintain();
  [[nodiscard]] bool hasPendingWork() const { return !pendingCaptures_.empty(); }
  [[nodiscard]] bool isBackedUp() const {
    return pendingCaptures_.size() >= kMaximumCapturesInFlight;
  }
  static constexpr size_t kMaximumCapturesInFlight = 256;

  [[nodiscard]] HistoryStoreStats stats() const;

 private:
  struct Payload {
    bool captured = false;
    bool empty = false;
    std::shared_ptr<const std::vector<uint8_t>> compressed;
    std::optional<io::ScratchLocation> disk;
    bool loading = false;
  };

  struct TileHistory {
    LayerTileKey key;
    Payload before;
    Payload after;
  };

  struct Entry {
    uint64_t sequence = 0;
    std::vector<TileHistory> tiles;
    std::unordered_map<LayerTileKey, size_t, LayerTileKeyHash> indexByKey;
    int32_t pendingPayloads = 0;
    bool finished = false;
    bool readyReported = false;
    bool spilling = false;
    bool applying = false;
    int64_t bytes = 0;
  };

  struct PendingCapture {
    int64_t entry = 0;
    size_t tileIndex = 0;
    bool before = true;
    std::unique_ptr<gpu::rhi::Readback> readback;
  };

  Entry& entryFor(int64_t entry);
  void capture(int64_t entryId, Entry& entry, size_t tileIndex, bool before);
  void storeCompressed(int64_t entryId, uint64_t generation, size_t tileIndex, bool before,
                       std::shared_ptr<const std::vector<uint8_t>> compressed);
  void payloadDone(int64_t entryId, Entry& entry);
  void pollCaptures();
  void spillOverBudget();
  [[nodiscard]] static Payload& payloadOf(TileHistory& tile, bool before) {
    return before ? tile.before : tile.after;
  }

  LayerTileStore& tileStore_;
  core::WorkerPool& ioPool_;
  PostToRenderThread postToRenderThread_;
  std::shared_ptr<io::ScratchFile> spillFile_;
  EntryReadyCallback onEntryReady_;

  std::map<int64_t, Entry> entries_;
  std::vector<PendingCapture> pendingCaptures_;
  uint64_t nextSequence_ = 1;
  uint64_t generation_ = 1;
  int64_t ramBudgetBytes_ = 256LL * 1024 * 1024;
  int64_t ramBytes_ = 0;
  int64_t spillingBytes_ = 0;
  int64_t diskBytes_ = 0;
};

}  // namespace nanquim::canvas
