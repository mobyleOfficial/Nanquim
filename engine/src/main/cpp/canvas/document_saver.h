#pragma once

#include <chrono>
#include <cstdint>
#include <deque>
#include <functional>
#include <memory>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "canvas/layer_tile_store.h"
#include "core/worker_pool.h"
#include "gpu/rhi/rhi.h"
#include "io/document_tile.h"

namespace nanquim::io {
class DocumentTransaction;
}

namespace nanquim::canvas {

struct SaveSurface {
  LayerId id = 0;
  std::string folder;
  io::DocumentTileFormat format = io::DocumentTileFormat::Rgba8;
};

struct SaveFile {
  std::string path;
  std::vector<uint8_t> bytes;
};

struct SaveRequest {
  int32_t requestId = 0;
  std::vector<SaveSurface> surfaces;
  std::vector<SaveFile> files;
};

struct SaveResult {
  int32_t requestId = 0;
  bool ok = false;
  std::string error;
  int64_t tilesWritten = 0;
  int64_t tilesRemoved = 0;
  double milliseconds = 0;
};

class DocumentSaver {
 public:
  using PostToRenderThread = std::function<void(std::function<void()>)>;

  DocumentSaver(LayerTileStore& tileStore, core::WorkerPool& ioPool,
                PostToRenderThread postToRenderThread);
  DocumentSaver(const DocumentSaver&) = delete;
  DocumentSaver& operator=(const DocumentSaver&) = delete;
  ~DocumentSaver();

  void resetForDocument(std::string folder, int32_t canvasWidth, int32_t canvasHeight);
  void setCanvasSize(int32_t canvasWidth, int32_t canvasHeight) {
    canvasWidth_ = canvasWidth;
    canvasHeight_ = canvasHeight;
  }
  void addSavedSurface(const SaveSurface& surface, const std::vector<TileCoordinate>& tilesOnDisk);
  void addOrphanFolder(std::string relativeFolder);

  void save(SaveRequest request, std::function<void(const SaveResult&)> done);

  void poll();

  [[nodiscard]] bool blocksTileWrites() const;
  [[nodiscard]] bool hasPendingWork() const { return active_ != nullptr || !queue_.empty(); }
  [[nodiscard]] const std::string& folder() const { return folder_; }

 private:
  struct SavedSurface {
    std::string folder;
    io::DocumentTileFormat format = io::DocumentTileFormat::Rgba8;
    std::unordered_set<TileCoordinate, TileCoordinateHash> tilesOnDisk;
  };

  struct GpuCapture {
    LayerTileKey key;
    uint64_t version = 0;
  };

  struct PendingReadback {
    LayerTileKey key;
    uint64_t version = 0;
    std::unique_ptr<gpu::rhi::Readback> readback;
  };

  enum class TileOutcome { Written, Implicit, Failed };

  struct TileJob {
    uint64_t generation = 0;
    LayerTileKey key;
    uint64_t version = 0;
    std::shared_ptr<io::DocumentTransaction> transaction;
    std::string surfaceFolder;
    io::DocumentTileFormat format = io::DocumentTileFormat::Rgba8;
    io::TilePlacement placement;
    PixelFormat engineFormat = PixelFormat::Rgba8PremulSrgb;
  };

  struct ActiveSave {
    SaveRequest request;
    std::function<void(const SaveResult&)> done;
    std::chrono::steady_clock::time_point start;
    std::shared_ptr<io::DocumentTransaction> transaction;
    std::deque<GpuCapture> gpuCaptures;
    std::vector<PendingReadback> readbacks;
    int64_t tileJobsInFlight = 0;
    bool committing = false;
    bool failed = false;
    std::unordered_map<LayerId, std::unordered_set<TileCoordinate, TileCoordinateHash>> kept;
    std::vector<std::pair<LayerTileKey, uint64_t>> written;
    std::unordered_map<LayerId, std::vector<TileCoordinate>> removedTiles;
    std::vector<std::string> removedFolders;
    std::vector<LayerId> forgottenSurfaces;
  };

  void start();
  void issueReadbacks();
  void pollReadbacks();
  [[nodiscard]] TileJob jobFor(const LayerTileKey& key, uint64_t version) const;
  static TileOutcome stageTile(const TileJob& job, const std::vector<uint8_t>& pixels);
  void finishTile(uint64_t generation, LayerTileKey key, uint64_t version, TileOutcome outcome);
  void commitIfReady();
  void finishSave(uint64_t generation, bool ok, std::string error);
  [[nodiscard]] const SavedSurface* savedSurfaceFor(LayerId id) const;

  LayerTileStore& tileStore_;
  core::WorkerPool& ioPool_;
  PostToRenderThread postToRenderThread_;

  std::string folder_;
  int32_t canvasWidth_ = 0;
  int32_t canvasHeight_ = 0;
  uint64_t generation_ = 1;
  std::unordered_map<LayerId, SavedSurface> savedSurfaces_;
  std::vector<std::string> orphanFolders_;
  std::deque<std::pair<SaveRequest, std::function<void(const SaveResult&)>>> queue_;
  std::unique_ptr<ActiveSave> active_;
};

}  // namespace nanquim::canvas
