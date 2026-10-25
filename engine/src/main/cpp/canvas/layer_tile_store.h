#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

#include "canvas/pixel_format.h"
#include "canvas/tile_coordinate.h"
#include "core/lru_tracker.h"
#include "core/worker_pool.h"
#include "gpu/rhi/rhi.h"
#include "io/document_tile.h"
#include "io/scratch_file.h"

namespace nanquim::canvas {

using LayerId = uint32_t;

inline gpu::rhi::Format textureFormat(PixelFormat format) {
  return format == PixelFormat::Rgba16fPremulLinear ? gpu::rhi::Format::Rgba16f
                                                    : gpu::rhi::Format::Rgba8;
}

struct LayerTileKey {
  LayerId layer = 0;
  TileCoordinate coordinate;

  friend bool operator==(const LayerTileKey& left, const LayerTileKey& right) {
    return left.layer == right.layer && left.coordinate == right.coordinate;
  }
};

struct LayerTileKeyHash {
  size_t operator()(const LayerTileKey& key) const {
    return TileCoordinateHash{}(key.coordinate) ^ (std::hash<uint32_t>{}(key.layer) * 0x9E3779B9U);
  }
};

struct AtlasSlot {
  uint32_t page = 0;
  uint32_t index = 0;
};

inline constexpr int32_t kAtlasPageSizePixels = 4096;
inline constexpr int32_t kAtlasSlotsPerRow = kAtlasPageSizePixels / kTileSizePixels;
inline constexpr int32_t kAtlasSlotsPerPage = kAtlasSlotsPerRow * kAtlasSlotsPerRow;

inline int32_t slotOriginX(const AtlasSlot& slot) {
  return static_cast<int32_t>(slot.index % kAtlasSlotsPerRow) * kTileSizePixels;
}
inline int32_t slotOriginY(const AtlasSlot& slot) {
  return static_cast<int32_t>(slot.index / kAtlasSlotsPerRow) * kTileSizePixels;
}

struct LayerTileStoreStats {
  int64_t tileCount = 0;
  int64_t gpuTiles = 0;
  int64_t gpuTileBytes = 0;
  int64_t atlasPages = 0;
  int64_t ramTiles = 0;
  int64_t ramBytes = 0;
  int64_t diskTiles = 0;
  int64_t fileTiles = 0;
  int64_t corruptFileTiles = 0;
  int64_t scratchFileBytes = 0;
  int64_t loadsInFlight = 0;
  int64_t readbacksInFlight = 0;
  int64_t spillsInFlight = 0;
  int64_t gpuBudgetBytes = 0;
  int64_t ramBudgetBytes = 0;
};

class LayerTileStore {
 public:
  using PostToRenderThread = std::function<void(std::function<void()>)>;
  using TileLoadedCallback = std::function<void(const LayerTileKey&)>;

  LayerTileStore(gpu::rhi::Device& device, core::WorkerPool& ioPool,
                 PostToRenderThread postToRenderThread, std::string scratchFilePath);
  LayerTileStore(const LayerTileStore&) = delete;
  LayerTileStore& operator=(const LayerTileStore&) = delete;
  ~LayerTileStore();

  void setBudgets(int64_t gpuBytes, int64_t ramBytes);

  void setPixelFormat(PixelFormat format);
  [[nodiscard]] PixelFormat pixelFormat() const { return format_; }
  [[nodiscard]] int64_t tileBytes() const { return tileBytes_; }
  [[nodiscard]] std::unique_ptr<gpu::rhi::Readback> readSlot(const AtlasSlot& slot);
  void setOnTileLoaded(TileLoadedCallback callback) { onTileLoaded_ = std::move(callback); }

  void beginFrame(int64_t frame) { frame_ = frame; }

  [[nodiscard]] bool hasTile(const LayerTileKey& key) const;

  enum class Availability { Absent, Resident, Loading };

  Availability ensureResident(const LayerTileKey& key);

  std::optional<AtlasSlot> slotForRead(const LayerTileKey& key);

  void markReadOnce(const LayerTileKey& key) { gpuLru_.demote(key); }

  std::optional<AtlasSlot> slotForWrite(const LayerTileKey& key);

  struct UpdateSlot {
    AtlasSlot slot;
    bool created = false;
  };

  std::optional<UpdateSlot> slotForUpdate(const LayerTileKey& key);

  [[nodiscard]] std::optional<uint64_t> version(const LayerTileKey& key) const;

  bool removeTileIfUnchanged(const LayerTileKey& key, uint64_t expectedVersion);

  void removeTile(const LayerTileKey& key);

  bool writeTile(const LayerTileKey& key, const uint8_t* pixels);

  [[nodiscard]] std::shared_ptr<const std::vector<uint8_t>> validCompressedCopy(
      const LayerTileKey& key) const;

  [[nodiscard]] std::vector<TileCoordinate> tilesOf(LayerId layer) const;

  [[nodiscard]] const gpu::rhi::Texture& pageTexture(uint32_t page) const { return *pages_[page]; }

  template <typename Visitor>
  void forEachTile(LayerId layer, Visitor&& visitor) const {
    auto found = tilesByLayer_.find(layer);
    if (found == tilesByLayer_.end()) {
      return;
    }
    for (const auto& [coordinate, record] : found->second) {
      visitor(coordinate);
    }
  }

  void removeLayer(LayerId layer);

  void setSurfaceFile(LayerId surface, std::string folder, io::DocumentTileFormat format);
  void addFileTile(const LayerTileKey& key);
  void markSavedToFile(const LayerTileKey& key, uint64_t version);
  [[nodiscard]] bool hasLayer(LayerId layer) const { return tilesByLayer_.count(layer) != 0; }

  struct SaveSource {
    uint64_t version = 0;
    bool fileUpToDate = false;
    std::shared_ptr<const std::vector<uint8_t>> compressed;
    std::optional<io::ScratchLocation> disk;
    std::optional<AtlasSlot> slot;
  };
  [[nodiscard]] std::optional<SaveSource> saveSource(const LayerTileKey& key) const;
  [[nodiscard]] const std::shared_ptr<io::ScratchFile>& scratchFile() const { return scratchFile_; }

  void clear();

  void maintain();

  [[nodiscard]] bool hasPendingWork() const { return !pendingReadbacks_.empty(); }

  [[nodiscard]] bool isOverGpuBudget() const;

  [[nodiscard]] LayerTileStoreStats stats() const;

 private:
  struct TileRecord {
    std::optional<AtlasSlot> slot;
    uint64_t version = 1;
    std::shared_ptr<const std::vector<uint8_t>> compressed;
    uint64_t compressedVersion = 0;
    std::optional<io::ScratchLocation> disk;
    uint64_t diskVersion = 0;
    uint64_t fileVersion = 0;
    bool loading = false;
    bool evicting = false;
    bool spilling = false;

    [[nodiscard]] bool hasValidRamCopy() const {
      return compressed != nullptr && compressedVersion == version;
    }
    [[nodiscard]] bool hasValidDiskCopy() const {
      return disk.has_value() && diskVersion == version;
    }
    [[nodiscard]] bool hasValidFileCopy() const { return fileVersion != 0 && fileVersion == version; }
  };

  struct PendingReadback {
    LayerTileKey key;
    uint64_t version = 0;
    std::unique_ptr<gpu::rhi::Readback> readback;
  };

  TileRecord* find(const LayerTileKey& key);
  std::optional<AtlasSlot> allocateSlot();
  void releaseGpuCopy(const LayerTileKey& key, TileRecord& record);
  void dropRamCopy(const LayerTileKey& key, TileRecord& record);
  void requestLoad(const LayerTileKey& key, TileRecord& record);
  void finishLoad(const LayerTileKey& key, uint64_t generation, uint64_t version,
                  std::vector<uint8_t> pixels, bool decoded);
  void uploadTile(const AtlasSlot& slot, const uint8_t* pixels);
  void startReadback(const LayerTileKey& key, TileRecord& record);
  void pollReadbacks();
  void finishEviction(const LayerTileKey& key, uint64_t generation, uint64_t version,
                      std::shared_ptr<const std::vector<uint8_t>> compressed);
  void enforceGpuBudget();
  void enforceRamBudget();
  void finishSpill(const LayerTileKey& key, uint64_t generation, uint64_t version,
                   std::optional<io::ScratchLocation> location);

  gpu::rhi::Device& device_;
  core::WorkerPool& ioPool_;
  PostToRenderThread postToRenderThread_;
  std::shared_ptr<io::ScratchFile> scratchFile_;
  TileLoadedCallback onTileLoaded_;

  std::unordered_map<LayerId, std::unordered_map<TileCoordinate, TileRecord, TileCoordinateHash>>
      tilesByLayer_;
  std::vector<std::unique_ptr<gpu::rhi::Texture>> pages_;
  std::vector<AtlasSlot> freeSlots_;
  struct SurfaceFile {
    std::string folder;
    io::DocumentTileFormat format = io::DocumentTileFormat::Rgba8;
  };
  std::unordered_map<LayerId, SurfaceFile> surfaceFiles_;
  core::LruTracker<LayerTileKey, LayerTileKeyHash> gpuLru_;
  core::LruTracker<LayerTileKey, LayerTileKeyHash> ramLru_;
  std::vector<PendingReadback> pendingReadbacks_;
  std::vector<std::unique_ptr<gpu::rhi::Texture>> stagingTextures_;
  size_t nextStagingTexture_ = 0;

  PixelFormat format_ = PixelFormat::Rgba8PremulSrgb;
  int64_t tileBytes_ = canvas::tileBytes(PixelFormat::Rgba8PremulSrgb);

  int64_t gpuBudgetBytes_ = 0;
  int64_t ramBudgetBytes_ = 0;
  int64_t frame_ = 0;
  uint64_t generation_ = 1;
  int64_t loadsInFlight_ = 0;
  int64_t spillsInFlight_ = 0;
  int64_t spillBytesInFlight_ = 0;
  int64_t validDiskCopies_ = 0;
  int64_t corruptFileTiles_ = 0;
};

}  // namespace nanquim::canvas
