#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

#include "canvas/tile_coordinate.h"

namespace nanquim::canvas {

inline int32_t pyramidLevelCount(int32_t widthPixels, int32_t heightPixels) {
  int32_t levelCount = 1;
  int32_t width = widthPixels;
  int32_t height = heightPixels;
  while (width > kTileSizePixels || height > kTileSizePixels) {
    width = (width + 1) / 2;
    height = (height + 1) / 2;
    ++levelCount;
  }
  return levelCount;
}

inline int32_t levelDimension(int32_t levelZeroPixels, int32_t level) {
  int32_t size = levelZeroPixels;
  for (int32_t index = 0; index < level; ++index) {
    size = (size + 1) / 2;
  }
  return std::max(size, 1);
}

struct LevelChoice {
  int32_t fineLevel = 0;
  int32_t coarseLevel = 0;
  float coarseWeight = 0;
  bool nearest = false;
};

inline LevelChoice chooseLevels(double zoom, int32_t levelCount, bool showPixels) {
  constexpr double kShowPixelsMinimumZoom = 4.0;
  if (showPixels && zoom >= kShowPixelsMinimumZoom) {
    return LevelChoice{0, 0, 0, true};
  }
  if (zoom >= 1.0 || levelCount <= 1) {
    return LevelChoice{0, 0, 0, false};
  }
  const double levelOfDetail =
      std::clamp(std::log2(1.0 / zoom), 0.0, static_cast<double>(levelCount - 1));
  const auto fine = static_cast<int32_t>(std::floor(levelOfDetail));
  const int32_t coarse = std::min(fine + 1, levelCount - 1);
  const auto weight = coarse == fine ? 0.0F : static_cast<float>(levelOfDetail - fine);
  return LevelChoice{fine, coarse, weight, false};
}

}  // namespace nanquim::canvas
