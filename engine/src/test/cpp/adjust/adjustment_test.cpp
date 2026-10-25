#include "adjust/adjustment.h"

#include <gtest/gtest.h>

#include <array>
#include <memory>
#include <vector>

namespace nanquim::adjust {
namespace {

void expectColor(const std::array<double, 3>& actual, const std::array<double, 3>& expected) {
  for (size_t channel = 0; channel < 3; ++channel) {
    EXPECT_NEAR(actual[channel], expected[channel], 1e-9) << "channel " << channel;
  }
}

TEST(Adjustment, HsvRoundTrips) {
  for (const std::array<double, 3> color : {std::array<double, 3>{0.9, 0.1, 0.1},
                                            {0.2, 0.8, 0.3},
                                            {0.1, 0.3, 0.9},
                                            {1, 1, 0},
                                            {0.5, 0.5, 0.5},
                                            {0, 0, 0},
                                            {0.3, 0.6, 0.6}}) {
    expectColor(rgbFromHsv(hsvFromRgb(color)), color);
  }
}

TEST(Adjustment, HueTurnsHalfATurnAtOne) {
  expectColor(hueSaturationBrightness({1, 0, 0}, 1, 0, 0), {0, 1, 1});
  expectColor(hueSaturationBrightness({1, 0, 0}, -1, 0, 0), {0, 1, 1});
  expectColor(hueSaturationBrightness({1, 0, 0}, 0.5, 0, 0), {0.5, 1, 0});
  expectColor(hueSaturationBrightness({0.5, 0.5, 0.5}, 0.7, 0, 0), {0.5, 0.5, 0.5});
}

TEST(Adjustment, SaturationScalesAndClamps) {
  expectColor(hueSaturationBrightness({0.8, 0.4, 0.4}, 0, -1, 0), {0.8, 0.8, 0.8});
  expectColor(hueSaturationBrightness({0.8, 0.4, 0.4}, 0, 0.5, 0), {0.8, 0.2, 0.2});
  expectColor(hueSaturationBrightness({0.8, 0.4, 0.4}, 0, 1, 0), {0.8, 0, 0});
}

TEST(Adjustment, BrightnessMixesTowardWhiteOrBlack) {
  expectColor(hueSaturationBrightness({0.2, 0.4, 0.6}, 0, 0, 1), {1, 1, 1});
  expectColor(hueSaturationBrightness({0.2, 0.4, 0.6}, 0, 0, -1), {0, 0, 0});
  expectColor(hueSaturationBrightness({0.2, 0.4, 0.6}, 0, 0, 0.5), {0.6, 0.7, 0.8});
  expectColor(hueSaturationBrightness({0.2, 0.4, 0.6}, 0, 0, -0.5), {0.1, 0.2, 0.3});
}

TEST(Adjustment, PixelsKeepAlphaAndMixByWeight) {
  Parameters parameters;
  parameters.values = {0, -1, 0};
  const std::array<double, 4> pixel{0.4, 0.2, 0.2, 0.5};
  const auto full = applyToPixel(parameters, pixel, 1);
  EXPECT_NEAR(full[0], 0.4, 1e-9);
  EXPECT_NEAR(full[1], 0.4, 1e-9);
  EXPECT_NEAR(full[3], 0.5, 1e-9);
  const auto half = applyToPixel(parameters, pixel, 0.5);
  EXPECT_NEAR(half[1], 0.3, 1e-9);
  EXPECT_EQ(applyToPixel(parameters, pixel, 0), pixel);
  const std::array<double, 4> transparent{0, 0, 0, 0};
  EXPECT_EQ(applyToPixel(parameters, transparent, 1), transparent);
}

TEST(Adjustment, ColorBalanceShiftsTheRangeOfTheLuminance) {
  std::array<float, kParameterCount> shifts{};
  shifts[3] = 1;
  expectColor(colorBalance({0.5, 0.5, 0.5}, shifts), {1, 0.5, 0.5});
  expectColor(colorBalance({0, 0, 0}, shifts), {0, 0, 0});
  expectColor(colorBalance({1, 1, 1}, shifts), {1, 1, 1});
  shifts = {};
  shifts[2] = -1;
  const double t = 0.244 / 0.5;
  const double shadows = 1 - t * t * (3 - 2 * t);
  expectColor(colorBalance({0.2, 0.2, 0.6}, shifts), {0.2, 0.2, 0.6 - 0.5 * shadows});
}

TEST(Adjustment, MonotoneCurvesPassThroughTheirPointsWithoutOvershoot) {
  const std::vector<std::array<double, 2>> points{{0, 0}, {0.25, 0.6}, {0.5, 0.65}, {1, 1}};
  for (const auto& point : points) {
    EXPECT_NEAR(monotoneCurve(points, point[0]), point[1], 1e-12);
  }
  double previous = 0;
  for (int step = 0; step <= 1000; ++step) {
    const double value = monotoneCurve(points, step / 1000.0);
    EXPECT_GE(value, previous - 1e-12);
    previous = value;
  }
  EXPECT_EQ(monotoneCurve({{0.2, 0.3}, {0.8, 0.9}}, 0.1), 0.3);
  EXPECT_EQ(monotoneCurve({{0.2, 0.3}, {0.8, 0.9}}, 0.95), 0.9);
  EXPECT_EQ(monotoneCurve({{0.5, 0.7}}, 0.1), 0.7);
}

TEST(Adjustment, StraightCurvesChangeNothing) {
  Parameters parameters;
  parameters.filter = Filter::Curves;
  const auto table = bakeTable(Filter::Curves, defaultTableData(Filter::Curves));
  ASSERT_TRUE(table.has_value());
  parameters.table = std::make_shared<const Table>(*table);
  const std::array<double, 4> pixel{0.3, 0.1, 0.2, 0.5};
  const auto result = applyToPixel(parameters, pixel, 1);
  for (size_t channel = 0; channel < 4; ++channel) {
    EXPECT_NEAR(result[channel], pixel[channel], 1e-6);
  }
}

TEST(Adjustment, CurvesApplyMasterThenChannelAndAlpha) {
  const std::vector<float> data{2, 0, 1, 1, 0, 1, 0.5F, 1, 2, 0, 0,   1,
                                1, 2, 0, 0, 1, 1, 2,    0, 0, 1, 0.5F};
  const auto table = bakeTable(Filter::Curves, data);
  ASSERT_TRUE(table.has_value());
  Parameters parameters;
  parameters.filter = Filter::Curves;
  parameters.table = std::make_shared<const Table>(*table);
  const auto color = filterColor(parameters, {0.25, 0.25, 0.75, 1});
  EXPECT_NEAR(color[0], 1, 1e-6);
  EXPECT_NEAR(color[1], 0.75, 1e-3);
  EXPECT_NEAR(color[2], 0.25, 1e-3);
  EXPECT_NEAR(color[3], 0.5, 1e-3);
  EXPECT_FALSE(bakeTable(Filter::Curves, {2, 0.5F, 0, 0.4F, 1, 2, 0, 0, 1, 1, 2, 0, 0,
                                          1, 1,    2, 0,    0, 1, 1, 2, 0, 0, 1, 1}));
  std::vector<float> tooMany{17};
  tooMany.resize(1 + 34, 0);
  EXPECT_FALSE(bakeTable(Filter::Curves, tooMany));
  auto trailing = defaultTableData(Filter::Curves);
  trailing.push_back(0);
  EXPECT_FALSE(bakeTable(Filter::Curves, trailing));
}

TEST(Adjustment, GradientMapMapsTheLuminance) {
  const std::vector<float> data{3, 0, 0, 0, 0, 0.5F, 1, 0, 0, 1, 1, 1, 1};
  const auto table = bakeTable(Filter::GradientMap, data);
  ASSERT_TRUE(table.has_value());
  Parameters parameters;
  parameters.filter = Filter::GradientMap;
  parameters.values[0] = 1;
  parameters.table = std::make_shared<const Table>(*table);
  const auto gray = filterColor(parameters, {0.5, 0.5, 0.5, 1});
  EXPECT_NEAR(gray[0], 1, 1e-3);
  EXPECT_NEAR(gray[1], 0, 1e-3);
  const auto white = filterColor(parameters, {1, 1, 1, 0.4});
  EXPECT_NEAR(white[1], 1, 1e-6);
  EXPECT_EQ(white[3], 0.4);
  parameters.values[0] = 0.5F;
  EXPECT_NEAR(filterColor(parameters, {0.5, 0.5, 0.5, 1})[1], 0.25, 1e-3);
  EXPECT_FALSE(bakeTable(Filter::GradientMap, {2, 0.6F, 0, 0, 0, 0.4F, 1, 1, 1}));
}

TEST(Adjustment, NamesRoundTrip) {
  EXPECT_EQ(filterFromName("hueSaturationBrightness"), Filter::HueSaturationBrightness);
  EXPECT_FALSE(filterFromName("blur").has_value());
  EXPECT_EQ(parameterNames(Filter::HueSaturationBrightness).size(), 3U);
  EXPECT_EQ(parameterNames(Filter::ColorBalance).size(), 9U);
  for (const Filter filter : {Filter::HueSaturationBrightness, Filter::ColorBalance, Filter::Curves,
                              Filter::GradientMap}) {
    EXPECT_EQ(filterFromName(filterName(filter)), filter);
    EXPECT_LE(parameterNames(filter).size(), kParameterCount);
  }
}

TEST(Adjustment, HashIsTheSharedContract) {
  EXPECT_EQ(hash32(1, 2, 3), 0x5f24cf5aU);
  EXPECT_EQ(hash32(-5, 7, 11), 0x86c015b7U);
  EXPECT_LT(hashUnit(-5, 7, 11), 1.0);
  EXPECT_DOUBLE_EQ(valueNoise(3, 4, 9), hashUnit(3, 4, 9));
}

TEST(Adjustment, NoiseMixesInOverlay) {
  Parameters parameters;
  parameters.filter = Filter::Noise;
  parameters.values[1] = 0.2f;
  const PixelPosition position{100, 60, 512, 256};
  EXPECT_EQ(filterColor(parameters, {0.3, 0.6, 0.2, 1}, position)[0], 0.3);
  parameters.values[0] = 1;
  for (const int type : {0, 1, 2}) {
    parameters.values[4] = static_cast<float>(type);
    for (int32_t x = 0; x < 512; x += 37) {
      const double noise = noiseAt(parameters, {static_cast<double>(x), 30, 512, 256});
      EXPECT_GE(noise, 0);
      EXPECT_LE(noise, 1);
    }
  }
  parameters.values[4] = 0;
  EXPECT_NE(noiseAt(parameters, {10, 10, 512, 256}), noiseAt(parameters, {200, 90, 512, 256}));
  EXPECT_EQ(overlay(0, 0.8), 0);
  EXPECT_EQ(overlay(1, 0.2), 1);
  EXPECT_NEAR(overlay(0.5, 0.5), 0.5, 1e-12);
}

}  // namespace
}  // namespace nanquim::adjust
