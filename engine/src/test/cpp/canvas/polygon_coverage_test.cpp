#include "canvas/polygon_coverage.h"

#include <gtest/gtest.h>

#include <cmath>
#include <numeric>

namespace nanquim::canvas {
namespace {

double sampledCoverage(const std::vector<PolygonPoint>& polygon, int32_t pixelX, int32_t pixelY,
                       int32_t samples) {
  int32_t inside = 0;
  for (int32_t sy = 0; sy < samples; ++sy) {
    for (int32_t sx = 0; sx < samples; ++sx) {
      const double x = pixelX + (sx + 0.5) / samples;
      const double y = pixelY + (sy + 0.5) / samples;
      int32_t winding = 0;
      for (size_t index = 0; index < polygon.size(); ++index) {
        const PolygonPoint& a = polygon[index];
        const PolygonPoint& b = polygon[(index + 1) % polygon.size()];
        if ((a.y <= y) != (b.y <= y)) {
          const double crossX = a.x + (y - a.y) / (b.y - a.y) * (b.x - a.x);
          if (crossX > x) {
            winding += b.y > a.y ? 1 : -1;
          }
        }
      }
      inside += winding != 0 ? 1 : 0;
    }
  }
  return static_cast<double>(inside) / (samples * samples);
}

double coverageAt(const TileCoverageMap& tiles, int32_t x, int32_t y) {
  const auto found = tiles.find({x / kTileSizePixels, y / kTileSizePixels});
  if (found == tiles.end()) {
    return 0;
  }
  return found->second[static_cast<size_t>(y % kTileSizePixels) * kTileSizePixels +
                       static_cast<size_t>(x % kTileSizePixels)] /
         255.0;
}

TEST(PolygonCoverage, PixelAlignedRectangleIsExact) {
  const auto tiles = rasterizePolygon(rectanglePolygon(10, 20, 300, 5), 600, 400);
  ASSERT_EQ(tiles.size(), 2U);
  EXPECT_EQ(coverageAt(tiles, 10, 20), 1.0);
  EXPECT_EQ(coverageAt(tiles, 309, 24), 1.0);
  EXPECT_EQ(coverageAt(tiles, 9, 20), 0.0);
  EXPECT_EQ(coverageAt(tiles, 310, 20), 0.0);
  EXPECT_EQ(coverageAt(tiles, 100, 25), 0.0);
}

TEST(PolygonCoverage, FractionalEdgesGiveAreas) {
  const auto tiles = rasterizePolygon(rectanglePolygon(10.25, 20.5, 2.5, 1), 64, 64);
  EXPECT_NEAR(coverageAt(tiles, 10, 20), 0.75 * 0.5, 1.0 / 255);
  EXPECT_NEAR(coverageAt(tiles, 11, 20), 0.5, 1.0 / 255);
  EXPECT_NEAR(coverageAt(tiles, 12, 21), 0.75 * 0.5, 1.0 / 255);
}

TEST(PolygonCoverage, MatchesSupersamplingForRandomPolygons) {
  uint32_t state = 12345;
  auto random = [&state]() {
    state = state * 1664525U + 1013904223U;
    return (state >> 8U) / static_cast<double>(1U << 24U);
  };
  for (int32_t polygonIndex = 0; polygonIndex < 20; ++polygonIndex) {
    std::vector<PolygonPoint> polygon;
    const int32_t count = 3 + polygonIndex % 7;
    for (int32_t index = 0; index < count; ++index) {
      polygon.push_back({random() * 360 - 30, random() * 300 - 20});
    }
    const auto tiles = rasterizePolygon(polygon, 300, 260);
    double totalError = 0;
    for (int32_t y = 0; y < 260; y += 3) {
      for (int32_t x = 0; x < 300; x += 3) {
        const double expected = sampledCoverage(polygon, x, y, 16);
        const double actual = coverageAt(tiles, x, y);
        EXPECT_NEAR(actual, expected, 0.2) << polygonIndex << " at " << x << "," << y;
        totalError += std::abs(actual - expected);
      }
    }
    EXPECT_LT(totalError / (100.0 * 87), 0.01) << polygonIndex;
  }
}

TEST(PolygonCoverage, EllipseAreaAndBounds) {
  const auto tiles = rasterizePolygon(ellipsePolygon(100, 50, 400, 200), 1024, 512);
  double area = 0;
  for (const auto& [coordinate, coverage] : tiles) {
    area += std::accumulate(coverage.begin(), coverage.end(), 0.0) / 255.0;
  }
  EXPECT_NEAR(area, 3.14159265358979 * 200 * 100, 30);
  EXPECT_EQ(coverageAt(tiles, 300, 150), 1.0);
  EXPECT_EQ(coverageAt(tiles, 102, 52), 0.0);
}

TEST(PolygonCoverage, DegenerateInputGivesNothing) {
  EXPECT_TRUE(rasterizePolygon({{0, 0}, {10, 10}}, 64, 64).empty());
  EXPECT_TRUE(rasterizePolygon({{0, 0}, {NAN, 10}, {5, 5}}, 64, 64).empty());
  EXPECT_TRUE(rasterizePolygon(rectanglePolygon(-50, -50, 20, 20), 64, 64).empty());
}

TEST(PolygonCoverage, CombineAndFeather) {
  EXPECT_EQ(combineSelection(SelectionCombine::Add, 0.25, 0.5), 0.5);
  EXPECT_EQ(combineSelection(SelectionCombine::Subtract, 0.5, 0.5), 0.25);
  EXPECT_EQ(combineSelection(SelectionCombine::Intersect, 0.25, 0.5), 0.25);
  EXPECT_EQ(combineSelection(SelectionCombine::Replace, 0.25, 0.5), 0.5);
  EXPECT_EQ(featherWeights(0).size(), 1U);
  const auto weights = featherWeights(10);
  ASSERT_EQ(weights.size(), 11U);
  double total = weights[0];
  for (size_t index = 1; index < weights.size(); ++index) {
    total += 2 * weights[index];
    EXPECT_LT(weights[index], weights[index - 1]);
  }
  EXPECT_NEAR(total, 1.0, 1e-6);
}

}  // namespace
}  // namespace nanquim::canvas
