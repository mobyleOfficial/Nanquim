#include "canvas/selection_overlay.h"

#include <algorithm>
#include <array>
#include <cstddef>

#include "embedded_shaders.h"

namespace nanquim::canvas {
namespace {

struct SelectionReduceParameters {
  std::array<int32_t, 2> slotOrigin;
  std::array<int32_t, 2> strokeSlotOrigin;
  std::array<int32_t, 2> regionOrigin;
  float strokeOpacity;
  float strokeLuminance;
  int32_t reduction;
  int32_t hasTile;
  int32_t hasStrokeTile;
  int32_t padding;
};
static_assert(offsetof(SelectionReduceParameters, strokeOpacity) == 24);
static_assert(offsetof(SelectionReduceParameters, hasStrokeTile) == 40);
static_assert(sizeof(SelectionReduceParameters) == 48);

constexpr uint32_t kTilePageBinding = 1;
constexpr uint32_t kStrokePageBinding = 2;

}  // namespace

bool SelectionOverlay::initialize(gpu::rhi::Device& device, std::string& errorMessage) {
  device_ = &device;
  device.registerProgram("selection_reduce", shaders::fullscreen_vertex,
                         shaders::selection_reduce_fragment);
  gpu::rhi::PipelineDescription description;
  description.program = "selection_reduce";
  description.targetFormat = gpu::rhi::Format::R8;
  pipeline_ = device.pipeline(description, errorMessage);
  return pipeline_ != nullptr;
}

void SelectionOverlay::configure(int32_t canvasWidthPixels, int32_t canvasHeightPixels) {
  release();
  canvasTiles_ = tileRangeForCanvas(canvasWidthPixels, canvasHeightPixels);
  reduction_ = 1;
  while (std::max(canvasWidthPixels, canvasHeightPixels) > kMaximumSide * reduction_ &&
         reduction_ < kTileSizePixels) {
    reduction_ *= 2;
  }
  textureWidth_ = (canvasWidthPixels + reduction_ - 1) / reduction_;
  textureHeight_ = (canvasHeightPixels + reduction_ - 1) / reduction_;
  dirty_.assign(static_cast<size_t>(canvasTiles_.count()), 0);
  markAllDirty();
}

void SelectionOverlay::release() {
  texture_.reset();
  dirty_.clear();
  dirtyCount_ = 0;
  textureWidth_ = 0;
  textureHeight_ = 0;
  canvasTiles_ = TileRange{};
}

void SelectionOverlay::setSurface(std::optional<LayerId> surface) {
  if (surface == surface_) {
    return;
  }
  surface_ = surface;
  if (surface_) {
    markAllDirty();
  } else {
    texture_.reset();
  }
}

void SelectionOverlay::markTileChanged(const LayerTileKey& key) {
  if (!surface_ || key.layer != *surface_ || !canvasTiles_.contains(key.coordinate)) {
    return;
  }
  const auto index = static_cast<size_t>((key.coordinate.row - canvasTiles_.firstRow) *
                                             (canvasTiles_.endColumn - canvasTiles_.firstColumn) +
                                         (key.coordinate.column - canvasTiles_.firstColumn));
  if (dirty_[index] == 0) {
    dirty_[index] = 1;
    ++dirtyCount_;
  }
}

void SelectionOverlay::markAllDirty() {
  built_ = false;
  std::fill(dirty_.begin(), dirty_.end(), 1);
  dirtyCount_ = static_cast<int64_t>(dirty_.size());
}

bool SelectionOverlay::update(LayerTileStore& tileStore, int32_t maxTiles) {
  built_ = built_ || (surface_.has_value() && dirtyCount_ == 0 && texture_ != nullptr);
  if (!surface_ || dirtyCount_ == 0 || textureWidth_ <= 0) {
    return false;
  }
  if (!texture_) {
    gpu::rhi::TextureDescription description;
    description.format = gpu::rhi::Format::R8;
    description.width = textureWidth_;
    description.height = textureHeight_;
    description.usage = gpu::rhi::kUsageSampled | gpu::rhi::kUsageRenderTarget;
    texture_ = device_->createTexture(description);
  }
  gpu::rhi::CommandContext& commands = device_->commands();
  commands.beginPass(gpu::rhi::RenderTarget::of(*texture_), gpu::rhi::LoadAction::Load);
  commands.bindPipeline(*pipeline_);
  const int32_t regionSide = kTileSizePixels / reduction_;
  int32_t done = 0;
  size_t index = 0;
  canvasTiles_.forEach([&](TileCoordinate coordinate) {
    const size_t current = index++;
    if (dirty_[current] == 0 || done >= maxTiles) {
      return;
    }
    const LayerTileKey key{*surface_, coordinate};
    const auto availability = tileStore.ensureResident(key);
    if (availability == LayerTileStore::Availability::Loading) {
      return;
    }
    const int32_t x = coordinate.column * regionSide;
    const int32_t y = coordinate.row * regionSide;
    const gpu::rhi::Rect region{x, y, std::min(regionSide, textureWidth_ - x),
                                std::min(regionSide, textureHeight_ - y)};
    commands.setViewport(gpu::rhi::Rect{x, y, regionSide, regionSide});
    commands.setScissor(region);
    const auto slot = tileStore.slotForRead(key);
    std::optional<std::pair<const StrokeBuffer*, StrokeBuffer::Slot>> stroke;
    if (strokePreview_) {
      for (const StrokeBuffer* buffer : {strokePreview_->provisional, strokePreview_->buffer}) {
        if (buffer != nullptr && !stroke) {
          if (const auto strokeSlot = buffer->find(coordinate)) {
            stroke = std::make_pair(buffer, *strokeSlot);
          }
        }
      }
    }
    if (!slot && !stroke) {
      commands.clear({0, 0, 0, 0}, region);
    } else {
      const gpu::rhi::Texture* tilePage = nullptr;
      const gpu::rhi::Texture* strokePage = nullptr;
      SelectionReduceParameters parameters{};
      parameters.regionOrigin = {x, y};
      parameters.reduction = reduction_;
      if (slot) {
        tilePage = &tileStore.pageTexture(slot->page);
        parameters.hasTile = 1;
        parameters.slotOrigin = {slotOriginX(*slot), slotOriginY(*slot)};
      }
      if (stroke) {
        strokePage = &stroke->first->pageTexture(stroke->second.page);
        parameters.hasStrokeTile = 1;
        parameters.strokeSlotOrigin = {stroke->second.origin[0], stroke->second.origin[1]};
        parameters.strokeOpacity = strokePreview_->opacity;
        parameters.strokeLuminance = strokePreview_->luminance;
      }
      commands.bindTexture(kTilePageBinding, tilePage);
      commands.bindTexture(kStrokePageBinding, strokePage);
      commands.setUniforms(parameters);
      commands.draw(3);
    }
    dirty_[current] = 0;
    --dirtyCount_;
    ++done;
  });
  commands.endPass();
  built_ = built_ || dirtyCount_ == 0;
  return dirtyCount_ > 0;
}

}  // namespace nanquim::canvas
