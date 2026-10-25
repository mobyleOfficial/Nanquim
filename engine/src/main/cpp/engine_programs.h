#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

#include "adjust/adjustment.h"
#include "adjust/spatial.h"

namespace nanquim {

struct TestPatternParameters {
  std::array<int32_t, 2> slotOrigin;
  std::array<int32_t, 2> tileCanvasOrigin;
  std::array<float, 3> baseColor;
  float seed;
};
static_assert(offsetof(TestPatternParameters, baseColor) == 16);
static_assert(sizeof(TestPatternParameters) == 32);

struct FillRectParameters {
  std::array<int32_t, 2> slotOrigin;
  std::array<int32_t, 2> tileCanvasOrigin;
  std::array<float, 4> rect;
  std::array<float, 4> color;
  std::array<float, 4> toColor;
  int32_t gradient;
  int32_t vertical;
  int32_t maskTarget;
  int32_t linearDocument;
};
static_assert(offsetof(FillRectParameters, rect) == 16);
static_assert(offsetof(FillRectParameters, gradient) == 64);
static_assert(sizeof(FillRectParameters) == 80);

struct InvertTileParameters {
  std::array<int32_t, 2> slotOrigin;
  int32_t hasTile;
  int32_t mode;
  int32_t linearDocument;
  std::array<int32_t, 3> padding;
};
static_assert(sizeof(InvertTileParameters) == 32);
inline constexpr uint32_t kInvertTileBinding = 1;

struct SelectionCombineParameters {
  std::array<int32_t, 2> slotOrigin;
  int32_t hasTile;
  int32_t mode;
};
static_assert(sizeof(SelectionCombineParameters) == 16);

inline constexpr size_t kSelectionBlurWeights = 101;
struct SelectionBlurParameters {
  std::array<std::array<int32_t, 4>, 3> slotOrigins;
  std::array<std::array<int32_t, 4>, 3> hasTiles;
  int32_t horizontal;
  int32_t padding0;
  std::array<int32_t, 2> tileOrigin;
  std::array<int32_t, 2> canvasSize;
  int32_t halfWidth;
  int32_t padding1;
  std::array<std::array<float, 4>, kSelectionBlurWeights> weights;
};
static_assert(offsetof(SelectionBlurParameters, hasTiles) == 48);
static_assert(offsetof(SelectionBlurParameters, horizontal) == 96);
static_assert(offsetof(SelectionBlurParameters, tileOrigin) == 104);
static_assert(offsetof(SelectionBlurParameters, halfWidth) == 120);
static_assert(offsetof(SelectionBlurParameters, weights) == 128);
static_assert(sizeof(SelectionBlurParameters) == 1744);
inline constexpr uint32_t kSelectionBlurFirstBinding = 1;

struct CoverageFillParameters {
  std::array<int32_t, 2> slotOrigin;
  int32_t hasTile;
  int32_t hasSelection;
  std::array<int32_t, 2> selectionSlotOrigin;
  std::array<int32_t, 2> padding0;
  std::array<float, 4> color;
  int32_t maskTarget;
  int32_t operation;
  int32_t linearDocument;
  int32_t padding1;
};
static_assert(offsetof(CoverageFillParameters, selectionSlotOrigin) == 16);
static_assert(offsetof(CoverageFillParameters, color) == 32);
static_assert(offsetof(CoverageFillParameters, maskTarget) == 48);
static_assert(sizeof(CoverageFillParameters) == 64);
inline constexpr uint32_t kTilePageBinding = 1;
inline constexpr uint32_t kCoverageBinding = 2;
inline constexpr uint32_t kCoverageSelectionBinding = 3;

struct MaskedCopyParameters {
  std::array<int32_t, 2> sourceSlotOrigin;
  std::array<int32_t, 2> targetOrigin;
  std::array<int32_t, 2> selectionSlotOrigin;
  int32_t selectionMode;
  int32_t padding;
};
static_assert(sizeof(MaskedCopyParameters) == 32);
inline constexpr uint32_t kMaskedCopySourceBinding = 1;
inline constexpr uint32_t kMaskedCopySelectionBinding = 2;

struct TransformMeshParameters {
  std::array<float, 2> tileOrigin;
  std::array<float, 2> floatingOrigin;
  std::array<float, 2> floatingSize;
  int32_t filterMode;
  int32_t padding;
};
static_assert(sizeof(TransformMeshParameters) == 32);
inline constexpr uint32_t kTransformFloatingBinding = 1;

struct AdjustTileParameters {
  std::array<int32_t, 2> fieldSize;
  float fieldScale;
  int32_t liquifyCubic;
  std::array<int32_t, 2> sourceSlotOrigin;
  std::array<int32_t, 2> selectionSlotOrigin;
  std::array<int32_t, 2> maskSlotOrigin;
  std::array<int32_t, 2> strokeSlotOrigin;
  int32_t maskMode;
  int32_t hasStroke;
  float strokeOpacity;
  float strokeLuminance;
  std::array<int32_t, 2> targetOrigin;
  int32_t sampleStride;
  int32_t hasSelection;
  float amount;
  int32_t filterKind;
  std::array<int32_t, 2> padding0;
  std::array<std::array<float, 4>, adjust::kParameterCount> parameters;
  std::array<int32_t, 2> canvasSize;
  int32_t hasSource;
  int32_t encodeSource;
  int32_t decodeResult;
  int32_t spatialMode;
  float sharpenStrength;
  int32_t padding1;
  std::array<int32_t, 2> canvasOrigin;
  int32_t canvasStride;
  float filteredScale;
  float filteredOffset;
  int32_t padding2;
  std::array<int32_t, 2> filteredRectOrigin;
  std::array<int32_t, 2> filteredRectSize;
  std::array<int32_t, 2> filteredLevelSize;
};
static_assert(offsetof(AdjustTileParameters, maskMode) == 48);
static_assert(offsetof(AdjustTileParameters, targetOrigin) == 64);
static_assert(offsetof(AdjustTileParameters, parameters) == 96);
static_assert(offsetof(AdjustTileParameters, canvasSize) == 352);
static_assert(offsetof(AdjustTileParameters, canvasOrigin) == 384);
static_assert(offsetof(AdjustTileParameters, filteredOffset) == 400);
static_assert(offsetof(AdjustTileParameters, filteredRectOrigin) == 408);
static_assert(sizeof(AdjustTileParameters) == 432);
inline constexpr uint32_t kAdjustSourceBinding = 1;
inline constexpr uint32_t kAdjustSelectionBinding = 2;
inline constexpr uint32_t kAdjustTableBinding = 3;
inline constexpr uint32_t kAdjustMaskBinding = 4;
inline constexpr uint32_t kAdjustStrokeBinding = 5;
inline constexpr uint32_t kAdjustFilteredBinding = 6;
inline constexpr uint32_t kAdjustFieldBinding = 7;

struct AdjustPassParameters {
  int32_t passKind;
  int32_t padding0;
  std::array<int32_t, 2> outputOrigin;
  std::array<int32_t, 2> rectOrigin;
  std::array<int32_t, 2> rectSize;
  std::array<int32_t, 2> levelSize;
  std::array<int32_t, 2> inputTexelOrigin;
  std::array<int32_t, 2> inputBase;
  std::array<int32_t, 2> inputLimit;
  int32_t boxFactor;
  int32_t encodeInput;
  std::array<int32_t, 2> padding1;
  std::array<std::array<float, 4>, adjust::kMaximumGaussianRadius + 1> weights;
  int32_t radius;
  int32_t taps;
  std::array<float, 2> tapStep;
  std::array<float, 2> center;
  float spacing;
  int32_t padding2;
  std::array<float, 4> values;
  int32_t variant;
  float mappingOffset;
  float mappingScale;
  int32_t exactReads;
};
static_assert(offsetof(AdjustPassParameters, inputLimit) == 56);
static_assert(offsetof(AdjustPassParameters, weights) == 80);
static_assert(offsetof(AdjustPassParameters, radius) == 224);
static_assert(offsetof(AdjustPassParameters, center) == 240);
static_assert(offsetof(AdjustPassParameters, values) == 256);
static_assert(sizeof(AdjustPassParameters) == 288);
inline constexpr uint32_t kAdjustPassInputBinding = 1;

}  // namespace nanquim
