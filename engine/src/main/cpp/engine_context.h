#pragma once

#include <android/native_window.h>

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <thread>
#include <tuple>
#include <unordered_set>
#include <vector>

#include "adjust/adjustment.h"
#include "adjust/liquify.h"
#include "adjust/spatial.h"
#include "canvas/composite_plan.h"
#include "canvas/compositor.h"
#include "canvas/document_saver.h"
#include "canvas/history_store.h"
#include "canvas/layer_tile_store.h"
#include "canvas/polygon_coverage.h"
#include "canvas/selection_overlay.h"
#include "canvas/stroke_renderer.h"
#include "canvas/transform_mesh.h"
#include "canvas/viewport_renderer.h"
#include "core/affine_transform.h"
#include "core/task_queue.h"
#include "core/worker_pool.h"
#include "gpu/gles/shader_variant_cache.h"
#include "gpu/gpu_profiler.h"
#include "gpu/rhi/rhi.h"
#include "scene/scene.h"
#include "stroke/dab_generator.h"
#include "stroke/input_sample.h"
#include "stroke/loaded_brush.h"
#include "stroke/particles.h"
#include "text/font_face.h"
#include "text/text_model.h"
#include "text/text_raster.h"

namespace nanquim {

enum class EngineErrorCode : int32_t {
  EglInitializationFailed = 1,
  GlesVersionUnsupported = 2,
  WindowSurfaceFailed = 3,
  DocumentOpenFailed = 4,
  RendererInitializationFailed = 5,
};

enum class StrokeTool : int32_t { Paint = 0, Erase = 1, Smudge = 2 };
constexpr int32_t kStrokeToolCount = 3;

struct StrokeStyle {
  float sizePixels = 20;
  float opacity = 1;
  std::array<float, 4> color{0, 0, 0, 1};
  std::array<float, 3> secondaryColor{1, 1, 1};
  bool maskTarget = false;
  int64_t historyEntry = 0;
  std::optional<canvas::LayerId> selection;
  bool selectionTarget = false;
  struct Clone {
    bool composite = true;
    canvas::LayerId layer = 0;
    std::array<int32_t, 2> offset{};
  };
  std::optional<Clone> clone;
  std::vector<core::AffineTransform> symmetry;
};

struct FillRequest {
  enum class Target { Layer, Mask, Selection };
  canvas::LayerId source = 0;
  double seedX = 0;
  double seedY = 0;
  double threshold = 0.1;
  bool expand = true;
  bool whole = false;
  enum class Operation { Color, Clear, Invert };
  Operation operation = Operation::Color;
  Target target = Target::Layer;
  canvas::LayerId surface = 0;
  std::array<float, 4> color{0, 0, 0, 1};
  std::optional<canvas::LayerId> selection;
  canvas::SelectionCombine combine = canvas::SelectionCombine::Replace;
};

enum class Renderer : int32_t { Gles = 0, Vulkan = 1 };

struct EngineConfig {
  int64_t memoryBudgetBytes = 0;
  std::string scratchDirectory;
  Renderer renderer = Renderer::Gles;
};

class EngineCallbacks {
 public:
  virtual ~EngineCallbacks() = default;
  virtual void onGpuInfo(int32_t requestId, const std::string& json) = 0;
  virtual void onStats(int32_t requestId, const std::string& json) = 0;
  virtual void onError(EngineErrorCode code, const std::string& message, bool recoverable) = 0;
  virtual void onEvent(const std::string& json) = 0;
  virtual void onSceneRendered(int32_t requestId, int32_t width, int32_t height,
                               const std::vector<uint8_t>& pixels, const std::string& error) = 0;
  virtual void onRenderThreadStopping() = 0;
};

class EngineContext {
 public:
  EngineContext(EngineConfig config, std::unique_ptr<EngineCallbacks> callbacks);
  EngineContext(const EngineContext&) = delete;
  EngineContext& operator=(const EngineContext&) = delete;
  ~EngineContext();

  void attachWindow(ANativeWindow* window, int32_t widthPixels, int32_t heightPixels);

  void detachWindowAndWait();

  void setClearColor(float red, float green, float blue, float alpha);

  void setLatencyTestPoint(float xPixels, float yPixels, bool visible);

  void requestGpuInfo(int32_t requestId);

  void openDocument(int32_t widthPixels, int32_t heightPixels, canvas::LayerTree tree);

  void openDocumentFolder(int32_t widthPixels, int32_t heightPixels, canvas::LayerTree tree,
                          std::string documentId, std::string folder,
                          std::vector<canvas::SaveSurface> surfaces);
  void saveDocument(canvas::SaveRequest request);
  void writeSurfaceTileRow(canvas::LayerId surface, int32_t tileRow, std::vector<uint8_t> pixels);
  void requestCompositeThumbnail(int32_t requestId, int32_t maxSide);
  void requestCompositePreview(int32_t requestId, int32_t maxSide);
  void openTestDocument(int32_t widthPixels, int32_t heightPixels, int32_t layerCount,
                        std::array<float, 4> backgroundColor);
  void closeDocument();
  void setLayerTree(canvas::LayerTree tree);
  void setLayerVisible(canvas::LayerId layer, bool visible);
  void setLayerOpacity(canvas::LayerId layer, float opacity);
  void setActiveLayer(canvas::LayerId layer);
  void setBackground(std::array<float, 4> color, bool hidden);

  void fillRandomTiles(canvas::LayerId layer, int32_t tileCount, uint32_t seed);

  void setCamera(const core::AffineTransform& canvasToScreen);
  void setViewportStyle(const canvas::ViewportStyle& style);
  void setDirtyTileOverlay(bool enabled);
  void keepGpuAwake(std::chrono::milliseconds duration);
  void pokeGpu();
  void setDisplayColor(std::vector<uint16_t> lut, bool displayP3);
  void setSelectionDisplay(std::optional<canvas::LayerId> selection, bool dimOutside);
  void setMemoryBudget(int64_t bytes);

  void requestStats(int32_t requestId);
  void resetFrameStatistics();
  void setFrontBufferEnabled(bool enabled);

  void setBrush(StrokeTool tool, std::shared_ptr<const stroke::LoadedBrush> brush);
  void setPressureCurve(std::optional<stroke::Curve> curve);
  void setGlobalSmoothing(float amount);

  void registerFont(std::string fontId, std::string path, std::vector<uint8_t> data,
                    int32_t faceIndex);
  void setFontFallbacks(std::vector<std::string> fontIds);
  void setTextLayer(canvas::LayerId layer, std::string modelJson, double dpi);
  void requestTextGeometry(int32_t requestId, std::string modelJson, double dpi);
  void setStylusRoll(bool reported);

  void beginStroke(StrokeTool tool, canvas::LayerId layer, StrokeStyle style);
  void addStrokeSamples(std::vector<uint8_t> packedSamples);
  void endStroke();
  void cancelStroke();
  void replaceStroke(std::vector<uint8_t> packedSamples);

  void fillSurface(canvas::LayerId surface, std::array<float, 4> color, bool mask,
                   int64_t historyEntry);
  void clearSurface(canvas::LayerId surface, int64_t historyEntry);
  void invertSurface(canvas::LayerId surface, bool mask, int64_t historyEntry);
  void invertSelection(canvas::LayerId selection, int64_t historyEntry);
  void selectContents(canvas::LayerId selection, canvas::LayerId layer, int64_t historyEntry);
  void selectPolygon(canvas::LayerId selection, std::vector<canvas::PolygonPoint> polygon,
                     canvas::SelectionCombine combine, int64_t historyEntry);
  void featherSelection(canvas::LayerId selection, double radius, int64_t historyEntry);

  void beginFill(FillRequest request);
  void setFillThreshold(double threshold);
  void endFill(bool commit, int64_t historyEntry);

  void beginTransform(canvas::LayerId layer, std::optional<canvas::LayerId> selection,
                      int32_t requestId);
  void setTransformMesh(std::vector<canvas::MeshVertex> triangles, canvas::TransformFilter filter);
  void endTransform(bool commit, int64_t historyEntry);
  void beginAdjustment(canvas::LayerId layer, std::optional<canvas::LayerId> selection,
                       std::optional<canvas::LayerId> mask, adjust::Parameters parameters,
                       float amount);
  void setAdjustment(adjust::Parameters parameters, float amount);
  void endAdjustment(bool commit, int64_t historyEntry);
  void beginLiquifyStroke(adjust::LiquifyBrush brush);
  void addLiquifySamples(std::vector<adjust::LiquifySample> samples);
  void endLiquifyStroke();
  void cancelLiquifyStroke();
  void liquifyStep(bool undo);
  void resetLiquify();
  void scaleLiquify(float factor);
  void copySurface(canvas::LayerId source, canvas::LayerId destination);
  void copyMasked(canvas::LayerId source, canvas::LayerId destination, canvas::LayerId selection);
  void remapSurface(canvas::LayerId surface, int32_t kind,
                    std::vector<canvas::MeshVertex> triangles, canvas::TransformFilter filter,
                    int32_t newWidth, int32_t newHeight, int64_t historyEntry);
  void setCanvasSize(int32_t width, int32_t height);
  void composeIntoSurface(canvas::LayerId target, canvas::LayerTree tree, int64_t historyEntry);
  void discardSurfaces(std::vector<canvas::LayerId> surfaces);
  void prefetchSurfaces(std::vector<canvas::LayerId> surfaces);
  void applyHistory(int64_t historyEntry, bool undo);
  void releaseHistory(std::vector<int64_t> entries);
  void setHistoryRamBudget(int64_t bytes);

  void renderScene(int32_t requestId, std::string sceneJson,
                   std::map<std::string, std::vector<uint8_t>> files);
  void readComposite(int32_t requestId, bool deep = false);
  struct ViewportImageRequest {
    int32_t widthPixels = 0;
    int32_t heightPixels = 0;
    core::AffineTransform canvasToScreen;
    bool showPixels = false;
    std::optional<canvas::LayerId> selection;
    bool dimOutsideSelection = false;
    float antsPhasePixels = 0;
  };
  void renderViewportImage(int32_t requestId, ViewportImageRequest request);
  void requestThumbnails(int32_t requestId, std::vector<canvas::LayerId> surfaces,
                         std::vector<bool> masks, int32_t maxSide);
  void sampleColor(int32_t requestId, double canvasX, double canvasY, int32_t size);
  void sampleLayerAlphas(int32_t requestId, int32_t canvasX, int32_t canvasY,
                         std::vector<canvas::LayerId> surfaces);
  void requestTimelapseFrame(int32_t requestId, int32_t maxSide,
                             std::vector<canvas::LayerId> excluded);

 private:
  struct LatencyTestPoint {
    float xPixels = 0;
    float yPixels = 0;
    bool visible = false;
  };

  struct Document {
    int32_t widthPixels = 0;
    int32_t heightPixels = 0;
    canvas::LayerTree tree;
  };

  struct PendingFill {
    canvas::LayerTileKey key;
    std::array<float, 3> color{};
    float patternSeed = 0;
  };

  static constexpr size_t kFrameHistorySize = 600;

  void post(std::function<void()> task);
  void runRenderLoop();
  bool initializeBackend(EngineErrorCode& errorCode, std::string& errorMessage);
  bool initializeRenderers(std::string& errorMessage);
  void renderFrame();
  void releaseWindow();
  void resetWindowSurfaceState();
  void applyBudgets();
  bool openDocumentFolderNow(int32_t widthPixels, int32_t heightPixels, canvas::LayerTree tree,
                             const std::string& documentId, const std::string& folder,
                             const std::vector<canvas::SaveSurface>& surfaces);
  bool writeSurfaceTileRowNow(canvas::LayerId surface, int32_t tileRow,
                              const std::vector<uint8_t>& pixels);
  bool sendCompositeThumbnail(int32_t requestId, int32_t maxSide);
  struct ReadableRegion {
    const gpu::rhi::Texture* texture = nullptr;
    gpu::rhi::Rect rect;
    std::unique_ptr<gpu::rhi::Texture> encoded;
  };
  ReadableRegion readableRegion(const gpu::rhi::Texture& texture, const gpu::rhi::Rect& rect);
  bool readRegionRgba8(const gpu::rhi::Texture& texture, const gpu::rhi::Rect& rect, uint8_t* out);
  struct ThumbnailTileParameters {
    std::array<int32_t, 2> slotOrigin;
    std::array<int32_t, 2> targetOrigin;
    int32_t sampleStride;
    int32_t encode;
    std::array<int32_t, 2> padding;
  };
  static constexpr uint32_t kThumbnailSourceBinding = 1;
  [[nodiscard]] io::DocumentTileFormat layerFileFormat(io::DocumentTileFormat declared) const;
  [[nodiscard]] gpu::rhi::Format documentFormat() const;
  enum class EngineProgram : uint8_t {
    TestPattern,
    FillRect,
    Invert,
    SelectionCombine,
    SelectionBlur,
    CoverageFill,
    MaskedCopy,
    TransformMesh,
    AdjustTile,
    AdjustTileSpatial,
    AdjustTileLiquify,
    AdjustPass,
    AdjustPassRays,
    AdjustPassTexels,
  };
  const gpu::rhi::Pipeline* enginePipeline(EngineProgram program, gpu::rhi::Format targetFormat,
                                           bool blend = false);
  std::unique_ptr<gpu::rhi::Texture> createSurfaceTexture(
      int32_t width, int32_t height, std::optional<gpu::rhi::Format> format = std::nullopt);
  void clearTexture(const gpu::rhi::Texture& texture, std::array<float, 4> color);
  void waitForGpu();
  bool startCompositePreview(int32_t requestId, int32_t maxSide);
  void pollCompositePreview();
  void reportCorruptTiles();
  void reportDocumentShown();
  [[nodiscard]] canvas::TileRange visibleTiles() const;
  void processPendingFills();
  void paintTestPattern(const canvas::LayerTileKey& key, const canvas::AtlasSlot& slot,
                        std::array<float, 3> color, float seed);
  [[nodiscard]] std::string statsJson() const;
  void adaptCompositeBudget(float frameIntervalMilliseconds);
  void recordLatencyFrame(bool singleBuffered);
  void collectLatencies();
  void enterFrontBufferMode();
  void tryEnterFrontBufferMode();
  [[nodiscard]] bool backBufferFramesPresented();
  void checkSingleBufferPresented();
  void leaveFrontBufferMode();
  void waitForSingleBufferFrame();
  [[nodiscard]] std::optional<std::array<int32_t, 4>> screenRegionOf(
      const std::array<double, 4>& canvasRect) const;

  struct PaintSession {
    StrokeTool tool = StrokeTool::Paint;
    canvas::LayerId layer = 0;
    canvas::StrokeRenderer::CommitOptions commit;
    float opacity = 1;
    stroke::DabGenerator generator;
    std::vector<canvas::TileCoordinate> touchedTiles;
    std::optional<stroke::DabGenerator> dualGenerator;
    std::optional<stroke::ParticleSystem> particles;
    std::chrono::steady_clock::time_point liftTime;
    bool ended = false;
    std::vector<stroke::InputSample> prediction;
    bool provisionalOutdated = false;
    int64_t latestInputNanos = 0;
    int64_t firstInputNanos = 0;
    bool frontBufferUpdateRecorded = false;
    int64_t historyEntry = 0;
  };

  struct PendingLatency {
    uint64_t frameId = 0;
    int64_t inputNanos = 0;
  };
  static constexpr size_t kLatencyHistorySize = 600;

  struct PendingGpuLatency {
    std::unique_ptr<gpu::rhi::Fence> fence;
    int64_t inputNanos = 0;
    int64_t strokeStartNanos = 0;
  };
  static constexpr size_t kFirstUpdateHistorySize = 64;

  void runStrokeTask(std::function<void()> task);
  bool openDocumentNow(int32_t widthPixels, int32_t heightPixels, canvas::LayerTree tree);
  void applyLayerTree();
  void fillSceneRect(const scene::FillAction& fill, canvas::LayerId surface);
  void writeSceneText(const scene::TextAction& action);
  bool writeTextIntoLayer(canvas::LayerId layer, const text::TextModel& model);
  bool writeTextTile(canvas::LayerId layer, const text::TextRasterizer& rasterizer,
                     canvas::TileCoordinate coordinate, std::vector<uint8_t>& tile);
  [[nodiscard]] canvas::TileRange tilesUnder(const text::PixelRect& rect) const;
  bool rasterizeTextLayerNow(canvas::LayerId layer, const std::string& modelJson, double dpi);
  const text::FontFace* fontFace(const std::string& fontId);

  struct RegisteredFont {
    std::string path;
    std::vector<uint8_t> data;
    int32_t faceIndex = 0;
    std::shared_ptr<text::FontFace> face;
    bool failed = false;
  };
  std::unique_ptr<text::FontLibrary> fontLibrary_;
  std::map<std::string, RegisteredFont> fonts_;
  std::vector<std::string> fontFallbacks_;
  bool startTimelapseFrame(int32_t requestId, int32_t maxSide,
                           const std::vector<canvas::LayerId>& excluded);
  void sampleLayerAlphasNow(int32_t requestId, int32_t canvasX, int32_t canvasY,
                            std::vector<canvas::LayerId> surfaces, int32_t attempt);
  std::vector<canvas::LayerId> prefetchSurfaces_;
  int64_t incompleteFrames_ = 0;
  void prefetchTiles();
  struct TextLayerShapes {
    int32_t widthPixels = 0;
    int32_t heightPixels = 0;
    std::vector<text::TextRasterizer::ShapeSignature> shapes;
  };
  std::map<canvas::LayerId, TextLayerShapes> textLayerShapes_;
  std::deque<float> textRasterMilliseconds_;

  struct DocumentOperation {
    enum class Kind {
      Fill,
      Clear,
      Invert,
      Copy,
      Compose,
      ApplyHistory,
      SelectContents,
      SelectPolygon,
      Feather,
      CopyMasked,
      Remap
    };
    Kind kind = Kind::Fill;
    canvas::LayerId surface = 0;
    canvas::LayerId source = 0;
    bool mask = false;
    bool selection = false;
    std::shared_ptr<const canvas::TileCoverageMap> coverage;
    canvas::SelectionCombine combine = canvas::SelectionCombine::Replace;
    canvas::LayerId limit = 0;
    int32_t surfaceKind = 0;
    std::vector<canvas::MeshVertex> triangles;
    canvas::TransformFilter filter = canvas::TransformFilter::Nearest;
    std::array<int32_t, 2> newSize{};
    std::shared_ptr<gpu::rhi::Texture> snapshot;
    std::shared_ptr<gpu::rhi::Buffer> meshBuffer;
    std::vector<float> weights;
    int32_t pass = 0;
    std::array<float, 4> color{};
    std::optional<canvas::CompositePlan> plan;
    int64_t historyEntry = 0;
    bool undo = true;
    bool started = false;
    std::deque<canvas::TileCoordinate> remaining;
  };
  [[nodiscard]] bool documentBusy() const;
  void runDeferredTasks();
  void markTileChanged(const canvas::LayerTileKey& key);
  void processOperations(std::chrono::steady_clock::time_point deadline);
  bool stepOperation(DocumentOperation& operation, std::chrono::steady_clock::time_point deadline);
  bool operateOnTile(DocumentOperation& operation, canvas::TileCoordinate coordinate);
  struct TransformSession {
    canvas::LayerId layer = 0;
    std::optional<canvas::LayerId> selection;
    int32_t requestId = 0;
    bool lifted = false;
    std::unique_ptr<gpu::rhi::Texture> floating;
    std::array<int32_t, 2> floatingOrigin{};
    std::array<int32_t, 2> floatingSize{};
    std::vector<canvas::MeshVertex> triangles;
    canvas::TransformFilter filter = canvas::TransformFilter::Bilinear;
    std::unique_ptr<gpu::rhi::Buffer> vertexBuffer;
    bool vertexBufferOutdated = true;
    canvas::TileRange shownTiles;
    bool endRequested = false;
    bool commit = false;
    int64_t historyEntry = 0;
  };
  void processTransform();
  bool liftTransformContent();
  void renderTransformTile(canvas::TileCoordinate coordinate, const gpu::rhi::Texture& target);
  bool finishTransform();
  [[nodiscard]] canvas::TileRange transformMeshTiles() const;
  struct SpatialResult {
    bool valid = false;
    uint64_t version = 0;
    int32_t outputLevel = -1;
    int32_t minimumLevel = 0;
    canvas::TileRange covered;
    adjust::SpatialPlan plan;
    adjust::LevelMapping mapping;
    std::array<int32_t, 2> rectOrigin{};
    std::array<int32_t, 2> rectSize{};
    std::array<int32_t, 2> levelSize{};
    std::array<std::unique_ptr<gpu::rhi::Texture>, 2> images;
    std::array<std::array<int32_t, 2>, 2> imageSizes{};
    std::array<gpu::rhi::Format, 2> imageFormats{};
    const gpu::rhi::Texture* result = nullptr;
  };
  struct AdjustmentSession {
    canvas::LayerId layer = 0;
    std::optional<canvas::LayerId> selection;
    std::optional<canvas::LayerId> mask;
    adjust::Parameters parameters;
    float amount = 1;
    uint64_t version = 0;
    bool shown = false;
    int32_t reducedLevel = -1;
    std::unique_ptr<gpu::rhi::Texture> reducedLayer;
    std::unique_ptr<gpu::rhi::Texture> reducedSelection;
    std::unique_ptr<gpu::rhi::Texture> reducedMask;
    std::unordered_set<canvas::TileCoordinate, canvas::TileCoordinateHash> reducedMaskOutdated;
    std::unique_ptr<gpu::rhi::Texture> reducedResult;
    bool reducedResultOutdated = true;
    SpatialResult spatialFull;
    SpatialResult spatialPreview;
    struct PooledImage {
      std::array<int32_t, 2> size{};
      gpu::rhi::Format format = gpu::rhi::Format::Rgba8;
      std::unique_ptr<gpu::rhi::Texture> texture;
    };
    std::vector<PooledImage> imagePool;
    bool spatialRetry = false;
    bool spatialDraftShown = false;
    std::optional<adjust::LiquifyField> liquifyField;
    std::unique_ptr<gpu::rhi::Texture> fieldTexture;
    std::unique_ptr<adjust::LiquifyStroke> liquifyStroke;
    std::chrono::steady_clock::time_point liquifyStrokeEnd;
    std::vector<adjust::LiquifyField::Change> liquifyUndo;
    std::vector<adjust::LiquifyField::Change> liquifyRedo;
    std::array<int32_t, 4> liquifyLevelDirty{};
    std::vector<canvas::TileCoordinate> shownTiles;
    std::unique_ptr<gpu::rhi::Texture> tableTexture;
    std::shared_ptr<const adjust::Table> uploadedTable;
    std::chrono::steady_clock::time_point lastChange;
    bool endRequested = false;
    bool commit = false;
    int64_t historyEntry = 0;
  };
  void processAdjustment();
  bool showAdjustment();
  void renderAdjustmentTile(canvas::TileCoordinate coordinate, int32_t sampleStride,
                            const gpu::rhi::Texture& target);
  const gpu::rhi::Texture* renderAdjustmentLevel(int32_t level);
  void prepareAdjustmentTextures();
  struct AdjustmentTexture {
    const gpu::rhi::Texture* texture = nullptr;
    std::array<int32_t, 2> origin{};
  };
  struct AdjustmentStroke {
    AdjustmentTexture tile;
    float opacity = 1;
    float luminance = 1;
  };
  struct AdjustmentInputs {
    AdjustmentTexture source;
    std::optional<AdjustmentTexture> selection;
    bool masked = false;
    std::optional<AdjustmentTexture> mask;
    std::optional<AdjustmentStroke> stroke;
    std::array<int32_t, 2> targetOrigin{};
    int32_t sampleStride = 1;
    float amount = 1;
    bool hasSource = true;
    const SpatialResult* spatial = nullptr;
    std::array<int32_t, 2> canvasOrigin{};
    int32_t canvasStride = 1;
    bool encodeSource = false;
    bool decodeResult = false;
  };
  void drawAdjustment(const AdjustmentInputs& inputs, gpu::rhi::Format targetFormat);
  [[nodiscard]] bool paintsAdjustmentMask() const;
  void adjustmentMaskChanged(const std::vector<canvas::TileCoordinate>& coordinates);
  [[nodiscard]] bool adjustmentChanging() const;
  bool finishAdjustment();
  [[nodiscard]] std::vector<canvas::TileCoordinate> adjustmentTiles() const;
  [[nodiscard]] std::vector<canvas::TileCoordinate> adjustmentOutputTiles() const;
  bool ensureReducedCopies(int32_t level);
  bool computeSpatial(SpatialResult& result, int32_t outputLevel, int32_t minimumLevel = -1,
                      std::optional<canvas::TileRange> covered = std::nullopt);
  struct SpatialPassInputs {
    const gpu::rhi::Texture* texture = nullptr;
    bool encodeInput = false;
    int32_t kind = 0;
    const adjust::SpatialPass* pass = nullptr;
    std::array<int32_t, 2> outputOrigin{};
    std::array<int32_t, 2> rectOrigin{};
    std::array<int32_t, 2> rectSize{};
    std::array<int32_t, 2> levelSize{};
    std::array<int32_t, 2> inputTexelOrigin{};
    std::array<int32_t, 2> inputBase{};
    std::array<int32_t, 2> inputLimit{};
    int32_t boxFactor = 1;
    std::array<float, 2> center{};
    adjust::LevelMapping mapping;
    bool exact = false;
  };
  void drawSpatialPass(const SpatialPassInputs& inputs, gpu::rhi::Format targetFormat);
  [[nodiscard]] int32_t draftLevel() const;
  AdjustmentSession* liquifySession();
  void liquifyFieldChanged();
  void uploadLiquifyField();
  void finishLiquifyStroke();
  void pushLiquifyStep(adjust::LiquifyField::Change change);
  struct FillSession {
    FillRequest request;
    std::shared_ptr<const std::vector<uint8_t>> sourcePixels;
    bool thresholdDirty = false;
    bool computing = false;
    uint64_t generation = 0;
    std::shared_ptr<const canvas::TileCoverageMap> result;
    std::shared_ptr<const canvas::TileCoverageMap> applied;
    std::unordered_map<canvas::TileCoordinate, bool, canvas::TileCoordinateHash> originals;
    bool endRequested = false;
    bool commit = false;
    int64_t historyEntry = 0;
    uint64_t id = 0;
    std::chrono::steady_clock::time_point started;
    double readMilliseconds = 0;
    double regionMilliseconds = 0;
  };
  void processFill();
  [[nodiscard]] bool fillNeedsWork() const {
    return fillSession_ && !fillSession_->computing &&
           (fillSession_->thresholdDirty || fillSession_->result || fillSession_->endRequested ||
            !fillSession_->sourcePixels);
  }
  bool readSurfacePixels(canvas::LayerId surface, std::vector<uint8_t>& pixels);
  bool fillTilesResident(const std::vector<canvas::TileCoordinate>& positions);
  bool applyFillPreview(const canvas::TileCoverageMap& coverage);
  void restoreFillOriginal(canvas::TileCoordinate coordinate);
  void applyFillTile(canvas::TileCoordinate coordinate, const canvas::TileCoverage& coverage,
                     int64_t historyEntry);
  void copySurfaceTile(const canvas::LayerTileKey& from, const canvas::LayerTileKey& to);
  bool finishFill(bool commit);
  void runFillNow(const FillRequest& request);
  void copyTileThroughScratch(const canvas::LayerTileKey& from, const canvas::LayerTileKey& to,
                              std::optional<canvas::LayerTileKey> selection = std::nullopt);
  bool snapshotForRemap(DocumentOperation& operation);
  bool operateOnRemapTile(DocumentOperation& operation, canvas::TileCoordinate coordinate);
  void drawMesh(const gpu::rhi::Buffer& vertices, uint32_t vertexCount,
                const gpu::rhi::Texture& texture, std::array<int32_t, 2> textureOrigin,
                std::array<int32_t, 2> textureSize, canvas::TransformFilter filter,
                canvas::TileCoordinate coordinate, gpu::rhi::Format targetFormat, bool blend);
  bool operateOnMaskedCopyTile(DocumentOperation& operation, canvas::TileCoordinate coordinate);
  bool operateOnSelectionTile(DocumentOperation& operation, canvas::TileCoordinate coordinate);
  void copyOperationScratchTo(const canvas::LayerTileKey& key);
  bool ensureOperationScratch();
  void drawMaskedCopies(
      const std::vector<std::pair<canvas::TileCoordinate, canvas::AtlasSlot>>& slots,
      gpu::rhi::Format targetFormat);
  void markStrokeTilesChanged(const std::vector<canvas::TileCoordinate>& coordinates);
  void emitHistoryEntryReady(int64_t entry, int64_t bytes);
  bool sendCompositePixels(int32_t requestId, bool deep = false);
  std::vector<uint8_t> readDeepComposite();
  bool sendThumbnails(int32_t requestId, const std::vector<canvas::LayerId>& surfaces,
                      const std::vector<bool>& masks, int32_t maxSide);
  void prepareCloneSource(const StrokeStyle::Clone& clone);
  void snapshotSource(bool composite, canvas::LayerId layer);
  std::unique_ptr<gpu::rhi::Texture> cloneSnapshot_;
  [[nodiscard]] const gpu::rhi::Texture* cloneSnapshotTexture() const {
    return cloneSnapshot_.get();
  }
  std::array<int32_t, 2> cloneSnapshotSize_{};
  PaintSession& startPaintSession(StrokeTool tool, canvas::LayerId layer, bool maskTarget,
                                  std::optional<canvas::LayerId> selection, bool selectionTarget,
                                  bool clone, std::shared_ptr<const stroke::LoadedBrush> dual,
                                  stroke::BrushParameters brush,
                                  const stroke::StrokeSettings& strokeSettings,
                                  std::shared_ptr<const stroke::GrayTexture> shapeTexture,
                                  std::shared_ptr<const stroke::GrayTexture> grainTexture);
  void renderStrokeDabs(PaintSession& session, const std::vector<stroke::Dab>& dabs);
  void addStrokePoint(PaintSession& session, const stroke::PathPoint& point);
  void finishStrokePoints(PaintSession& session);
  bool feedStrokeSamples(PaintSession& session, const std::vector<stroke::InputSample>& samples);
  void restartPaintSession(PaintSession& session);
  void advanceParticles(PaintSession& session, double untilMilliseconds);
  bool particlesStillFlying(PaintSession& session);
  void updateProvisionalStroke(PaintSession& session);
  void clearProvisionalStroke();
  void tryCommitStroke();
  void abortStroke();

  EngineConfig config_;
  std::unique_ptr<EngineCallbacks> callbacks_;
  core::TaskQueue<std::function<void()>> tasks_;
  core::WorkerPool ioPool_{2};

  std::unique_ptr<gpu::rhi::Presenter> presenter_;
  std::unique_ptr<gpu::rhi::Device> device_;
  bool eglReady_ = false;
  ANativeWindow* window_ = nullptr;
  int32_t surfaceWidthPixels_ = 0;
  int32_t surfaceHeightPixels_ = 0;
  std::array<float, 4> clearColor_{1.0F, 1.0F, 1.0F, 1.0F};
  LatencyTestPoint latencyTestPoint_;
  bool frameRequested_ = false;
  bool hasPendingRenderWork_ = false;

  std::unique_ptr<gpu::gles::ShaderVariantCache> shaderCache_;
  std::unique_ptr<canvas::LayerTileStore> tileStore_;
  std::unique_ptr<canvas::Compositor> compositor_;
  std::unique_ptr<canvas::StrokeRenderer> strokeRenderer_;
  std::unique_ptr<canvas::ViewportRenderer> viewportRenderer_;
  std::unique_ptr<canvas::SelectionOverlay> selectionOverlay_;
  std::unique_ptr<canvas::HistoryStore> history_;
  std::unique_ptr<canvas::DocumentSaver> saver_;
  std::deque<std::function<bool()>> pendingSurfaceWrites_;
  int64_t reportedCorruptTiles_ = 0;
  std::map<std::tuple<EngineProgram, gpu::rhi::Format, bool>, const gpu::rhi::Pipeline*>
      enginePipelines_;
  std::optional<FillSession> fillSession_;
  std::optional<TransformSession> transformSession_;
  std::optional<AdjustmentSession> adjustmentSession_;
  std::unique_ptr<gpu::rhi::Sampler> linearSampler_;
  std::atomic<uint64_t> fillCounter_{0};
  struct PendingFillControl {
    std::optional<double> threshold;
    std::optional<std::pair<bool, int64_t>> end;
  };
  std::map<uint64_t, PendingFillControl> pendingFillControls_;
  std::unique_ptr<gpu::rhi::Texture> coverageTexture_;
  const gpu::rhi::Pipeline* thumbnailPipeline_ = nullptr;
  std::unique_ptr<gpu::rhi::Texture> operationScratch_;
  std::deque<DocumentOperation> operations_;
  int64_t historyRamBudgetBytes_ = 256LL * 1024 * 1024;
  gpu::GpuProfiler gpuProfiler_;
  bool documentShownReported_ = true;
  struct PreviewReadback {
    int32_t requestId = 0;
    int32_t width = 0;
    int32_t height = 0;
    std::unique_ptr<gpu::rhi::Readback> readback;
  };
  std::optional<PreviewReadback> previewReadback_;
  std::optional<PreviewReadback> frameReadback_;
  std::optional<std::pair<int32_t, int32_t>> nextPreview_;

  std::optional<Document> document_;
  std::string documentId_;
  uint64_t strokeIndex_ = 0;
  std::array<std::shared_ptr<const stroke::LoadedBrush>, kStrokeToolCount> brushes_;
  std::optional<stroke::Curve> pressureCurve_;
  float globalSmoothing_ = 0;
  bool stylusRoll_ = false;
  std::optional<PaintSession> paintSession_;
  std::deque<std::function<void()>> deferredStrokeTasks_;
  std::vector<stroke::Dab> dabScratch_;
  std::vector<stroke::Dab> dualScratch_;
  std::vector<stroke::Dab> particleScratch_;
  std::vector<canvas::TileCoordinate> touchedScratch_;
  std::deque<PendingFill> pendingFills_;
  core::AffineTransform canvasToScreen_;
  canvas::ViewportStyle viewportStyle_;
  bool dirtyTileOverlay_ = false;
  bool dimOutsideSelection_ = false;
  float antsPhasePixels_ = 0;
  std::optional<std::chrono::steady_clock::time_point> nextAntsStep_;
  std::optional<std::chrono::steady_clock::time_point> awakeUntil_;
  std::chrono::steady_clock::time_point lastGpuWork_;
  std::unique_ptr<gpu::rhi::Texture> awakeTarget_;

  int64_t frameIndex_ = 0;
  std::array<float, kFrameHistorySize> frameIntervalsMilliseconds_{};
  std::array<float, kFrameHistorySize> frameCpuMilliseconds_{};
  size_t frameHistoryCount_ = 0;
  std::optional<std::chrono::steady_clock::time_point> lastSwapTime_;
  int64_t compositeLayerTileBudget_ = 256;
  std::deque<PendingLatency> pendingLatencies_;
  int64_t lastLatencyInputNanos_ = 0;
  std::array<float, kLatencyHistorySize> latenciesMilliseconds_{};
  size_t latencyCount_ = 0;
  std::deque<PendingGpuLatency> pendingGpuLatencies_;
  std::array<float, kLatencyHistorySize> gpuLatenciesMilliseconds_{};
  size_t gpuLatencyCount_ = 0;
  std::array<float, kFirstUpdateHistorySize> firstUpdateLatenciesMilliseconds_{};
  size_t firstUpdateLatencyCount_ = 0;
  bool lastFrameFrontBuffered_ = false;
  bool frontBufferEnabled_ = true;
  bool frontBufferRequested_ = false;
  bool frontBufferWanted_ = false;
  std::chrono::steady_clock::time_point frontBufferWantedSince_;
  struct SingleBufferProbe {
    std::optional<uint64_t> frameId;
    std::chrono::steady_clock::time_point swapTime;
  };
  std::optional<SingleBufferProbe> singleBufferProbe_;
  std::optional<uint64_t> lastBackBufferFrameId_;
  std::optional<std::chrono::steady_clock::time_point> lastBackBufferSwapTime_;
  std::deque<std::unique_ptr<gpu::rhi::Fence>> singleBufferFrameFences_;
  int32_t fullRedrawsPending_ = 0;
  float expectedFrameIntervalMilliseconds_ = 1000.0F / 60.0F;

  std::thread renderThread_;
};

}  // namespace nanquim
