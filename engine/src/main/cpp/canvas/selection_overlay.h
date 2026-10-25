#pragma once

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "canvas/layer_tile_store.h"
#include "canvas/stroke_buffer.h"
#include "canvas/tile_coordinate.h"
#include "gpu/rhi/rhi.h"

namespace nanquim::canvas {

class SelectionOverlay {
 public:
  bool initialize(gpu::rhi::Device& device, std::string& errorMessage);
  void configure(int32_t canvasWidthPixels, int32_t canvasHeightPixels);
  void release();

  void setSurface(std::optional<LayerId> surface);
  [[nodiscard]] std::optional<LayerId> surface() const { return surface_; }
  void markTileChanged(const LayerTileKey& key);

  struct StrokePreview {
    const StrokeBuffer* buffer = nullptr;
    const StrokeBuffer* provisional = nullptr;
    float opacity = 1;
    float luminance = 1;
  };
  void setStrokePreview(std::optional<StrokePreview> preview) { strokePreview_ = preview; }

  bool update(LayerTileStore& tileStore, int32_t maxTiles);

  [[nodiscard]] bool isReady() const { return surface_.has_value() && built_; }
  [[nodiscard]] const gpu::rhi::Texture* texture() const { return texture_.get(); }
  [[nodiscard]] int32_t reduction() const { return reduction_; }
  [[nodiscard]] int32_t textureWidth() const { return textureWidth_; }
  [[nodiscard]] int32_t textureHeight() const { return textureHeight_; }

  static constexpr int32_t kMaximumSide = 4096;

 private:
  void markAllDirty();

  gpu::rhi::Device* device_ = nullptr;
  const gpu::rhi::Pipeline* pipeline_ = nullptr;
  std::unique_ptr<gpu::rhi::Texture> texture_;
  std::optional<LayerId> surface_;
  std::optional<StrokePreview> strokePreview_;
  TileRange canvasTiles_;
  int32_t reduction_ = 1;
  int32_t textureWidth_ = 0;
  int32_t textureHeight_ = 0;
  std::vector<uint8_t> dirty_;
  int64_t dirtyCount_ = 0;
  bool built_ = false;
};

}  // namespace nanquim::canvas
