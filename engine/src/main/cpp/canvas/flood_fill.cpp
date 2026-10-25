#include "canvas/flood_fill.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace nanquim::canvas {

namespace {

constexpr uint8_t kInside = 255;
constexpr uint8_t kRejected = 1;

inline int32_t squaredDistance(const uint8_t* pixel, const uint8_t* seed) {
  int32_t sum = 0;
  for (size_t channel = 0; channel < 4; ++channel) {
    const int32_t difference = static_cast<int32_t>(pixel[channel]) - seed[channel];
    sum += difference * difference;
  }
  return sum;
}

}  // namespace

std::vector<uint8_t> floodFillCoverage(const uint8_t* pixels, int32_t width, int32_t height,
                                       int32_t seedX, int32_t seedY,
                                       const FloodFillOptions& options) {
  const size_t count = static_cast<size_t>(width) * static_cast<size_t>(height);
  std::vector<uint8_t> coverage(count, 0);
  if (seedX < 0 || seedY < 0 || seedX >= width || seedY >= height) {
    return coverage;
  }
  const double threshold = std::clamp(options.threshold, 0.0, 1.0);
  const double limit = 2.0 * 255.0 * threshold;
  const auto limitSquared = static_cast<int32_t>(std::floor(limit * limit + 1e-9));
  uint8_t seed[4];
  std::copy_n(pixels + (static_cast<size_t>(seedY) * width + seedX) * 4, 4, seed);
  uint32_t seedWord = 0;
  std::memcpy(&seedWord, seed, 4);
  int32_t minimumX = seedX;
  int32_t maximumX = seedX;
  int32_t minimumY = seedY;
  int32_t maximumY = seedY;
  auto inside = [&](int32_t x, int32_t y) {
    const size_t index = static_cast<size_t>(y) * width + x;
    if (coverage[index] != 0) {
      return false;
    }
    uint32_t word = 0;
    std::memcpy(&word, pixels + index * 4, 4);
    if (word != seedWord && squaredDistance(pixels + index * 4, seed) > limitSquared) {
      coverage[index] = kRejected;
      return false;
    }
    return true;
  };
  std::vector<std::pair<int32_t, int32_t>> stack{{seedX, seedY}};
  while (!stack.empty()) {
    const auto [startX, y] = stack.back();
    stack.pop_back();
    if (!inside(startX, y)) {
      continue;
    }
    int32_t left = startX;
    while (left > 0 && inside(left - 1, y)) {
      --left;
    }
    int32_t right = startX;
    while (right + 1 < width && inside(right + 1, y)) {
      ++right;
    }
    uint8_t* row = coverage.data() + static_cast<size_t>(y) * width;
    std::fill(row + left, row + right + 1, kInside);
    minimumX = std::min(minimumX, left);
    maximumX = std::max(maximumX, right);
    minimumY = std::min(minimumY, y);
    maximumY = std::max(maximumY, y);
    for (const int32_t nextY : {y - 1, y + 1}) {
      if (nextY < 0 || nextY >= height) {
        continue;
      }
      bool inRun = false;
      for (int32_t x = left; x <= right; ++x) {
        const bool candidate = inside(x, nextY);
        if (candidate && !inRun) {
          stack.emplace_back(x, nextY);
        }
        inRun = candidate;
      }
    }
  }
  std::vector<std::pair<size_t, uint8_t>> ring;
  for (int32_t y = std::max(minimumY - 1, 0); y <= std::min(maximumY + 1, height - 1); ++y) {
    for (int32_t x = std::max(minimumX - 1, 0); x <= std::min(maximumX + 1, width - 1); ++x) {
      const size_t index = static_cast<size_t>(y) * width + x;
      if (coverage[index] == kInside) {
        continue;
      }
      coverage[index] = 0;
      const bool touches = (x > 0 && coverage[index - 1] == kInside) ||
                           (x + 1 < width && coverage[index + 1] == kInside) ||
                           (y > 0 && coverage[index - width] == kInside) ||
                           (y + 1 < height && coverage[index + width] == kInside);
      if (!touches) {
        continue;
      }
      double value = 1.0;
      if (!options.expand) {
        const double distance =
            std::sqrt(static_cast<double>(squaredDistance(pixels + index * 4, seed))) /
            (2.0 * 255.0);
        value = std::clamp(1.0 - (distance - threshold) / kFloodFillSoftness, 0.0, 1.0);
      }
      ring.emplace_back(index, static_cast<uint8_t>(std::lround(value * 255.0)));
    }
  }
  for (const auto& [index, value] : ring) {
    coverage[index] = value;
  }
  return coverage;
}

TileCoverageMap coverageTiles(const std::vector<uint8_t>& coverage, int32_t width, int32_t height) {
  TileCoverageMap tiles;
  const int32_t columns = (width + kTileSizePixels - 1) / kTileSizePixels;
  const int32_t rows = (height + kTileSizePixels - 1) / kTileSizePixels;
  for (int32_t tileRow = 0; tileRow < rows; ++tileRow) {
    for (int32_t tileColumn = 0; tileColumn < columns; ++tileColumn) {
      const int32_t left = tileColumn * kTileSizePixels;
      const int32_t top = tileRow * kTileSizePixels;
      const int32_t tileWidth = std::min(kTileSizePixels, width - left);
      const int32_t tileHeight = std::min(kTileSizePixels, height - top);
      bool any = false;
      for (int32_t y = 0; y < tileHeight && !any; ++y) {
        const uint8_t* row = coverage.data() + static_cast<size_t>(top + y) * width + left;
        any = std::any_of(row, row + tileWidth, [](uint8_t value) { return value != 0; });
      }
      if (!any) {
        continue;
      }
      TileCoverage tile(static_cast<size_t>(kTileSizePixels) * kTileSizePixels, 0);
      for (int32_t y = 0; y < tileHeight; ++y) {
        std::copy_n(coverage.data() + static_cast<size_t>(top + y) * width + left, tileWidth,
                    tile.data() + static_cast<size_t>(y) * kTileSizePixels);
      }
      tiles.emplace(TileCoordinate{tileColumn, tileRow}, std::move(tile));
    }
  }
  return tiles;
}

}  // namespace nanquim::canvas
