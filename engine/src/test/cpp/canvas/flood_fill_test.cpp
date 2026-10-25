#include "canvas/flood_fill.h"

#include <gtest/gtest.h>

#include <chrono>
#include <cstdio>

namespace nanquim::canvas {
namespace {

struct Picture {
  int32_t width;
  int32_t height;
  std::vector<uint8_t> pixels;

  Picture(int32_t w, int32_t h) : width(w), height(h), pixels(static_cast<size_t>(w) * h * 4, 0) {}

  void set(int32_t x, int32_t y, std::array<uint8_t, 4> color) {
    std::copy(color.begin(), color.end(),
              pixels.begin() + (static_cast<ptrdiff_t>(y) * width + x) * 4);
  }
};

TEST(FloodFill, FillsTheEnclosedRegionAndTheRing) {
  Picture picture(20, 20);
  for (int32_t index = 2; index < 18; ++index) {
    picture.set(index, 2, {0, 0, 0, 255});
    picture.set(index, 17, {0, 0, 0, 255});
    picture.set(2, index, {0, 0, 0, 255});
    picture.set(17, index, {0, 0, 0, 255});
  }
  FloodFillOptions options;
  options.threshold = 0.1;
  options.expand = true;
  const auto inside = floodFillCoverage(picture.pixels.data(), 20, 20, 10, 10, options);
  EXPECT_EQ(inside[10 * 20 + 10], 255);
  EXPECT_EQ(inside[3 * 20 + 3], 255);
  EXPECT_EQ(inside[2 * 20 + 10], 255);
  EXPECT_EQ(inside[1 * 20 + 10], 0);
  options.expand = false;
  const auto noExpansion = floodFillCoverage(picture.pixels.data(), 20, 20, 10, 10, options);
  EXPECT_EQ(noExpansion[2 * 20 + 10], 0);
  const auto outside = floodFillCoverage(picture.pixels.data(), 20, 20, 0, 0, options);
  EXPECT_EQ(outside[19 * 20 + 19], 255);
  EXPECT_EQ(outside[10 * 20 + 10], 0);
}

TEST(FloodFill, ThresholdAndSoftEdge) {
  Picture picture(10, 1);
  for (int32_t x = 0; x < 10; ++x) {
    const auto value = static_cast<uint8_t>(x * 20);
    picture.set(x, 0, {value, value, value, 255});
  }
  FloodFillOptions options;
  options.expand = false;
  options.threshold = 0.15;
  const auto coverage = floodFillCoverage(picture.pixels.data(), 10, 1, 0, 0, options);
  EXPECT_EQ(coverage[2], 255);
  EXPECT_GT(coverage[3], 0);
  EXPECT_LT(coverage[3], 255);
  EXPECT_EQ(coverage[4], 0);
  options.threshold = 0;
  const auto exact = floodFillCoverage(picture.pixels.data(), 10, 1, 0, 0, options);
  EXPECT_EQ(exact[0], 255);
  EXPECT_LT(exact[1], 64);
  EXPECT_EQ(exact[2], 0);
  EXPECT_EQ(floodFillCoverage(picture.pixels.data(), 10, 1, -1, 0, options)[0], 0);
}

TEST(FloodFill, CoverageTilesSkipEmptyOnes) {
  std::vector<uint8_t> coverage(600 * 300, 0);
  coverage[299 * 600 + 599] = 7;
  const auto tiles = coverageTiles(coverage, 600, 300);
  ASSERT_EQ(tiles.size(), 1U);
  const auto& tile = tiles.at({2, 1});
  EXPECT_EQ(tile[43 * kTileSizePixels + 87], 7);
}

TEST(FloodFill, LargeCanvasTiming) {
  const int32_t side = 4096;
  Picture picture(side, side);
  for (int32_t x = 0; x < side; ++x) {
    picture.set(x, side / 2, {0, 0, 0, 255});
  }
  FloodFillOptions options;
  const auto start = std::chrono::steady_clock::now();
  const auto coverage = floodFillCoverage(picture.pixels.data(), side, side, 5, 5, options);
  const auto tiles = coverageTiles(coverage, side, side);
  const double milliseconds =
      std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
  std::printf("flood fill 4096²: %.1f ms, %zu tiles\n", milliseconds, tiles.size());
  EXPECT_EQ(coverage[(side - 1) * static_cast<size_t>(side)], 0);
  EXPECT_EQ(coverage[(side / 2) * static_cast<size_t>(side)], 255);
}

}  // namespace
}  // namespace nanquim::canvas
