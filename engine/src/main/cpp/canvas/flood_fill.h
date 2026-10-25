#pragma once

#include <cstdint>
#include <vector>

#include "canvas/polygon_coverage.h"

namespace nanquim::canvas {

struct FloodFillOptions {
  double threshold = 0.1;
  bool expand = true;
};

inline constexpr double kFloodFillSoftness = 0.08;

std::vector<uint8_t> floodFillCoverage(const uint8_t* pixels, int32_t width, int32_t height,
                                       int32_t seedX, int32_t seedY,
                                       const FloodFillOptions& options);

TileCoverageMap coverageTiles(const std::vector<uint8_t>& coverage, int32_t width, int32_t height);

}  // namespace nanquim::canvas
