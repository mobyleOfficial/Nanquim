#include "stroke/auto_repeat.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstdlib>

#include "stroke/random.h"

namespace nanquim::stroke {
namespace {

std::vector<uint8_t> noise(int32_t width, int32_t height, int32_t cell, uint64_t seed) {
  Random random(seed);
  const int32_t columns = width / cell + 2;
  const int32_t rows = height / cell + 2;
  std::vector<double> grid(size_t(columns) * size_t(rows));
  for (double& value : grid) {
    value = random.nextUnit();
  }
  std::vector<uint8_t> pixels(size_t(width) * size_t(height));
  for (int32_t y = 0; y < height; ++y) {
    for (int32_t x = 0; x < width; ++x) {
      const double gx = double(x) / cell;
      const double gy = double(y) / cell;
      const auto x0 = int32_t(gx);
      const auto y0 = int32_t(gy);
      const double fx = gx - x0;
      const double fy = gy - y0;
      auto at = [&](int32_t i, int32_t j) { return grid[size_t(j) * size_t(columns) + size_t(i)]; };
      const double value = (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) +
                           (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy;
      pixels[size_t(y) * size_t(width) + size_t(x)] = uint8_t(std::lround(value * 255));
    }
  }
  return pixels;
}

double columnStep(const AutoRepeatResult& result, int32_t a, int32_t b) {
  double sum = 0;
  for (int32_t y = 0; y < result.side; ++y) {
    sum += std::abs(int32_t(result.pixels[size_t(y) * size_t(result.side) + size_t(a)]) -
                    int32_t(result.pixels[size_t(y) * size_t(result.side) + size_t(b)]));
  }
  return sum / result.side;
}

double rowStep(const AutoRepeatResult& result, int32_t a, int32_t b) {
  double sum = 0;
  for (int32_t x = 0; x < result.side; ++x) {
    sum += std::abs(int32_t(result.pixels[size_t(a) * size_t(result.side) + size_t(x)]) -
                    int32_t(result.pixels[size_t(b) * size_t(result.side) + size_t(x)]));
  }
  return sum / result.side;
}

TEST(AutoRepeatTest, TheResultWrapsAsSmoothlyAsItsInterior) {
  const auto source = noise(160, 128, 12, 7);
  AutoRepeatParameters parameters;
  parameters.outputSize = 128;
  parameters.patchScale = 0.3;
  parameters.seamOverlap = 0.3;
  const AutoRepeatResult result = autoRepeat(source, 160, 128, parameters);
  ASSERT_EQ(result.side, 128);
  ASSERT_EQ(result.pixels.size(), 128U * 128U);
  double interiorColumns = 0;
  double interiorRows = 0;
  for (int32_t index = 0; index + 1 < result.side; ++index) {
    interiorColumns += columnStep(result, index, index + 1);
    interiorRows += rowStep(result, index, index + 1);
  }
  interiorColumns /= result.side - 1;
  interiorRows /= result.side - 1;
  EXPECT_LT(columnStep(result, result.side - 1, 0), 3 * interiorColumns + 1);
  EXPECT_LT(rowStep(result, result.side - 1, 0), 3 * interiorRows + 1);
  double sourceWrap = 0;
  for (int32_t y = 0; y < 128; ++y) {
    sourceWrap +=
        std::abs(int32_t(source[size_t(y) * 160]) - int32_t(source[size_t(y) * 160 + 159]));
  }
  EXPECT_GT(sourceWrap / 128, columnStep(result, result.side - 1, 0));
}

TEST(AutoRepeatTest, SameInputGivesTheSameResultAndOptionsRun) {
  const auto source = noise(96, 96, 8, 3);
  AutoRepeatParameters parameters;
  parameters.mirroring = true;
  parameters.pyramidBlending = true;
  parameters.maskHardness = 0;
  const auto first = autoRepeat(source, 96, 96, parameters);
  const auto second = autoRepeat(source, 96, 96, parameters);
  ASSERT_EQ(first.side, 96);
  EXPECT_EQ(first.pixels, second.pixels);
  parameters.seed = 2;
  EXPECT_NE(autoRepeat(source, 96, 96, parameters).pixels, first.pixels);
}

TEST(AutoRepeatTest, IgnoringLowBandsRemovesAGradient) {
  auto source = noise(128, 128, 4, 5);
  for (int32_t y = 0; y < 128; ++y) {
    for (int32_t x = 0; x < 128; ++x) {
      uint8_t& value = source[size_t(y) * 128 + size_t(x)];
      value = uint8_t(std::clamp(value / 4 + x * 190 / 127, 0, 255));
    }
  }
  AutoRepeatParameters parameters;
  parameters.ignoreLowBands = true;
  const auto result = autoRepeat(source, 128, 128, parameters);
  double low = 255;
  double high = 0;
  for (int32_t x = 0; x < result.side; ++x) {
    double mean = 0;
    for (int32_t y = 0; y < result.side; ++y) {
      mean += result.pixels[size_t(y) * size_t(result.side) + size_t(x)];
    }
    mean /= result.side;
    low = std::min(low, mean);
    high = std::max(high, mean);
  }
  EXPECT_LT(high - low, 60);
}

TEST(AutoRepeatTest, ProgressCanCancelAndBadInputGivesNothing) {
  const auto source = noise(64, 64, 8, 1);
  int calls = 0;
  const auto canceled = autoRepeat(source, 64, 64, {}, [&](double fraction) {
    EXPECT_GT(fraction, 0);
    EXPECT_LE(fraction, 1);
    return ++calls < 2;
  });
  EXPECT_TRUE(canceled.pixels.empty());
  EXPECT_EQ(calls, 2);
  EXPECT_TRUE(autoRepeat({1, 2, 3}, 2, 2, {}).pixels.empty());
}

}  // namespace
}  // namespace nanquim::stroke
