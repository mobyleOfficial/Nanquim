#include "engine_context.h"

#include <android/trace.h>
#include <sys/stat.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <future>
#include <limits>
#include <random>
#include <set>
#include <unordered_map>
#include <unordered_set>
#include <utility>

#include "canvas/color_conversion.h"
#include "canvas/level_selection.h"
#include "core/half_float.h"
#include "core/json_writer.h"
#include "core/log.h"
#include "embedded_shaders.h"
#include "engine_programs.h"
#include "gpu/gles/gles_device.h"
#include "gpu/gles/gles_info.h"
#include "gpu/gles/gles_presenter.h"
#include "gpu/vulkan/vulkan_device.h"
#include "gpu/vulkan/vulkan_presenter.h"
#include "io/document_tile.h"
#include "io/document_transaction.h"
#include "io/file_system.h"
#include "stroke/brush_parameters.h"
#include "stroke/particles.h"
#include "stroke/random.h"
#include "stroke/stroke_effects.h"
#include "stroke/symmetry.h"
#include "text/layout.h"
#include "text/text_raster.h"

namespace nanquim {

namespace {

constexpr int kRequiredGlesMajorVersion = 3;
constexpr int kRequiredGlesMinorVersion = 1;
constexpr float kLatencyTestDotRadiusPixels = 24.0F;
constexpr auto kCompositeBudgetPerFrame = std::chrono::microseconds(6000);
constexpr double kGpuShareOfRemainingBudget = 0.85;
constexpr float kIdleGapMilliseconds = 250.0F;
constexpr auto kBackgroundPollInterval = std::chrono::milliseconds(4);
constexpr size_t kMaximumSingleBufferFramesInFlight = 2;
constexpr uint64_t kSingleBufferFrameWaitNanoseconds = 100'000'000;
constexpr auto kBackBufferPresentPollInterval = std::chrono::milliseconds(2);
constexpr auto kAntsStepInterval = std::chrono::milliseconds(100);
constexpr auto kAwakePokeInterval = std::chrono::milliseconds(33);
constexpr float kAntsStepPixels = 3.0F;
constexpr int32_t kSelectionOverlayTilesPerFrame = 64;
constexpr canvas::LayerId kFeatherScratchSurface = 0xFFFFFFF0U;
constexpr auto kBackBufferPresentTimeout = std::chrono::milliseconds(100);
constexpr auto kBackBufferPresentFallbackDelay = std::chrono::milliseconds(50);
constexpr auto kSingleBufferPresentTimeout = std::chrono::milliseconds(100);
constexpr int64_t kMinimumLayerTileBudget = 32;
constexpr int64_t kMaximumLayerTileBudget = 8192;
constexpr int64_t kLayerTileBudgetStep = 32;
constexpr float kLateFrameFactor = 1.3F;
constexpr size_t kExpectedIntervalRefreshFrames = 30;

double percentile(std::vector<float> values, double fraction) {
  if (values.empty()) {
    return 0;
  }
  std::sort(values.begin(), values.end());
  const auto count = static_cast<double>(values.size());
  const auto index = static_cast<size_t>(std::min(count - 1, std::floor(fraction * count)));
  return values[index];
}

}  // namespace

EngineContext::EngineContext(EngineConfig config, std::unique_ptr<EngineCallbacks> callbacks)
    : config_(std::move(config)),
      callbacks_(std::move(callbacks)),
      renderThread_([this] { runRenderLoop(); }) {}

EngineContext::~EngineContext() {
  try {
    tasks_.close();
    renderThread_.join();
  } catch (const std::exception& exception) {
    NANQUIM_LOG_ERROR("Render thread shutdown failed: %s", exception.what());
  }
}

void EngineContext::post(std::function<void()> task) {
  if (!tasks_.push(std::move(task))) {
    NANQUIM_LOG_ERROR("Task posted after the engine was closed; dropped");
  }
}

void EngineContext::attachWindow(ANativeWindow* window, int32_t widthPixels, int32_t heightPixels) {
  post([this, window, widthPixels, heightPixels] {
    surfaceWidthPixels_ = widthPixels;
    surfaceHeightPixels_ = heightPixels;
    frameRequested_ = true;
    if (window == window_) {
      ANativeWindow_release(window);
      return;
    }
    releaseWindow();
    window_ = window;
    if (!eglReady_) {
      return;
    }
    std::string errorMessage;
    if (!presenter_->attachWindow(window_, errorMessage)) {
      callbacks_->onError(EngineErrorCode::WindowSurfaceFailed, errorMessage, true);
    }
  });
}

void EngineContext::detachWindowAndWait() {
  std::promise<void> released;
  std::future<void> releasedFuture = released.get_future();
  post([this, &released] {
    releaseWindow();
    released.set_value();
  });
  releasedFuture.wait();
}

void EngineContext::releaseWindow() {
  if (window_ == nullptr) {
    return;
  }
  presenter_->detachWindow();
  ANativeWindow_release(window_);
  window_ = nullptr;
  resetWindowSurfaceState();
}

void EngineContext::resetWindowSurfaceState() {
  lastSwapTime_.reset();
  lastBackBufferFrameId_.reset();
  lastBackBufferSwapTime_.reset();
  singleBufferProbe_.reset();
}

void EngineContext::setClearColor(float red, float green, float blue, float alpha) {
  post([this, red, green, blue, alpha] {
    clearColor_ = {red, green, blue, alpha};
    frameRequested_ = true;
  });
}

void EngineContext::setLatencyTestPoint(float xPixels, float yPixels, bool visible) {
  post([this, xPixels, yPixels, visible] {
    latencyTestPoint_ = LatencyTestPoint{xPixels, yPixels, visible};
    frameRequested_ = true;
  });
}

void EngineContext::requestGpuInfo(int32_t requestId) {
  post([this, requestId] {
    if (!eglReady_) {
      callbacks_->onError(EngineErrorCode::EglInitializationFailed,
                          "GPU information unavailable: EGL is not initialized", true);
      return;
    }
    callbacks_->onGpuInfo(requestId, device_->infoJson());
  });
}

void EngineContext::openDocument(int32_t widthPixels, int32_t heightPixels,
                                 canvas::LayerTree tree) {
  post([this, widthPixels, heightPixels, tree = std::move(tree)]() mutable {
    openDocumentNow(widthPixels, heightPixels, std::move(tree));
  });
}

void EngineContext::openTestDocument(int32_t widthPixels, int32_t heightPixels, int32_t layerCount,
                                     std::array<float, 4> backgroundColor) {
  canvas::LayerTree tree;
  tree.background = backgroundColor;
  for (int32_t index = 1; index <= layerCount; ++index) {
    canvas::LayerTreeNode layer;
    layer.id = static_cast<canvas::LayerId>(index);
    tree.layers.push_back(layer);
  }
  if (layerCount > 0) {
    tree.activeLayer = static_cast<canvas::LayerId>(layerCount);
  }
  openDocument(widthPixels, heightPixels, std::move(tree));
}

bool EngineContext::openDocumentNow(int32_t widthPixels, int32_t heightPixels,
                                    canvas::LayerTree tree) {
  if (!compositor_) {
    return false;
  }
  textLayerShapes_.clear();
  abortStroke();
  deferredStrokeTasks_.clear();
  operations_.clear();
  history_->clear();
  tileStore_->clear();
  pendingFills_.clear();
  pendingSurfaceWrites_.clear();
  saver_->resetForDocument({}, 0, 0);
  document_.reset();
  if (tree.pixelFormat == canvas::PixelFormat::Rgba16fPremulLinear &&
      !device_->isRenderable(gpu::rhi::Format::Rgba16f)) {
    callbacks_->onError(EngineErrorCode::DocumentOpenFailed,
                        "16-bit documents need half-float render targets", true);
    frameRequested_ = true;
    return false;
  }
  if (tileStore_->pixelFormat() != tree.pixelFormat) {
    operationScratch_.reset();
    cloneSnapshot_.reset();
    cloneSnapshotSize_ = {};
  }
  tileStore_->setPixelFormat(tree.pixelFormat);
  std::string errorMessage;
  if (!compositor_->configure(widthPixels, heightPixels, errorMessage)) {
    callbacks_->onError(EngineErrorCode::DocumentOpenFailed, errorMessage, true);
    frameRequested_ = true;
    return false;
  }
  selectionOverlay_->setSurface(std::nullopt);
  selectionOverlay_->configure(widthPixels, heightPixels);
  Document document;
  document.widthPixels = widthPixels;
  document.heightPixels = heightPixels;
  document.tree = std::move(tree);
  compositor_->setLayerTree(document.tree);
  compositor_->setActiveLayer(document.tree.activeLayer);
  const size_t layerCount = document.tree.rasterLayers().size();
  document_ = std::move(document);
  documentShownReported_ = false;
  documentId_ = "test-document-" + std::to_string(widthPixels) + "x" +
                std::to_string(heightPixels) + "x" + std::to_string(layerCount);
  strokeIndex_ = 0;
  applyBudgets();
  frameRequested_ = true;
  return true;
}

void EngineContext::openDocumentFolder(int32_t widthPixels, int32_t heightPixels,
                                       canvas::LayerTree tree, std::string documentId,
                                       std::string folder,
                                       std::vector<canvas::SaveSurface> surfaces) {
  post([this, widthPixels, heightPixels, tree = std::move(tree), documentId = std::move(documentId),
        folder = std::move(folder), surfaces = std::move(surfaces)]() mutable {
    openDocumentFolderNow(widthPixels, heightPixels, std::move(tree), documentId, folder,
                          surfaces);
  });
}

bool EngineContext::openDocumentFolderNow(int32_t widthPixels, int32_t heightPixels,
                                          canvas::LayerTree tree, const std::string& documentId,
                                          const std::string& folder,
                                          const std::vector<canvas::SaveSurface>& surfaces) {
  std::string error;
  if (!io::recoverDocumentFolder(folder, error)) {
    callbacks_->onError(EngineErrorCode::DocumentOpenFailed, error, true);
    return false;
  }
  if (!openDocumentNow(widthPixels, heightPixels, std::move(tree))) {
    return false;
  }
  documentId_ = documentId;
  saver_->resetForDocument(folder, widthPixels, heightPixels);
  const canvas::TileRange canvasTiles = compositor_->canvasTiles();
  std::unordered_set<std::string> surfaceFolders;
  for (canvas::SaveSurface surface : surfaces) {
    surface.format = layerFileFormat(surface.format);
    surfaceFolders.insert(surface.folder);
    const std::string surfaceFolder = io::joinPath(folder, surface.folder);
    tileStore_->setSurfaceFile(surface.id, surfaceFolder, surface.format);
    bool rebuilt = false;
    const io::SurfaceIndex index = io::readSurfaceIndex(surfaceFolder, rebuilt);
    std::vector<canvas::TileCoordinate> tiles;
    for (const canvas::TileCoordinate coordinate : index.tiles) {
      if (canvasTiles.contains(coordinate)) {
        tileStore_->addFileTile({surface.id, coordinate});
        tiles.push_back(coordinate);
      }
    }
    saver_->addSavedSurface(surface, tiles);
    if (rebuilt && io::pathExists(surfaceFolder)) {
      NANQUIM_LOG_ERROR("Index of %s rebuilt from its tile files", surface.folder.c_str());
      saver_->addOrphanFolder(surface.folder + "/index.json");
    }
  }
  for (const char* parent : {"layers", "masks"}) {
    for (const std::string& name : io::listFolder(io::joinPath(folder, parent))) {
      const std::string relative = std::string(parent) + "/" + name;
      if (surfaceFolders.count(relative) == 0) {
        saver_->addOrphanFolder(relative);
      }
    }
  }
  compositor_->invalidateAll();
  reportedCorruptTiles_ = 0;
  frameRequested_ = true;
  return true;
}

gpu::rhi::Format EngineContext::documentFormat() const {
  return canvas::textureFormat(tileStore_->pixelFormat());
}

const gpu::rhi::Pipeline* EngineContext::enginePipeline(EngineProgram program,
                                                        gpu::rhi::Format targetFormat, bool blend) {
  const gpu::rhi::Pipeline*& cached = enginePipelines_[{program, targetFormat, blend}];
  if (cached != nullptr) {
    return cached;
  }
  gpu::rhi::PipelineDescription description;
  description.targetFormat = targetFormat;
  if (blend) {
    description.blend.enabled = true;
    description.blend.sourceColor = gpu::rhi::BlendFactor::One;
    description.blend.destinationColor = gpu::rhi::BlendFactor::OneMinusSourceAlpha;
    description.blend.sourceAlpha = gpu::rhi::BlendFactor::One;
    description.blend.destinationAlpha = gpu::rhi::BlendFactor::OneMinusSourceAlpha;
  }
  switch (program) {
    case EngineProgram::TestPattern:
      description.program = "test_pattern";
      break;
    case EngineProgram::FillRect:
      description.program = "fill_rect";
      break;
    case EngineProgram::Invert:
      description.program = "invert_tile";
      break;
    case EngineProgram::SelectionCombine:
      description.program = "selection_combine";
      break;
    case EngineProgram::SelectionBlur:
      description.program = "selection_blur";
      break;
    case EngineProgram::CoverageFill:
      description.program = "coverage_fill";
      break;
    case EngineProgram::MaskedCopy:
      description.program = "masked_copy";
      break;
    case EngineProgram::TransformMesh:
      description.program = "transform_mesh";
      description.vertexLayout =
          gpu::rhi::VertexLayout{3, sizeof(canvas::MeshVertex), false, {2, 1, 2}};
      break;
    case EngineProgram::AdjustTile:
    case EngineProgram::AdjustTileSpatial:
    case EngineProgram::AdjustTileLiquify:
      description.program = "adjust_tile";
      if (program == EngineProgram::AdjustTileSpatial) {
        description.defines = {"ADJUST_SPATIAL"};
      } else if (program == EngineProgram::AdjustTileLiquify) {
        description.defines = {"ADJUST_LIQUIFY"};
      }
      break;
    case EngineProgram::AdjustPass:
    case EngineProgram::AdjustPassRays:
    case EngineProgram::AdjustPassTexels:
      description.program = "adjust_pass";
      if (program == EngineProgram::AdjustPassRays) {
        description.defines = {"PASS_RAYS"};
      } else if (program == EngineProgram::AdjustPassTexels) {
        description.defines = {"PASS_TEXELS"};
      }
      break;
  }
  std::string errorMessage;
  cached = device_->pipeline(description, errorMessage);
  if (cached == nullptr) {
    NANQUIM_LOG_ERROR("%s", errorMessage.c_str());
  }
  return cached;
}

std::unique_ptr<gpu::rhi::Texture> EngineContext::createSurfaceTexture(
    int32_t width, int32_t height, std::optional<gpu::rhi::Format> format) {
  gpu::rhi::TextureDescription description;
  description.format = format.value_or(documentFormat());
  description.width = width;
  description.height = height;
  description.usage = gpu::rhi::kUsageSampled | gpu::rhi::kUsageRenderTarget |
                      gpu::rhi::kUsageUpload | gpu::rhi::kUsageReadback;
  return device_->createTexture(description);
}

void EngineContext::clearTexture(const gpu::rhi::Texture& texture, std::array<float, 4> color) {
  gpu::rhi::CommandContext& commands = device_->commands();
  commands.beginPass(gpu::rhi::RenderTarget::of(texture), gpu::rhi::LoadAction::Clear, color);
  commands.endPass();
}

void EngineContext::waitForGpu() {
  constexpr uint64_t kWaitNanoseconds = 5000000000;
  device_->commands().insertFence()->wait(kWaitNanoseconds);
}

io::DocumentTileFormat EngineContext::layerFileFormat(io::DocumentTileFormat declared) const {
  if (declared == io::DocumentTileFormat::Rgba8 && document_) {
    return io::layerTileFormat(document_->tree.pixelFormat);
  }
  return declared;
}

void EngineContext::saveDocument(canvas::SaveRequest request) {
  post([this, request = std::move(request)]() mutable {
    runStrokeTask([this, request = std::move(request)]() mutable {
      for (canvas::SaveSurface& surface : request.surfaces) {
        surface.format = layerFileFormat(surface.format);
      }
      const int32_t requestId = request.requestId;
      saver_->save(std::move(request), [this, requestId](const canvas::SaveResult& result) {
        core::JsonWriter writer;
        writer.beginObject();
        writer.key("type");
        writer.stringValue("documentSaved");
        writer.key("requestId");
        writer.integerValue(static_cast<int64_t>(requestId));
        writer.key("ok");
        writer.booleanValue(result.ok);
        writer.key("error");
        writer.stringValue(result.error);
        writer.key("tilesWritten");
        writer.integerValue(result.tilesWritten);
        writer.key("tilesRemoved");
        writer.integerValue(result.tilesRemoved);
        writer.key("milliseconds");
        writer.numberValue(result.milliseconds);
        writer.endObject();
        callbacks_->onEvent(writer.text());
      });
      frameRequested_ = true;
    });
  });
}

void EngineContext::writeSurfaceTileRow(canvas::LayerId surface, int32_t tileRow,
                                        std::vector<uint8_t> pixels) {
  auto shared = std::make_shared<const std::vector<uint8_t>>(std::move(pixels));
  post([this, surface, tileRow, shared] {
    runStrokeTask([this, surface, tileRow, shared] {
      pendingSurfaceWrites_.push_back(
          [this, surface, tileRow, shared] { return writeSurfaceTileRowNow(surface, tileRow, *shared); });
    });
  });
}

bool EngineContext::writeSurfaceTileRowNow(canvas::LayerId surface, int32_t tileRow,
                                           const std::vector<uint8_t>& pixels) {
  if (!document_) {
    return true;
  }
  if (tileStore_->isOverGpuBudget() || saver_->blocksTileWrites()) {
    return false;
  }
  const int32_t width = document_->widthPixels;
  const int32_t rowsInBand = std::min(canvas::kTileSizePixels,
                                      document_->heightPixels - tileRow * canvas::kTileSizePixels);
  const size_t rowBytes = static_cast<size_t>(width) * 4;
  if (tileRow < 0 || rowsInBand <= 0 ||
      pixels.size() < rowBytes * static_cast<size_t>(rowsInBand)) {
    NANQUIM_LOG_ERROR("writeSurfaceTileRow: row %d of %zu bytes does not fit the canvas", tileRow,
                      pixels.size());
    return true;
  }
  std::vector<uint8_t> tile(canvas::kTileBytesRgba8);
  const bool halfFloat = tileStore_->pixelFormat() == canvas::PixelFormat::Rgba16fPremulLinear;
  std::vector<uint8_t> halfTile(halfFloat ? static_cast<size_t>(tileStore_->tileBytes()) : 0);
  const bool layerSurface = document_->tree.find(surface) != nullptr;
  const int32_t columns = (width + canvas::kTileSizePixels - 1) / canvas::kTileSizePixels;
  for (int32_t column = 0; column < columns; ++column) {
    std::fill(tile.begin(), tile.end(), 0);
    const int32_t firstX = column * canvas::kTileSizePixels;
    const int32_t columnsInTile = std::min(canvas::kTileSizePixels, width - firstX);
    bool empty = true;
    for (int32_t row = 0; row < rowsInBand; ++row) {
      const uint8_t* source =
          pixels.data() + static_cast<size_t>(row) * rowBytes + static_cast<size_t>(firstX) * 4;
      uint8_t* destination = tile.data() + static_cast<size_t>(row) * canvas::kTileSizePixels * 4;
      std::memcpy(destination, source, static_cast<size_t>(columnsInTile) * 4);
      for (int32_t x = 0; x < columnsInTile && empty; ++x) {
        empty = source[x * 4 + 3] == 0;
      }
    }
    const canvas::LayerTileKey key{surface, {column, tileRow}};
    if (!empty && halfFloat) {
      const auto pixelCount = static_cast<size_t>(canvas::kTileSizePixels) * canvas::kTileSizePixels;
      if (layerSurface) {
        canvas::premultipliedSrgb8ToLinearHalf(tile.data(), halfTile.data(), pixelCount);
      } else {
        canvas::rgba8ToHalf(tile.data(), halfTile.data(), pixelCount);
      }
    }
    if (empty) {
      tileStore_->removeTile(key);
    } else if (!tileStore_->writeTile(key, halfFloat ? halfTile.data() : tile.data())) {
      return false;
    }
    markTileChanged(key);
  }
  frameRequested_ = true;
  return true;
}

EngineContext::ReadableRegion EngineContext::readableRegion(const gpu::rhi::Texture& texture,
                                                            const gpu::rhi::Rect& rect) {
  ReadableRegion region;
  if (!compositor_->linearLight()) {
    region.texture = &texture;
    region.rect = rect;
    return region;
  }
  gpu::rhi::TextureDescription description;
  description.width = rect.width;
  description.height = rect.height;
  description.usage = gpu::rhi::kUsageRenderTarget | gpu::rhi::kUsageReadback;
  region.encoded = device_->createTexture(description);
  if (!region.encoded) {
    return region;
  }
  gpu::rhi::CommandContext& commands = device_->commands();
  commands.beginPass(gpu::rhi::RenderTarget::of(*region.encoded), gpu::rhi::LoadAction::DontCare);
  commands.bindPipeline(*thumbnailPipeline_);
  commands.bindTexture(kThumbnailSourceBinding, &texture);
  commands.setUniforms(ThumbnailTileParameters{{rect.x, rect.y}, {0, 0}, 1, 1, {0, 0}});
  commands.draw(3);
  commands.endPass();
  region.texture = region.encoded.get();
  region.rect = gpu::rhi::Rect{0, 0, rect.width, rect.height};
  return region;
}

bool EngineContext::readRegionRgba8(const gpu::rhi::Texture& texture, const gpu::rhi::Rect& rect,
                                    uint8_t* out) {
  const ReadableRegion region = readableRegion(texture, rect);
  return region.texture != nullptr &&
         device_->commands().readTexture(*region.texture, region.rect, out);
}

void EngineContext::requestCompositeThumbnail(int32_t requestId, int32_t maxSide) {
  post([this, requestId, maxSide] {
    runStrokeTask([this, requestId, maxSide] {
      if (!document_ || maxSide < 1) {
        callbacks_->onSceneRendered(requestId, 0, 0, {}, "no document");
        return;
      }
      if (!sendCompositeThumbnail(requestId, maxSide)) {
        requestCompositeThumbnail(requestId, maxSide);
      }
    });
  });
}

void EngineContext::requestCompositePreview(int32_t requestId, int32_t maxSide) {
  post([this, requestId, maxSide] {
    runStrokeTask([this, requestId, maxSide] {
      if (!document_ || maxSide < 1) {
        callbacks_->onSceneRendered(requestId, 0, 0, {}, "no document");
        return;
      }
      if (previewReadback_) {
        if (nextPreview_) {
          callbacks_->onSceneRendered(nextPreview_->first, 0, 0, {}, "replaced");
        }
        nextPreview_ = std::make_pair(requestId, maxSide);
        return;
      }
      if (!startCompositePreview(requestId, maxSide)) {
        requestCompositePreview(requestId, maxSide);
      }
    });
  });
}

bool EngineContext::startCompositePreview(int32_t requestId, int32_t maxSide) {
  ATrace_beginSection("NanquimPreviewComposite");
  int32_t level = 0;
  const auto canvasSide = [this](int32_t candidate) {
    return (std::max(document_->widthPixels, document_->heightPixels) + (1 << candidate) - 1) >>
           candidate;
  };
  while (level + 1 < compositor_->levelCount() && canvasSide(level) > maxSide) {
    ++level;
  }
  const canvas::TileRange everything = compositor_->canvasTiles();
  const auto farDeadline = std::chrono::steady_clock::now() + std::chrono::hours(1);
  constexpr int kMaximumUpdates = 1000;
  for (int update = 0;
       update < kMaximumUpdates &&
       compositor_->update(everything, level, 0, farDeadline, kMaximumLayerTileBudget);
       ++update) {
    if (tileStore_->stats().loadsInFlight > 0) {
      ATrace_endSection();
      return false;
    }
  }
  const int32_t width = std::min(compositor_->levelWidth(level),
                                 (document_->widthPixels + (1 << level) - 1) >> level);
  const int32_t height = std::min(compositor_->levelHeight(level),
                                  (document_->heightPixels + (1 << level) - 1) >> level);
  PreviewReadback readback;
  readback.requestId = requestId;
  readback.width = width;
  readback.height = height;
  const ReadableRegion region =
      readableRegion(compositor_->levelTexture(level), gpu::rhi::Rect{0, 0, width, height});
  if (region.texture != nullptr) {
    readback.readback = device_->commands().readTextureAsync(*region.texture, region.rect);
    device_->commands().submit();
  }
  ATrace_endSection();
  if (!readback.readback) {
    callbacks_->onSceneRendered(requestId, 0, 0, {}, "readback failed");
    return true;
  }
  previewReadback_ = std::move(readback);
  return true;
}

void EngineContext::pollCompositePreview() {
  if (frameReadback_ && frameReadback_->readback->isReady()) {
    ATrace_beginSection("NanquimTimelapseRead");
    std::vector<uint8_t> pixels(frameReadback_->readback->size());
    const bool read = frameReadback_->readback->read(pixels.data());
    ATrace_endSection();
    const PreviewReadback done = std::move(*frameReadback_);
    frameReadback_.reset();
    callbacks_->onSceneRendered(done.requestId, read ? done.width : 0, read ? done.height : 0,
                                read ? pixels : std::vector<uint8_t>(), read ? "" : "readback failed");
  }
  if (!previewReadback_) {
    return;
  }
  if (!previewReadback_->readback->isReady()) {
    return;
  }
  ATrace_beginSection("NanquimPreviewRead");
  std::vector<uint8_t> pixels(previewReadback_->readback->size());
  std::string error;
  if (!previewReadback_->readback->read(pixels.data())) {
    error = "readback failed";
  }
  const PreviewReadback done = std::move(*previewReadback_);
  previewReadback_.reset();
  ATrace_endSection();
  if (error.empty()) {
    callbacks_->onSceneRendered(done.requestId, done.width, done.height, pixels, "");
  } else {
    callbacks_->onSceneRendered(done.requestId, 0, 0, {}, error);
  }
  if (nextPreview_) {
    const auto [requestId, maxSide] = *nextPreview_;
    nextPreview_.reset();
    requestCompositePreview(requestId, maxSide);
  }
}

void EngineContext::requestTimelapseFrame(int32_t requestId, int32_t maxSide,
                                          std::vector<canvas::LayerId> excluded) {
  if (excluded.empty()) {
    requestCompositePreview(requestId, maxSide);
    return;
  }
  post([this, requestId, maxSide, excluded = std::move(excluded)]() mutable {
    runStrokeTask([this, requestId, maxSide, excluded = std::move(excluded)]() mutable {
      if (!document_ || maxSide < 1) {
        callbacks_->onSceneRendered(requestId, 0, 0, {}, "no document");
        return;
      }
      if (frameReadback_) {
        callbacks_->onSceneRendered(requestId, 0, 0, {}, "busy");
        return;
      }
      if (!startTimelapseFrame(requestId, maxSide, excluded)) {
        requestTimelapseFrame(requestId, maxSide, std::move(excluded));
      }
    });
  });
}

bool EngineContext::startTimelapseFrame(int32_t requestId, int32_t maxSide,
                                        const std::vector<canvas::LayerId>& excluded) {
  ATrace_beginSection("NanquimTimelapseFrame");
  canvas::LayerTree tree = document_->tree;
  for (const canvas::LayerId id : excluded) {
    std::function<bool(std::vector<canvas::LayerTreeNode>&)> hide =
        [&](std::vector<canvas::LayerTreeNode>& nodes) {
          for (canvas::LayerTreeNode& node : nodes) {
            if (node.id == id) {
              node.hidden = true;
              return true;
            }
            if (hide(node.children)) {
              return true;
            }
          }
          return false;
        };
    hide(tree.layers);
  }
  tree.solo.reset();
  const canvas::CompositePlan plan = canvas::buildCompositePlan(tree, std::nullopt);
  bool resident = true;
  compositor_->canvasTiles().forEach([&](canvas::TileCoordinate coordinate) {
    resident = compositor_->planIsResident(plan, coordinate) && resident;
  });
  if (!resident) {
    ATrace_endSection();
    return false;
  }
  int32_t level = 0;
  const auto canvasSide = [this](int32_t candidate) {
    return (std::max(document_->widthPixels, document_->heightPixels) + (1 << candidate) - 1) >>
           candidate;
  };
  while (level + 1 < compositor_->levelCount() && canvasSide(level) > maxSide) {
    ++level;
  }
  const int32_t width = (document_->widthPixels + (1 << level) - 1) >> level;
  const int32_t height = (document_->heightPixels + (1 << level) - 1) >> level;
  const gpu::rhi::Texture* target = compositor_->renderPlanAtLevel(plan, level, width, height);
  PreviewReadback readback;
  readback.requestId = requestId;
  readback.width = width;
  readback.height = height;
  if (target != nullptr) {
    const ReadableRegion region = readableRegion(*target, gpu::rhi::Rect{0, 0, width, height});
    if (region.texture != nullptr) {
      readback.readback = device_->commands().readTextureAsync(*region.texture, region.rect);
      device_->commands().submit();
    }
  }
  ATrace_endSection();
  if (!readback.readback) {
    callbacks_->onSceneRendered(requestId, 0, 0, {}, "readback failed");
    return true;
  }
  frameReadback_ = std::move(readback);
  frameRequested_ = true;
  return true;
}

bool EngineContext::sendCompositeThumbnail(int32_t requestId, int32_t maxSide) {
  int32_t level = 0;
  while (level + 1 < compositor_->levelCount() &&
         std::max(compositor_->levelWidth(level), compositor_->levelHeight(level)) > maxSide * 2) {
    ++level;
  }
  const canvas::TileRange everything = compositor_->canvasTiles();
  const auto farDeadline = std::chrono::steady_clock::now() + std::chrono::hours(1);
  constexpr int kMaximumUpdates = 1000;
  ATrace_beginSection("NanquimThumbnailComposite");
  for (int update = 0;
       update < kMaximumUpdates &&
       compositor_->update(everything, level, 0, farDeadline, kMaximumLayerTileBudget);
       ++update) {
    if (tileStore_->stats().loadsInFlight > 0) {
      ATrace_endSection();
      return false;
    }
  }
  ATrace_endSection();
  ATrace_beginSection("NanquimThumbnailRead");
  const int32_t levelWidth = compositor_->levelWidth(level);
  const int32_t levelHeight = compositor_->levelHeight(level);
  std::vector<uint8_t> source(static_cast<size_t>(levelWidth) * static_cast<size_t>(levelHeight) *
                              4);
  const bool read = readRegionRgba8(compositor_->levelTexture(level),
                                    gpu::rhi::Rect{0, 0, levelWidth, levelHeight}, source.data());
  ATrace_endSection();
  if (!read) {
    callbacks_->onSceneRendered(requestId, 0, 0, {}, "readback failed");
    return true;
  }
  ATrace_beginSection("NanquimThumbnailFilter");
  const double scale =
      std::min(1.0, static_cast<double>(maxSide) /
                        std::max(document_->widthPixels, document_->heightPixels));
  const int32_t width =
      std::max(1, static_cast<int32_t>(std::lround(document_->widthPixels * scale)));
  const int32_t height =
      std::max(1, static_cast<int32_t>(std::lround(document_->heightPixels * scale)));
  const double canvasLevelWidth = document_->widthPixels / static_cast<double>(1 << level);
  const double canvasLevelHeight = document_->heightPixels / static_cast<double>(1 << level);
  std::vector<uint8_t> pixels(static_cast<size_t>(width) * static_cast<size_t>(height) * 4);
  for (int32_t y = 0; y < height; ++y) {
    const auto firstRow = static_cast<int32_t>(y * canvasLevelHeight / height);
    const int32_t endRow = std::min(
        levelHeight,
        std::max(firstRow + 1, static_cast<int32_t>((y + 1) * canvasLevelHeight / height)));
    for (int32_t x = 0; x < width; ++x) {
      const auto firstColumn = static_cast<int32_t>(x * canvasLevelWidth / width);
      const int32_t endColumn = std::min(
          levelWidth,
          std::max(firstColumn + 1, static_cast<int32_t>((x + 1) * canvasLevelWidth / width)));
      std::array<uint32_t, 4> sum{};
      uint32_t count = 0;
      for (int32_t row = firstRow; row < endRow; ++row) {
        for (int32_t column = firstColumn; column < endColumn; ++column) {
          const uint8_t* texel =
              &source[(static_cast<size_t>(row) * static_cast<size_t>(levelWidth) +
                       static_cast<size_t>(column)) *
                      4];
          for (size_t channel = 0; channel < 4; ++channel) {
            sum[channel] += texel[channel];
          }
          ++count;
        }
      }
      uint8_t* out = &pixels[(static_cast<size_t>(y) * static_cast<size_t>(width) +
                              static_cast<size_t>(x)) *
                             4];
      const uint32_t alphaSum = sum[3];
      for (size_t channel = 0; channel < 3; ++channel) {
        out[channel] = alphaSum == 0 ? 0
                                     : static_cast<uint8_t>(std::min<uint32_t>(
                                           255, (sum[channel] * 255U + alphaSum / 2) / alphaSum));
      }
      out[3] = count == 0 ? 0 : static_cast<uint8_t>((alphaSum + count / 2) / count);
    }
  }
  ATrace_endSection();
  callbacks_->onSceneRendered(requestId, width, height, pixels, "");
  frameRequested_ = true;
  return true;
}

void EngineContext::reportDocumentShown() {
  documentShownReported_ = true;
  core::JsonWriter writer;
  writer.beginObject();
  writer.key("type");
  writer.stringValue("documentShown");
  writer.endObject();
  callbacks_->onEvent(writer.text());
}

void EngineContext::reportCorruptTiles() {
  const int64_t corrupt = tileStore_->stats().corruptFileTiles;
  if (corrupt <= reportedCorruptTiles_) {
    return;
  }
  reportedCorruptTiles_ = corrupt;
  core::JsonWriter writer;
  writer.beginObject();
  writer.key("type");
  writer.stringValue("documentTilesCorrupt");
  writer.key("count");
  writer.integerValue(corrupt);
  writer.endObject();
  callbacks_->onEvent(writer.text());
}

void EngineContext::closeDocument() {
  post([this] {
    textLayerShapes_.clear();
    abortStroke();
    deferredStrokeTasks_.clear();
    operations_.clear();
    if (history_) {
      history_->clear();
    }
    if (tileStore_) {
      tileStore_->clear();
    }
    if (compositor_) {
      compositor_->release();
    }
    if (selectionOverlay_) {
      selectionOverlay_->setSurface(std::nullopt);
      selectionOverlay_->release();
    }
    if (saver_) {
      saver_->resetForDocument({}, 0, 0);
    }
    pendingFills_.clear();
    pendingSurfaceWrites_.clear();
    document_.reset();
    frameRequested_ = true;
  });
}

void EngineContext::setLayerTree(canvas::LayerTree tree) {
  post([this, tree = std::move(tree)]() mutable {
    runStrokeTask([this, tree = std::move(tree)] {
      if (!document_) {
        return;
      }
      document_->tree = tree;
      applyLayerTree();
    });
  });
}

void EngineContext::applyLayerTree() {
  compositor_->setLayerTree(document_->tree);
  if (!paintSession_) {
    compositor_->setActiveLayer(document_->tree.activeLayer);
  }
  frameRequested_ = true;
}

namespace {

canvas::LayerTreeNode* findNode(std::vector<canvas::LayerTreeNode>& nodes, canvas::LayerId id) {
  for (canvas::LayerTreeNode& node : nodes) {
    if (node.id == id) {
      return &node;
    }
    if (canvas::LayerTreeNode* found = findNode(node.children, id)) {
      return found;
    }
  }
  return nullptr;
}

}  // namespace

void EngineContext::setLayerVisible(canvas::LayerId layer, bool visible) {
  post([this, layer, visible] {
    if (!document_) {
      return;
    }
    if (canvas::LayerTreeNode* node = findNode(document_->tree.layers, layer)) {
      node->hidden = !visible;
      applyLayerTree();
    }
  });
}

void EngineContext::setLayerOpacity(canvas::LayerId layer, float opacity) {
  post([this, layer, opacity] {
    if (!document_) {
      return;
    }
    if (canvas::LayerTreeNode* node = findNode(document_->tree.layers, layer)) {
      node->opacity = std::clamp(opacity, 0.0F, 1.0F);
      applyLayerTree();
    }
  });
}

void EngineContext::setActiveLayer(canvas::LayerId layer) {
  post([this, layer] {
    if (!document_) {
      return;
    }
    compositor_->setActiveLayer(layer == 0 ? std::nullopt : std::optional<canvas::LayerId>(layer));
    applyBudgets();
    frameRequested_ = true;
  });
}

void EngineContext::setBackground(std::array<float, 4> color, bool hidden) {
  post([this, color, hidden] {
    if (document_) {
      document_->tree.background = color;
      document_->tree.backgroundHidden = hidden;
      applyLayerTree();
    }
  });
}

void EngineContext::fillRandomTiles(canvas::LayerId layer, int32_t tileCount, uint32_t seed) {
  post([this, layer, tileCount, seed] {
    if (!document_ || document_->tree.rasterLayers().empty()) {
      return;
    }
    std::vector<canvas::LayerId> layerIds;
    for (const canvas::LayerTreeNode* node : document_->tree.rasterLayers()) {
      layerIds.push_back(node->id);
    }
    const canvas::TileRange tiles = compositor_->canvasTiles();
    std::mt19937 generator(seed);
    std::uniform_int_distribution<int32_t> columnDistribution(tiles.firstColumn,
                                                              tiles.endColumn - 1);
    std::uniform_int_distribution<int32_t> rowDistribution(tiles.firstRow, tiles.endRow - 1);
    std::uniform_int_distribution<size_t> layerDistribution(0, layerIds.size() - 1);
    std::uniform_real_distribution<float> unitDistribution(0.0F, 1.0F);

    std::vector<canvas::LayerTileKey> keys;
    if (tileCount < 0) {
      for (const canvas::LayerId id : layerIds) {
        if (layer == 0 || id == layer) {
          tiles.forEach([&](canvas::TileCoordinate coordinate) {
            keys.push_back(canvas::LayerTileKey{id, coordinate});
          });
        }
      }
    } else {
      for (int32_t index = 0; index < tileCount; ++index) {
        const canvas::LayerId target =
            layer != 0 ? layer : layerIds[layerDistribution(generator)];
        keys.push_back(canvas::LayerTileKey{
            target,
            canvas::TileCoordinate{columnDistribution(generator), rowDistribution(generator)}});
      }
    }

    for (const auto& key : keys) {
      const std::array<float, 3> color{unitDistribution(generator), unitDistribution(generator),
                                       unitDistribution(generator)};
      pendingFills_.push_back(PendingFill{key, color, unitDistribution(generator) * 100.0F});
    }
    frameRequested_ = true;
  });
}

void EngineContext::processPendingFills() {
  constexpr int kFillsPerIteration = 32;
  for (int count = 0; count < kFillsPerIteration && !pendingFills_.empty(); ++count) {
    if (tileStore_->isOverGpuBudget()) {
      return;
    }
    const PendingFill fill = pendingFills_.front();
    pendingFills_.pop_front();
    const auto slot = tileStore_->slotForWrite(fill.key);
    if (!slot) {
      callbacks_->onError(EngineErrorCode::DocumentOpenFailed,
                          "Not enough GPU memory for more tiles", true);
      pendingFills_.clear();
      return;
    }
    paintTestPattern(fill.key, *slot, fill.color, fill.patternSeed);
    markTileChanged(fill.key);
  }
}

void EngineContext::paintTestPattern(const canvas::LayerTileKey& key, const canvas::AtlasSlot& slot,
                                     std::array<float, 3> color, float seed) {
  const gpu::rhi::Texture& page = tileStore_->pageTexture(slot.page);
  const gpu::rhi::Pipeline* pipeline =
      enginePipeline(EngineProgram::TestPattern, page.description().format);
  if (pipeline == nullptr) {
    return;
  }
  gpu::rhi::CommandContext& commands = device_->commands();
  commands.beginPass(gpu::rhi::RenderTarget::of(page), gpu::rhi::LoadAction::Load);
  commands.setScissor(gpu::rhi::Rect{canvas::slotOriginX(slot), canvas::slotOriginY(slot),
                                     canvas::kTileSizePixels, canvas::kTileSizePixels});
  commands.bindPipeline(*pipeline);
  commands.setUniforms(TestPatternParameters{{canvas::slotOriginX(slot), canvas::slotOriginY(slot)},
                                             {key.coordinate.column * canvas::kTileSizePixels,
                                              key.coordinate.row * canvas::kTileSizePixels},
                                             color,
                                             seed});
  commands.draw(3);
  commands.endPass();
}

void EngineContext::setCamera(const core::AffineTransform& canvasToScreen) {
  post([this, canvasToScreen] {
    canvasToScreen_ = canvasToScreen;
    frameRequested_ = true;
  });
}

void EngineContext::setViewportStyle(const canvas::ViewportStyle& style) {
  post([this, style] {
    viewportStyle_ = style;
    frameRequested_ = true;
  });
}

void EngineContext::keepGpuAwake(std::chrono::milliseconds duration) {
  post([this, duration] {
    const auto until = std::chrono::steady_clock::now() + duration;
    if (!awakeUntil_ || *awakeUntil_ < until) {
      awakeUntil_ = until;
    }
  });
}

void EngineContext::pokeGpu() {
  lastGpuWork_ = std::chrono::steady_clock::now();
  if (!eglReady_ || !device_) {
    return;
  }
  if (!awakeTarget_) {
    gpu::rhi::TextureDescription description;
    description.width = 1;
    description.height = 1;
    description.usage = gpu::rhi::kUsageRenderTarget;
    awakeTarget_ = device_->createTexture(description);
    if (!awakeTarget_) {
      return;
    }
  }
  auto& commands = device_->commands();
  commands.beginPass(gpu::rhi::RenderTarget::of(*awakeTarget_), gpu::rhi::LoadAction::Clear);
  commands.endPass();
  commands.submit();
}

void EngineContext::setDirtyTileOverlay(bool enabled) {
  post([this, enabled] {
    dirtyTileOverlay_ = enabled;
    frameRequested_ = true;
  });
}

void EngineContext::setDisplayColor(std::vector<uint16_t> lut, bool displayP3) {
  post([this, lut = std::move(lut), displayP3] {
    if (!eglReady_) {
      return;
    }
    viewportRenderer_->setDisplayLut(lut);
    const bool wasDisplayP3 = presenter_->isDisplayP3();
    presenter_->setDisplayP3(displayP3);
    if (presenter_->isDisplayP3() != wasDisplayP3 && window_ != nullptr) {
      resetWindowSurfaceState();
      std::string errorMessage;
      if (!presenter_->attachWindow(window_, errorMessage)) {
        callbacks_->onError(EngineErrorCode::WindowSurfaceFailed, errorMessage, true);
      }
    }
    NANQUIM_LOG_INFO("Display color: %s, %s", lut.empty() ? "no LUT" : "LUT",
                     presenter_->isDisplayP3() ? "Display P3 surface" : "sRGB surface");
    frameRequested_ = true;
  });
}

void EngineContext::setSelectionDisplay(std::optional<canvas::LayerId> selection, bool dimOutside) {
  post([this, selection, dimOutside] {
    if (selectionOverlay_) {
      selectionOverlay_->setSurface(selection);
    }
    dimOutsideSelection_ = dimOutside;
    frameRequested_ = true;
  });
}

void EngineContext::markTileChanged(const canvas::LayerTileKey& key) {
  if (selectionOverlay_ && selectionOverlay_->surface() == key.layer) {
    selectionOverlay_->markTileChanged(key);
    frameRequested_ = true;
    return;
  }
  compositor_->markTileChanged(key);
}

void EngineContext::setMemoryBudget(int64_t bytes) {
  post([this, bytes] {
    config_.memoryBudgetBytes = bytes;
    applyBudgets();
  });
}

void EngineContext::applyBudgets() {
  if (!tileStore_) {
    return;
  }
  int64_t remaining = config_.memoryBudgetBytes;
  if (compositor_) {
    const canvas::CompositorStats stats = compositor_->stats();
    remaining -= stats.compositeBytes + stats.cacheBytes;
  }
  remaining = std::max<int64_t>(remaining, 0);
  const auto gpuBytes =
      static_cast<int64_t>(static_cast<double>(remaining) * kGpuShareOfRemainingBudget);
  tileStore_->setBudgets(gpuBytes, remaining - gpuBytes);
}

canvas::TileRange EngineContext::visibleTiles() const {
  const auto screenToCanvas = canvasToScreen_.inverted();
  if (!screenToCanvas) {
    return {};
  }
  const auto width = static_cast<double>(surfaceWidthPixels_);
  const auto height = static_cast<double>(surfaceHeightPixels_);
  const core::Point corners[] = {screenToCanvas->apply({0, 0}), screenToCanvas->apply({width, 0}),
                                 screenToCanvas->apply({0, height}),
                                 screenToCanvas->apply({width, height})};
  double minX = corners[0].x;
  double maxX = corners[0].x;
  double minY = corners[0].y;
  double maxY = corners[0].y;
  for (const auto& corner : corners) {
    minX = std::min(minX, corner.x);
    maxX = std::max(maxX, corner.x);
    minY = std::min(minY, corner.y);
    maxY = std::max(maxY, corner.y);
  }
  return canvas::tileRangeCoveringRect(minX, minY, maxX, maxY);
}

void EngineContext::runStrokeTask(std::function<void()> task) {
  const bool pencilOpen = adjustmentSession_ && adjustmentSession_->mask &&
                          adjustmentSession_->shown && !adjustmentSession_->endRequested;
  const bool busyBesidesAdjustment = (paintSession_ && paintSession_->ended) ||
                                     !operations_.empty() || fillSession_.has_value() ||
                                     transformSession_.has_value();
  if (documentBusy() && !(pencilOpen && !busyBesidesAdjustment)) {
    deferredStrokeTasks_.push_back(std::move(task));
  } else {
    task();
  }
}

void EngineContext::setBrush(StrokeTool tool, std::shared_ptr<const stroke::LoadedBrush> brush) {
  post([this, tool, brush = std::move(brush)] {
    runStrokeTask([this, tool, brush] {
      brushes_[static_cast<size_t>(tool)] = brush;
      if (strokeRenderer_ && brush) {
        strokeRenderer_->setBrush(brush->parameters, brush->shapeTexture, brush->grainTexture);
      }
    });
  });
}

void EngineContext::setPressureCurve(std::optional<stroke::Curve> curve) {
  post([this, curve = std::move(curve)] { pressureCurve_ = curve; });
}

void EngineContext::setGlobalSmoothing(float amount) {
  post([this, amount] { globalSmoothing_ = std::clamp(amount, 0.0F, 1.0F); });
}

void EngineContext::setStylusRoll(bool reported) {
  post([this, reported] { stylusRoll_ = reported; });
}

void EngineContext::beginStroke(StrokeTool tool, canvas::LayerId layer, StrokeStyle style) {
  post([this, tool, layer, style] {
    runStrokeTask([this, tool, layer, style] {
      if (!document_ || !strokeRenderer_) {
        return;
      }
      const canvas::LayerTreeNode* node = document_->tree.find(layer);
      const bool paintsSelection = style.selectionTarget && style.selection.has_value();
      if (!paintsSelection &&
          (node == nullptr || node->isGroup() || (style.maskTarget && !node->mask))) {
        return;
      }
      if (tool == StrokeTool::Smudge && (style.maskTarget || style.selectionTarget || style.clone)) {
        return;
      }
      stroke::StrokeSettings settings;
      settings.sizePixels = std::max(style.sizePixels, 1.0F);
      settings.opacity = std::clamp(style.opacity, 0.0F, 1.0F);
      settings.color = {style.color[0], style.color[1], style.color[2], style.color[3]};
      settings.secondaryColor = {style.secondaryColor[0], style.secondaryColor[1],
                                 style.secondaryColor[2]};
      settings.seed = stroke::strokeSeed(documentId_, strokeIndex_++);
      settings.pressureCurve = pressureCurve_;
      settings.globalSmoothing = globalSmoothing_;
      settings.viewRotation = std::atan2(canvasToScreen_.b, canvasToScreen_.a);
      settings.rollFromOrientation = stylusRoll_;
      settings.symmetry = style.symmetry;
      const auto& brush = brushes_[static_cast<size_t>(tool)];
      PaintSession& session =
          brush ? startPaintSession(tool, layer, style.maskTarget, style.selection,
                                    style.selectionTarget, style.clone.has_value(), brush->dual,
                                    brush->parameters, settings, brush->shapeTexture,
                                    brush->grainTexture)
                : startPaintSession(tool, layer, style.maskTarget, style.selection,
                                    style.selectionTarget, style.clone.has_value(), nullptr,
                                    stroke::roundBrush(), settings, nullptr, nullptr);
      session.historyEntry = style.historyEntry;
      if (style.clone && !style.selectionTarget) {
        prepareCloneSource(*style.clone);
      }
    });
  });
}

EngineContext::PaintSession& EngineContext::startPaintSession(
    StrokeTool tool, canvas::LayerId layer, bool maskTarget,
    std::optional<canvas::LayerId> selection, bool selectionTarget, bool clone,
    std::shared_ptr<const stroke::LoadedBrush> dual, stroke::BrushParameters brush,
    const stroke::StrokeSettings& strokeSettings,
    std::shared_ptr<const stroke::GrayTexture> shapeTexture,
    std::shared_ptr<const stroke::GrayTexture> grainTexture) {
  abortStroke();
  strokeRenderer_->setBrush(brush, std::move(shapeTexture), std::move(grainTexture));
  const bool paintsLayerColors = tool == StrokeTool::Paint && !maskTarget && !selectionTarget;
  const stroke::PaintMixing mixing =
      maskTarget || selectionTarget || clone ? stroke::PaintMixing::None
      : tool == StrokeTool::Smudge           ? stroke::PaintMixing::Smudge
      : tool == StrokeTool::Paint && brush.wetMix.enabled ? stroke::PaintMixing::WetMix
                                                          : stroke::PaintMixing::None;
  stroke::StrokeSettings settings = strokeSettings;
  if (mixing != stroke::PaintMixing::None || clone ||
      settings.symmetry.size() > stroke::kMaximumSymmetryCopies) {
    settings.symmetry.clear();
  }
  strokeRenderer_->setStrokeEffects(
      mixing == stroke::PaintMixing::None
          ? stroke::effectiveRender(brush.render, paintsLayerColors, clone, selectionTarget,
                                    canvas::isLinear(tileStore_->pixelFormat()))
          : stroke::RenderParameters{},
      settings.sizePixels, document_ ? document_->widthPixels : 0,
      document_ ? document_->heightPixels : 0);
  if (document_ && !strokeRenderer_->beginMixing(mixing, layer, settings.opacity,
                                                 document_->widthPixels,
                                                 document_->heightPixels)) {
    NANQUIM_LOG_ERROR("Smudge: no working copy");
  }
  const bool usesDual = dual && brush.dual.enabled && mixing == stroke::PaintMixing::None &&
                        !clone && !selectionTarget;
  strokeRenderer_->setDualBrush(
      usesDual ? std::optional(canvas::StrokeRenderer::DualBrush{
                     dual->parameters, dual->shapeTexture, dual->grainTexture, brush.dual.blend,
                     {static_cast<float>(settings.color[0]), static_cast<float>(settings.color[1]),
                      static_cast<float>(settings.color[2])}})
               : std::nullopt);
  if (!selectionTarget) {
    compositor_->setActiveLayer(layer);
  }
  applyBudgets();
  selectionTarget = selectionTarget && selection.has_value();
  if (!selectionTarget) {
    enterFrontBufferMode();
  }
  const canvas::LayerTreeNode* node = document_ ? document_->tree.find(layer) : nullptr;
  canvas::StrokeRenderer::CommitOptions commit;
  commit.surface = maskTarget && node != nullptr && node->mask ? node->mask->id : layer;
  commit.erase = tool == StrokeTool::Erase;
  commit.opacity = static_cast<float>(settings.opacity);
  commit.blend = brush.render.blend;
  commit.alphaLock = !maskTarget && node != nullptr && node->alphaLock;
  commit.maskTarget = maskTarget;
  commit.selection = selection;
  commit.maskLuminance =
      commit.erase ? 0.0F
                   : static_cast<float>(0.3 * settings.color[0] + 0.59 * settings.color[1] +
                                        0.11 * settings.color[2]);
  if (selectionTarget) {
    commit.surface = *selection;
    commit.selection.reset();
    commit.alphaLock = false;
    commit.blend = canvas::BlendMode::Normal;
    commit.maskTarget = true;
    commit.selectionTarget = true;
    commit.maskLuminance = commit.erase ? 0.0F : 1.0F;
  }
  const bool usesParticles = brush.particles.active() && mixing == stroke::PaintMixing::None &&
                             !clone && !selectionTarget;
  strokeRenderer_->setParticles(usesParticles, brush.particles.useShape);
  const stroke::ParticleParameters particleParameters = brush.particles;
  PaintSession& session = paintSession_.emplace(PaintSession{
      tool, layer, commit, commit.opacity, stroke::DabGenerator(std::move(brush), settings), {},
      std::nullopt, std::nullopt, {}, false});
  if (usesDual) {
    session.dualGenerator.emplace(dual->parameters, stroke::dualStrokeSettings(settings));
  }
  if (usesParticles) {
    session.particles.emplace(particleParameters, settings.seed);
  }
  if (paintsAdjustmentMask()) {
    return session;
  }
  if (selectionTarget) {
    selectionOverlay_->setStrokePreview(canvas::SelectionOverlay::StrokePreview{
        &strokeRenderer_->buffer(), &strokeRenderer_->provisional(), commit.opacity,
        commit.maskLuminance});
    return session;
  }
  if (tool == StrokeTool::Paint && !maskTarget &&
      session.generator.brush().render.accumulation == stroke::Accumulation::BlendIntense) {
    snapshotSource(false, layer);
    strokeRenderer_->setLayerSource(
        canvas::StrokeRenderer::CloneSource{cloneSnapshotTexture(), {0, 0}, cloneSnapshotSize_});
  }
  canvas::Compositor::StrokePreview preview;
  preview.buffer = &strokeRenderer_->buffer();
  preview.provisional = &strokeRenderer_->provisional();
  preview.erase = commit.erase;
  preview.opacity = session.opacity;
  preview.brushBlend = commit.blend;
  preview.alphaLock = commit.alphaLock;
  preview.maskTarget = commit.maskTarget;
  preview.maskLuminance = commit.maskLuminance;
  preview.selection = commit.selection;
  preview.effects = strokeRenderer_->effects();
  preview.workingCopy = strokeRenderer_->workingCopy();
  compositor_->setStrokePreview(preview);
  return session;
}

void EngineContext::addStrokeSamples(std::vector<uint8_t> packedSamples) {
  post([this, packedSamples = std::move(packedSamples)] {
    runStrokeTask([this, packedSamples] {
      if (!paintSession_ || paintSession_->ended) {
        return;
      }
      std::vector<stroke::InputSample> samples;
      if (!stroke::decodePackedSamples(packedSamples.data(), packedSamples.size(), samples)) {
        NANQUIM_LOG_ERROR("Stroke samples: %zu bytes is not a whole number of records",
                          packedSamples.size());
        return;
      }
      feedStrokeSamples(*paintSession_, samples);
    });
  });
}

bool EngineContext::feedStrokeSamples(PaintSession& session,
                                      const std::vector<stroke::InputSample>& samples) {
  dabScratch_.clear();
  dualScratch_.clear();
  session.prediction.clear();
  ATrace_beginSection("NanquimStrokePoints");
  for (const stroke::InputSample& sample : samples) {
    if (stroke::isCanceledSample(sample)) {
      ATrace_endSection();
      abortStroke();
      return false;
    }
    if ((sample.flags & stroke::kSamplePredicted) != 0) {
      session.prediction.push_back(sample);
    } else if (stroke::isPathSample(sample)) {
      addStrokePoint(session, stroke::pathPointFrom(sample));
      session.latestInputNanos = std::max(session.latestInputNanos, sample.eventTimeNanos);
      if (session.firstInputNanos == 0) {
        session.firstInputNanos = sample.eventTimeNanos;
      }
    }
  }
  ATrace_endSection();
  renderStrokeDabs(session, dabScratch_);
  session.provisionalOutdated = true;
  frameRequested_ = true;
  return true;
}

void EngineContext::replaceStroke(std::vector<uint8_t> packedSamples) {
  post([this, packedSamples = std::move(packedSamples)] {
    runStrokeTask([this, packedSamples] {
      if (!paintSession_ || paintSession_->ended) {
        return;
      }
      std::vector<stroke::InputSample> samples;
      if (!stroke::decodePackedSamples(packedSamples.data(), packedSamples.size(), samples)) {
        NANQUIM_LOG_ERROR("Stroke samples: %zu bytes is not a whole number of records",
                          packedSamples.size());
        return;
      }
      ATrace_beginSection("NanquimReplaceStroke");
      restartPaintSession(*paintSession_);
      feedStrokeSamples(*paintSession_, samples);
      ATrace_endSection();
    });
  });
}

void EngineContext::restartPaintSession(PaintSession& session) {
  ATrace_beginSection("NanquimRestartStroke");
  clearProvisionalStroke();
  markStrokeTilesChanged(session.touchedTiles);
  if (document_) {
    strokeRenderer_->restartStroke(session.layer, document_->widthPixels,
                                   document_->heightPixels);
  }
  session.generator = session.generator.replacement();
  if (session.dualGenerator) {
    session.dualGenerator = session.dualGenerator->replacement();
  }
  if (session.particles) {
    session.particles.emplace(session.generator.brush().particles,
                              session.generator.settings().seed);
  }
  session.prediction.clear();
  session.latestInputNanos = 0;
  frameRequested_ = true;
  ATrace_endSection();
}

void EngineContext::endStroke() {
  post([this] {
    runStrokeTask([this] {
      if (!paintSession_ || paintSession_->ended) {
        return;
      }
      PaintSession& session = *paintSession_;
      clearProvisionalStroke();
      finishStrokePoints(session);
      renderStrokeDabs(session, dabScratch_);
      session.ended = true;
      session.liftTime = std::chrono::steady_clock::now();
      tryCommitStroke();
    });
  });
}

void EngineContext::advanceParticles(PaintSession& session, double untilMilliseconds) {
  if (!session.particles || !document_) {
    return;
  }
  particleScratch_.clear();
  session.particles->advance(untilMilliseconds, particleScratch_);
  if (particleScratch_.empty()) {
    return;
  }
  touchedScratch_.clear();
  strokeRenderer_->renderParticleDabs(particleScratch_, document_->widthPixels,
                                      document_->heightPixels, touchedScratch_);
  markStrokeTilesChanged(touchedScratch_);
  session.touchedTiles.insert(session.touchedTiles.end(), touchedScratch_.begin(),
                              touchedScratch_.end());
  frameRequested_ = true;
}

bool EngineContext::particlesStillFlying(PaintSession& session) {
  if (!session.particles) {
    return false;
  }
  const double lastInput = static_cast<double>(session.latestInputNanos) / 1e6;
  const double end = lastInput + stroke::ParticleSystem::kMaximumTailMilliseconds;
  const double elapsed = std::chrono::duration<double, std::milli>(
                             std::chrono::steady_clock::now() - session.liftTime)
                             .count();
  advanceParticles(session, std::min(end, lastInput + elapsed));
  if (session.particles->active(end)) {
    frameRequested_ = true;
    return true;
  }
  return false;
}

void EngineContext::addStrokePoint(PaintSession& session, const stroke::PathPoint& point) {
  session.generator.addPoint(point, dabScratch_);
  if (session.dualGenerator) {
    session.dualGenerator->addPoint(point, dualScratch_);
  }
}

void EngineContext::finishStrokePoints(PaintSession& session) {
  dabScratch_.clear();
  dualScratch_.clear();
  session.generator.finish(dabScratch_);
  if (session.dualGenerator) {
    session.dualGenerator->finish(dualScratch_);
  }
}

void EngineContext::cancelStroke() {
  post([this] { runStrokeTask([this] { abortStroke(); }); });
}

void EngineContext::renderStrokeDabs(PaintSession& session, const std::vector<stroke::Dab>& dabs) {
  if ((dabs.empty() && dualScratch_.empty()) || !document_) {
    return;
  }
  ATrace_beginSection("NanquimStrokeDabs");
  touchedScratch_.clear();
  {
    const gpu::GpuProfileScope gpuTime(gpuProfiler_, gpu::GpuProfiler::Section::StrokeDabs);
    strokeRenderer_->renderDabs(dabs, document_->widthPixels, document_->heightPixels,
                                touchedScratch_);
    if (session.dualGenerator && !dualScratch_.empty()) {
      strokeRenderer_->renderDualDabs(dualScratch_, document_->widthPixels, document_->heightPixels,
                                      touchedScratch_);
      dualScratch_.clear();
    }
  }
  if (session.particles) {
    session.particles->emit(dabs);
    const double inputTime = static_cast<double>(session.latestInputNanos) / 1e6;
    const double horizon = std::min(session.generator.finalTimeMilliseconds(), inputTime);
    particleScratch_.clear();
    session.particles->advance(horizon, particleScratch_);
    strokeRenderer_->renderParticleDabs(particleScratch_, document_->widthPixels,
                                        document_->heightPixels, touchedScratch_);
  }
  markStrokeTilesChanged(touchedScratch_);
  session.touchedTiles.insert(session.touchedTiles.end(), touchedScratch_.begin(),
                              touchedScratch_.end());
  frameRequested_ = true;
  ATrace_endSection();
}

void EngineContext::tryCommitStroke() {
  if (!paintSession_ || !paintSession_->ended) {
    return;
  }
  if (particlesStillFlying(*paintSession_)) {
    return;
  }
  const PaintSession& session = *paintSession_;
  std::vector<canvas::LayerTileKey> changedTiles;
  const int64_t historyEntry = session.historyEntry;
  if (historyEntry != 0 && history_->isBackedUp()) {
    return;
  }
  if (saver_->blocksTileWrites()) {
    return;
  }
  const auto result = strokeRenderer_->commit(
      session.commit, changedTiles, [this, historyEntry](const canvas::LayerTileKey& key) {
        if (historyEntry != 0) {
          history_->captureBefore(historyEntry, key);
        }
      });
  if (result == canvas::StrokeRenderer::CommitResult::Waiting) {
    return;
  }
  if (historyEntry != 0) {
    for (const canvas::LayerTileKey& key : changedTiles) {
      history_->captureAfter(historyEntry, key);
    }
    history_->finishEntry(historyEntry);
  }
  compositor_->setStrokePreview(std::nullopt);
  strokeRenderer_->setCloneSource(std::nullopt);
  strokeRenderer_->setLayerSource(std::nullopt);
  strokeRenderer_->endMixing();
  strokeRenderer_->setDualBrush(std::nullopt);
  strokeRenderer_->setParticles(false, true);
  if (paintsAdjustmentMask()) {
    adjustmentMaskChanged(session.touchedTiles);
  } else if (session.commit.selectionTarget) {
    markStrokeTilesChanged(session.touchedTiles);
    selectionOverlay_->setStrokePreview(std::nullopt);
  } else {
    strokeRenderer_->checkForEmptyTiles(changedTiles);
  }
  leaveFrontBufferMode();
  paintSession_.reset();
  if (document_) {
    compositor_->setActiveLayer(document_->tree.activeLayer);
  }
  frameRequested_ = true;
  runDeferredTasks();
}

void EngineContext::updateProvisionalStroke(PaintSession& session) {
  if (!session.provisionalOutdated || session.ended || !document_) {
    return;
  }
  session.provisionalOutdated = false;
  ATrace_beginSection("NanquimProvisionalStroke");
  stroke::DabGenerator provisional = session.generator;
  dabScratch_.clear();
  for (const stroke::InputSample& sample : session.prediction) {
    provisional.addPoint(stroke::pathPointFrom(sample), dabScratch_);
  }
  provisional.finish(dabScratch_);
  touchedScratch_.clear();
  {
    const gpu::GpuProfileScope gpuTime(gpuProfiler_, gpu::GpuProfiler::Section::Provisional);
    strokeRenderer_->renderProvisionalDabs(dabScratch_, document_->widthPixels,
                                           document_->heightPixels, touchedScratch_);
  }
  markStrokeTilesChanged(touchedScratch_);
  ATrace_endSection();
}

void EngineContext::clearProvisionalStroke() {
  if (!strokeRenderer_ || !compositor_) {
    return;
  }
  touchedScratch_.clear();
  strokeRenderer_->clearProvisional(touchedScratch_);
  markStrokeTilesChanged(touchedScratch_);
}

void EngineContext::markStrokeTilesChanged(const std::vector<canvas::TileCoordinate>& coordinates) {
  if (paintsAdjustmentMask()) {
    compositor_->markStrokeTilesChanged(coordinates);
    compositor_->invalidatePreviews();
    frameRequested_ = true;
    return;
  }
  if (paintSession_ && paintSession_->commit.selectionTarget) {
    for (const canvas::TileCoordinate coordinate : coordinates) {
      selectionOverlay_->markTileChanged({paintSession_->commit.surface, coordinate});
    }
    frameRequested_ = true;
    return;
  }
  compositor_->markStrokeTilesChanged(coordinates);
}

void EngineContext::abortStroke() {
  if (!paintSession_) {
    return;
  }
  clearProvisionalStroke();
  const PaintSession& session = *paintSession_;
  if (compositor_) {
    markStrokeTilesChanged(session.touchedTiles);
    compositor_->setStrokePreview(std::nullopt);
  }
  if (selectionOverlay_) {
    selectionOverlay_->setStrokePreview(std::nullopt);
  }
  if (strokeRenderer_) {
    strokeRenderer_->buffer().clear();
    strokeRenderer_->setCloneSource(std::nullopt);
    strokeRenderer_->setLayerSource(std::nullopt);
    strokeRenderer_->endMixing();
    strokeRenderer_->setDualBrush(std::nullopt);
    strokeRenderer_->setParticles(false, true);
  }
  paintSession_.reset();
  leaveFrontBufferMode();
  frameRequested_ = true;
}

void EngineContext::setFrontBufferEnabled(bool enabled) {
  post([this, enabled] {
    frontBufferEnabled_ = enabled;
    if (!enabled) {
      leaveFrontBufferMode();
    }
  });
}

void EngineContext::enterFrontBufferMode() {
  if (!frontBufferEnabled_ || frontBufferRequested_ || frontBufferWanted_ ||
      !presenter_->supportsFrontBuffer()) {
    return;
  }
  frontBufferWanted_ = true;
  frontBufferWantedSince_ = std::chrono::steady_clock::now();
  tryEnterFrontBufferMode();
}

void EngineContext::tryEnterFrontBufferMode() {
  if (!frontBufferWanted_ || frontBufferRequested_) {
    return;
  }
  if (!backBufferFramesPresented()) {
    if (std::chrono::steady_clock::now() - frontBufferWantedSince_ < kBackBufferPresentTimeout) {
      return;
    }
    NANQUIM_LOG_ERROR("Double-buffered frame not presented in time: stroke stays double buffered");
    frontBufferWanted_ = false;
    return;
  }
  frontBufferWanted_ = false;
  presenter_->requestFrontBuffer(true);
  frontBufferRequested_ = true;
  fullRedrawsPending_ = 2;
  frameRequested_ = true;
  singleBufferProbe_ = SingleBufferProbe{};
}

void EngineContext::checkSingleBufferPresented() {
  if (!singleBufferProbe_ || !singleBufferProbe_->frameId) {
    return;
  }
  int64_t presentNanos = 0;
  switch (presenter_->presentTime(*singleBufferProbe_->frameId, presentNanos)) {
    case gpu::rhi::Presenter::PresentTime::Known:
      ATrace_beginSection("NanquimSingleBufferPresented");
      ATrace_endSection();
      singleBufferProbe_.reset();
      return;
    case gpu::rhi::Presenter::PresentTime::Unavailable:
      ATrace_beginSection("NanquimSingleBufferUnverified");
      ATrace_endSection();
      singleBufferProbe_.reset();
      return;
    case gpu::rhi::Presenter::PresentTime::Pending:
      break;
  }
  if (std::chrono::steady_clock::now() - singleBufferProbe_->swapTime <
      kSingleBufferPresentTimeout) {
    return;
  }
  NANQUIM_LOG_ERROR("Single-buffer frame not presented: stroke continues double buffered");
  ATrace_beginSection("NanquimSingleBufferNotPresented");
  ATrace_endSection();
  leaveFrontBufferMode();
}

bool EngineContext::backBufferFramesPresented() {
  if (!lastBackBufferSwapTime_) {
    return true;
  }
  if (lastBackBufferFrameId_) {
    int64_t presentNanos = 0;
    switch (presenter_->presentTime(*lastBackBufferFrameId_, presentNanos)) {
      case gpu::rhi::Presenter::PresentTime::Known:
        lastBackBufferFrameId_.reset();
        lastBackBufferSwapTime_.reset();
        return true;
      case gpu::rhi::Presenter::PresentTime::Pending:
        return false;
      case gpu::rhi::Presenter::PresentTime::Unavailable:
        break;
    }
  }
  return std::chrono::steady_clock::now() - *lastBackBufferSwapTime_ >=
         kBackBufferPresentFallbackDelay;
}

void EngineContext::leaveFrontBufferMode() {
  frontBufferWanted_ = false;
  singleBufferProbe_.reset();
  if (!frontBufferRequested_) {
    return;
  }
  presenter_->requestFrontBuffer(false);
  frontBufferRequested_ = false;
  fullRedrawsPending_ = 2;
  frameRequested_ = true;
}

void EngineContext::waitForSingleBufferFrame() {
  while (!singleBufferFrameFences_.empty() && singleBufferFrameFences_.front()->isSignaled()) {
    singleBufferFrameFences_.pop_front();
  }
  if (singleBufferFrameFences_.size() < kMaximumSingleBufferFramesInFlight) {
    return;
  }
  ATrace_beginSection("NanquimWaitSingleBufferFrame");
  singleBufferFrameFences_.front()->wait(kSingleBufferFrameWaitNanoseconds);
  singleBufferFrameFences_.pop_front();
  ATrace_endSection();
}

std::optional<std::array<int32_t, 4>> EngineContext::screenRegionOf(
    const std::array<double, 4>& canvasRect) const {
  const auto& [minX, minY, maxX, maxY] = canvasRect;
  const core::Point corners[] = {
      canvasToScreen_.apply({minX, minY}), canvasToScreen_.apply({maxX, minY}),
      canvasToScreen_.apply({minX, maxY}), canvasToScreen_.apply({maxX, maxY})};
  double left = corners[0].x;
  double right = corners[0].x;
  double top = corners[0].y;
  double bottom = corners[0].y;
  for (const core::Point& corner : corners) {
    left = std::min(left, corner.x);
    right = std::max(right, corner.x);
    top = std::min(top, corner.y);
    bottom = std::max(bottom, corner.y);
  }
  constexpr double kMargin = 2.0;
  const auto clampX = [this](double value) {
    return static_cast<int32_t>(std::clamp(value, 0.0, static_cast<double>(surfaceWidthPixels_)));
  };
  const auto clampY = [this](double value) {
    return static_cast<int32_t>(std::clamp(value, 0.0, static_cast<double>(surfaceHeightPixels_)));
  };
  const std::array<int32_t, 4> region{
      clampX(std::floor(left - kMargin)), clampY(std::floor(top - kMargin)),
      clampX(std::ceil(right + kMargin)), clampY(std::ceil(bottom + kMargin))};
  if (region[2] <= region[0] || region[3] <= region[1]) {
    return std::nullopt;
  }
  return region;
}

void EngineContext::renderScene(int32_t requestId, std::string sceneJson,
                                std::map<std::string, std::vector<uint8_t>> files) {
  post([this, requestId, sceneJson = std::move(sceneJson), files = std::move(files)] {
    auto fail = [&](const std::string& message) {
      callbacks_->onSceneRendered(requestId, 0, 0, {}, message);
    };
    std::string errorMessage;
    const auto parsed = scene::parseScene(
        sceneJson,
        [&files](const std::string& name) -> std::optional<std::vector<uint8_t>> {
          auto found = files.find(name);
          return found == files.end() ? std::nullopt
                                      : std::optional<std::vector<uint8_t>>(found->second);
        },
        errorMessage);
    if (!parsed) {
      fail(errorMessage);
      return;
    }
    const scene::Scene& sceneToRender = *parsed;
    canvas::LayerTree tree = sceneToRender.layers;
    tree.activeLayer.reset();
    if (!strokeRenderer_ ||
        !openDocumentNow(sceneToRender.width, sceneToRender.height, std::move(tree))) {
      fail("cannot open a document for the scene");
      return;
    }
    auto runOperation = [this](DocumentOperation operation) {
      while (!stepOperation(operation, std::chrono::steady_clock::now() + std::chrono::hours(1))) {
      }
    };
    for (const scene::SceneAction& sceneAction : sceneToRender.actions) {
      if (sceneAction.type == scene::SceneAction::Type::Select) {
        const scene::SelectAction& select = sceneAction.select;
        DocumentOperation operation;
        operation.surface = scene::kSelectionSurface;
        switch (select.operation) {
          case scene::SelectAction::Operation::All:
            operation.kind = DocumentOperation::Kind::Fill;
            operation.mask = true;
            operation.color = {1, 1, 1, 1};
            break;
          case scene::SelectAction::Operation::None:
            operation.kind = DocumentOperation::Kind::Clear;
            break;
          case scene::SelectAction::Operation::Invert:
            operation.kind = DocumentOperation::Kind::Invert;
            operation.selection = true;
            break;
          case scene::SelectAction::Operation::Contents:
            operation.kind = DocumentOperation::Kind::SelectContents;
            operation.source = static_cast<canvas::LayerId>(select.layer);
            break;
          case scene::SelectAction::Operation::Polygon:
            operation.kind = DocumentOperation::Kind::SelectPolygon;
            operation.combine = select.combine;
            operation.coverage =
                std::make_shared<const canvas::TileCoverageMap>(canvas::rasterizePolygon(
                    select.polygon, sceneToRender.width, sceneToRender.height));
            break;
          case scene::SelectAction::Operation::Feather:
            operation.kind = DocumentOperation::Kind::Feather;
            operation.weights = canvas::featherWeights(select.radius);
            break;
          case scene::SelectAction::Operation::Auto: {
            FillRequest request;
            request.source = static_cast<canvas::LayerId>(select.layer);
            request.seedX = select.x;
            request.seedY = select.y;
            request.threshold = select.threshold;
            request.expand = select.expand;
            request.target = FillRequest::Target::Selection;
            request.surface = scene::kSelectionSurface;
            request.combine = select.combine;
            runFillNow(request);
            continue;
          }
        }
        runOperation(std::move(operation));
        continue;
      }
      if (sceneAction.type == scene::SceneAction::Type::Transform) {
        const scene::TransformAction& transform = sceneAction.transform;
        TransformSession& session = transformSession_.emplace();
        session.layer = static_cast<canvas::LayerId>(transform.layer);
        if (transform.selection) {
          session.selection = scene::kSelectionSurface;
        }
        session.triangles = transform.mesh;
        session.filter = transform.filter;
        liftTransformContent();
        session.shownTiles = transformMeshTiles();
        session.endRequested = true;
        session.commit = true;
        finishTransform();
        continue;
      }
      if (sceneAction.type == scene::SceneAction::Type::Adjust) {
        const scene::AdjustAction& adjustment = sceneAction.adjust;
        AdjustmentSession& session = adjustmentSession_.emplace();
        session.layer = static_cast<canvas::LayerId>(adjustment.layer);
        if (adjustment.selection) {
          session.selection = scene::kSelectionSurface;
        }
        if (adjustment.pencil) {
          session.mask = scene::kAdjustmentMaskSurface;
        }
        session.parameters = adjustment.parameters;
        session.amount = static_cast<float>(adjustment.amount);
        session.endRequested = true;
        session.commit = true;
        finishAdjustment();
        if (adjustment.pencil) {
          DocumentOperation clear;
          clear.kind = DocumentOperation::Kind::Clear;
          clear.surface = scene::kAdjustmentMaskSurface;
          runOperation(std::move(clear));
        }
        continue;
      }
      if (sceneAction.type == scene::SceneAction::Type::Liquify) {
        const scene::LiquifyAction& liquify = sceneAction.liquify;
        AdjustmentSession& session = adjustmentSession_.emplace();
        session.layer = static_cast<canvas::LayerId>(liquify.layer);
        if (liquify.selection) {
          session.selection = scene::kSelectionSurface;
        }
        session.parameters.filter = adjust::Filter::Liquify;
        session.parameters.values[4] = 1;
        session.liquifyField.emplace(
            scene::liquifyField(liquify, compositor_->widthPixels(), compositor_->heightPixels()));
        session.endRequested = true;
        session.commit = true;
        finishAdjustment();
        continue;
      }
      if (sceneAction.type == scene::SceneAction::Type::Text) {
        writeSceneText(sceneAction.text);
        continue;
      }
      if (sceneAction.type == scene::SceneAction::Type::CopyLayer) {
        const scene::CopyLayerAction& copy = sceneAction.copyLayer;
        DocumentOperation operation;
        operation.source = static_cast<canvas::LayerId>(copy.from);
        operation.surface = static_cast<canvas::LayerId>(copy.to);
        if (copy.masked) {
          operation.kind = DocumentOperation::Kind::CopyMasked;
          operation.limit = scene::kSelectionSurface;
        } else {
          DocumentOperation clear;
          clear.kind = DocumentOperation::Kind::Clear;
          clear.surface = operation.surface;
          runOperation(std::move(clear));
          operation.kind = DocumentOperation::Kind::Copy;
        }
        runOperation(std::move(operation));
        continue;
      }
      if (sceneAction.type == scene::SceneAction::Type::FloodFill) {
        const scene::FloodFillAction& fill = sceneAction.floodFill;
        const canvas::LayerTreeNode* layer =
            document_->tree.find(static_cast<canvas::LayerId>(fill.layer));
        FillRequest request;
        request.source = static_cast<canvas::LayerId>(fill.source);
        request.seedX = fill.x;
        request.seedY = fill.y;
        request.threshold = fill.threshold;
        request.expand = fill.expand;
        request.whole = fill.whole;
        request.target = fill.mask ? FillRequest::Target::Mask : FillRequest::Target::Layer;
        request.surface = fill.mask ? layer->mask->id : layer->id;
        request.color = {static_cast<float>(fill.color[0]), static_cast<float>(fill.color[1]),
                         static_cast<float>(fill.color[2]), static_cast<float>(fill.color[3])};
        if (fill.selection) {
          request.selection = scene::kSelectionSurface;
        }
        runFillNow(request);
        continue;
      }
      if (sceneAction.type == scene::SceneAction::Type::Fill) {
        const scene::FillAction& fill = sceneAction.fill;
        if (fill.selection) {
          fillSceneRect(fill, scene::kSelectionSurface);
          continue;
        }
        const canvas::LayerTreeNode* layer =
            document_->tree.find(static_cast<canvas::LayerId>(fill.layer));
        fillSceneRect(fill, fill.mask ? layer->mask->id : layer->id);
        continue;
      }
      const scene::StrokeAction& action = sceneAction.stroke;
      if (action.tool == scene::Tool::Smudge &&
          (action.mask || action.selectionTarget || action.clone)) {
        continue;
      }
      PaintSession& session = startPaintSession(
          action.tool == scene::Tool::Erase    ? StrokeTool::Erase
          : action.tool == scene::Tool::Smudge ? StrokeTool::Smudge
                                               : StrokeTool::Paint,
          static_cast<canvas::LayerId>(action.layer), action.mask,
          action.adjustmentMaskTarget ? std::optional(scene::kAdjustmentMaskSurface)
          : action.selection || action.selectionTarget ? std::optional(scene::kSelectionSurface)
                                                       : std::nullopt,
          action.selectionTarget, action.clone.has_value(), action.dual, action.brush,
          action.settings, action.shapeTexture, action.grainTexture);
      if (action.clone) {
        prepareCloneSource(StrokeStyle::Clone{action.clone->composite,
                                              static_cast<canvas::LayerId>(action.clone->layer),
                                              action.clone->offset});
      }
      bool canceled = false;
      auto flushPrediction = [&] {
        if (session.prediction.empty()) {
          return;
        }
        session.provisionalOutdated = true;
        updateProvisionalStroke(session);
        compositor_->update(compositor_->canvasTiles(), 0, 0,
                            std::chrono::steady_clock::now() + std::chrono::hours(1),
                            kMaximumLayerTileBudget);
        session.prediction.clear();
      };
      auto play = [&](const std::vector<stroke::InputSample>& samples) {
        for (const stroke::InputSample& sample : samples) {
          if (stroke::isCanceledSample(sample)) {
            canceled = true;
            break;
          }
          if ((sample.flags & stroke::kSamplePredicted) != 0) {
            session.prediction.push_back(sample);
            continue;
          }
          flushPrediction();
          if (stroke::isPathSample(sample)) {
            dabScratch_.clear();
            dualScratch_.clear();
            session.latestInputNanos = std::max(session.latestInputNanos, sample.eventTimeNanos);
            addStrokePoint(session, stroke::pathPointFrom(sample));
            renderStrokeDabs(session, dabScratch_);
          }
        }
        flushPrediction();
      };
      play(action.samples);
      if (!canceled && !action.replacement.empty()) {
        compositor_->update(compositor_->canvasTiles(), 0, 0,
                            std::chrono::steady_clock::now() + std::chrono::hours(1),
                            kMaximumLayerTileBudget);
        restartPaintSession(session);
        play(action.replacement);
      }
      if (canceled) {
        abortStroke();
        continue;
      }
      clearProvisionalStroke();
      finishStrokePoints(session);
      renderStrokeDabs(session, dabScratch_);
      session.ended = true;
      advanceParticles(session, static_cast<double>(session.latestInputNanos) / 1e6 +
                                    stroke::ParticleSystem::kMaximumTailMilliseconds);
      session.liftTime = std::chrono::steady_clock::now() - std::chrono::hours(1);
      tryCommitStroke();
      if (paintSession_) {
        abortStroke();
        fail("stroke commit did not complete (layer tiles not resident)");
        return;
      }
    }
    sendCompositePixels(requestId);
  });
}

void EngineContext::readComposite(int32_t requestId, bool deep) {
  post([this, requestId, deep] {
    runStrokeTask([this, requestId, deep] {
      if (!document_) {
        callbacks_->onSceneRendered(requestId, 0, 0, {}, "no document");
        return;
      }
      if (!sendCompositePixels(requestId, deep)) {
        readComposite(requestId, deep);
      }
    });
  });
}

void EngineContext::renderViewportImage(int32_t requestId, ViewportImageRequest request) {
  post([this, requestId, request] {
    runStrokeTask([this, requestId, request] {
      if (!document_ || !compositor_->isConfigured() || request.widthPixels < 1 ||
          request.heightPixels < 1) {
        callbacks_->onSceneRendered(requestId, 0, 0, {}, "no document or empty image");
        return;
      }
      if (compositor_->update(compositor_->canvasTiles(), compositor_->levelCount() - 1, 0,
                              std::chrono::steady_clock::now() + std::chrono::hours(1),
                              kMaximumLayerTileBudget)) {
        renderViewportImage(requestId, request);
        return;
      }
      const auto shownSelection = selectionOverlay_->surface();
      std::optional<canvas::SelectionDisplay> selection;
      if (request.selection) {
        selectionOverlay_->setSurface(request.selection);
        while (selectionOverlay_->update(*tileStore_, std::numeric_limits<int32_t>::max())) {
        }
        if (selectionOverlay_->isReady()) {
          selection = canvas::SelectionDisplay{
              selectionOverlay_->texture(),      selectionOverlay_->reduction(),
              selectionOverlay_->textureWidth(), selectionOverlay_->textureHeight(),
              request.dimOutsideSelection,       request.antsPhasePixels};
        }
      }
      gpu::rhi::TextureDescription description;
      description.width = request.widthPixels;
      description.height = request.heightPixels;
      description.usage = gpu::rhi::kUsageRenderTarget | gpu::rhi::kUsageReadback;
      const std::unique_ptr<gpu::rhi::Texture> target = device_->createTexture(description);
      if (!target) {
        callbacks_->onSceneRendered(requestId, 0, 0, {}, "out of GPU memory");
        return;
      }
      canvas::ViewportStyle style = viewportStyle_;
      style.showPixels = request.showPixels;
      viewportRenderer_->render(*compositor_, request.canvasToScreen,
                                gpu::rhi::RenderTarget::of(*target), style, nullptr, std::nullopt,
                                std::nullopt, selection);
      const auto rowBytes = static_cast<size_t>(request.widthPixels) * 4;
      std::vector<uint8_t> read(rowBytes * static_cast<size_t>(request.heightPixels));
      device_->commands().readTexture(
          *target, gpu::rhi::Rect{0, 0, request.widthPixels, request.heightPixels}, read.data());
      std::vector<uint8_t> pixels(read.size());
      for (int32_t row = 0; row < request.heightPixels; ++row) {
        std::copy_n(read.data() + static_cast<size_t>(request.heightPixels - 1 - row) * rowBytes,
                    rowBytes, pixels.data() + static_cast<size_t>(row) * rowBytes);
      }
      if (request.selection) {
        selectionOverlay_->setSurface(shownSelection);
      }
      frameRequested_ = true;
      callbacks_->onSceneRendered(requestId, request.widthPixels, request.heightPixels,
                                  std::move(pixels), "");
    });
  });
}

void EngineContext::requestThumbnails(int32_t requestId, std::vector<canvas::LayerId> surfaces,
                                      std::vector<bool> masks, int32_t maxSide) {
  post([this, requestId, surfaces = std::move(surfaces), masks = std::move(masks), maxSide] {
    runStrokeTask([this, requestId, surfaces, masks, maxSide] {
      if (!document_ || surfaces.size() != masks.size() || maxSide < 1) {
        callbacks_->onSceneRendered(requestId, 0, 0, {}, "invalid thumbnail request");
        return;
      }
      if (!sendThumbnails(requestId, surfaces, masks, maxSide)) {
        requestThumbnails(requestId, surfaces, masks, maxSide);
      }
    });
  });
}

void EngineContext::sampleColor(int32_t requestId, double canvasX, double canvasY,
                                int32_t size) {
  post([this, requestId, canvasX, canvasY, size] {
    if (!document_ || !compositor_->isConfigured()) {
      callbacks_->onSceneRendered(requestId, 0, 0, {}, "no document");
      return;
    }
    const auto centerX = static_cast<int32_t>(std::floor(canvasX));
    const auto centerY = static_cast<int32_t>(std::floor(canvasY));
    if (centerX < 0 || centerY < 0 || centerX >= document_->widthPixels ||
        centerY >= document_->heightPixels) {
      callbacks_->onSceneRendered(requestId, 0, 0, {}, "outside the canvas");
      return;
    }
    const canvas::TileRange around =
        canvas::tileRangeCoveringRect(centerX - 1, centerY - 1, centerX + 2, centerY + 2)
            .intersection(compositor_->canvasTiles());
    compositor_->update(around, 0, 0, std::chrono::steady_clock::now() + std::chrono::seconds(1),
                        kMaximumLayerTileBudget);
    const int32_t radius = size >= 3 ? 1 : 0;
    const int32_t left = std::max(centerX - radius, 0);
    const int32_t top = std::max(centerY - radius, 0);
    const int32_t right = std::min(centerX + radius + 1, document_->widthPixels);
    const int32_t bottom = std::min(centerY + radius + 1, document_->heightPixels);
    const int32_t width = right - left;
    const int32_t height = bottom - top;
    std::vector<uint8_t> block(static_cast<size_t>(width) * static_cast<size_t>(height) * 4);
    if (!readRegionRgba8(compositor_->levelTexture(0), gpu::rhi::Rect{left, top, width, height},
                         block.data())) {
      callbacks_->onSceneRendered(requestId, 0, 0, {}, "readback failed");
      return;
    }
    std::array<uint32_t, 4> sum{};
    for (size_t offset = 0; offset < block.size(); offset += 4) {
      for (size_t channel = 0; channel < 4; ++channel) {
        sum[channel] += block[offset + channel];
      }
    }
    const auto count = static_cast<uint32_t>(width * height);
    std::vector<uint8_t> pixel(4);
    const uint32_t alpha = (sum[3] + count / 2) / count;
    for (size_t channel = 0; channel < 3; ++channel) {
      const uint32_t premultiplied = (sum[channel] + count / 2) / count;
      pixel[channel] = alpha == 0 ? 0
                                  : static_cast<uint8_t>(std::min<uint32_t>(
                                        255, (premultiplied * 255U + alpha / 2) / alpha));
    }
    pixel[3] = static_cast<uint8_t>(alpha);
    callbacks_->onSceneRendered(requestId, 1, 1, pixel, "");
  });
}

void EngineContext::sampleLayerAlphas(int32_t requestId, int32_t canvasX, int32_t canvasY,
                                      std::vector<canvas::LayerId> surfaces) {
  post([this, requestId, canvasX, canvasY, surfaces = std::move(surfaces)]() mutable {
    sampleLayerAlphasNow(requestId, canvasX, canvasY, std::move(surfaces), 0);
  });
}

void EngineContext::sampleLayerAlphasNow(int32_t requestId, int32_t canvasX, int32_t canvasY,
                                         std::vector<canvas::LayerId> surfaces, int32_t attempt) {
  constexpr int32_t kMaximumAttempts = 120;
  if (!document_ || canvasX < 0 || canvasY < 0 || canvasX >= document_->widthPixels ||
      canvasY >= document_->heightPixels) {
    callbacks_->onSceneRendered(requestId, 0, 0, {}, "outside the canvas");
    return;
  }
  const canvas::TileCoordinate coordinate{canvasX / canvas::kTileSizePixels,
                                          canvasY / canvas::kTileSizePixels};
  bool loading = false;
  for (const canvas::LayerId surface : surfaces) {
    if (tileStore_->ensureResident(canvas::LayerTileKey{surface, coordinate}) ==
        canvas::LayerTileStore::Availability::Loading) {
      loading = true;
    }
  }
  if (loading && attempt < kMaximumAttempts) {
    post([this, requestId, canvasX, canvasY, surfaces = std::move(surfaces), attempt]() mutable {
      sampleLayerAlphasNow(requestId, canvasX, canvasY, std::move(surfaces), attempt + 1);
    });
    frameRequested_ = true;
    return;
  }
  std::vector<uint8_t> alphas(surfaces.size(), 0);
  const int32_t localX = canvasX - coordinate.column * canvas::kTileSizePixels;
  const int32_t localY = canvasY - coordinate.row * canvas::kTileSizePixels;
  for (size_t index = 0; index < surfaces.size(); ++index) {
    const auto slot = tileStore_->slotForRead(canvas::LayerTileKey{surfaces[index], coordinate});
    if (!slot) {
      continue;
    }
    std::array<uint8_t, 4> pixel{};
    if (readRegionRgba8(tileStore_->pageTexture(slot->page),
                        gpu::rhi::Rect{canvas::slotOriginX(*slot) + localX,
                                       canvas::slotOriginY(*slot) + localY, 1, 1},
                        pixel.data())) {
      alphas[index] = pixel[3];
    }
  }
  callbacks_->onSceneRendered(requestId, static_cast<int32_t>(alphas.size()), 1, alphas, "");
}

bool EngineContext::sendThumbnails(int32_t requestId, const std::vector<canvas::LayerId>& surfaces,
                                   const std::vector<bool>& masks, int32_t maxSide) {
  bool resident = true;
  for (const canvas::LayerId surface : surfaces) {
    for (const canvas::TileCoordinate coordinate : tileStore_->tilesOf(surface)) {
      if (tileStore_->ensureResident(canvas::LayerTileKey{surface, coordinate}) ==
          canvas::LayerTileStore::Availability::Loading) {
        resident = false;
      }
    }
  }
  if (!resident) {
    return false;
  }
  const int32_t longSide = std::max(document_->widthPixels, document_->heightPixels);
  int32_t stride = 1;
  while (stride < canvas::kTileSizePixels && longSide / stride > maxSide) {
    stride *= 2;
  }
  const int32_t cellWidth = (document_->widthPixels + stride - 1) / stride;
  const int32_t cellHeight = (document_->heightPixels + stride - 1) / stride;
  const int32_t width = cellWidth * static_cast<int32_t>(surfaces.size());
  if (width == 0) {
    callbacks_->onSceneRendered(requestId, 0, cellHeight, {}, "");
    return true;
  }
  gpu::rhi::TextureDescription description;
  description.width = width;
  description.height = cellHeight;
  description.usage = gpu::rhi::kUsageRenderTarget | gpu::rhi::kUsageReadback;
  const std::unique_ptr<gpu::rhi::Texture> target = device_->createTexture(description);
  if (!target) {
    callbacks_->onSceneRendered(requestId, 0, 0, {}, "out of GPU memory");
    return true;
  }
  gpu::rhi::CommandContext& commands = device_->commands();
  commands.beginPass(gpu::rhi::RenderTarget::of(*target), gpu::rhi::LoadAction::DontCare);
  commands.bindPipeline(*thumbnailPipeline_);
  ThumbnailTileParameters parameters{};
  parameters.sampleStride = stride;
  const int32_t tileCells = canvas::kTileSizePixels / stride;
  for (size_t index = 0; index < surfaces.size(); ++index) {
    const int32_t cellX = static_cast<int32_t>(index) * cellWidth;
    const float clear = masks[index] ? 1.0F : 0.0F;
    commands.clear({clear, clear, clear, clear}, gpu::rhi::Rect{cellX, 0, cellWidth, cellHeight});
    parameters.encode = compositor_->linearLight() && !masks[index] ? 1 : 0;
    for (const canvas::TileCoordinate coordinate : tileStore_->tilesOf(surfaces[index])) {
      const auto slot = tileStore_->slotForRead(canvas::LayerTileKey{surfaces[index], coordinate});
      if (!slot) {
        continue;
      }
      const int32_t originX = cellX + coordinate.column * tileCells;
      const int32_t originY = coordinate.row * tileCells;
      if (originX >= cellX + cellWidth || originY >= cellHeight) {
        continue;
      }
      commands.setScissor(gpu::rhi::Rect{originX, originY,
                                         std::min(tileCells, cellX + cellWidth - originX),
                                         std::min(tileCells, cellHeight - originY)});
      commands.bindTexture(kThumbnailSourceBinding, &tileStore_->pageTexture(slot->page));
      parameters.slotOrigin = {canvas::slotOriginX(*slot), canvas::slotOriginY(*slot)};
      parameters.targetOrigin = {originX, originY};
      commands.setUniforms(parameters);
      commands.draw(3);
    }
  }
  commands.endPass();
  std::vector<uint8_t> pixels(static_cast<size_t>(width) * static_cast<size_t>(cellHeight) * 4);
  commands.readTexture(*target, gpu::rhi::Rect{0, 0, width, cellHeight}, pixels.data());
  for (size_t offset = 0; offset < pixels.size(); offset += 4) {
    const uint32_t alpha = pixels[offset + 3];
    for (size_t channel = 0; channel < 3; ++channel) {
      pixels[offset + channel] =
          alpha == 0 ? 0
                     : static_cast<uint8_t>(std::min<uint32_t>(
                           255, (pixels[offset + channel] * 255U + alpha / 2) / alpha));
    }
  }
  callbacks_->onSceneRendered(requestId, width, cellHeight, pixels, "");
  return true;
}

bool EngineContext::sendCompositePixels(int32_t requestId, bool deep) {
  {
    compositor_->invalidateAll();
    const canvas::TileRange everything = compositor_->canvasTiles();
    const auto farDeadline = std::chrono::steady_clock::now() + std::chrono::hours(1);
    constexpr int kMaximumUpdates = 1000;
    for (int update = 0;
         update < kMaximumUpdates &&
         compositor_->update(everything, 0, 0, farDeadline, kMaximumLayerTileBudget);
         ++update) {
      if (tileStore_->stats().loadsInFlight > 0) {
        return false;
      }
    }
    const int32_t width = document_->widthPixels;
    const int32_t height = document_->heightPixels;
    if (deep && compositor_->linearLight()) {
      callbacks_->onSceneRendered(requestId, width, height, readDeepComposite(), "");
      frameRequested_ = true;
      return true;
    }
    std::vector<uint8_t> pixels(static_cast<size_t>(width) * static_cast<size_t>(height) * 4);
    if (!readRegionRgba8(compositor_->levelTexture(0), gpu::rhi::Rect{0, 0, width, height},
                         pixels.data())) {
      callbacks_->onSceneRendered(requestId, 0, 0, {}, "readback failed");
      return true;
    }
    for (size_t offset = 0; offset < pixels.size(); offset += 4) {
      const uint32_t alpha = pixels[offset + 3];
      for (size_t channel = 0; channel < 3; ++channel) {
        pixels[offset + channel] =
            alpha == 0 ? 0
                       : static_cast<uint8_t>(std::min<uint32_t>(
                             255, (pixels[offset + channel] * 255U + alpha / 2) / alpha));
      }
    }
    callbacks_->onSceneRendered(requestId, width, height, pixels, "");
    frameRequested_ = true;
  }
  return true;
}

std::vector<uint8_t> EngineContext::readDeepComposite() {
  constexpr int32_t kBandRows = 64;
  const int32_t width = document_->widthPixels;
  const int32_t height = document_->heightPixels;
  const size_t pixelsPerRow = static_cast<size_t>(width);
  std::vector<uint8_t> result(pixelsPerRow * static_cast<size_t>(height) * 8);
  std::vector<uint8_t> halves(pixelsPerRow * kBandRows * 8);
  std::vector<uint16_t> samples(pixelsPerRow * kBandRows * 4);
  for (int32_t top = 0; top < height; top += kBandRows) {
    const int32_t rows = std::min(kBandRows, height - top);
    const size_t count = pixelsPerRow * static_cast<size_t>(rows);
    device_->commands().readTexture(compositor_->levelTexture(0),
                                    gpu::rhi::Rect{0, top, width, rows}, halves.data());
    canvas::premultipliedLinearHalfToStraightSrgb16(halves.data(), samples.data(), count);
    uint8_t* out = result.data() + static_cast<size_t>(top) * pixelsPerRow * 8;
    for (size_t index = 0; index < count * 4; ++index) {
      out[index * 2] = static_cast<uint8_t>(samples[index] & 0xFFU);
      out[index * 2 + 1] = static_cast<uint8_t>(samples[index] >> 8U);
    }
  }
  return result;
}

void EngineContext::writeSceneText(const scene::TextAction& action) {
  const auto layer = static_cast<canvas::LayerId>(action.layer);
  DocumentOperation clear;
  clear.kind = DocumentOperation::Kind::Clear;
  clear.surface = layer;
  while (!stepOperation(clear, std::chrono::steady_clock::now() + std::chrono::hours(1))) {
  }
  std::string error;
  const std::optional<text::TextModel> model = action.model(compositor_->linearLight(), error);
  if (!model) {
    NANQUIM_LOG_ERROR("Scene text: %s", error.c_str());
    return;
  }
  writeTextIntoLayer(layer, *model);
}

bool EngineContext::writeTextIntoLayer(canvas::LayerId layer, const text::TextModel& model) {
  const text::TextLayout layout = text::TextLayout::build(model.layout);
  const text::TextRasterizer rasterizer(layout, model.paints, model.transform);
  frameRequested_ = true;
  const auto bounds = rasterizer.bounds();
  if (!bounds) {
    return true;
  }
  std::vector<uint8_t> tile(static_cast<size_t>(tileStore_->tileBytes()));
  bool written = true;
  tilesUnder(*bounds).forEach([&](canvas::TileCoordinate coordinate) {
    written = written && writeTextTile(layer, rasterizer, coordinate, tile);
  });
  return written;
}

canvas::TileRange EngineContext::tilesUnder(const text::PixelRect& rect) const {
  const int32_t left = std::max(rect.x, 0);
  const int32_t top = std::max(rect.y, 0);
  const int32_t right = std::min(rect.x + rect.width, document_->widthPixels);
  const int32_t bottom = std::min(rect.y + rect.height, document_->heightPixels);
  if (right <= left || bottom <= top) {
    return canvas::TileRange{};
  }
  return canvas::tileRangeCoveringRect(left, top, right, bottom);
}

bool EngineContext::writeTextTile(canvas::LayerId layer, const text::TextRasterizer& rasterizer,
                                  canvas::TileCoordinate coordinate, std::vector<uint8_t>& tile) {
  const canvas::LayerTileKey key{layer, coordinate};
  const text::PixelRect target{coordinate.column * canvas::kTileSizePixels,
                               coordinate.row * canvas::kTileSizePixels, canvas::kTileSizePixels,
                               canvas::kTileSizePixels};
  const std::vector<float> pixels =
      rasterizer.reaches(target) ? rasterizer.rasterize(target) : std::vector<float>();
  const bool halfFloat = tileStore_->pixelFormat() == canvas::PixelFormat::Rgba16fPremulLinear;
  std::fill(tile.begin(), tile.end(), 0);
  bool empty = true;
  const size_t pixelCount = pixels.size() / 4;
  for (size_t pixel = 0; pixel < pixelCount; ++pixel) {
    if (pixels[pixel * 4 + 3] <= 0) {
      continue;
    }
    empty = false;
    for (size_t channel = 0; channel < 4; ++channel) {
      const size_t index = pixel * 4 + channel;
      const float value = std::clamp(pixels[index], 0.0F, 1.0F);
      if (halfFloat) {
        const uint16_t half = core::floatToHalf(value);
        std::memcpy(tile.data() + index * 2, &half, 2);
      } else {
        tile[index] = static_cast<uint8_t>(std::lround(static_cast<double>(value) * 255.0));
      }
    }
  }
  if (empty) {
    tileStore_->removeTile(key);
  } else if (!tileStore_->writeTile(key, tile.data())) {
    return false;
  }
  markTileChanged(key);
  return true;
}

void EngineContext::registerFont(std::string fontId, std::string path, std::vector<uint8_t> data,
                                 int32_t faceIndex) {
  post([this, fontId = std::move(fontId), path = std::move(path), data = std::move(data),
        faceIndex]() mutable {
    RegisteredFont& font = fonts_[fontId];
    font = RegisteredFont{};
    font.path = std::move(path);
    font.data = std::move(data);
    font.faceIndex = faceIndex;
  });
}

void EngineContext::prefetchSurfaces(std::vector<canvas::LayerId> surfaces) {
  post([this, surfaces = std::move(surfaces)]() mutable { prefetchSurfaces_ = std::move(surfaces); });
}

void EngineContext::prefetchTiles() {
  for (const canvas::LayerId surface : prefetchSurfaces_) {
    for (const canvas::TileCoordinate coordinate : tileStore_->tilesOf(surface)) {
      tileStore_->ensureResident(canvas::LayerTileKey{surface, coordinate});
    }
  }
}

void EngineContext::setFontFallbacks(std::vector<std::string> fontIds) {
  post([this, fontIds = std::move(fontIds)]() mutable { fontFallbacks_ = std::move(fontIds); });
}

void EngineContext::setTextLayer(canvas::LayerId layer, std::string modelJson, double dpi) {
  post([this, layer, modelJson = std::move(modelJson), dpi] {
    pendingSurfaceWrites_.emplace_back(
        [this, layer, modelJson, dpi] { return rasterizeTextLayerNow(layer, modelJson, dpi); });
    frameRequested_ = true;
  });
}

void EngineContext::requestTextGeometry(int32_t requestId, std::string modelJson, double dpi) {
  post([this, requestId, modelJson = std::move(modelJson), dpi] {
    std::vector<const text::FontFace*> fallbacks;
    for (const std::string& fontId : fontFallbacks_) {
      if (const text::FontFace* face = fontFace(fontId)) {
        fallbacks.push_back(face);
      }
    }
    std::string error;
    const std::optional<text::TextModel> model = text::parseTextModel(
        modelJson, dpi, [this](const std::string& fontId) { return fontFace(fontId); }, fallbacks,
        false, error);
    core::JsonWriter writer;
    writer.beginObject();
    if (!model) {
      writer.key("error");
      writer.stringValue(error);
      writer.endObject();
      callbacks_->onStats(requestId, writer.text());
      return;
    }
    const text::TextLayout layout = text::TextLayout::build(model->layout);
    const auto numbers = [&writer](std::initializer_list<double> values) {
      writer.beginArray();
      for (const double value : values) {
        writer.numberValue(value);
      }
      writer.endArray();
    };
    writer.key("width");
    writer.numberValue(layout.width());
    writer.key("height");
    writer.numberValue(layout.height());
    writer.key("vertical");
    writer.booleanValue(layout.vertical());
    writer.key("length");
    writer.integerValue(static_cast<int64_t>(layout.text().size()));
    writer.key("transform");
    writer.beginArray();
    for (const double value : model->transform) {
      writer.numberValue(value);
    }
    writer.endArray();
    writer.key("lines");
    writer.beginArray();
    for (const text::LayoutLine& line : layout.lines()) {
      numbers({line.box.x, line.box.y, line.box.width, line.box.height});
    }
    writer.endArray();
    writer.key("clusters");
    writer.beginArray();
    for (const text::ClusterBox& cluster : layout.clusters()) {
      numbers({static_cast<double>(cluster.start), static_cast<double>(cluster.end),
               static_cast<double>(cluster.line), cluster.from, cluster.to,
               cluster.rightToLeft ? 1.0 : 0.0});
    }
    writer.endArray();
    writer.key("carets");
    writer.beginArray();
    for (size_t index = 0; index <= layout.text().size(); ++index) {
      const auto caret = layout.caret(index);
      numbers({caret[0], caret[1], caret[2], caret[3]});
    }
    writer.endArray();
    writer.endObject();
    callbacks_->onStats(requestId, writer.text());
  });
}

const text::FontFace* EngineContext::fontFace(const std::string& fontId) {
  const auto found = fonts_.find(fontId);
  if (found == fonts_.end() || found->second.failed) {
    return nullptr;
  }
  RegisteredFont& font = found->second;
  if (!font.face) {
    if (!fontLibrary_) {
      fontLibrary_ = std::make_unique<text::FontLibrary>();
    }
    std::vector<uint8_t> bytes = font.data;
    if (bytes.empty() && !font.path.empty()) {
      bytes = io::readFile(font.path, static_cast<int64_t>(text::kMaximumFontBytes))
                  .value_or(std::vector<uint8_t>{});
    }
    std::string error;
    font.face = fontLibrary_->load(std::move(bytes), font.faceIndex, error);
    if (!font.face) {
      NANQUIM_LOG_ERROR("Font %s: %s", fontId.c_str(), error.c_str());
      font.failed = true;
      return nullptr;
    }
    font.data.clear();
  }
  return font.face.get();
}

bool EngineContext::rasterizeTextLayerNow(canvas::LayerId layer, const std::string& modelJson,
                                          double dpi) {
  if (!document_ || document_->tree.find(layer) == nullptr) {
    textLayerShapes_.erase(layer);
    return true;
  }
  if (tileStore_->isOverGpuBudget() || saver_->blocksTileWrites()) {
    return false;
  }
  ATrace_beginSection("NanquimRasterizeText");
  const auto started = std::chrono::steady_clock::now();
  std::optional<text::TextModel> model;
  if (!modelJson.empty()) {
    std::vector<const text::FontFace*> fallbacks;
    for (const std::string& fontId : fontFallbacks_) {
      if (const text::FontFace* face = fontFace(fontId)) {
        fallbacks.push_back(face);
      }
    }
    std::string error;
    model = text::parseTextModel(
        modelJson, dpi, [this](const std::string& fontId) { return fontFace(fontId); }, fallbacks,
        compositor_->linearLight(), error);
    if (!model) {
      NANQUIM_LOG_ERROR("Text layer %u: %s", layer, error.c_str());
    }
  }
  const auto clearLayer = [this, layer] {
    DocumentOperation clear;
    clear.kind = DocumentOperation::Kind::Clear;
    clear.surface = layer;
    while (!stepOperation(clear, std::chrono::steady_clock::now() + std::chrono::hours(1))) {
    }
  };
  bool done = true;
  const auto previous = textLayerShapes_.find(layer);
  const bool incremental = model && previous != textLayerShapes_.end() &&
                           previous->second.widthPixels == document_->widthPixels &&
                           previous->second.heightPixels == document_->heightPixels;
  if (!model) {
    clearLayer();
    textLayerShapes_.erase(layer);
  } else {
    const text::TextLayout layout = text::TextLayout::build(model->layout);
    const text::TextRasterizer rasterizer(layout, model->paints, model->transform);
    std::vector<text::TextRasterizer::ShapeSignature> next = rasterizer.signatures();
    std::vector<uint8_t> tile(static_cast<size_t>(tileStore_->tileBytes()));
    if (!incremental) {
      clearLayer();
      if (const auto bounds = rasterizer.bounds()) {
        tilesUnder(*bounds).forEach([&](canvas::TileCoordinate coordinate) {
          done = done && writeTextTile(layer, rasterizer, coordinate, tile);
        });
      }
    } else {
      std::unordered_map<uint64_t, int32_t> balance;
      for (const auto& shape : previous->second.shapes) {
        ++balance[shape.hash];
      }
      for (const auto& shape : next) {
        --balance[shape.hash];
      }
      std::set<std::pair<int32_t, int32_t>> dirty;
      const auto markDirty = [&](const text::PixelRect& rect) {
        tilesUnder(rect).forEach([&](canvas::TileCoordinate coordinate) {
          dirty.emplace(coordinate.row, coordinate.column);
        });
      };
      std::unordered_map<uint64_t, int32_t> gone = balance;
      for (const auto& shape : previous->second.shapes) {
        if (gone[shape.hash] > 0) {
          --gone[shape.hash];
          markDirty(shape.bounds);
        }
      }
      std::unordered_map<uint64_t, int32_t> added = balance;
      for (const auto& shape : next) {
        if (added[shape.hash] < 0) {
          ++added[shape.hash];
          markDirty(shape.bounds);
        }
      }
      for (const auto& [row, column] : dirty) {
        done = done && writeTextTile(layer, rasterizer, canvas::TileCoordinate{column, row}, tile);
      }
    }
    if (done) {
      textLayerShapes_[layer] = {document_->widthPixels, document_->heightPixels, std::move(next)};
    }
    frameRequested_ = true;
  }
  ATrace_endSection();
  if (done) {
    textRasterMilliseconds_.push_back(
        std::chrono::duration<float, std::milli>(std::chrono::steady_clock::now() - started)
            .count());
    if (textRasterMilliseconds_.size() > kLatencyHistorySize) {
      textRasterMilliseconds_.pop_front();
    }
  } else {
    textLayerShapes_.erase(layer);
  }
  return done;
}

void EngineContext::fillSceneRect(const scene::FillAction& fill, canvas::LayerId surface) {
  const int32_t firstX = std::max(fill.rect[0], 0);
  const int32_t firstY = std::max(fill.rect[1], 0);
  const int32_t endX = std::min(fill.rect[0] + fill.rect[2], document_->widthPixels);
  const int32_t endY = std::min(fill.rect[1] + fill.rect[3], document_->heightPixels);
  if (endX <= firstX || endY <= firstY) {
    return;
  }
  const canvas::TileRange tiles = canvas::tileRangeCoveringRect(firstX, firstY, endX, endY);
  const auto& color = fill.color;
  const auto& toColor = fill.toColor ? *fill.toColor : fill.color;
  FillRectParameters parameters{};
  parameters.rect = {static_cast<float>(fill.rect[0]), static_cast<float>(fill.rect[1]),
                     static_cast<float>(fill.rect[2]), static_cast<float>(fill.rect[3])};
  parameters.color = {static_cast<float>(color[0]), static_cast<float>(color[1]),
                      static_cast<float>(color[2]), static_cast<float>(color[3])};
  parameters.toColor = {static_cast<float>(toColor[0]), static_cast<float>(toColor[1]),
                        static_cast<float>(toColor[2]), static_cast<float>(toColor[3])};
  parameters.gradient = fill.toColor ? 1 : 0;
  parameters.vertical = fill.vertical ? 1 : 0;
  parameters.maskTarget = fill.mask || fill.selection ? 1 : 0;
  parameters.linearDocument = compositor_->linearLight() ? 1 : 0;
  gpu::rhi::CommandContext& commands = device_->commands();
  tiles.forEach([&](canvas::TileCoordinate coordinate) {
    const canvas::LayerTileKey key{surface, coordinate};
    tileStore_->ensureResident(key);
    const auto target = tileStore_->slotForUpdate(key);
    if (!target) {
      NANQUIM_LOG_ERROR("Fill: no slot for tile (%d, %d)", coordinate.column, coordinate.row);
      return;
    }
    const int32_t slotX = canvas::slotOriginX(target->slot);
    const int32_t slotY = canvas::slotOriginY(target->slot);
    const gpu::rhi::Texture& page = tileStore_->pageTexture(target->slot.page);
    const gpu::rhi::Pipeline* pipeline =
        enginePipeline(EngineProgram::FillRect, page.description().format);
    if (pipeline == nullptr) {
      return;
    }
    commands.beginPass(gpu::rhi::RenderTarget::of(page), gpu::rhi::LoadAction::Load);
    commands.setViewport(
        gpu::rhi::Rect{slotX, slotY, canvas::kTileSizePixels, canvas::kTileSizePixels});
    if (target->created) {
      const float clear = fill.mask ? 1.0F : 0.0F;
      commands.clear(
          {clear, clear, clear, fill.selection ? 1.0F : clear},
          gpu::rhi::Rect{slotX, slotY, canvas::kTileSizePixels, canvas::kTileSizePixels});
    }
    const int32_t tileX = coordinate.column * canvas::kTileSizePixels;
    const int32_t tileY = coordinate.row * canvas::kTileSizePixels;
    const int32_t left = std::max(firstX, tileX);
    const int32_t top = std::max(firstY, tileY);
    const int32_t right = std::min(endX, tileX + canvas::kTileSizePixels);
    const int32_t bottom = std::min(endY, tileY + canvas::kTileSizePixels);
    commands.setScissor(
        gpu::rhi::Rect{slotX + left - tileX, slotY + top - tileY, right - left, bottom - top});
    commands.bindPipeline(*pipeline);
    parameters.slotOrigin = {slotX, slotY};
    parameters.tileCanvasOrigin = {tileX, tileY};
    commands.setUniforms(parameters);
    commands.draw(3);
    commands.endPass();
    markTileChanged(key);
  });
}

bool EngineContext::documentBusy() const {
  return (paintSession_ && paintSession_->ended) || !operations_.empty() ||
         fillSession_.has_value() || transformSession_.has_value() ||
         adjustmentSession_.has_value();
}

void EngineContext::runDeferredTasks() {
  while (!deferredStrokeTasks_.empty() && !documentBusy()) {
    auto task = std::move(deferredStrokeTasks_.front());
    deferredStrokeTasks_.pop_front();
    task();
  }
}

void EngineContext::fillSurface(canvas::LayerId surface, std::array<float, 4> color, bool mask,
                                int64_t historyEntry) {
  post([this, surface, color, mask, historyEntry] {
    runStrokeTask([this, surface, color, mask, historyEntry] {
      DocumentOperation operation;
      operation.kind = DocumentOperation::Kind::Fill;
      operation.surface = surface;
      operation.color = color;
      operation.mask = mask;
      operation.historyEntry = historyEntry;
      operations_.push_back(std::move(operation));
      frameRequested_ = true;
    });
  });
}

void EngineContext::clearSurface(canvas::LayerId surface, int64_t historyEntry) {
  post([this, surface, historyEntry] {
    runStrokeTask([this, surface, historyEntry] {
      DocumentOperation operation;
      operation.kind = DocumentOperation::Kind::Clear;
      operation.surface = surface;
      operation.historyEntry = historyEntry;
      operations_.push_back(std::move(operation));
      frameRequested_ = true;
    });
  });
}

void EngineContext::invertSurface(canvas::LayerId surface, bool mask, int64_t historyEntry) {
  post([this, surface, mask, historyEntry] {
    runStrokeTask([this, surface, mask, historyEntry] {
      DocumentOperation operation;
      operation.kind = DocumentOperation::Kind::Invert;
      operation.surface = surface;
      operation.mask = mask;
      operation.historyEntry = historyEntry;
      operations_.push_back(std::move(operation));
      frameRequested_ = true;
    });
  });
}

void EngineContext::invertSelection(canvas::LayerId selection, int64_t historyEntry) {
  post([this, selection, historyEntry] {
    runStrokeTask([this, selection, historyEntry] {
      DocumentOperation operation;
      operation.kind = DocumentOperation::Kind::Invert;
      operation.surface = selection;
      operation.selection = true;
      operation.historyEntry = historyEntry;
      operations_.push_back(std::move(operation));
      frameRequested_ = true;
    });
  });
}

void EngineContext::selectContents(canvas::LayerId selection, canvas::LayerId layer,
                                   int64_t historyEntry) {
  post([this, selection, layer, historyEntry] {
    runStrokeTask([this, selection, layer, historyEntry] {
      DocumentOperation operation;
      operation.kind = DocumentOperation::Kind::SelectContents;
      operation.surface = selection;
      operation.source = layer;
      operation.historyEntry = historyEntry;
      operations_.push_back(std::move(operation));
      frameRequested_ = true;
    });
  });
}

void EngineContext::selectPolygon(canvas::LayerId selection,
                                  std::vector<canvas::PolygonPoint> polygon,
                                  canvas::SelectionCombine combine, int64_t historyEntry) {
  post([this, selection, polygon = std::move(polygon), combine, historyEntry] {
    runStrokeTask([this, selection, polygon, combine, historyEntry] {
      if (!document_) {
        return;
      }
      DocumentOperation operation;
      operation.kind = DocumentOperation::Kind::SelectPolygon;
      operation.surface = selection;
      operation.combine = combine;
      operation.coverage = std::make_shared<const canvas::TileCoverageMap>(
          canvas::rasterizePolygon(polygon, document_->widthPixels, document_->heightPixels));
      operation.historyEntry = historyEntry;
      operations_.push_back(std::move(operation));
      frameRequested_ = true;
    });
  });
}

void EngineContext::featherSelection(canvas::LayerId selection, double radius,
                                     int64_t historyEntry) {
  post([this, selection, radius, historyEntry] {
    runStrokeTask([this, selection, radius, historyEntry] {
      DocumentOperation operation;
      operation.kind = DocumentOperation::Kind::Feather;
      operation.surface = selection;
      operation.weights = canvas::featherWeights(radius);
      operation.historyEntry = historyEntry;
      operations_.push_back(std::move(operation));
      frameRequested_ = true;
    });
  });
}

void EngineContext::copyMasked(canvas::LayerId source, canvas::LayerId destination,
                               canvas::LayerId selection) {
  post([this, source, destination, selection] {
    runStrokeTask([this, source, destination, selection] {
      DocumentOperation operation;
      operation.kind = DocumentOperation::Kind::CopyMasked;
      operation.source = source;
      operation.surface = destination;
      operation.limit = selection;
      operations_.push_back(std::move(operation));
      frameRequested_ = true;
    });
  });
}

void EngineContext::remapSurface(canvas::LayerId surface, int32_t kind,
                                 std::vector<canvas::MeshVertex> triangles,
                                 canvas::TransformFilter filter, int32_t newWidth,
                                 int32_t newHeight, int64_t historyEntry) {
  post([this, surface, kind, triangles = std::move(triangles), filter, newWidth, newHeight,
        historyEntry]() mutable {
    runStrokeTask([this, surface, kind, triangles = std::move(triangles), filter, newWidth,
                   newHeight, historyEntry] {
      DocumentOperation operation;
      operation.kind = DocumentOperation::Kind::Remap;
      operation.surface = surface;
      operation.surfaceKind = kind;
      operation.triangles = triangles;
      operation.filter = filter;
      operation.newSize = {newWidth, newHeight};
      operation.historyEntry = historyEntry;
      operations_.push_back(std::move(operation));
      frameRequested_ = true;
    });
  });
}

void EngineContext::setCanvasSize(int32_t width, int32_t height) {
  post([this, width, height] {
    runStrokeTask([this, width, height] {
      if (!document_ || (document_->widthPixels == width && document_->heightPixels == height)) {
        return;
      }
      std::string errorMessage;
      if (!compositor_->configure(width, height, errorMessage)) {
        callbacks_->onError(EngineErrorCode::DocumentOpenFailed, errorMessage, true);
        return;
      }
      document_->widthPixels = width;
      document_->heightPixels = height;
      compositor_->setLayerTree(document_->tree);
      compositor_->setActiveLayer(document_->tree.activeLayer);
      const auto shown = selectionOverlay_->surface();
      selectionOverlay_->configure(width, height);
      selectionOverlay_->setSurface(std::nullopt);
      selectionOverlay_->setSurface(shown);
      saver_->setCanvasSize(width, height);
      frameRequested_ = true;
    });
  });
}

void EngineContext::copySurface(canvas::LayerId source, canvas::LayerId destination) {
  post([this, source, destination] {
    runStrokeTask([this, source, destination] {
      DocumentOperation operation;
      operation.kind = DocumentOperation::Kind::Copy;
      operation.source = source;
      operation.surface = destination;
      operations_.push_back(std::move(operation));
      frameRequested_ = true;
    });
  });
}

void EngineContext::composeIntoSurface(canvas::LayerId target, canvas::LayerTree tree,
                                       int64_t historyEntry) {
  post([this, target, tree = std::move(tree), historyEntry]() mutable {
    runStrokeTask([this, target, tree = std::move(tree), historyEntry] {
      DocumentOperation operation;
      operation.kind = DocumentOperation::Kind::Compose;
      operation.surface = target;
      operation.plan = canvas::buildCompositePlan(tree, std::nullopt);
      operation.historyEntry = historyEntry;
      operations_.push_back(std::move(operation));
      frameRequested_ = true;
    });
  });
}

void EngineContext::discardSurfaces(std::vector<canvas::LayerId> surfaces) {
  post([this, surfaces = std::move(surfaces)]() mutable {
    runStrokeTask([this, surfaces = std::move(surfaces)] {
      for (const canvas::LayerId surface : surfaces) {
        if (document_ && document_->tree.find(surface) == nullptr &&
            document_->tree.maskOwner(surface) == nullptr) {
          tileStore_->removeLayer(surface);
        }
      }
    });
  });
}

void EngineContext::applyHistory(int64_t historyEntry, bool undo) {
  post([this, historyEntry, undo] {
    runStrokeTask([this, historyEntry, undo] {
      DocumentOperation operation;
      operation.kind = DocumentOperation::Kind::ApplyHistory;
      operation.historyEntry = historyEntry;
      operation.undo = undo;
      operations_.push_back(std::move(operation));
      frameRequested_ = true;
    });
  });
}

void EngineContext::releaseHistory(std::vector<int64_t> entries) {
  post([this, entries = std::move(entries)] {
    runStrokeTask([this, entries] {
      if (history_) {
        history_->release(entries);
      }
    });
  });
}

void EngineContext::setHistoryRamBudget(int64_t bytes) {
  post([this, bytes] {
    historyRamBudgetBytes_ = bytes;
    if (history_) {
      history_->setRamBudget(bytes);
    }
  });
}

void EngineContext::emitHistoryEntryReady(int64_t entry, int64_t bytes) {
  core::JsonWriter writer;
  writer.beginObject();
  writer.key("type");
  writer.stringValue("historyEntryReady");
  writer.key("entry");
  writer.integerValue(entry);
  writer.key("bytes");
  writer.integerValue(bytes);
  writer.endObject();
  callbacks_->onEvent(writer.text());
}

void EngineContext::processOperations(std::chrono::steady_clock::time_point deadline) {
  if (saver_->blocksTileWrites()) {
    return;
  }
  while (!operations_.empty() && document_) {
    if (!stepOperation(operations_.front(), deadline)) {
      return;
    }
    DocumentOperation& finished = operations_.front();
    if (finished.historyEntry != 0 && finished.kind != DocumentOperation::Kind::ApplyHistory) {
      history_->finishEntry(finished.historyEntry);
    }
    operations_.pop_front();
    frameRequested_ = true;
    runDeferredTasks();
  }
  if (!document_) {
    operations_.clear();
  }
}

bool EngineContext::stepOperation(DocumentOperation& operation,
                                  std::chrono::steady_clock::time_point deadline) {
  using Kind = DocumentOperation::Kind;
  if (operation.kind == Kind::ApplyHistory) {
    const auto result =
        history_->apply(operation.historyEntry, operation.undo,
                        [this](const canvas::LayerTileKey& key) { markTileChanged(key); });
    return result != canvas::HistoryStore::ApplyResult::NotReady;
  }
  if (!operation.started && operation.kind == Kind::Remap && !snapshotForRemap(operation)) {
    return false;
  }
  if (!operation.started) {
    operation.started = true;
    const canvas::TileRange canvasTiles = compositor_->canvasTiles();
    std::vector<canvas::TileCoordinate> positions;
    switch (operation.kind) {
      case Kind::Fill:
        canvasTiles.forEach([&](canvas::TileCoordinate coordinate) { positions.push_back(coordinate); });
        break;
      case Kind::Invert:
        if (operation.mask || operation.selection) {
          canvasTiles.forEach([&](canvas::TileCoordinate coordinate) { positions.push_back(coordinate); });
        } else {
          positions = tileStore_->tilesOf(operation.surface);
        }
        break;
      case Kind::Clear:
        positions = tileStore_->tilesOf(operation.surface);
        break;
      case Kind::Copy:
        positions = tileStore_->tilesOf(operation.source);
        break;
      case Kind::SelectContents: {
        std::unordered_set<canvas::TileCoordinate, canvas::TileCoordinateHash> unique;
        for (const canvas::LayerId surface : {operation.source, operation.surface}) {
          for (const canvas::TileCoordinate coordinate : tileStore_->tilesOf(surface)) {
            if (canvasTiles.contains(coordinate) && unique.insert(coordinate).second) {
              positions.push_back(coordinate);
            }
          }
        }
        break;
      }
      case Kind::Remap: {
        const canvas::TileRange newTiles =
            canvas::tileRangeForCanvas(operation.newSize[0], operation.newSize[1]);
        newTiles.forEach(
            [&](canvas::TileCoordinate coordinate) { positions.push_back(coordinate); });
        for (const canvas::TileCoordinate coordinate : tileStore_->tilesOf(operation.surface)) {
          if (!newTiles.contains(coordinate)) {
            positions.push_back(coordinate);
          }
        }
        break;
      }
      case Kind::CopyMasked: {
        std::unordered_set<canvas::TileCoordinate, canvas::TileCoordinateHash> unique;
        for (const canvas::LayerId surface : {operation.surface, operation.source}) {
          for (const canvas::TileCoordinate coordinate : tileStore_->tilesOf(surface)) {
            if (canvasTiles.contains(coordinate) && unique.insert(coordinate).second) {
              positions.push_back(coordinate);
            }
          }
        }
        break;
      }
      case Kind::SelectPolygon: {
        std::unordered_set<canvas::TileCoordinate, canvas::TileCoordinateHash> unique;
        auto add = [&](canvas::TileCoordinate coordinate) {
          if (canvasTiles.contains(coordinate) && unique.insert(coordinate).second) {
            positions.push_back(coordinate);
          }
        };
        const auto existing = tileStore_->tilesOf(operation.surface);
        const std::unordered_set<canvas::TileCoordinate, canvas::TileCoordinateHash> existingSet(
            existing.begin(), existing.end());
        using Combine = canvas::SelectionCombine;
        if (operation.combine == Combine::Replace || operation.combine == Combine::Intersect) {
          std::for_each(existing.begin(), existing.end(), add);
        }
        if (operation.combine != Combine::Intersect) {
          for (const auto& [coordinate, coverage] : *operation.coverage) {
            if (operation.combine != Combine::Subtract || existingSet.count(coordinate) != 0) {
              add(coordinate);
            }
          }
        }
        break;
      }
      case Kind::Feather: {
        std::unordered_set<canvas::TileCoordinate, canvas::TileCoordinateHash> unique;
        for (const canvas::TileCoordinate coordinate : tileStore_->tilesOf(operation.surface)) {
          for (int32_t offset = -1; offset <= 1; ++offset) {
            const canvas::TileCoordinate neighbor{coordinate.column + offset, coordinate.row};
            if (canvasTiles.contains(neighbor) && unique.insert(neighbor).second) {
              positions.push_back(neighbor);
            }
          }
        }
        break;
      }
      case Kind::Compose: {
        std::unordered_set<canvas::TileCoordinate, canvas::TileCoordinateHash> unique;
        std::vector<canvas::LayerId> surfaces = operation.plan->surfaces;
        surfaces.push_back(operation.surface);
        for (const canvas::LayerId surface : surfaces) {
          for (const canvas::TileCoordinate coordinate : tileStore_->tilesOf(surface)) {
            if (canvasTiles.contains(coordinate) && unique.insert(coordinate).second) {
              positions.push_back(coordinate);
            }
          }
        }
        break;
      }
      case Kind::ApplyHistory:
        break;
    }
    operation.remaining.assign(positions.begin(), positions.end());
  }
  const size_t count = operation.remaining.size();
  for (size_t visited = 0; visited < count && !operation.remaining.empty(); ++visited) {
    if (std::chrono::steady_clock::now() >= deadline) {
      break;
    }
    const canvas::TileCoordinate coordinate = operation.remaining.front();
    operation.remaining.pop_front();
    if (!operateOnTile(operation, coordinate)) {
      operation.remaining.push_back(coordinate);
    }
  }
  if (operation.remaining.empty() && operation.kind == Kind::Feather) {
    if (operation.pass == 0) {
      operation.pass = 1;
      const canvas::TileRange canvasTiles = compositor_->canvasTiles();
      std::unordered_set<canvas::TileCoordinate, canvas::TileCoordinateHash> unique;
      for (const canvas::TileCoordinate coordinate : tileStore_->tilesOf(kFeatherScratchSurface)) {
        for (int32_t offset = -1; offset <= 1; ++offset) {
          const canvas::TileCoordinate neighbor{coordinate.column, coordinate.row + offset};
          if (canvasTiles.contains(neighbor) && unique.insert(neighbor).second) {
            operation.remaining.push_back(neighbor);
          }
        }
      }
      return operation.remaining.empty();
    }
    for (const canvas::TileCoordinate coordinate : tileStore_->tilesOf(kFeatherScratchSurface)) {
      tileStore_->removeTile({kFeatherScratchSurface, coordinate});
    }
  }
  return operation.remaining.empty();
}

bool EngineContext::operateOnTile(DocumentOperation& operation, canvas::TileCoordinate coordinate) {
  using Kind = DocumentOperation::Kind;
  if (operation.historyEntry != 0 && history_->isBackedUp()) {
    return false;
  }
  if (operation.kind == Kind::SelectPolygon || operation.kind == Kind::Feather) {
    return operateOnSelectionTile(operation, coordinate);
  }
  if (operation.kind == Kind::CopyMasked) {
    return operateOnMaskedCopyTile(operation, coordinate);
  }
  if (operation.kind == Kind::Remap) {
    return operateOnRemapTile(operation, coordinate);
  }
  const canvas::LayerTileKey key{operation.surface, coordinate};
  bool resident = tileStore_->ensureResident(key) != canvas::LayerTileStore::Availability::Loading;
  if (operation.kind == Kind::Copy) {
    resident = tileStore_->ensureResident(canvas::LayerTileKey{operation.source, coordinate}) ==
               canvas::LayerTileStore::Availability::Resident;
  } else if (operation.kind == Kind::SelectContents) {
    resident = tileStore_->ensureResident(canvas::LayerTileKey{operation.source, coordinate}) !=
                   canvas::LayerTileStore::Availability::Loading &&
               resident;
  } else if (operation.kind == Kind::Compose) {
    resident = compositor_->planIsResident(*operation.plan, coordinate) && resident;
  }
  if (!resident) {
    return false;
  }
  if (operation.historyEntry != 0) {
    history_->captureBefore(operation.historyEntry, key);
  }
  gpu::rhi::CommandContext& commands = device_->commands();
  switch (operation.kind) {
    case Kind::Clear:
      tileStore_->removeTile(key);
      break;
    case Kind::Fill: {
      const auto& color = operation.color;
      if (!operation.mask && color[3] <= 0) {
        tileStore_->removeTile(key);
        break;
      }
      const auto slot = tileStore_->slotForWrite(key);
      if (!slot) {
        break;
      }
      std::array<float, 4> clear{};
      if (operation.mask) {
        const float luminance = 0.3F * color[0] + 0.59F * color[1] + 0.11F * color[2];
        clear = {luminance, luminance, luminance, 1};
      } else {
        const bool linear = compositor_->linearLight();
        clear = {canvas::documentChannel(color[0], linear) * color[3],
                 canvas::documentChannel(color[1], linear) * color[3],
                 canvas::documentChannel(color[2], linear) * color[3], color[3]};
      }
      commands.beginPass(gpu::rhi::RenderTarget::of(tileStore_->pageTexture(slot->page)),
                         gpu::rhi::LoadAction::Load);
      commands.clear(clear, gpu::rhi::Rect{canvas::slotOriginX(*slot), canvas::slotOriginY(*slot),
                                           canvas::kTileSizePixels, canvas::kTileSizePixels});
      commands.endPass();
      break;
    }
    case Kind::Invert:
    case Kind::SelectContents: {
      const bool contents = operation.kind == Kind::SelectContents;
      const canvas::LayerTileKey sourceKey =
          contents ? canvas::LayerTileKey{operation.source, coordinate} : key;
      if (contents && !tileStore_->hasTile(sourceKey)) {
        tileStore_->removeTile(key);
        break;
      }
      if (!ensureOperationScratch()) {
        break;
      }
      const gpu::rhi::Pipeline* pipeline =
          enginePipeline(EngineProgram::Invert, operationScratch_->description().format);
      if (pipeline == nullptr) {
        break;
      }
      const auto source = tileStore_->slotForRead(sourceKey);
      commands.beginPass(gpu::rhi::RenderTarget::of(*operationScratch_),
                         gpu::rhi::LoadAction::DontCare);
      commands.bindPipeline(*pipeline);
      commands.bindTexture(kInvertTileBinding,
                           source ? &tileStore_->pageTexture(source->page) : nullptr);
      InvertTileParameters parameters{};
      parameters.slotOrigin = {source ? canvas::slotOriginX(*source) : 0,
                               source ? canvas::slotOriginY(*source) : 0};
      parameters.hasTile = source ? 1 : 0;
      parameters.mode = contents ? 3 : operation.selection ? 2 : operation.mask ? 1 : 0;
      parameters.linearDocument = compositor_->linearLight() ? 1 : 0;
      commands.setUniforms(parameters);
      commands.draw(3);
      commands.endPass();
      copyOperationScratchTo(key);
      break;
    }
    case Kind::Copy:
      copyTileThroughScratch(canvas::LayerTileKey{operation.source, coordinate}, key);
      break;
    case Kind::Compose: {
      const bool targetExisted = tileStore_->hasTile(key);
      const auto target = tileStore_->slotForWrite(key);
      if (target) {
        if (!targetExisted) {
          commands.beginPass(gpu::rhi::RenderTarget::of(tileStore_->pageTexture(target->page)),
                             gpu::rhi::LoadAction::Load);
          commands.clear({0, 0, 0, 0},
                         gpu::rhi::Rect{canvas::slotOriginX(*target), canvas::slotOriginY(*target),
                                        canvas::kTileSizePixels, canvas::kTileSizePixels});
          commands.endPass();
        }
        compositor_->renderPlanIntoSlot(*operation.plan, coordinate,
                                        tileStore_->pageTexture(target->page), *target);
      }
      break;
    }
    case Kind::ApplyHistory:
    case Kind::SelectPolygon:
    case Kind::Feather:
    case Kind::CopyMasked:
    case Kind::Remap:
      break;
  }
  if (operation.historyEntry != 0) {
    history_->captureAfter(operation.historyEntry, key);
  }
  markTileChanged(key);
  return true;
}

bool EngineContext::ensureOperationScratch() {
  if (!operationScratch_) {
    operationScratch_ = createSurfaceTexture(canvas::kTileSizePixels, canvas::kTileSizePixels);
  }
  return operationScratch_ != nullptr;
}

void EngineContext::copyOperationScratchTo(const canvas::LayerTileKey& key) {
  const auto target = tileStore_->slotForWrite(key);
  if (target) {
    device_->commands().copyTexture(
        *operationScratch_, gpu::rhi::Rect{0, 0, canvas::kTileSizePixels, canvas::kTileSizePixels},
        tileStore_->pageTexture(target->page), canvas::slotOriginX(*target),
        canvas::slotOriginY(*target));
  }
}

bool EngineContext::operateOnMaskedCopyTile(DocumentOperation& operation,
                                            canvas::TileCoordinate coordinate) {
  const canvas::LayerTileKey key{operation.surface, coordinate};
  const canvas::LayerTileKey sourceKey{operation.source, coordinate};
  const canvas::LayerTileKey selectionKey{operation.limit, coordinate};
  bool resident = true;
  for (const canvas::LayerTileKey& needed : {key, sourceKey, selectionKey}) {
    resident =
        tileStore_->ensureResident(needed) != canvas::LayerTileStore::Availability::Loading &&
        resident;
  }
  if (!resident) {
    return false;
  }
  if (!tileStore_->hasTile(sourceKey) || !tileStore_->hasTile(selectionKey)) {
    tileStore_->removeTile(key);
    markTileChanged(key);
    return true;
  }
  copyTileThroughScratch(sourceKey, key, selectionKey);
  markTileChanged(key);
  return true;
}

void EngineContext::copyTileThroughScratch(const canvas::LayerTileKey& from,
                                           const canvas::LayerTileKey& to,
                                           std::optional<canvas::LayerTileKey> selection) {
  const auto source = tileStore_->slotForRead(from);
  if (!source) {
    return;
  }
  std::optional<canvas::AtlasSlot> selectionSlot;
  if (selection) {
    selectionSlot = tileStore_->slotForRead(*selection);
    if (!selectionSlot) {
      return;
    }
  }
  if (!ensureOperationScratch()) {
    return;
  }
  const gpu::rhi::Pipeline* pipeline =
      enginePipeline(EngineProgram::MaskedCopy, operationScratch_->description().format);
  if (pipeline == nullptr) {
    return;
  }
  gpu::rhi::CommandContext& commands = device_->commands();
  commands.beginPass(gpu::rhi::RenderTarget::of(*operationScratch_),
                     gpu::rhi::LoadAction::DontCare);
  commands.bindPipeline(*pipeline);
  commands.bindTexture(kMaskedCopySourceBinding, &tileStore_->pageTexture(source->page));
  commands.bindTexture(kMaskedCopySelectionBinding,
                       selectionSlot ? &tileStore_->pageTexture(selectionSlot->page) : nullptr);
  commands.setUniforms(
      MaskedCopyParameters{{canvas::slotOriginX(*source), canvas::slotOriginY(*source)},
                           {0, 0},
                           {selectionSlot ? canvas::slotOriginX(*selectionSlot) : 0,
                            selectionSlot ? canvas::slotOriginY(*selectionSlot) : 0},
                           selectionSlot ? 1 : 0,
                           0});
  commands.draw(3);
  commands.endPass();
  copyOperationScratchTo(to);
}

bool EngineContext::operateOnSelectionTile(DocumentOperation& operation,
                                           canvas::TileCoordinate coordinate) {
  using Kind = DocumentOperation::Kind;
  const bool feather = operation.kind == Kind::Feather;
  const canvas::LayerId target =
      feather && operation.pass == 0 ? kFeatherScratchSurface : operation.surface;
  const canvas::LayerTileKey key{target, coordinate};
  const bool recorded = operation.historyEntry != 0 && target == operation.surface;
  std::array<canvas::LayerTileKey, 3> sources{};
  size_t sourceCount = 0;
  if (feather) {
    const canvas::LayerId source = operation.pass == 0 ? operation.surface : kFeatherScratchSurface;
    for (int32_t offset = -1; offset <= 1; ++offset) {
      sources[sourceCount++] =
          operation.pass == 0
              ? canvas::LayerTileKey{source, {coordinate.column + offset, coordinate.row}}
              : canvas::LayerTileKey{source, {coordinate.column, coordinate.row + offset}};
    }
  } else {
    sources[sourceCount++] = key;
  }
  bool resident = tileStore_->ensureResident(key) != canvas::LayerTileStore::Availability::Loading;
  for (size_t index = 0; index < sourceCount; ++index) {
    resident = tileStore_->ensureResident(sources[index]) !=
                   canvas::LayerTileStore::Availability::Loading &&
               resident;
  }
  if (!resident) {
    return false;
  }
  if (recorded) {
    history_->captureBefore(operation.historyEntry, key);
  }
  if (!ensureOperationScratch()) {
    return false;
  }
  gpu::rhi::CommandContext& commands = device_->commands();
  const gpu::rhi::Format format = operationScratch_->description().format;
  bool remove = false;
  if (feather) {
    const gpu::rhi::Pipeline* pipeline = enginePipeline(EngineProgram::SelectionBlur, format);
    if (pipeline == nullptr) {
      return false;
    }
    auto parameters = std::make_unique<SelectionBlurParameters>();
    std::array<const gpu::rhi::Texture*, 3> pages{};
    for (size_t index = 0; index < 3; ++index) {
      const auto slot = tileStore_->slotForRead(sources[index]);
      pages[index] = slot ? &tileStore_->pageTexture(slot->page) : nullptr;
      parameters->slotOrigins[index] = {slot ? canvas::slotOriginX(*slot) : 0,
                                        slot ? canvas::slotOriginY(*slot) : 0, 0, 0};
      parameters->hasTiles[index] = {slot ? 1 : 0, 0, 0, 0};
    }
    parameters->horizontal = operation.pass == 0 ? 1 : 0;
    parameters->tileOrigin = {coordinate.column * canvas::kTileSizePixels,
                              coordinate.row * canvas::kTileSizePixels};
    parameters->canvasSize = {document_->widthPixels, document_->heightPixels};
    const size_t weightCount = std::min(operation.weights.size(), kSelectionBlurWeights);
    parameters->halfWidth = static_cast<int32_t>(weightCount) - 1;
    for (size_t index = 0; index < weightCount; ++index) {
      parameters->weights[index][0] = operation.weights[index];
    }
    commands.beginPass(gpu::rhi::RenderTarget::of(*operationScratch_),
                       gpu::rhi::LoadAction::DontCare);
    commands.bindPipeline(*pipeline);
    for (size_t index = 0; index < 3; ++index) {
      commands.bindTexture(kSelectionBlurFirstBinding + static_cast<uint32_t>(index), pages[index]);
    }
    commands.setUniforms(*parameters);
    commands.draw(3);
    commands.endPass();
  } else {
    const auto found = operation.coverage->find(coordinate);
    const bool hasCoverage = found != operation.coverage->end();
    const auto slot = tileStore_->slotForRead(key);
    using Combine = canvas::SelectionCombine;
    remove = (!hasCoverage &&
              (operation.combine == Combine::Replace || operation.combine == Combine::Intersect)) ||
             (!slot &&
              (operation.combine == Combine::Subtract || operation.combine == Combine::Intersect));
    if (!remove) {
      if (!coverageTexture_) {
        gpu::rhi::TextureDescription description;
        description.format = gpu::rhi::Format::R8;
        description.width = canvas::kTileSizePixels;
        description.height = canvas::kTileSizePixels;
        description.usage = gpu::rhi::kUsageSampled | gpu::rhi::kUsageUpload;
        coverageTexture_ = device_->createTexture(description);
      }
      const gpu::rhi::Pipeline* pipeline = enginePipeline(EngineProgram::SelectionCombine, format);
      if (!coverageTexture_ || pipeline == nullptr) {
        return false;
      }
      const std::vector<uint8_t> empty(
          hasCoverage ? 0 : static_cast<size_t>(canvas::kTileSizePixels) * canvas::kTileSizePixels,
          0);
      device_->uploadTexture(*coverageTexture_, hasCoverage ? found->second.data() : empty.data());
      commands.beginPass(gpu::rhi::RenderTarget::of(*operationScratch_),
                         gpu::rhi::LoadAction::DontCare);
      commands.bindPipeline(*pipeline);
      commands.bindTexture(kTilePageBinding, slot ? &tileStore_->pageTexture(slot->page) : nullptr);
      commands.bindTexture(kCoverageBinding, coverageTexture_.get());
      commands.setUniforms(SelectionCombineParameters{
          {slot ? canvas::slotOriginX(*slot) : 0, slot ? canvas::slotOriginY(*slot) : 0},
          slot ? 1 : 0,
          static_cast<int32_t>(operation.combine)});
      commands.draw(3);
      commands.endPass();
    }
  }
  if (remove) {
    tileStore_->removeTile(key);
  } else {
    copyOperationScratchTo(key);
  }
  if (recorded) {
    history_->captureAfter(operation.historyEntry, key);
  }
  if (target == operation.surface) {
    markTileChanged(key);
  }
  return true;
}

void EngineContext::requestStats(int32_t requestId) {
  post([this, requestId] { callbacks_->onStats(requestId, statsJson()); });
}

void EngineContext::resetFrameStatistics() {
  post([this] {
    frameHistoryCount_ = 0;
    incompleteFrames_ = 0;
    lastSwapTime_.reset();
    latencyCount_ = 0;
    pendingLatencies_.clear();
    gpuLatencyCount_ = 0;
    firstUpdateLatencyCount_ = 0;
    textRasterMilliseconds_.clear();
  });
}

std::string EngineContext::statsJson() const {
  const size_t count = std::min(frameHistoryCount_, kFrameHistorySize);
  const auto countOffset = static_cast<std::ptrdiff_t>(count);
  std::vector<float> intervals(frameIntervalsMilliseconds_.begin(),
                               frameIntervalsMilliseconds_.begin() + countOffset);
  std::vector<float> cpuTimes(frameCpuMilliseconds_.begin(),
                              frameCpuMilliseconds_.begin() + countOffset);
  const double medianInterval = percentile(intervals, 0.5);
  const auto droppedFrames = std::count_if(intervals.begin(), intervals.end(), [&](float value) {
    return medianInterval > 0 && value > medianInterval * 1.5;
  });

  core::JsonWriter writer;
  writer.beginObject();
  writer.key("pendingSurfaceWrites");
  writer.integerValue(static_cast<int64_t>(pendingSurfaceWrites_.size()));
  writer.key("frames");
  writer.beginObject();
  writer.key("sampleCount");
  writer.integerValue(static_cast<int64_t>(count));
  writer.key("intervalMedianMilliseconds");
  writer.numberValue(medianInterval);
  writer.key("intervalP99Milliseconds");
  writer.numberValue(percentile(intervals, 0.99));
  writer.key("intervalMaxMilliseconds");
  writer.numberValue(intervals.empty() ? 0.0
                                       : *std::max_element(intervals.begin(), intervals.end()));
  writer.key("droppedFrames");
  writer.integerValue(droppedFrames);
  writer.key("incompleteFrames");
  writer.integerValue(incompleteFrames_);
  writer.key("cpuMedianMilliseconds");
  writer.numberValue(percentile(cpuTimes, 0.5));
  writer.key("cpuP99Milliseconds");
  writer.numberValue(percentile(cpuTimes, 0.99));
  writer.endObject();

  writer.key("document");
  if (document_) {
    writer.beginObject();
    writer.key("widthPixels");
    writer.integerValue(document_->widthPixels);
    writer.key("heightPixels");
    writer.integerValue(document_->heightPixels);
    writer.key("layerCount");
    writer.integerValue(static_cast<int64_t>(document_->tree.rasterLayers().size()));
    writer.endObject();
  } else {
    writer.nullValue();
  }

  {
    const size_t latencySamples = std::min(latencyCount_, kLatencyHistorySize);
    std::vector<float> latencies(
        latenciesMilliseconds_.begin(),
        latenciesMilliseconds_.begin() + static_cast<std::ptrdiff_t>(latencySamples));
    writer.key("latency");
    writer.beginObject();
    writer.key("sampleCount");
    writer.integerValue(static_cast<int64_t>(latencySamples));
    writer.key("inputToDisplayMedianMilliseconds");
    writer.numberValue(percentile(latencies, 0.5));
    writer.key("inputToDisplayP95Milliseconds");
    writer.numberValue(percentile(latencies, 0.95));
    const size_t gpuSamples = std::min(gpuLatencyCount_, kLatencyHistorySize);
    std::vector<float> gpuLatencies(
        gpuLatenciesMilliseconds_.begin(),
        gpuLatenciesMilliseconds_.begin() + static_cast<std::ptrdiff_t>(gpuSamples));
    writer.key("frontBufferSampleCount");
    writer.integerValue(static_cast<int64_t>(gpuSamples));
    writer.key("inputToFrontBufferMedianMilliseconds");
    writer.numberValue(percentile(gpuLatencies, 0.5));
    writer.key("inputToFrontBufferP95Milliseconds");
    writer.numberValue(percentile(gpuLatencies, 0.95));
    const size_t firstSamples = std::min(firstUpdateLatencyCount_, kFirstUpdateHistorySize);
    std::vector<float> firstUpdates(
        firstUpdateLatenciesMilliseconds_.begin(),
        firstUpdateLatenciesMilliseconds_.begin() + static_cast<std::ptrdiff_t>(firstSamples));
    writer.key("firstFrontBufferSampleCount");
    writer.integerValue(static_cast<int64_t>(firstSamples));
    writer.key("inputToFirstFrontBufferMedianMilliseconds");
    writer.numberValue(percentile(firstUpdates, 0.5));
    writer.key("inputToFirstFrontBufferMaxMilliseconds");
    writer.numberValue(percentile(firstUpdates, 1.0));
    writer.key("frontBufferSupported");
    writer.booleanValue(presenter_->supportsFrontBuffer());
    writer.endObject();
  }

  {
    const std::vector<float> rasterTimes(textRasterMilliseconds_.begin(),
                                         textRasterMilliseconds_.end());
    writer.key("text");
    writer.beginObject();
    writer.key("rasterCount");
    writer.integerValue(static_cast<int64_t>(rasterTimes.size()));
    writer.key("rasterMedianMilliseconds");
    writer.numberValue(percentile(rasterTimes, 0.5));
    writer.key("rasterMaxMilliseconds");
    writer.numberValue(percentile(rasterTimes, 1.0));
    writer.endObject();
  }

  writer.key("memoryBudgetBytes");
  writer.integerValue(config_.memoryBudgetBytes);
  if (tileStore_) {
    const canvas::LayerTileStoreStats tiles = tileStore_->stats();
    writer.key("tiles");
    writer.beginObject();
    writer.key("count");
    writer.integerValue(tiles.tileCount);
    writer.key("gpuTiles");
    writer.integerValue(tiles.gpuTiles);
    writer.key("gpuBytes");
    writer.integerValue(tiles.gpuTileBytes);
    writer.key("gpuBudgetBytes");
    writer.integerValue(tiles.gpuBudgetBytes);
    writer.key("atlasPages");
    writer.integerValue(tiles.atlasPages);
    writer.key("ramTiles");
    writer.integerValue(tiles.ramTiles);
    writer.key("ramBytes");
    writer.integerValue(tiles.ramBytes);
    writer.key("ramBudgetBytes");
    writer.integerValue(tiles.ramBudgetBytes);
    writer.key("diskTiles");
    writer.integerValue(tiles.diskTiles);
    writer.key("scratchFileBytes");
    writer.integerValue(tiles.scratchFileBytes);
    writer.key("loadsInFlight");
    writer.integerValue(tiles.loadsInFlight);
    writer.key("readbacksInFlight");
    writer.integerValue(tiles.readbacksInFlight);
    writer.key("spillsInFlight");
    writer.integerValue(tiles.spillsInFlight);
    writer.endObject();
  }
  if (compositor_) {
    const canvas::CompositorStats composite = compositor_->stats();
    writer.key("composite");
    writer.beginObject();
    writer.key("dirtyTiles");
    writer.integerValue(composite.dirtyTiles);
    writer.key("tilesComposedLastFrame");
    writer.integerValue(composite.tilesComposedLastUpdate);
    writer.key("pyramidTilesBuiltLastFrame");
    writer.integerValue(composite.pyramidTilesBuiltLastUpdate);
    writer.key("compositeBytes");
    writer.integerValue(composite.compositeBytes);
    writer.key("cacheBytes");
    writer.integerValue(composite.cacheBytes);
    writer.key("levelCount");
    writer.integerValue(composite.levelCount);
    writer.key("lastSettleMilliseconds");
    writer.numberValue(composite.lastSettleMilliseconds);
    writer.key("lastVisibleResultMilliseconds");
    writer.numberValue(composite.lastVisibleResultMilliseconds);
    writer.key("layerTileBudget");
    writer.integerValue(compositeLayerTileBudget_);
    writer.endObject();
  }
  if (history_) {
    const canvas::HistoryStoreStats history = history_->stats();
    writer.key("history");
    writer.beginObject();
    writer.key("entries");
    writer.integerValue(history.entries);
    writer.key("ramBytes");
    writer.integerValue(history.ramBytes);
    writer.key("diskBytes");
    writer.integerValue(history.diskBytes);
    writer.key("capturesInFlight");
    writer.integerValue(history.capturesInFlight);
    writer.key("ramBudgetBytes");
    writer.integerValue(historyRamBudgetBytes_);
    writer.endObject();
  }
  if (strokeRenderer_) {
    writer.key("stroke");
    writer.beginObject();
    writer.key("active");
    writer.booleanValue(paintSession_.has_value());
    writer.key("bufferTiles");
    writer.integerValue(static_cast<int64_t>(strokeRenderer_->buffer().tiles().size()));
    writer.key("bufferBytes");
    writer.integerValue(strokeRenderer_->buffer().bytes());
    writer.key("halfFloatBuffer");
    writer.booleanValue(strokeRenderer_->buffer().isHalfFloat());
    if (shaderCache_) {
      writer.key("shaderVariantsCompiled");
      writer.integerValue(shaderCache_->stats().compiled);
      writer.key("shaderVariantsLoaded");
      writer.integerValue(shaderCache_->stats().loadedFromBinary);
    }
    writer.key("provisionalTilesRendered");
    writer.integerValue(strokeRenderer_->provisionalTilesRendered());
    writer.endObject();
  }
  writer.endObject();
  return writer.text();
}

void EngineContext::recordLatencyFrame(bool singleBuffered) {
  constexpr size_t kMaximumPending = 64;
  if (!paintSession_ || paintSession_->latestInputNanos <= lastLatencyInputNanos_) {
    return;
  }
  lastLatencyInputNanos_ = paintSession_->latestInputNanos;
  if (singleBuffered) {
    if (pendingGpuLatencies_.size() >= kMaximumPending) {
      pendingGpuLatencies_.pop_front();
    }
    int64_t strokeStartNanos = 0;
    if (!paintSession_->frontBufferUpdateRecorded) {
      paintSession_->frontBufferUpdateRecorded = true;
      strokeStartNanos = paintSession_->firstInputNanos;
    }
    pendingGpuLatencies_.push_back(PendingGpuLatency{device_->commands().insertFence(),
                                                     lastLatencyInputNanos_, strokeStartNanos});
    return;
  }
  if (const auto frameId = presenter_->nextFrameId()) {
    if (pendingLatencies_.size() >= kMaximumPending) {
      pendingLatencies_.pop_front();
    }
    pendingLatencies_.push_back(PendingLatency{*frameId, lastLatencyInputNanos_});
  }
}

void EngineContext::collectLatencies() {
  while (!pendingGpuLatencies_.empty()) {
    const PendingGpuLatency& pending = pendingGpuLatencies_.front();
    if (!pending.fence->isSignaled()) {
      break;
    }
    const auto nowNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(
                              std::chrono::steady_clock::now().time_since_epoch())
                              .count();
    gpuLatenciesMilliseconds_[gpuLatencyCount_ % kLatencyHistorySize] =
        static_cast<float>(nowNanos - pending.inputNanos) / 1e6F;
    ++gpuLatencyCount_;
    if (pending.strokeStartNanos != 0) {
      firstUpdateLatenciesMilliseconds_[firstUpdateLatencyCount_ % kFirstUpdateHistorySize] =
          static_cast<float>(nowNanos - pending.strokeStartNanos) / 1e6F;
      ++firstUpdateLatencyCount_;
    }
    pendingGpuLatencies_.pop_front();
  }
  while (!pendingLatencies_.empty()) {
    const PendingLatency& pending = pendingLatencies_.front();
    int64_t presentNanos = 0;
    const auto state = presenter_->presentTime(pending.frameId, presentNanos);
    if (state == gpu::rhi::Presenter::PresentTime::Pending) {
      return;
    }
    if (state == gpu::rhi::Presenter::PresentTime::Known) {
      latenciesMilliseconds_[latencyCount_ % kLatencyHistorySize] =
          static_cast<float>(presentNanos - pending.inputNanos) / 1e6F;
      ++latencyCount_;
    }
    pendingLatencies_.pop_front();
  }
}

void EngineContext::adaptCompositeBudget(float frameIntervalMilliseconds) {
  if (frameHistoryCount_ % kExpectedIntervalRefreshFrames == 0) {
    const size_t count = std::min(frameHistoryCount_, kFrameHistorySize);
    std::vector<float> intervals(
        frameIntervalsMilliseconds_.begin(),
        frameIntervalsMilliseconds_.begin() + static_cast<std::ptrdiff_t>(count));
    expectedFrameIntervalMilliseconds_ = static_cast<float>(percentile(intervals, 0.25));
  }
  if (frameIntervalMilliseconds > expectedFrameIntervalMilliseconds_ * kLateFrameFactor) {
    compositeLayerTileBudget_ =
        std::max(kMinimumLayerTileBudget, compositeLayerTileBudget_ * 3 / 4);
  } else {
    compositeLayerTileBudget_ =
        std::min(kMaximumLayerTileBudget, compositeLayerTileBudget_ + kLayerTileBudgetStep);
  }
}

bool EngineContext::initializeBackend(EngineErrorCode& errorCode, std::string& errorMessage) {
  const std::string shaderDirectory = config_.scratchDirectory + "/shaders";
  mkdir(shaderDirectory.c_str(), 0700);
  if (config_.renderer == Renderer::Vulkan) {
    std::string vulkanError;
    std::shared_ptr<gpu::vulkan::VulkanContext> context =
        gpu::vulkan::VulkanContext::create(vulkanError);
    if (context && !context->hasSharedPresentableImage()) {
      vulkanError = "VK_KHR_shared_presentable_image missing";
      context.reset();
    }
    if (context) {
      auto device =
          std::make_unique<gpu::vulkan::VulkanDevice>(std::move(context), shaderDirectory);
      if (device->isReady(vulkanError)) {
        presenter_ = std::make_unique<gpu::vulkan::VulkanPresenter>(*device);
        device_ = std::move(device);
        NANQUIM_LOG_INFO("Render thread on Vulkan");
        return true;
      }
    }
    NANQUIM_LOG_ERROR("Vulkan unavailable, rendering with OpenGL ES: %s", vulkanError.c_str());
  }
  auto presenter = std::make_unique<gpu::gles::GlesPresenter>();
  const bool initialized = presenter->initialize(errorMessage);
  const EGLDisplay display = presenter->display();
  presenter_ = std::move(presenter);
  if (!initialized) {
    errorCode = EngineErrorCode::EglInitializationFailed;
    return false;
  }
  const gpu::gles::GlesVersion version = gpu::gles::queryGlesVersion();
  const bool versionSupported =
      version.major > kRequiredGlesMajorVersion ||
      (version.major == kRequiredGlesMajorVersion && version.minor >= kRequiredGlesMinorVersion);
  if (!versionSupported) {
    errorCode = EngineErrorCode::GlesVersionUnsupported;
    errorMessage = "OpenGL ES " + std::to_string(kRequiredGlesMajorVersion) + "." +
                   std::to_string(kRequiredGlesMinorVersion) + " required, found " +
                   std::to_string(version.major) + "." + std::to_string(version.minor);
    return false;
  }
  shaderCache_ = std::make_unique<gpu::gles::ShaderVariantCache>(shaderDirectory);
  shaderCache_->initialize();
  device_ = std::make_unique<gpu::gles::GlesDevice>(display, *shaderCache_);
  NANQUIM_LOG_INFO("Render thread on OpenGL ES %d.%d", version.major, version.minor);
  return true;
}

bool EngineContext::initializeRenderers(std::string& errorMessage) {
  gpuProfiler_.setDevice(device_.get());
  tileStore_ = std::make_unique<canvas::LayerTileStore>(
      *device_, ioPool_, [this](std::function<void()> task) { post(std::move(task)); },
      config_.scratchDirectory + "/tiles.scratch");
  compositor_ = std::make_unique<canvas::Compositor>(*device_, *tileStore_);
  history_ = std::make_unique<canvas::HistoryStore>(
      *tileStore_, ioPool_, [this](std::function<void()> task) { post(std::move(task)); },
      config_.scratchDirectory + "/history.scratch");
  history_->setRamBudget(historyRamBudgetBytes_);
  saver_ = std::make_unique<canvas::DocumentSaver>(
      *tileStore_, ioPool_, [this](std::function<void()> task) { post(std::move(task)); });
  history_->setOnEntryReady(
      [this](int64_t entry, int64_t bytes) { emitHistoryEntryReady(entry, bytes); });
  strokeRenderer_ = std::make_unique<canvas::StrokeRenderer>(*device_, *tileStore_);
  viewportRenderer_ = std::make_unique<canvas::ViewportRenderer>();
  selectionOverlay_ = std::make_unique<canvas::SelectionOverlay>();
  tileStore_->setOnTileLoaded([this](const canvas::LayerTileKey&) { frameRequested_ = true; });
  device_->registerProgram("test_pattern", shaders::fullscreen_vertex,
                           shaders::test_pattern_fragment);
  device_->registerProgram("fill_rect", shaders::fullscreen_vertex, shaders::fill_rect_fragment);
  device_->registerProgram("invert_tile", shaders::fullscreen_vertex,
                           shaders::invert_tile_fragment);
  device_->registerProgram("selection_combine", shaders::fullscreen_vertex,
                           shaders::selection_combine_fragment);
  device_->registerProgram("selection_blur", shaders::fullscreen_vertex,
                           shaders::selection_blur_fragment);
  device_->registerProgram("coverage_fill", shaders::fullscreen_vertex,
                           shaders::coverage_fill_fragment);
  device_->registerProgram("masked_copy", shaders::fullscreen_vertex,
                           shaders::masked_copy_fragment);
  device_->registerProgram("transform_mesh", shaders::transform_mesh_vertex,
                           shaders::transform_mesh_fragment);
  device_->registerProgram("adjust_tile", shaders::fullscreen_vertex,
                           shaders::adjust_tile_fragment);
  device_->registerProgram("adjust_pass", shaders::fullscreen_vertex,
                           shaders::adjust_pass_fragment);
  bool programsReady = true;
  for (int32_t program = 0; program <= static_cast<int32_t>(EngineProgram::AdjustPassTexels);
       ++program) {
    programsReady =
        enginePipeline(static_cast<EngineProgram>(program), gpu::rhi::Format::Rgba8) != nullptr &&
        programsReady;
  }
  if (!programsReady) {
    errorMessage = "An engine program failed to compile (see the log)";
  }
  linearSampler_ = device_->createSampler(gpu::rhi::Filter::Linear);
  device_->registerProgram("thumbnail_tile", shaders::fullscreen_vertex,
                           shaders::thumbnail_tile_fragment);
  gpu::rhi::PipelineDescription thumbnail;
  thumbnail.program = "thumbnail_tile";
  thumbnailPipeline_ = device_->pipeline(thumbnail, errorMessage);
  return programsReady && thumbnailPipeline_ != nullptr && compositor_->initialize(errorMessage) &&
         viewportRenderer_->initialize(*device_, errorMessage) &&
         strokeRenderer_->initialize(errorMessage) &&
         selectionOverlay_->initialize(*device_, errorMessage);
}

void EngineContext::runRenderLoop() {
  std::string errorMessage;
  EngineErrorCode errorCode = EngineErrorCode::RendererInitializationFailed;
  if (!initializeBackend(errorCode, errorMessage)) {
    callbacks_->onError(errorCode, errorMessage, false);
  } else if (!initializeRenderers(errorMessage)) {
    callbacks_->onError(EngineErrorCode::RendererInitializationFailed, errorMessage, false);
  } else {
    eglReady_ = true;
    applyBudgets();
  }

  for (;;) {
    const bool backgroundWork =
        eglReady_ &&
        (!pendingFills_.empty() || (tileStore_ && tileStore_->hasPendingWork()) ||
         (strokeRenderer_ && strokeRenderer_->hasPendingWork()) ||
         (paintSession_ && paintSession_->ended) || !operations_.empty() ||
         (saver_ && saver_->hasPendingWork()) || !pendingSurfaceWrites_.empty() ||
         fillNeedsWork() ||
         (transformSession_ && (!transformSession_->lifted || transformSession_->endRequested)) ||
         (adjustmentSession_ && (!adjustmentSession_->shown || adjustmentSession_->endRequested)) ||
         (history_ && history_->hasPendingWork()) || previewReadback_.has_value() ||
         frameReadback_.has_value());
    const bool keepRendering = eglReady_ && presenter_->hasWindow() && hasPendingRenderWork_;
    const bool probing = singleBufferProbe_.has_value() && presenter_->hasWindow();
    if (!keepRendering && !backgroundWork && probing) {
      if (auto task = tasks_.waitPopFor(kBackBufferPresentPollInterval)) {
        (*task)();
      }
    } else if (!keepRendering && !backgroundWork && presenter_->hasPendingSwitch() &&
               presenter_->hasWindow()) {
      if (auto task = tasks_.waitPopFor(kBackBufferPresentPollInterval)) {
        (*task)();
      }
      frameRequested_ = true;
    } else if (!keepRendering && !backgroundWork && nextAntsStep_ && presenter_->hasWindow()) {
      const auto now = std::chrono::steady_clock::now();
      if (now < *nextAntsStep_) {
        if (auto task = tasks_.waitPopFor(
                std::chrono::duration_cast<std::chrono::milliseconds>(*nextAntsStep_ - now) +
                std::chrono::milliseconds(1))) {
          (*task)();
        }
      }
      if (std::chrono::steady_clock::now() >= *nextAntsStep_) {
        frameRequested_ = true;
      }
    } else if (!keepRendering && !backgroundWork && awakeUntil_ && presenter_->hasWindow()) {
      const auto now = std::chrono::steady_clock::now();
      const auto nextPoke = lastGpuWork_ + kAwakePokeInterval;
      if (now < nextPoke) {
        if (auto task = tasks_.waitPopFor(
                std::chrono::duration_cast<std::chrono::milliseconds>(nextPoke - now) +
                std::chrono::milliseconds(1))) {
          (*task)();
        }
      }
      if (std::chrono::steady_clock::now() >= *awakeUntil_) {
        awakeUntil_.reset();
      } else if (std::chrono::steady_clock::now() >= nextPoke) {
        pokeGpu();
      }
    } else if (!keepRendering && !backgroundWork) {
      auto task = tasks_.waitPop();
      if (!task) {
        break;
      }
      (*task)();
    } else if (backgroundWork && !presenter_->hasWindow()) {
      if (auto task = tasks_.waitPopFor(kBackgroundPollInterval)) {
        (*task)();
      }
    } else if (frontBufferWanted_ && presenter_->hasWindow()) {
      if (auto task = tasks_.waitPopFor(kBackBufferPresentPollInterval)) {
        (*task)();
      }
    }
    checkSingleBufferPresented();
    waitForSingleBufferFrame();
    while (auto task = tasks_.tryPop()) {
      (*task)();
    }
    if (tasks_.isClosed()) {
      break;
    }
    if (eglReady_ && tileStore_) {
      ATrace_beginSection("NanquimTileMaintenance");
      tileStore_->beginFrame(++frameIndex_);
      processPendingFills();
      tileStore_->maintain();
      if (!prefetchSurfaces_.empty()) {
        prefetchTiles();
      }
      tryCommitStroke();
      processOperations(std::chrono::steady_clock::now() + kCompositeBudgetPerFrame);
      processFill();
      processTransform();
      processAdjustment();
      while (!pendingSurfaceWrites_.empty() && pendingSurfaceWrites_.front()()) {
        pendingSurfaceWrites_.pop_front();
      }
      saver_->poll();
      pollCompositePreview();
      reportCorruptTiles();
      history_->maintain();
      strokeRenderer_->poll();
      if (tileStore_->hasPendingWork() || history_->hasPendingWork() || saver_->hasPendingWork() ||
          strokeRenderer_->hasPendingWork() || previewReadback_ || frameReadback_) {
        device_->commands().submit();
      }
      ATrace_endSection();
    }
    if (presenter_->hasWindow() && (frameRequested_ || keepRendering || backgroundWork)) {
      frameRequested_ = false;
      lastGpuWork_ = std::chrono::steady_clock::now();
      renderFrame();
    }
  }

  releaseWindow();
  singleBufferFrameFences_.clear();
  gpuProfiler_.setDevice(nullptr);
  viewportRenderer_.reset();
  selectionOverlay_.reset();
  operations_.clear();
  saver_.reset();
  history_.reset();
  cloneSnapshot_.reset();
  transformSession_.reset();
  adjustmentSession_.reset();
  enginePipelines_.clear();
  linearSampler_.reset();
  fillSession_.reset();
  coverageTexture_.reset();
  thumbnailPipeline_ = nullptr;
  previewReadback_.reset();
  operationScratch_.reset();
  awakeTarget_.reset();
  pendingGpuLatencies_.clear();
  compositor_.reset();
  strokeRenderer_.reset();
  tileStore_.reset();
  device_.reset();
  shaderCache_.reset();
  presenter_.reset();
  callbacks_->onRenderThreadStopping();
}

void EngineContext::renderFrame() {
  if (frontBufferWanted_) {
    tryEnterFrontBufferMode();
    if (frontBufferWanted_) {
      hasPendingRenderWork_ = true;
      return;
    }
  }
  presenter_->prepareFrame();
  ATrace_beginSection("NanquimRenderFrame");
  const auto frameStart = std::chrono::steady_clock::now();
  hasPendingRenderWork_ = false;
  nextAntsStep_.reset();
  bool composedThisFrame = false;

  const bool showDocument = eglReady_ && document_ && compositor_ && compositor_->isConfigured();
  const gpu::rhi::RenderTarget windowTarget =
      presenter_->windowTarget(surfaceWidthPixels_, surfaceHeightPixels_);
  const bool singleBuffered = presenter_->isFrontBuffered();
  if (singleBuffered && !lastFrameFrontBuffered_) {
    fullRedrawsPending_ = std::max(fullRedrawsPending_, 1);
  }
  lastFrameFrontBuffered_ = singleBuffered;
  bool drewSomething = true;
  if (showDocument) {
    const canvas::LevelChoice levels = canvas::chooseLevels(
        canvasToScreen_.scaleFactor(), compositor_->levelCount(), viewportStyle_.showPixels);
    const canvas::TileRange visible = visibleTiles();
    bool moreWork = !pendingFills_.empty();
    if (paintSession_) {
      updateProvisionalStroke(*paintSession_);
    }
    if (pendingFills_.empty()) {
      const bool adjusting = adjustmentSession_ && adjustmentSession_->shown;
      int32_t previewLevel = levels.nearest || levels.coarseLevel == 0 ? 0
                             : adjusting ? levels.coarseLevel
                                         : std::max(levels.fineLevel, 1);
      int32_t coarsestLevel = levels.coarseLevel;
      constexpr int32_t kZoomedInAdjustmentLevel = 2;
      if (adjusting && compositor_->levelCount() > 1) {
        const int32_t draft = std::min(kZoomedInAdjustmentLevel, compositor_->levelCount() - 1);
        coarsestLevel = std::max(coarsestLevel, draft);
        if (previewLevel == 0 && adjustmentChanging()) {
          previewLevel = draft;
        }
      }
      const bool waitForChange = previewLevel >= 1 && adjustmentChanging();
      const int64_t layerTileBudget = !waitForChange ? compositeLayerTileBudget_
                                      : adjust::isSpatial(adjustmentSession_->parameters.filter)
                                          ? 0
                                          : 1;
      gpuProfiler_.begin(gpu::GpuProfiler::Section::Compose);
      moreWork = compositor_->update(visible, coarsestLevel, previewLevel,
                                     frameStart + kCompositeBudgetPerFrame, layerTileBudget);
      gpuProfiler_.end();
      moreWork = moreWork || adjustmentChanging();
      composedThisFrame = compositor_->stats().tilesComposedLastUpdate > 0;
    }
    hasPendingRenderWork_ = moreWork || tileStore_->hasPendingWork() || dirtyTileOverlay_ ||
                            (paintSession_.has_value() && !singleBuffered);
    const gpu::rhi::Texture* dirtyAges =
        dirtyTileOverlay_ ? compositor_->updateDirtyAgeTexture() : nullptr;
    std::optional<std::array<int32_t, 4>> screenRegion;
    if (singleBuffered && fullRedrawsPending_ == 0 && !dirtyTileOverlay_) {
      const auto changed = compositor_->lastUpdateChangedRegion();
      screenRegion = changed ? screenRegionOf(*changed) : std::nullopt;
      drewSomething = screenRegion.has_value();
    }
    std::optional<canvas::SelectionDisplay> selectionDisplay;
    if (selectionOverlay_->surface()) {
      if (selectionOverlay_->update(*tileStore_, kSelectionOverlayTilesPerFrame)) {
        hasPendingRenderWork_ = true;
      }
      if (selectionOverlay_->isReady()) {
        if (!singleBuffered && !paintSession_) {
          const auto steps =
              std::chrono::duration_cast<std::chrono::milliseconds>(frameStart.time_since_epoch())
                  .count() /
              kAntsStepInterval.count();
          antsPhasePixels_ = static_cast<float>(steps % 1000) * kAntsStepPixels;
          nextAntsStep_ = frameStart + kAntsStepInterval;
        }
        selectionDisplay = canvas::SelectionDisplay{selectionOverlay_->texture(),
                                                    selectionOverlay_->reduction(),
                                                    selectionOverlay_->textureWidth(),
                                                    selectionOverlay_->textureHeight(),
                                                    dimOutsideSelection_,
                                                    antsPhasePixels_};
      }
    }
    if (!documentShownReported_ && !moreWork && !tileStore_->hasPendingWork() && drewSomething) {
      reportDocumentShown();
    }
    if (drewSomething) {
      const gpu::GpuProfileScope gpuTime(gpuProfiler_, gpu::GpuProfiler::Section::Screen);
      viewportRenderer_->render(*compositor_, canvasToScreen_, windowTarget, viewportStyle_,
                                dirtyAges ? &*dirtyAges : nullptr,
                                compositor_->displayLevelOverride(visible), screenRegion,
                                selectionDisplay);
    }
  } else {
    device_->commands().beginPass(windowTarget, gpu::rhi::LoadAction::Clear, clearColor_);
    device_->commands().endPass();
  }

  if (latencyTestPoint_.visible) {
    const auto radius = static_cast<int32_t>(kLatencyTestDotRadiusPixels);
    const auto centerX = static_cast<int32_t>(latencyTestPoint_.xPixels);
    const auto centerY = static_cast<int32_t>(latencyTestPoint_.yPixels);
    const int32_t centerRow =
        windowTarget.rowsFromBottom ? surfaceHeightPixels_ - centerY : centerY;
    gpu::rhi::CommandContext& commands = device_->commands();
    commands.beginPass(windowTarget, gpu::rhi::LoadAction::Load);
    commands.clear({0.9F, 0.1F, 0.1F, 1.0F},
                   gpu::rhi::Rect{centerX - radius, centerRow - radius, radius * 2, radius * 2});
    commands.endPass();
  }

  const auto cpuEnd = std::chrono::steady_clock::now();
  ATrace_endSection();
  if (fullRedrawsPending_ > 0) {
    --fullRedrawsPending_;
  }
  if (!drewSomething && !latencyTestPoint_.visible) {
    collectLatencies();
    return;
  }
  recordLatencyFrame(singleBuffered);
  if (!singleBuffered) {
    lastBackBufferFrameId_ = presenter_->nextFrameId();
    lastBackBufferSwapTime_ = std::chrono::steady_clock::now();
  } else if (singleBufferProbe_ && !singleBufferProbe_->frameId) {
    singleBufferProbe_->frameId = presenter_->nextFrameId();
    singleBufferProbe_->swapTime = std::chrono::steady_clock::now();
    if (!singleBufferProbe_->frameId) {
      singleBufferProbe_.reset();
    }
  }
  if (compositor_->dirtyTileCount() > 0) {
    ++incompleteFrames_;
  }
  presenter_->present();
  gpuProfiler_.endFrame();
  collectLatencies();
  const auto swapEnd = std::chrono::steady_clock::now();
  if (singleBuffered) {
    singleBufferFrameFences_.push_back(device_->commands().insertFence());
    lastSwapTime_.reset();
    return;
  }

  if (lastSwapTime_) {
    const float interval =
        std::chrono::duration<float, std::milli>(swapEnd - *lastSwapTime_).count();
    if (interval < kIdleGapMilliseconds) {
      const size_t slot = frameHistoryCount_ % kFrameHistorySize;
      frameIntervalsMilliseconds_[slot] = interval;
      frameCpuMilliseconds_[slot] =
          std::chrono::duration<float, std::milli>(cpuEnd - frameStart).count();
      ++frameHistoryCount_;
      if (composedThisFrame) {
        adaptCompositeBudget(interval);
      }
    }
  }
  lastSwapTime_ = swapEnd;
  ATrace_setCounter("NanquimCompositeLayerTileBudget", compositeLayerTileBudget_);
}

}  // namespace nanquim
