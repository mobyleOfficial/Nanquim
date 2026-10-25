#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

#include "canvas/tile_coordinate.h"
#include "gpu/rhi/rhi.h"

namespace nanquim::canvas {

class StrokeBuffer {
 public:
  struct Slot {
    uint32_t page = 0;
    std::array<int32_t, 2> origin{};
  };

  void initialize(gpu::rhi::Device& device);

  std::optional<Slot> acquire(TileCoordinate coordinate);
  [[nodiscard]] std::optional<Slot> find(TileCoordinate coordinate) const;

  void clear();
  void release();

  [[nodiscard]] const gpu::rhi::Texture& pageTexture(uint32_t page) const { return *pages_[page]; }
  [[nodiscard]] bool isHalfFloat() const { return format_ == gpu::rhi::Format::Rgba16f; }
  [[nodiscard]] const std::vector<TileCoordinate>& tiles() const { return tileOrder_; }
  [[nodiscard]] int64_t bytes() const;

  static constexpr int32_t kPageSizePixels = 2048;
  static constexpr int32_t kSlotsPerRow = kPageSizePixels / kTileSizePixels;
  static constexpr int32_t kSlotsPerPage = kSlotsPerRow * kSlotsPerRow;

 private:
  bool addPage();
  void clearPage(uint32_t page);

  gpu::rhi::Device* device_ = nullptr;
  gpu::rhi::Format format_ = gpu::rhi::Format::Rgba8;
  std::vector<std::unique_ptr<gpu::rhi::Texture>> pages_;
  std::vector<Slot> freeSlots_;
  std::unordered_map<TileCoordinate, Slot, TileCoordinateHash> tiles_;
  std::vector<TileCoordinate> tileOrder_;
};

}  // namespace nanquim::canvas
