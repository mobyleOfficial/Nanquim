#include "canvas/stroke_renderer.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <unordered_map>
#include <utility>

#include "canvas/color_conversion.h"
#include "core/half_float.h"
#include "core/log.h"
#include "embedded_shaders.h"
#include "stroke/stroke_effects.h"
#include "stroke/tip_coverage.h"

namespace nanquim::canvas {

namespace {

constexpr const char* kDabPipeline = "strokeDab";
constexpr const char* kCommitPipeline = "strokeCommit";
constexpr const char* kReducePipeline = "tileAlphaReduce";
constexpr const char* kEdgeDownsamplePipeline = "edgeDownsample";
constexpr const char* kEdgeBlurPipeline = "edgeBlur";
constexpr const char* kWorkingCopyPipeline = "workingCopy";
constexpr const char* kMixSamplePipeline = "mixSample";
constexpr const char* kMixApplyPipeline = "mixApply";
constexpr const char* kGlazePipeline = "strokeGlaze";
constexpr size_t kMixingStateBytes = 3 * 4 * sizeof(float) + 4 * sizeof(int32_t);
constexpr uint32_t kWorkingCopyImageBinding = 0;
constexpr uint32_t kMixingStateBinding = 1;
constexpr uint32_t kGlazeResultBinding = 0;
constexpr uint32_t kGlazeDabsBinding = 1;
constexpr uint32_t kGlazePageBinding = 3;
constexpr const char* kEraseDefine = "ERASE";
constexpr const char* kMaskTargetDefine = "MASK_TARGET";
constexpr const char* kSelectionDefine = "SELECTION";
constexpr const char* kSelectionTargetDefine = "SELECTION_TARGET";
constexpr const char* kActiveStrokePipeline = "activeLayerStroke";
constexpr int32_t kPollsBeforeReduction = 8;

constexpr uint32_t kDabAttributes = 5;
constexpr size_t kFloatsPerDab = kDabAttributes * 4;
constexpr size_t kInstanceStride = kFloatsPerDab * sizeof(float);
constexpr uint32_t kShapeBinding = 1;
constexpr uint32_t kGrainBinding = 2;
constexpr uint32_t kCloneBinding = 3;
constexpr uint32_t kLayerBinding = 4;
constexpr uint32_t kSourceBinding = 1;
constexpr float kSquareRootOfTwo = 1.41421356F;

constexpr int32_t kReductionBlock = 16;
constexpr int32_t kIntermediateBlock = 64;
constexpr int32_t kReductionTilesPerRow = 16;
constexpr int32_t kReductionTargetSize = kReductionBlock * kReductionTilesPerRow;
constexpr int32_t kIntermediateTargetSize = kIntermediateBlock * kReductionTilesPerRow;
constexpr auto kReductionTilesPerBatch =
    static_cast<size_t>(kReductionTilesPerRow) * static_cast<size_t>(kReductionTilesPerRow);

using Int2 = std::array<int32_t, 2>;
using Float4 = std::array<float, 4>;

struct StrokeCommitParameters {
  Int2 strokeSlotOrigin;
  Int2 targetSlotOrigin;
  float opacity;
  float maskLuminance;
  Int2 padding;
};
static_assert(sizeof(StrokeCommitParameters) == 32);

struct TileAlphaReduceParameters {
  Int2 sourceOrigin;
  Int2 targetOrigin;
  int32_t alphaChannel;
  std::array<int32_t, 3> padding;
};
static_assert(sizeof(TileAlphaReduceParameters) == 32);

struct EdgeDownsampleParameters {
  Int2 strokeSlotOrigin;
  Int2 targetOrigin;
};
static_assert(sizeof(EdgeDownsampleParameters) == 16);

struct EdgeBlurParameters {
  Int2 sourceSize;
  Int2 direction;
  int32_t radius;
  std::array<int32_t, 3> padding;
  std::array<Float4, stroke::kMaximumEdgeBlurRadius + 1> weights;
};
static_assert(offsetof(EdgeBlurParameters, weights) == 32);
static_assert(sizeof(EdgeBlurParameters) == 240);

struct WorkingCopyParameters {
  Int2 sourceSlotOrigin;
  Int2 targetOrigin;
};
static_assert(sizeof(WorkingCopyParameters) == 16);

gpu::rhi::BlendState blendFunction(gpu::rhi::BlendFactor source,
                                   gpu::rhi::BlendFactor destination) {
  gpu::rhi::BlendState blend;
  blend.enabled = true;
  blend.sourceColor = source;
  blend.sourceAlpha = source;
  blend.destinationColor = destination;
  blend.destinationAlpha = destination;
  return blend;
}

gpu::rhi::BlendState overBlend() {
  return blendFunction(gpu::rhi::BlendFactor::One, gpu::rhi::BlendFactor::OneMinusSourceAlpha);
}

gpu::rhi::BlendState maxBlend() {
  gpu::rhi::BlendState blend =
      blendFunction(gpu::rhi::BlendFactor::One, gpu::rhi::BlendFactor::One);
  blend.colorOperation = gpu::rhi::BlendOperation::Max;
  blend.alphaOperation = gpu::rhi::BlendOperation::Max;
  return blend;
}

stroke::Dab inDocumentColor(const stroke::Dab& dab, bool linear) {
  if (!linear) {
    return dab;
  }
  stroke::Dab result = dab;
  for (size_t channel = 0; channel < 3; ++channel) {
    result.color[channel] = srgbToLinearValue(dab.color[channel]);
  }
  return result;
}

std::array<int32_t, 2> tileOriginOf(TileCoordinate coordinate) {
  return {coordinate.column * kTileSizePixels, coordinate.row * kTileSizePixels};
}

}  // namespace

struct StrokeDabParameters {
  std::array<float, 2> tileOrigin;
  float extentScale;
  StrokeRenderer::TipUniforms tip;
  int32_t padding0;
  Int2 cloneOffset;
  Int2 cloneSize;
  Int2 layerSize;
  float layerMixAmount;
  float padding1;
};
static_assert(offsetof(StrokeDabParameters, tip) == 12);
static_assert(offsetof(StrokeDabParameters, cloneOffset) == 64);
static_assert(sizeof(StrokeDabParameters) == 96);

struct MixSampleParameters {
  Int2 workingSize;
  Int2 padding0;
  Float4 dabGeometry;
  Float4 dabShape;
  Float4 dabGrain;
  Float4 dabGradation;
  float boundingRadius;
  int32_t mixingMode;
  Int2 padding1;
  Float4 brushPaint;
  float pickUp;
  float useBrushColor;
  float pull;
  StrokeRenderer::TipUniforms tip;
  int32_t padding2;
};
static_assert(offsetof(MixSampleParameters, dabGeometry) == 16);
static_assert(offsetof(MixSampleParameters, brushPaint) == 96);
static_assert(offsetof(MixSampleParameters, tip) == 124);
static_assert(sizeof(MixSampleParameters) == 176);

struct MixApplyParameters {
  Float4 dabGeometry;
  Float4 dabShape;
  Float4 dabGrain;
  Float4 dabGradation;
  Int2 boxOrigin;
  Int2 boxSize;
  int32_t mixingMode;
  float amount;
  float blur;
  StrokeRenderer::TipUniforms tip;
  int32_t padding;
};
static_assert(offsetof(MixApplyParameters, boxOrigin) == 64);
static_assert(offsetof(MixApplyParameters, tip) == 92);
static_assert(sizeof(MixApplyParameters) == 144);

struct StrokeGlazeParameters {
  Int2 slotOrigin;
  std::array<float, 2> tileOrigin;
  Int2 canvasLimit;
  int32_t firstDab;
  int32_t dabCount;
  float extentScale;
  StrokeRenderer::TipUniforms tip;
  std::array<int32_t, 3> padding;
};
static_assert(offsetof(StrokeGlazeParameters, firstDab) == 24);
static_assert(offsetof(StrokeGlazeParameters, tip) == 36);
static_assert(sizeof(StrokeGlazeParameters) == 96);

StrokeRenderer::StrokeRenderer(gpu::rhi::Device& device, LayerTileStore& tileStore)
    : device_(device), tileStore_(tileStore) {}

const gpu::rhi::Pipeline* StrokeRenderer::pipeline(const char* program,
                                                   const std::vector<std::string>& defines,
                                                   const gpu::rhi::BlendState& blend,
                                                   gpu::rhi::Format target, bool dabs) {
  gpu::rhi::PipelineDescription description;
  description.program = program;
  description.defines = defines;
  description.blend = blend;
  description.targetFormat = target;
  if (dabs) {
    description.topology = gpu::rhi::Topology::TriangleStrip;
    description.vertexLayout =
        gpu::rhi::VertexLayout{kDabAttributes, static_cast<uint32_t>(kInstanceStride), true};
  }
  std::string errorMessage;
  const gpu::rhi::Pipeline* result = device_.pipeline(description, errorMessage);
  if (result == nullptr) {
    NANQUIM_LOG_ERROR("%s", errorMessage.c_str());
  }
  return result;
}

std::unique_ptr<gpu::rhi::Texture> StrokeRenderer::createTarget(gpu::rhi::Format format,
                                                                int32_t width, int32_t height,
                                                                uint32_t extraUsage) {
  gpu::rhi::TextureDescription description;
  description.format = format;
  description.width = width;
  description.height = height;
  description.usage = gpu::rhi::kUsageSampled | gpu::rhi::kUsageRenderTarget |
                      gpu::rhi::kUsageReadback | extraUsage;
  return device_.createTexture(description);
}

bool StrokeRenderer::initialize(std::string& errorMessage) {
  device_.registerProgram(kDabPipeline, shaders::stroke_dab_vertex, shaders::stroke_dab_fragment);
  device_.registerProgram(kCommitPipeline, shaders::fullscreen_vertex,
                          shaders::stroke_commit_fragment);
  device_.registerProgram(kReducePipeline, shaders::fullscreen_vertex,
                          shaders::tile_alpha_reduce_fragment);
  device_.registerProgram(kEdgeDownsamplePipeline, shaders::fullscreen_vertex,
                          shaders::edge_downsample_fragment);
  device_.registerProgram(kEdgeBlurPipeline, shaders::fullscreen_vertex,
                          shaders::edge_blur_fragment);
  device_.registerProgram(kWorkingCopyPipeline, shaders::fullscreen_vertex,
                          shaders::working_copy_fragment);
  device_.registerComputeProgram(kMixSamplePipeline, shaders::mix_sample_compute);
  device_.registerComputeProgram(kMixApplyPipeline, shaders::mix_apply_compute);
  device_.registerComputeProgram(kGlazePipeline, shaders::stroke_glaze_compute);
  buffer_.initialize(device_);
  provisional_.initialize(device_);
  dualBuffer_.initialize(device_);
  particleBuffer_.initialize(device_);
  const gpu::rhi::Format strokeFormat =
      buffer_.isHalfFloat() ? gpu::rhi::Format::Rgba16f : gpu::rhi::Format::Rgba8;
  const gpu::rhi::Format tileFormat = textureFormat(tileStore_.pixelFormat());
  if (pipeline(kDabPipeline, {}, overBlend(), strokeFormat, true) == nullptr ||
      pipeline(kCommitPipeline, {}, overBlend(), tileFormat) == nullptr ||
      pipeline(kCommitPipeline, {kEraseDefine}, overBlend(), tileFormat) == nullptr ||
      pipeline(kCommitPipeline, {kMaskTargetDefine}, overBlend(), tileFormat) == nullptr ||
      pipeline(kReducePipeline, {}, {}, tileFormat) == nullptr) {
    errorMessage = "Stroke shaders failed to compile";
    return false;
  }
  destinationRead_ = device_.readsDestination();
  instanceBuffer_ = device_.createBuffer(kInstanceStride * 64,
                                         gpu::rhi::kBufferVertex | gpu::rhi::kBufferStorage);
  nearestClampSampler_ = device_.createSampler(gpu::rhi::SamplerDescription{
      gpu::rhi::Filter::Nearest, false, gpu::rhi::AddressMode::ClampToEdge});
  mipmapClampSampler_ = device_.createSampler(gpu::rhi::SamplerDescription{
      gpu::rhi::Filter::Linear, true, gpu::rhi::AddressMode::ClampToEdge});
  nearestRepeatSampler_ = device_.createSampler(gpu::rhi::SamplerDescription{
      gpu::rhi::Filter::Nearest, false, gpu::rhi::AddressMode::Repeat});
  mipmapRepeatSampler_ = device_.createSampler(
      gpu::rhi::SamplerDescription{gpu::rhi::Filter::Linear, true, gpu::rhi::AddressMode::Repeat});
  return instanceBuffer_ != nullptr;
}

void StrokeRenderer::setBrush(const stroke::BrushParameters& brush,
                              std::shared_ptr<const stroke::GrayTexture> shapeTexture,
                              std::shared_ptr<const stroke::GrayTexture> grainTexture) {
  brush_ = brush;
  primitive_ = stroke::primitiveFor(brush.render.accumulation).primitive;
  shapeTexture_ = std::move(shapeTexture);
  grainTexture_ = brush.grain.enabled() ? std::move(grainTexture) : nullptr;
  textureFor(shapeTexture_);
  textureFor(grainTexture_);
  if (glazesByCompute()) {
    glazePipeline(buffer_.isHalfFloat());
  } else {
    pipeline(kDabPipeline, dabDefines(), overBlend(),
             buffer_.isHalfFloat() ? gpu::rhi::Format::Rgba16f : gpu::rhi::Format::Rgba8, true);
  }
}

void StrokeRenderer::setStrokeEffects(const stroke::RenderParameters& render, double sizePixels,
                                      int32_t canvasWidth, int32_t canvasHeight) {
  effects_ = StrokeEffectsBinding{render, nullptr, {}};
  if (!render.hasEdges()) {
    return;
  }
  const std::array<int32_t, 2> size{
      (canvasWidth + stroke::kEdgeMapScale - 1) / stroke::kEdgeMapScale,
      (canvasHeight + stroke::kEdgeMapScale - 1) / stroke::kEdgeMapScale};
  if (!edgeMap_ || edgeMapSize_ != size) {
    const gpu::rhi::Format format =
        buffer_.isHalfFloat() ? gpu::rhi::Format::R16f : gpu::rhi::Format::R8;
    edgeMap_ = createTarget(format, size[0], size[1]);
    edgeRows_ = createTarget(format, size[0], size[1]);
    edgeBlurred_ = createTarget(format, size[0], size[1]);
    edgeMapSize_ = size;
    if (!edgeMap_ || !edgeRows_ || !edgeBlurred_) {
      NANQUIM_LOG_ERROR("No GPU memory for the edge map; the stroke has no edges");
      edgeMap_.reset();
      edgeRows_.reset();
      edgeBlurred_.reset();
      edgeMapSize_ = {};
      return;
    }
  }
  clearEdgeMap();
  edgeWeights_ = stroke::edgeBlurWeights(sizePixels);
  effects_.edgeMap = edgeBlurred_.get();
  effects_.edgeMapSize = size;
  const gpu::rhi::Format format = edgeMap_->description().format;
  pipeline(kEdgeDownsamplePipeline, {}, {}, format);
  pipeline(kEdgeBlurPipeline, {}, {}, format);
}

void StrokeRenderer::clearEdgeMap() {
  gpu::rhi::CommandContext& commands = device_.commands();
  for (const auto* texture : {edgeMap_.get(), edgeRows_.get(), edgeBlurred_.get()}) {
    if (texture != nullptr) {
      commands.beginPass(gpu::rhi::RenderTarget::of(*texture), gpu::rhi::LoadAction::Clear);
      commands.endPass();
    }
  }
}

void StrokeRenderer::restartStroke(LayerId layer, int32_t canvasWidth, int32_t canvasHeight) {
  buffer_.clear();
  dualBuffer_.clear();
  particleBuffer_.clear();
  if (effects_.edges()) {
    clearEdgeMap();
  }
  if (mixing()) {
    beginMixing(mixing_, layer, mixingOpacity_, canvasWidth, canvasHeight);
  }
}

void StrokeRenderer::updateEdgeMap(const std::vector<TileCoordinate>& tiles, bool strokeOnly) {
  if (!effects_.edges() || tiles.empty()) {
    return;
  }
  const gpu::rhi::Format format = edgeMap_->description().format;
  const gpu::rhi::Pipeline* downsample = pipeline(kEdgeDownsamplePipeline, {}, {}, format);
  const gpu::rhi::Pipeline* blur = pipeline(kEdgeBlurPipeline, {}, {}, format);
  if (downsample == nullptr || blur == nullptr) {
    return;
  }
  constexpr int32_t kBlock = kTileSizePixels / stroke::kEdgeMapScale;
  gpu::rhi::CommandContext& commands = device_.commands();
  commands.beginPass(gpu::rhi::RenderTarget::of(*edgeMap_), gpu::rhi::LoadAction::Load);
  commands.bindPipeline(*downsample);
  std::array<int32_t, 4> box{edgeMapSize_[0], edgeMapSize_[1], 0, 0};
  for (const TileCoordinate coordinate : tiles) {
    const int32_t left = coordinate.column * kBlock;
    const int32_t top = coordinate.row * kBlock;
    if (left >= edgeMapSize_[0] || top >= edgeMapSize_[1] || left < 0 || top < 0) {
      continue;
    }
    const int32_t width = std::min(kBlock, edgeMapSize_[0] - left);
    const int32_t height = std::min(kBlock, edgeMapSize_[1] - top);
    box = {std::min(box[0], left), std::min(box[1], top), std::max(box[2], left + width),
           std::max(box[3], top + height)};
    const gpu::rhi::Rect block{left, top, width, height};
    commands.setScissor(block);
    std::optional<StrokeBuffer::Slot> slot;
    const StrokeBuffer* source = &buffer_;
    if (!strokeOnly) {
      slot = provisional_.find(coordinate);
      source = &provisional_;
    }
    if (!slot) {
      slot = buffer_.find(coordinate);
      source = &buffer_;
    }
    if (!slot) {
      commands.clear({0, 0, 0, 0}, block);
      continue;
    }
    commands.bindTexture(kSourceBinding, &source->pageTexture(slot->page));
    commands.setUniforms(EdgeDownsampleParameters{slot->origin, {left, top}});
    commands.draw(3);
  }
  commands.endPass();
  if (box[0] >= box[2]) {
    return;
  }
  const auto radius = static_cast<int32_t>(edgeWeights_.size()) - 1;
  const int32_t left = std::max(0, box[0] - radius);
  const int32_t right = std::min(edgeMapSize_[0], box[2] + radius);
  const int32_t top = std::max(0, box[1] - radius);
  const int32_t bottom = std::min(edgeMapSize_[1], box[3] + radius);
  EdgeBlurParameters parameters{};
  parameters.sourceSize = edgeMapSize_;
  parameters.radius = radius;
  for (size_t index = 0; index < edgeWeights_.size() && index < parameters.weights.size();
       ++index) {
    parameters.weights[index][0] = edgeWeights_[index];
  }
  parameters.direction = {1, 0};
  commands.beginPass(gpu::rhi::RenderTarget::of(*edgeRows_), gpu::rhi::LoadAction::Load);
  commands.setScissor(gpu::rhi::Rect{left, box[1], right - left, box[3] - box[1]});
  commands.bindPipeline(*blur);
  commands.bindTexture(kSourceBinding, edgeMap_.get());
  commands.setUniforms(parameters);
  commands.draw(3);
  commands.endPass();
  parameters.direction = {0, 1};
  commands.beginPass(gpu::rhi::RenderTarget::of(*edgeBlurred_), gpu::rhi::LoadAction::Load);
  commands.setScissor(gpu::rhi::Rect{left, top, right - left, bottom - top});
  commands.bindPipeline(*blur);
  commands.bindTexture(kSourceBinding, edgeRows_.get());
  commands.setUniforms(parameters);
  commands.draw(3);
  commands.endPass();
}

std::vector<std::string> StrokeRenderer::tipDefines() const {
  std::vector<std::string> defines;
  if (shapeTexture_) {
    defines.emplace_back("SHAPE_TEXTURE");
    if (shapeTexture_->hasColor()) {
      defines.emplace_back("SHAPE_COLOR");
    }
  }
  if (grainTexture_) {
    defines.emplace_back("GRAIN");
    if (!brush_.grain.moving()) {
      defines.emplace_back("GRAIN_TEXTURIZED");
    }
    defines.push_back("GRAIN_BLEND_" + std::to_string(static_cast<int>(brush_.grain.blend)));
  }
  return defines;
}

bool StrokeRenderer::linearDocument() const { return isLinear(tileStore_.pixelFormat()); }

bool StrokeRenderer::workingHalf() const { return linearDocument(); }

std::vector<std::string> StrokeRenderer::mixingDefines() const {
  std::vector<std::string> defines = tipDefines();
  if (workingHalf()) {
    defines.emplace_back("WORKING_HALF");
  }
  return defines;
}

std::vector<std::string> StrokeRenderer::workingDefines() const {
  return workingHalf() ? std::vector<std::string>{"WORKING_HALF"} : std::vector<std::string>{};
}

bool StrokeRenderer::beginMixing(stroke::PaintMixing mode, LayerId layer, double strokeOpacity,
                                 int32_t canvasWidth, int32_t canvasHeight) {
  mixing_ = stroke::PaintMixing::None;
  workingMissing_.clear();
  if (mode == stroke::PaintMixing::None) {
    return true;
  }
  std::string errorMessage;
  const gpu::rhi::Pipeline* copy =
      pipeline(kWorkingCopyPipeline, workingDefines(), {}, gpu::rhi::Format::R32ui);
  if (copy == nullptr ||
      device_.computePipeline({kMixSamplePipeline, mixingDefines()}, errorMessage) == nullptr ||
      device_.computePipeline({kMixApplyPipeline, mixingDefines()}, errorMessage) == nullptr) {
    NANQUIM_LOG_ERROR("%s", errorMessage.c_str());
    return false;
  }
  const int32_t texelsPerPixel = workingHalf() ? 2 : 1;
  if (canvasWidth * texelsPerPixel > device_.maxTextureSize()) {
    NANQUIM_LOG_ERROR("Working copy wider than the GPU allows");
    return false;
  }
  const std::array<int32_t, 2> size{canvasWidth * texelsPerPixel, canvasHeight};
  if (!workingCopy_ || workingCopySize_ != size) {
    workingCopy_ =
        createTarget(gpu::rhi::Format::R32ui, size[0], canvasHeight, gpu::rhi::kUsageStorage);
    workingCopySize_ = workingCopy_ ? size : std::array<int32_t, 2>{};
  }
  if (!mixingState_) {
    mixingState_ = device_.createBuffer(kMixingStateBytes, gpu::rhi::kBufferStorage);
  }
  if (!workingCopy_ || !mixingState_) {
    NANQUIM_LOG_ERROR("No GPU memory for the working copy");
    return false;
  }
  const std::array<uint8_t, kMixingStateBytes> zeros{};
  device_.uploadBuffer(*mixingState_, zeros.data(), zeros.size());
  gpu::rhi::CommandContext& commands = device_.commands();
  commands.beginPass(gpu::rhi::RenderTarget::of(*workingCopy_), gpu::rhi::LoadAction::Clear);
  commands.bindPipeline(*copy);
  const TileRange canvasTiles = tileRangeForCanvas(canvasWidth, canvasHeight);
  for (const TileCoordinate coordinate : tileStore_.tilesOf(layer)) {
    if (!canvasTiles.contains(coordinate)) {
      continue;
    }
    tileStore_.ensureResident(LayerTileKey{layer, coordinate});
    const auto slot = tileStore_.slotForRead(LayerTileKey{layer, coordinate});
    if (!slot) {
      workingMissing_.insert(coordinate);
      continue;
    }
    const auto [originX, originY] = tileOriginOf(coordinate);
    commands.setViewport(gpu::rhi::Rect{originX * texelsPerPixel, originY,
                                        kTileSizePixels * texelsPerPixel, kTileSizePixels});
    commands.bindTexture(kSourceBinding, &tileStore_.pageTexture(slot->page));
    commands.setUniforms(
        WorkingCopyParameters{{slotOriginX(*slot), slotOriginY(*slot)}, {originX, originY}});
    commands.draw(3);
  }
  commands.endPass();
  mixing_ = mode;
  mixingOpacity_ = strokeOpacity;
  mixingStarted_ = false;
  mixingPreviousDistance_ = 0;
  return true;
}

void StrokeRenderer::setDualBrush(std::optional<DualBrush> dual) {
  dualBuffer_.clear();
  dual_ = std::move(dual);
  effects_.dual = dual_ ? &dualBuffer_ : nullptr;
  if (dual_) {
    effects_.dualBlend = dual_->blend;
    effects_.dualColor = dual_->color;
    if (linearDocument()) {
      for (float& channel : effects_.dualColor) {
        channel = static_cast<float>(srgbToLinearValue(channel));
      }
    }
    textureFor(dual_->shapeTexture);
    textureFor(dual_->grainTexture);
  }
}

void StrokeRenderer::renderDualDabs(const std::vector<stroke::Dab>& dabs, int32_t canvasWidth,
                                    int32_t canvasHeight,
                                    std::vector<TileCoordinate>& touchedTiles) {
  if (!dual_ || dabs.empty() || mixing()) {
    return;
  }
  std::swap(brush_, dual_->brush);
  std::swap(shapeTexture_, dual_->shapeTexture);
  std::swap(grainTexture_, dual_->grainTexture);
  const stroke::Accumulation primitive =
      std::exchange(primitive_, stroke::primitiveFor(brush_.render.accumulation).primitive);
  const std::optional<CloneSource> clone = std::exchange(cloneSource_, std::nullopt);
  const std::optional<CloneSource> layer = std::exchange(layerSource_, std::nullopt);
  const StrokeEffectsBinding effects = std::exchange(effects_, StrokeEffectsBinding{});
  const size_t first = touchedTiles.size();
  renderDabsInto(dualBuffer_, false, dabs, canvasWidth, canvasHeight, touchedTiles);
  effects_ = effects;
  layerSource_ = layer;
  cloneSource_ = clone;
  primitive_ = primitive;
  std::swap(grainTexture_, dual_->grainTexture);
  std::swap(shapeTexture_, dual_->shapeTexture);
  std::swap(brush_, dual_->brush);
  for (size_t index = first; index < touchedTiles.size(); ++index) {
    buffer_.acquire(touchedTiles[index]);
  }
}

void StrokeRenderer::setParticles(bool enabled, bool useShape) {
  particleBuffer_.clear();
  particlesUseShape_ = useShape;
  effects_.particles = enabled ? &particleBuffer_ : nullptr;
}

void StrokeRenderer::renderParticleDabs(const std::vector<stroke::Dab>& dabs,
                                        int32_t canvasWidth, int32_t canvasHeight,
                                        std::vector<TileCoordinate>& touchedTiles) {
  if (effects_.particles == nullptr || dabs.empty()) {
    return;
  }
  const stroke::BrushParameters brush = brush_;
  brush_.render.accumulation = stroke::Accumulation::BuildUp;
  const stroke::Accumulation primitive = std::exchange(primitive_, stroke::Accumulation::BuildUp);
  std::shared_ptr<const stroke::GrayTexture> shape = shapeTexture_;
  if (!particlesUseShape_) {
    shapeTexture_.reset();
  }
  const std::optional<CloneSource> clone = std::exchange(cloneSource_, std::nullopt);
  const std::optional<CloneSource> layer = std::exchange(layerSource_, std::nullopt);
  const StrokeEffectsBinding effects = std::exchange(effects_, StrokeEffectsBinding{});
  const size_t first = touchedTiles.size();
  renderDabsInto(particleBuffer_, false, dabs, canvasWidth, canvasHeight, touchedTiles);
  effects_ = effects;
  layerSource_ = layer;
  cloneSource_ = clone;
  shapeTexture_ = std::move(shape);
  primitive_ = primitive;
  brush_ = brush;
  for (size_t index = first; index < touchedTiles.size(); ++index) {
    buffer_.acquire(touchedTiles[index]);
  }
}

void StrokeRenderer::releaseWorkingCopy() {
  workingCopy_.reset();
  workingCopySize_ = {};
  mixingState_.reset();
}

StrokeRenderer::TipUniforms StrokeRenderer::bindTip() {
  gpu::rhi::CommandContext& commands = device_.commands();
  TipUniforms tip{};
  tip.hardness = static_cast<float>(brush_.shape.hardness);
  if (shapeTexture_) {
    const stroke::GrayTexture& shape = *shapeTexture_;
    const bool nearest = brush_.shape.filtering == stroke::TextureFiltering::None;
    commands.bindTexture(kShapeBinding, textureFor(shapeTexture_),
                         nearest ? nearestClampSampler_.get() : mipmapClampSampler_.get());
    tip.shapeSize = static_cast<float>(std::max(shape.width(), shape.height()));
    tip.shapeInvert = brush_.shape.invert ? 1 : 0;
    tip.shapeFiltering = static_cast<int32_t>(brush_.shape.filtering);
  }
  if (grainTexture_) {
    const stroke::GrayTexture& grain = *grainTexture_;
    const stroke::GrainParameters& parameters = brush_.grain;
    const bool nearest = parameters.filtering == stroke::TextureFiltering::None;
    commands.bindTexture(kGrainBinding, textureFor(grainTexture_),
                         nearest ? nearestRepeatSampler_.get() : mipmapRepeatSampler_.get());
    tip.grainSize = static_cast<float>(std::max(grain.width(), grain.height()));
    tip.grainScale = static_cast<float>(parameters.scale);
    tip.grainRotation = static_cast<float>(parameters.rotation);
    tip.grainRotateWithDab = parameters.rotateWithDab ? 1 : 0;
    tip.grainBrightness = static_cast<float>(parameters.brightness);
    tip.grainContrast = static_cast<float>(parameters.contrast);
    tip.grainInvert = parameters.invert ? 1 : 0;
    tip.grainImproved = parameters.filtering == stroke::TextureFiltering::Improved ? 1 : 0;
  }
  return tip;
}

void StrokeRenderer::renderMixingDabs(const std::vector<stroke::Dab>& dabs, int32_t canvasWidth,
                                      int32_t canvasHeight,
                                      std::vector<TileCoordinate>& touchedTiles) {
  if (dabs.empty()) {
    return;
  }
  const bool textured = shapeTexture_ != nullptr;
  const TileRange canvasTiles = tileRangeForCanvas(canvasWidth, canvasHeight);
  std::unordered_set<TileCoordinate, TileCoordinateHash> seen;
  for (const stroke::Dab& dab : dabs) {
    const double extent = stroke::dabBoundingRadius(dab, textured);
    tileRangeCoveringRect(dab.x - extent, dab.y - extent, dab.x + extent, dab.y + extent)
        .intersection(canvasTiles)
        .forEach([&](TileCoordinate coordinate) {
          if (seen.insert(coordinate).second && buffer_.acquire(coordinate)) {
            touchedTiles.push_back(coordinate);
          }
        });
  }
  std::string errorMessage;
  const std::vector<std::string> defines = mixingDefines();
  const gpu::rhi::Pipeline* sample =
      device_.computePipeline({kMixSamplePipeline, defines}, errorMessage);
  const gpu::rhi::Pipeline* apply =
      device_.computePipeline({kMixApplyPipeline, defines}, errorMessage);
  if (sample == nullptr || apply == nullptr) {
    NANQUIM_LOG_ERROR("%s", errorMessage.c_str());
    return;
  }
  gpu::rhi::CommandContext& commands = device_.commands();
  commands.beginCompute();
  const TipUniforms tip = bindTip();
  commands.bindStorageImage(kWorkingCopyImageBinding, *workingCopy_,
                            gpu::rhi::StorageAccess::ReadWrite);
  commands.bindStorageBuffer(kMixingStateBinding, *mixingState_);
  MixSampleParameters sampleParameters{};
  sampleParameters.workingSize = {canvasWidth, canvasHeight};
  sampleParameters.mixingMode = static_cast<int32_t>(mixing_);
  sampleParameters.tip = tip;
  MixApplyParameters applyParameters{};
  applyParameters.mixingMode = static_cast<int32_t>(mixing_);
  applyParameters.tip = tip;
  for (const stroke::Dab& dab : dabs) {
    const double extent = stroke::dabBoundingRadius(dab, textured);
    const stroke::DabMixing mixing =
        stroke::dabMixing(mixing_, brush_, mixingOpacity_, inDocumentColor(dab, linearDocument()),
                          mixingStarted_ ? mixingPreviousDistance_ : dab.distance);
    mixingStarted_ = true;
    mixingPreviousDistance_ = dab.distance;
    const float flips = (dab.flipX ? 1.0F : 0.0F) + (dab.flipY ? 2.0F : 0.0F);
    const Float4 geometry{static_cast<float>(dab.x), static_cast<float>(dab.y),
                          static_cast<float>(dab.diameter / 2), static_cast<float>(dab.angle)};
    const Float4 shape{static_cast<float>(dab.roundness),
                       static_cast<float>(dab.opacity * dab.flow), flips,
                       static_cast<float>(dab.bleed)};
    const Float4 grain{static_cast<float>(dab.grainOffset[0]),
                       static_cast<float>(dab.grainOffset[1]), static_cast<float>(dab.grainDepth),
                       static_cast<float>(dab.grainPeriod)};
    const Float4 gradation{static_cast<float>(dab.gradation),
                           static_cast<float>(dab.gradationAngle), 0, 0};
    sampleParameters.dabGeometry = geometry;
    sampleParameters.dabShape = shape;
    sampleParameters.dabGrain = grain;
    sampleParameters.dabGradation = gradation;
    sampleParameters.boundingRadius = static_cast<float>(extent);
    sampleParameters.brushPaint = {
        static_cast<float>(mixing.brushPaint[0]), static_cast<float>(mixing.brushPaint[1]),
        static_cast<float>(mixing.brushPaint[2]), static_cast<float>(mixing.brushPaint[3])};
    sampleParameters.pickUp = static_cast<float>(mixing.pickUp);
    sampleParameters.useBrushColor = static_cast<float>(mixing.useBrushColor);
    sampleParameters.pull = static_cast<float>(mixing.pull);
    commands.bindComputePipeline(*sample);
    commands.setUniforms(sampleParameters);
    commands.dispatch(1, 1, 1);
    commands.barrier(gpu::rhi::kBarrierStorage);
    const int32_t firstX = std::max(0, static_cast<int32_t>(std::floor(dab.x - extent)));
    const int32_t firstY = std::max(0, static_cast<int32_t>(std::floor(dab.y - extent)));
    const int32_t endX =
        std::min(canvasWidth, static_cast<int32_t>(std::ceil(dab.x + extent)) + 1);
    const int32_t endY =
        std::min(canvasHeight, static_cast<int32_t>(std::ceil(dab.y + extent)) + 1);
    if (endX <= firstX || endY <= firstY) {
      continue;
    }
    applyParameters.dabGeometry = geometry;
    applyParameters.dabShape = shape;
    applyParameters.dabGrain = grain;
    applyParameters.dabGradation = gradation;
    applyParameters.boxOrigin = {firstX, firstY};
    applyParameters.boxSize = {endX - firstX, endY - firstY};
    applyParameters.amount = static_cast<float>(mixing.amount);
    applyParameters.blur = static_cast<float>(mixing.blur);
    commands.bindComputePipeline(*apply);
    commands.setUniforms(applyParameters);
    constexpr uint32_t kGroup = 8;
    commands.dispatch((static_cast<uint32_t>(endX - firstX) + kGroup - 1) / kGroup,
                      (static_cast<uint32_t>(endY - firstY) + kGroup - 1) / kGroup, 1);
    commands.barrier(gpu::rhi::kBarrierStorage | gpu::rhi::kBarrierSampled);
  }
  commands.endCompute();
}

std::vector<std::string> StrokeRenderer::dabDefines() const {
  std::vector<std::string> defines;
  if (shapeTexture_) {
    defines.emplace_back("SHAPE_TEXTURE");
    if (shapeTexture_->hasColor()) {
      defines.emplace_back("SHAPE_COLOR");
      if (linearDocument()) {
        defines.emplace_back("LINEAR_DOCUMENT");
      }
    }
  }
  if (grainTexture_) {
    defines.emplace_back("GRAIN");
    if (!brush_.grain.moving()) {
      defines.emplace_back("GRAIN_TEXTURIZED");
    }
    defines.push_back("GRAIN_BLEND_" + std::to_string(static_cast<int>(brush_.grain.blend)));
  }
  if (cloneSource_) {
    defines.emplace_back("CLONE");
    return defines;
  }
  const stroke::Accumulation accumulation = brush_.render.accumulation;
  if (stroke::isGlaze(accumulation)) {
    if (destinationRead_) {
      defines.emplace_back("GLAZE");
    }
    if (accumulation == stroke::Accumulation::GlazeLight ||
        accumulation == stroke::Accumulation::GlazeUniform) {
      defines.emplace_back("GLAZE_FLOW");
    } else if (accumulation == stroke::Accumulation::GlazeHeavy) {
      defines.emplace_back("GLAZE_HEAVY");
    }
  }
  if (layerSource_ && accumulation == stroke::Accumulation::BlendIntense) {
    defines.emplace_back("LAYER_MIX");
  }
  if (effects_.render.gammaCorrect) {
    defines.emplace_back("GAMMA");
  }
  return defines;
}

bool StrokeRenderer::glazesByCompute() const {
  return !destinationRead_ && !cloneSource_ && stroke::isGlaze(brush_.render.accumulation);
}

const gpu::rhi::Pipeline* StrokeRenderer::glazePipeline(bool halfFloat) {
  std::vector<std::string> defines = dabDefines();
  if (halfFloat) {
    defines.emplace_back("HALF_FLOAT");
  }
  std::string errorMessage;
  const gpu::rhi::Pipeline* result =
      device_.computePipeline({kGlazePipeline, defines}, errorMessage);
  if (result == nullptr) {
    NANQUIM_LOG_ERROR("%s", errorMessage.c_str());
  }
  return result;
}

const gpu::rhi::Texture* StrokeRenderer::glazeScratchTile(bool halfFloat) {
  std::unique_ptr<gpu::rhi::Texture>& scratch = glazeScratch_[halfFloat ? 1 : 0];
  if (!scratch) {
    scratch = createTarget(halfFloat ? gpu::rhi::Format::Rgba16f : gpu::rhi::Format::Rgba8,
                           kTileSizePixels, kTileSizePixels, gpu::rhi::kUsageStorage);
    if (!scratch) {
      NANQUIM_LOG_ERROR("Glaze: no GPU memory for the scratch tile");
    }
  }
  return scratch.get();
}

const gpu::rhi::Texture* StrokeRenderer::textureFor(
    const std::shared_ptr<const stroke::GrayTexture>& source) {
  if (!source) {
    return nullptr;
  }
  ++textureUseCounter_;
  for (CachedTexture& cached : textureCache_) {
    if (cached.source == source) {
      cached.lastUse = textureUseCounter_;
      return cached.texture.get();
    }
  }
  if (textureCache_.size() >= kTextureCacheSize) {
    textureCache_.erase(std::min_element(textureCache_.begin(), textureCache_.end(),
                                         [](const CachedTexture& left, const CachedTexture& right) {
                                           return left.lastUse < right.lastUse;
                                         }));
  }
  gpu::rhi::TextureDescription description;
  description.format = source->hasColor() ? gpu::rhi::Format::Rgba8 : gpu::rhi::Format::R8;
  description.width = source->width();
  description.height = source->height();
  description.levels = source->levelCount();
  description.usage = gpu::rhi::kUsageSampled | gpu::rhi::kUsageUpload;
  std::unique_ptr<gpu::rhi::Texture> texture = device_.createTexture(description);
  if (!texture) {
    return nullptr;
  }
  for (int32_t level = 0; level < source->levelCount(); ++level) {
    device_.uploadTextureLevel(*texture, level, source->level(level).data());
  }
  const gpu::rhi::Texture* result = texture.get();
  textureCache_.push_back(CachedTexture{source, std::move(texture), textureUseCounter_});
  return result;
}

void StrokeRenderer::renderDabs(const std::vector<stroke::Dab>& dabs, int32_t canvasWidth,
                                int32_t canvasHeight, std::vector<TileCoordinate>& touchedTiles) {
  if (mixing()) {
    renderMixingDabs(dabs, canvasWidth, canvasHeight, touchedTiles);
    return;
  }
  const size_t first = touchedTiles.size();
  renderDabsInto(buffer_, false, dabs, canvasWidth, canvasHeight, touchedTiles);
  if (effects_.edges()) {
    updateEdgeMap(std::vector<TileCoordinate>(touchedTiles.begin() + static_cast<ptrdiff_t>(first),
                                              touchedTiles.end()),
                  false);
  }
}

void StrokeRenderer::renderProvisionalDabs(const std::vector<stroke::Dab>& dabs,
                                           int32_t canvasWidth, int32_t canvasHeight,
                                           std::vector<TileCoordinate>& changedTiles) {
  if (mixing()) {
    return;
  }
  const size_t first = changedTiles.size();
  changedTiles.insert(changedTiles.end(), provisional_.tiles().begin(), provisional_.tiles().end());
  provisional_.clear();
  renderDabsInto(provisional_, true, dabs, canvasWidth, canvasHeight, changedTiles);
  provisionalTilesRendered_ += static_cast<int64_t>(provisional_.tiles().size());
  if (effects_.edges()) {
    updateEdgeMap(std::vector<TileCoordinate>(changedTiles.begin() + static_cast<ptrdiff_t>(first),
                                              changedTiles.end()),
                  false);
  }
}

void StrokeRenderer::clearProvisional(std::vector<TileCoordinate>& changedTiles) {
  const std::vector<TileCoordinate> cleared = provisional_.tiles();
  changedTiles.insert(changedTiles.end(), cleared.begin(), cleared.end());
  provisional_.clear();
  updateEdgeMap(cleared, true);
}

void StrokeRenderer::renderDabsInto(StrokeBuffer& target, bool startFromStroke,
                                    const std::vector<stroke::Dab>& dabs, int32_t canvasWidth,
                                    int32_t canvasHeight,
                                    std::vector<TileCoordinate>& touchedTiles) {
  if (dabs.empty()) {
    return;
  }
  const bool glaze = !cloneSource_ && stroke::isGlaze(brush_.render.accumulation);
  const gpu::rhi::Pipeline* dabPipeline = nullptr;
  const gpu::rhi::Pipeline* glazeCompute = nullptr;
  const gpu::rhi::Texture* glazeScratch = nullptr;
  if (glaze && !destinationRead_) {
    glazeCompute = glazePipeline(target.isHalfFloat());
    glazeScratch = glazeScratchTile(target.isHalfFloat());
    if (glazeCompute == nullptr || glazeScratch == nullptr) {
      return;
    }
  } else {
    gpu::rhi::BlendState blend;
    if (!glaze) {
      blend = primitive_ == stroke::Accumulation::Wash ? maxBlend() : overBlend();
    }
    dabPipeline =
        pipeline(kDabPipeline, dabDefines(), blend,
                 target.isHalfFloat() ? gpu::rhi::Format::Rgba16f : gpu::rhi::Format::Rgba8, true);
    if (dabPipeline == nullptr) {
      return;
    }
  }
  const bool textured = shapeTexture_ != nullptr;

  const TileRange canvasTiles = tileRangeForCanvas(canvasWidth, canvasHeight);
  std::vector<TileCoordinate> tileOrder;
  std::unordered_map<TileCoordinate, std::vector<uint32_t>, TileCoordinateHash> dabsByTile;
  for (size_t index = 0; index < dabs.size(); ++index) {
    const stroke::Dab& dab = dabs[index];
    const double extent = stroke::dabBoundingRadius(dab, textured);
    tileRangeCoveringRect(dab.x - extent, dab.y - extent, dab.x + extent, dab.y + extent)
        .intersection(canvasTiles)
        .forEach([&](TileCoordinate coordinate) {
          auto [entry, inserted] = dabsByTile.try_emplace(coordinate);
          if (inserted) {
            tileOrder.push_back(coordinate);
          }
          entry->second.push_back(static_cast<uint32_t>(index));
        });
  }

  struct Batch {
    TileCoordinate coordinate;
    StrokeBuffer::Slot slot;
    size_t firstInstance = 0;
    uint32_t instanceCount = 0;
  };
  std::vector<Batch> batches;
  instanceData_.clear();
  const bool linear = linearDocument();
  gpu::rhi::CommandContext& commands = device_.commands();
  for (const TileCoordinate coordinate : tileOrder) {
    const bool existed = target.find(coordinate).has_value();
    const auto slot = target.acquire(coordinate);
    if (!slot) {
      continue;
    }
    if (startFromStroke && !existed) {
      if (const auto strokeSlot = buffer_.find(coordinate)) {
        commands.copyTexture(buffer_.pageTexture(strokeSlot->page),
                             gpu::rhi::Rect{strokeSlot->origin[0], strokeSlot->origin[1],
                                            kTileSizePixels, kTileSizePixels},
                             target.pageTexture(slot->page), slot->origin[0], slot->origin[1]);
      }
    }
    const std::vector<uint32_t>& indices = dabsByTile.at(coordinate);
    batches.push_back(Batch{coordinate, *slot, instanceData_.size() / kFloatsPerDab,
                            static_cast<uint32_t>(indices.size())});
    for (const uint32_t index : indices) {
      const stroke::Dab dab = inDocumentColor(dabs[index], linear);
      const double strength = dab.opacity * dab.flow;
      const float flips = (dab.flipX ? 1.0F : 0.0F) + (dab.flipY ? 2.0F : 0.0F);
      const float values[kFloatsPerDab] = {static_cast<float>(dab.x),
                                           static_cast<float>(dab.y),
                                           static_cast<float>(dab.diameter / 2),
                                           static_cast<float>(dab.angle),
                                           static_cast<float>(dab.roundness),
                                           static_cast<float>(strength),
                                           flips,
                                           static_cast<float>(dab.bleed),
                                           static_cast<float>(dab.color[0]),
                                           static_cast<float>(dab.color[1]),
                                           static_cast<float>(dab.color[2]),
                                           static_cast<float>(dab.color[3]),
                                           static_cast<float>(dab.grainOffset[0]),
                                           static_cast<float>(dab.grainOffset[1]),
                                           static_cast<float>(dab.grainDepth),
                                           static_cast<float>(dab.grainPeriod),
                                           static_cast<float>(dab.gradation),
                                           static_cast<float>(dab.gradationAngle),
                                           static_cast<float>(dab.opacity),
                                           static_cast<float>(dab.flow)};
      instanceData_.insert(instanceData_.end(), std::begin(values), std::end(values));
    }
    touchedTiles.push_back(coordinate);
  }
  if (batches.empty()) {
    return;
  }
  std::stable_sort(batches.begin(), batches.end(), [](const Batch& first, const Batch& second) {
    return first.slot.page < second.slot.page;
  });

  device_.uploadBuffer(*instanceBuffer_, instanceData_.data(),
                       instanceData_.size() * sizeof(float));
  StrokeDabParameters parameters{};
  parameters.extentScale = textured ? kSquareRootOfTwo : 1.0F;
  if (cloneSource_) {
    parameters.cloneOffset = cloneSource_->offset;
    parameters.cloneSize = cloneSource_->size;
  }
  const bool usesLayer = !cloneSource_ && layerSource_ &&
                         brush_.render.accumulation == stroke::Accumulation::BlendIntense;
  if (usesLayer) {
    parameters.layerSize = layerSource_->size;
    parameters.layerMixAmount = static_cast<float>(stroke::kLayerMixAmount);
  }
  if (glazeCompute != nullptr) {
    StrokeGlazeParameters glazeParameters{};
    glazeParameters.extentScale = parameters.extentScale;
    for (const Batch& batch : batches) {
      const auto [originX, originY] = tileOriginOf(batch.coordinate);
      const gpu::rhi::Texture& page = target.pageTexture(batch.slot.page);
      commands.beginCompute();
      commands.bindComputePipeline(*glazeCompute);
      glazeParameters.tip = bindTip();
      commands.bindTexture(kGlazePageBinding, &page);
      commands.bindStorageImage(kGlazeResultBinding, *glazeScratch,
                                gpu::rhi::StorageAccess::WriteOnly);
      commands.bindStorageBuffer(kGlazeDabsBinding, *instanceBuffer_);
      glazeParameters.slotOrigin = batch.slot.origin;
      glazeParameters.tileOrigin = {static_cast<float>(originX), static_cast<float>(originY)};
      glazeParameters.canvasLimit = {std::min(kTileSizePixels, canvasWidth - originX),
                                     std::min(kTileSizePixels, canvasHeight - originY)};
      glazeParameters.firstDab = static_cast<int32_t>(batch.firstInstance);
      glazeParameters.dabCount = static_cast<int32_t>(batch.instanceCount);
      commands.setUniforms(glazeParameters);
      constexpr uint32_t kGroups = static_cast<uint32_t>(kTileSizePixels) / 16;
      commands.dispatch(kGroups, kGroups, 1);
      commands.barrier(gpu::rhi::kBarrierCopy);
      commands.endCompute();
      commands.copyTexture(*glazeScratch, gpu::rhi::Rect{0, 0, kTileSizePixels, kTileSizePixels},
                           page, batch.slot.origin[0], batch.slot.origin[1]);
    }
    return;
  }
  std::optional<uint32_t> boundPage;
  for (const Batch& batch : batches) {
    if (boundPage != batch.slot.page) {
      if (boundPage) {
        commands.endPass();
      }
      commands.beginPass(gpu::rhi::RenderTarget::of(target.pageTexture(batch.slot.page)),
                         gpu::rhi::LoadAction::Load);
      commands.bindPipeline(*dabPipeline);
      parameters.tip = bindTip();
      if (cloneSource_) {
        commands.bindTexture(kCloneBinding, cloneSource_->texture);
      }
      if (usesLayer) {
        commands.bindTexture(kLayerBinding, layerSource_->texture);
      }
      boundPage = batch.slot.page;
    }
    const auto [originX, originY] = tileOriginOf(batch.coordinate);
    commands.setViewport(gpu::rhi::Rect{batch.slot.origin[0], batch.slot.origin[1], kTileSizePixels,
                                        kTileSizePixels});
    commands.setScissor(gpu::rhi::Rect{batch.slot.origin[0], batch.slot.origin[1],
                                       std::min(kTileSizePixels, canvasWidth - originX),
                                       std::min(kTileSizePixels, canvasHeight - originY)});
    parameters.tileOrigin = {static_cast<float>(originX), static_cast<float>(originY)};
    commands.setUniforms(parameters);
    commands.bindVertexBuffer(*instanceBuffer_, batch.firstInstance * kInstanceStride);
    commands.draw(4, batch.instanceCount);
  }
  commands.endPass();
}

StrokeRenderer::CommitResult StrokeRenderer::commit(
    const CommitOptions& options, std::vector<LayerTileKey>& changedTiles,
    const std::function<void(const LayerTileKey&)>& beforeWrite) {
  const LayerId surface = options.surface;
  bool allResident = true;
  for (const TileCoordinate coordinate : buffer_.tiles()) {
    if (tileStore_.ensureResident(LayerTileKey{surface, coordinate}) ==
        LayerTileStore::Availability::Loading) {
      allResident = false;
    }
    if (options.selection &&
        tileStore_.ensureResident(LayerTileKey{*options.selection, coordinate}) ==
            LayerTileStore::Availability::Loading) {
      allResident = false;
    }
  }
  if (!allResident) {
    return CommitResult::Waiting;
  }
  if (options.erase && options.alphaLock && !options.maskTarget) {
    buffer_.clear();
    return CommitResult::Done;
  }
  updateEdgeMap(buffer_.tiles(), true);
  const bool inPlace =
      !options.selection && !effects_.any() && !mixing() &&
      (options.maskTarget || options.erase ||
       (!options.alphaLock &&
        (options.blend == BlendMode::Normal || options.blend == BlendMode::Behind)));
  std::vector<std::string> defines;
  if (options.maskTarget) {
    defines.emplace_back(kMaskTargetDefine);
    if (options.selectionTarget) {
      defines.emplace_back(kSelectionTargetDefine);
    }
  } else if (options.erase) {
    defines.emplace_back(kEraseDefine);
  }
  if (options.selection) {
    defines.emplace_back(kSelectionDefine);
  }
  if (mixing()) {
    defines = workingDefines();
    defines.emplace_back("WORKING");
    if (options.selection) {
      defines.emplace_back(kSelectionDefine);
    }
  } else if (!inPlace) {
    appendStrokeEffectDefines(effects_, !options.erase && !options.maskTarget, defines);
  }
  const gpu::rhi::Format tileFormat = textureFormat(tileStore_.pixelFormat());
  using gpu::rhi::BlendFactor;
  gpu::rhi::BlendState existingBlend = overBlend();
  if (options.erase && !options.maskTarget) {
    existingBlend = blendFunction(BlendFactor::Zero, BlendFactor::OneMinusSourceAlpha);
  } else if (options.blend == BlendMode::Behind && !options.maskTarget) {
    existingBlend = blendFunction(BlendFactor::OneMinusDestinationAlpha, BlendFactor::One);
  }
  const gpu::rhi::Pipeline* existingPipeline = nullptr;
  const gpu::rhi::Pipeline* createdPipeline = nullptr;
  if (inPlace) {
    existingPipeline = pipeline(kCommitPipeline, defines, existingBlend, tileFormat);
    createdPipeline =
        pipeline(kCommitPipeline, defines,
                 options.maskTarget ? overBlend() : gpu::rhi::BlendState{}, tileFormat);
  } else {
    existingPipeline = createdPipeline = pipeline(kActiveStrokePipeline, defines, {}, tileFormat);
  }
  if (existingPipeline == nullptr || createdPipeline == nullptr) {
    buffer_.clear();
    return CommitResult::Done;
  }
  struct Write {
    LayerTileKey key;
    LayerTileStore::UpdateSlot target;
    StrokeBuffer::Slot stroke;
  };
  std::vector<Write> writes;
  for (const TileCoordinate coordinate : buffer_.tiles()) {
    const LayerTileKey key{surface, coordinate};
    if ((options.erase || options.alphaLock) && !options.maskTarget && !tileStore_.hasTile(key)) {
      continue;
    }
    if (options.selectionTarget && options.erase && !tileStore_.hasTile(key)) {
      continue;
    }
    if (options.selection && !tileStore_.hasTile(LayerTileKey{*options.selection, coordinate})) {
      continue;
    }
    if (mixing() && workingMissing_.count(coordinate) != 0) {
      continue;
    }
    if (beforeWrite) {
      beforeWrite(key);
    }
    const auto target = tileStore_.slotForUpdate(key);
    const auto strokeSlot = buffer_.find(coordinate);
    if (!target || !strokeSlot) {
      NANQUIM_LOG_ERROR("Stroke commit: no slot for tile (%d, %d)", coordinate.column,
                        coordinate.row);
      continue;
    }
    writes.push_back(Write{key, *target, *strokeSlot});
  }
  std::stable_sort(writes.begin(), writes.end(), [](const Write& left, const Write& right) {
    return left.target.slot.page < right.target.slot.page;
  });
  if (!inPlace && (!commitScratch_ || commitScratchFormat_ != tileStore_.pixelFormat())) {
    commitScratchFormat_ = tileStore_.pixelFormat();
    commitScratch_ = createTarget(tileFormat, kTileSizePixels, kTileSizePixels);
    if (!commitScratch_) {
      NANQUIM_LOG_ERROR("Stroke commit: no GPU memory for the scratch tile");
      buffer_.clear();
      return CommitResult::Done;
    }
  }
  gpu::rhi::CommandContext& commands = device_.commands();
  std::optional<uint32_t> boundPage;
  for (const Write& write : writes) {
    const int32_t targetX = slotOriginX(write.target.slot);
    const int32_t targetY = slotOriginY(write.target.slot);
    if (!inPlace) {
      commands.beginPass(gpu::rhi::RenderTarget::of(*commitScratch_), gpu::rhi::LoadAction::Load);
      commands.bindPipeline(*createdPipeline);
      ActiveStrokeParameters parameters{};
      parameters.targetOrigin = {0, 0};
      parameters.strokeOpacity = options.opacity;
      parameters.layerOpacity = 1;
      parameters.brushBlendMode = static_cast<int32_t>(options.blend);
      parameters.alphaLock = options.alphaLock ? 1 : 0;
      parameters.maskLuminance = options.maskLuminance;
      commands.bindTexture(
          kActiveSurfaceBinding,
          write.target.created ? nullptr : &tileStore_.pageTexture(write.target.slot.page));
      commands.bindTexture(kActiveStrokeBinding, &buffer_.pageTexture(write.stroke.page));
      parameters.hasSurfaceTile = write.target.created ? 0 : 1;
      parameters.surfaceSlotOrigin = {targetX, targetY};
      parameters.strokeSlotOrigin = write.stroke.origin;
      bindStrokeEffects(commands, effects_, write.key.coordinate, parameters);
      if (mixing()) {
        commands.bindTexture(kActiveWorkingCopyBinding, workingCopy_.get());
        parameters.workingOrigin = tileOriginOf(write.key.coordinate);
      }
      if (options.selection) {
        const auto selectionSlot =
            tileStore_.slotForRead(LayerTileKey{*options.selection, write.key.coordinate});
        commands.bindTexture(
            kActiveSelectionBinding,
            selectionSlot ? &tileStore_.pageTexture(selectionSlot->page) : nullptr);
        parameters.hasSelectionTile = selectionSlot ? 1 : 0;
        parameters.selectionSlotOrigin =
            selectionSlot ? Int2{slotOriginX(*selectionSlot), slotOriginY(*selectionSlot)}
                          : Int2{0, 0};
      }
      commands.setUniforms(parameters);
      commands.draw(3);
      commands.endPass();
      commands.copyTexture(*commitScratch_, gpu::rhi::Rect{0, 0, kTileSizePixels, kTileSizePixels},
                           tileStore_.pageTexture(write.target.slot.page), targetX, targetY);
      changedTiles.push_back(write.key);
      continue;
    }
    if (boundPage != write.target.slot.page) {
      if (boundPage) {
        commands.endPass();
      }
      commands.beginPass(gpu::rhi::RenderTarget::of(tileStore_.pageTexture(write.target.slot.page)),
                         gpu::rhi::LoadAction::Load);
      boundPage = write.target.slot.page;
    }
    const gpu::rhi::Rect slotRect{targetX, targetY, kTileSizePixels, kTileSizePixels};
    commands.setViewport(slotRect);
    commands.setScissor(slotRect);
    if (write.target.created && options.maskTarget) {
      const float base = options.selectionTarget ? 0.0F : 1.0F;
      commands.clear({base, base, base, 1}, slotRect);
    }
    commands.bindPipeline(write.target.created ? *createdPipeline : *existingPipeline);
    commands.bindTexture(kSourceBinding, &buffer_.pageTexture(write.stroke.page));
    commands.setUniforms(StrokeCommitParameters{
        write.stroke.origin, {targetX, targetY}, options.opacity, options.maskLuminance, {0, 0}});
    commands.draw(3);
    changedTiles.push_back(write.key);
  }
  if (boundPage) {
    commands.endPass();
  }
  buffer_.clear();
  return CommitResult::Done;
}

namespace {

float reducedAlphaAt(const uint8_t* pixels, bool halfFloat, size_t pixel) {
  if (!halfFloat) {
    return pixels[pixel * 4];
  }
  uint16_t half = 0;
  std::memcpy(&half, pixels + pixel * 8, sizeof(half));
  return core::halfToFloat(half);
}

}  // namespace

void StrokeRenderer::checkForEmptyTiles(const std::vector<LayerTileKey>& tiles) {
  emptinessQueue_.insert(emptinessQueue_.end(), tiles.begin(), tiles.end());
  pollsBeforeReduction_ = kPollsBeforeReduction;
}

void StrokeRenderer::startReduction() {
  const gpu::rhi::Format format = textureFormat(tileStore_.pixelFormat());
  const gpu::rhi::Pipeline* program = pipeline(kReducePipeline, {}, {}, format);
  if (program == nullptr) {
    emptinessQueue_.clear();
    return;
  }
  if (!reductionTexture_ || reductionFormat_ != tileStore_.pixelFormat()) {
    reductionFormat_ = tileStore_.pixelFormat();
    reductionTexture_ = createTarget(format, kReductionTargetSize, kReductionTargetSize);
    intermediateTexture_ = createTarget(format, kIntermediateTargetSize, kIntermediateTargetSize);
    if (!reductionTexture_ || !intermediateTexture_) {
      reductionTexture_.reset();
      intermediateTexture_.reset();
      return;
    }
  }
  struct Source {
    const gpu::rhi::Texture* page = nullptr;
    std::array<int32_t, 2> origin{};
  };
  PendingReduction reduction;
  std::vector<Source> sources;
  while (!emptinessQueue_.empty() && reduction.tiles.size() < kReductionTilesPerBatch) {
    const LayerTileKey key = emptinessQueue_.front();
    emptinessQueue_.pop_front();
    const auto version = tileStore_.version(key);
    if (!version || tileStore_.ensureResident(key) != LayerTileStore::Availability::Resident) {
      continue;
    }
    const auto slot = tileStore_.slotForRead(key);
    if (!slot) {
      continue;
    }
    sources.push_back(
        Source{&tileStore_.pageTexture(slot->page), {slotOriginX(*slot), slotOriginY(*slot)}});
    reduction.tiles.emplace_back(key, *version);
  }
  if (reduction.tiles.empty()) {
    return;
  }

  auto targetOrigin = [](size_t position, int32_t block) {
    return std::array<int32_t, 2>{static_cast<int32_t>(position % kReductionTilesPerRow) * block,
                                  static_cast<int32_t>(position / kReductionTilesPerRow) * block};
  };
  gpu::rhi::CommandContext& commands = device_.commands();
  commands.beginPass(gpu::rhi::RenderTarget::of(*intermediateTexture_), gpu::rhi::LoadAction::Load);
  commands.bindPipeline(*program);
  for (size_t position = 0; position < sources.size(); ++position) {
    const auto target = targetOrigin(position, kIntermediateBlock);
    commands.setScissor(
        gpu::rhi::Rect{target[0], target[1], kIntermediateBlock, kIntermediateBlock});
    commands.bindTexture(kSourceBinding, sources[position].page);
    commands.setUniforms(TileAlphaReduceParameters{sources[position].origin, target, 1, {0, 0, 0}});
    commands.draw(3);
  }
  commands.endPass();
  commands.beginPass(gpu::rhi::RenderTarget::of(*reductionTexture_), gpu::rhi::LoadAction::Load);
  commands.bindPipeline(*program);
  commands.bindTexture(kSourceBinding, intermediateTexture_.get());
  for (size_t position = 0; position < sources.size(); ++position) {
    const auto source = targetOrigin(position, kIntermediateBlock);
    const auto target = targetOrigin(position, kReductionBlock);
    commands.setScissor(gpu::rhi::Rect{target[0], target[1], kReductionBlock, kReductionBlock});
    commands.setUniforms(TileAlphaReduceParameters{source, target, 0, {0, 0, 0}});
    commands.draw(3);
  }
  commands.endPass();
  reduction.rows = static_cast<int32_t>((reduction.tiles.size() + kReductionTilesPerRow - 1) /
                                        kReductionTilesPerRow) *
                   kReductionBlock;
  reduction.halfFloat = format == gpu::rhi::Format::Rgba16f;
  reduction.readback = commands.readTextureAsync(
      *reductionTexture_, gpu::rhi::Rect{0, 0, kReductionTargetSize, reduction.rows});
  if (reduction.readback) {
    pendingReductions_.push_back(std::move(reduction));
  }
}

int32_t StrokeRenderer::poll() {
  int32_t freed = 0;
  for (auto iterator = pendingReductions_.begin(); iterator != pendingReductions_.end();) {
    if (!iterator->readback->isReady()) {
      ++iterator;
      continue;
    }
    std::vector<uint8_t> pixels(iterator->readback->size());
    if (iterator->readback->read(pixels.data())) {
      for (size_t position = 0; position < iterator->tiles.size(); ++position) {
        const auto blockX =
            static_cast<int32_t>(position % kReductionTilesPerRow) * kReductionBlock;
        const auto blockY =
            static_cast<int32_t>(position / kReductionTilesPerRow) * kReductionBlock;
        bool empty = true;
        for (int32_t y = blockY; y < blockY + kReductionBlock && empty; ++y) {
          for (int32_t x = blockX; x < blockX + kReductionBlock; ++x) {
            if (reducedAlphaAt(pixels.data(), iterator->halfFloat,
                               static_cast<size_t>(y) * kReductionTargetSize + x) != 0) {
              empty = false;
              break;
            }
          }
        }
        const auto& [key, version] = iterator->tiles[position];
        if (empty && tileStore_.removeTileIfUnchanged(key, version)) {
          ++freed;
        }
      }
    }
    iterator = pendingReductions_.erase(iterator);
  }
  if (pollsBeforeReduction_ > 0) {
    --pollsBeforeReduction_;
  } else if (!emptinessQueue_.empty()) {
    startReduction();
  }
  return freed;
}

void StrokeRenderer::releaseMemory() {
  textureCache_.clear();
  buffer_.release();
  provisional_.release();
  dualBuffer_.release();
  particleBuffer_.release();
  reductionTexture_.reset();
  intermediateTexture_.reset();
  commitScratch_.reset();
  pendingReductions_.clear();
  emptinessQueue_.clear();
}

}  // namespace nanquim::canvas
