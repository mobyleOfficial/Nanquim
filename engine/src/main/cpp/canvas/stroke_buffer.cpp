#include "canvas/stroke_buffer.h"

#include "core/log.h"

namespace nanquim::canvas {

void StrokeBuffer::initialize(gpu::rhi::Device& device) {
  device_ = &device;
  const bool halfFloatRenderable = device.isRenderable(gpu::rhi::Format::Rgba16f);
  format_ = halfFloatRenderable ? gpu::rhi::Format::Rgba16f : gpu::rhi::Format::Rgba8;
  if (!halfFloatRenderable) {
    NANQUIM_LOG_INFO("Half-float color buffers unavailable; the stroke buffer uses RGBA8");
  }
  if (!addPage()) {
    NANQUIM_LOG_ERROR("Stroke buffer: initial page allocation failed");
  }
}

bool StrokeBuffer::addPage() {
  gpu::rhi::TextureDescription description;
  description.format = format_;
  description.width = kPageSizePixels;
  description.height = kPageSizePixels;
  description.usage = gpu::rhi::kUsageSampled | gpu::rhi::kUsageRenderTarget |
                      gpu::rhi::kUsageUpload | gpu::rhi::kUsageReadback;
  std::unique_ptr<gpu::rhi::Texture> page = device_->createTexture(description);
  if (!page) {
    NANQUIM_LOG_ERROR("Stroke buffer page allocation failed (out of GPU memory)");
    return false;
  }
  const auto pageIndex = static_cast<uint32_t>(pages_.size());
  pages_.push_back(std::move(page));
  clearPage(pageIndex);
  for (int32_t index = kSlotsPerPage - 1; index >= 0; --index) {
    freeSlots_.push_back(
        Slot{pageIndex,
             {(index % kSlotsPerRow) * kTileSizePixels, (index / kSlotsPerRow) * kTileSizePixels}});
  }
  return true;
}

void StrokeBuffer::clearPage(uint32_t page) {
  gpu::rhi::CommandContext& commands = device_->commands();
  commands.beginPass(gpu::rhi::RenderTarget::of(pageTexture(page)), gpu::rhi::LoadAction::Clear);
  commands.endPass();
}

std::optional<StrokeBuffer::Slot> StrokeBuffer::find(TileCoordinate coordinate) const {
  auto found = tiles_.find(coordinate);
  return found == tiles_.end() ? std::nullopt : std::optional<Slot>(found->second);
}

std::optional<StrokeBuffer::Slot> StrokeBuffer::acquire(TileCoordinate coordinate) {
  if (auto existing = find(coordinate)) {
    return existing;
  }
  if (freeSlots_.empty() && !addPage()) {
    return std::nullopt;
  }
  const Slot slot = freeSlots_.back();
  freeSlots_.pop_back();
  tiles_.emplace(coordinate, slot);
  tileOrder_.push_back(coordinate);
  return slot;
}

void StrokeBuffer::clear() {
  if (tiles_.empty()) {
    return;
  }
  std::vector<bool> used(pages_.size(), false);
  for (const auto& [coordinate, slot] : tiles_) {
    used[slot.page] = true;
  }
  for (uint32_t page = 0; page < pages_.size(); ++page) {
    if (used[page]) {
      clearPage(page);
    }
  }
  tiles_.clear();
  tileOrder_.clear();
  freeSlots_.clear();
  for (auto page = static_cast<uint32_t>(pages_.size()); page-- > 0;) {
    for (int32_t index = kSlotsPerPage - 1; index >= 0; --index) {
      freeSlots_.push_back(Slot{
          page,
          {(index % kSlotsPerRow) * kTileSizePixels, (index / kSlotsPerRow) * kTileSizePixels}});
    }
  }
}

void StrokeBuffer::release() {
  tiles_.clear();
  tileOrder_.clear();
  freeSlots_.clear();
  pages_.clear();
}

int64_t StrokeBuffer::bytes() const {
  const int64_t bytesPerPixel = isHalfFloat() ? 8 : 4;
  return static_cast<int64_t>(pages_.size()) * kPageSizePixels * kPageSizePixels * bytesPerPixel;
}

}  // namespace nanquim::canvas
