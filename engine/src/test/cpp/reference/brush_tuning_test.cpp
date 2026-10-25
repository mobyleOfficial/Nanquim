#include "brush_tuning.h"

#include <gtest/gtest.h>

#include <cmath>
#include <map>

#include "test_sheet.h"

namespace nanquim::reference {
namespace {

Rgba8Image whiteSheet(int32_t size) {
  return Rgba8Image{size, size, std::vector<uint8_t>(static_cast<size_t>(size) * size * 4, 255)};
}

void paint(Rgba8Image& image, int32_t x, int32_t y, uint8_t gray) {
  uint8_t* pixel = &image.pixels[(static_cast<size_t>(y) * image.width + x) * 4];
  pixel[0] = pixel[1] = pixel[2] = gray;
}

std::map<std::string, double> byName(const std::vector<StrokeStatistic>& statistics) {
  std::map<std::string, double> values;
  for (const auto& statistic : statistics) {
    values[statistic.name] = statistic.value;
  }
  return values;
}

nlohmann::json roundBrushDocument(double sizeMinimum, double spacing = 0.05) {
  nlohmann::json document = nlohmann::json::parse(R"({ "format": 1, "id": "t", "name": "Test",
    "shape": { "texture": null, "hardness": 1 }, "render": { "accumulation": "wash" },
    "limits": { "maxSize": 200 }, "defaults": { "size": 0.5 } })");
  document["path"]["spacing"] = spacing;
  document["dynamics"]["size"]["min"] = sizeMinimum;
  document["dynamics"]["size"]["pressure"] = "curve:linear";
  return document;
}

TEST(TestSheetTest, RegionsScaleAndStayOnTheSheet) {
  for (const double scale : {1.0, 0.25}) {
    const auto regions = sheetRegions(scale);
    EXPECT_EQ(regions.size(), 8U);
    for (const auto& region : regions) {
      EXPECT_GE(region.rect.left, 0);
      EXPECT_LE(region.rect.right, std::lround(kSheetSize * scale));
      EXPECT_LE(region.rect.bottom, std::lround(kSheetSize * scale));
    }
  }
  stroke::LoadedBrush brush;
  brush.parameters.defaults.size = 0.5;
  brush.parameters.limits.maxSize = 201;
  EXPECT_DOUBLE_EQ(defaultBrushSize(brush.parameters), 51);
  const auto scene = testSheetScene(brush, 0.5);
  EXPECT_EQ(scene.width, 1024);
  EXPECT_EQ(scene.actions.size(), 2U + 1U + 6U + 3U + 5U + 10U + 2U);
}

TEST(StrokeStatisticsTest, MeasuresAKnownBand) {
  Rgba8Image image = whiteSheet(kSheetSize);
  for (int32_t x = 160; x < 1888; ++x) {
    for (int32_t y = 166; y < 186; ++y) {
      paint(image, x, y, 0);
    }
    for (int32_t y = 427; y < 437; ++y) {
      paint(image, x, y, 128);
    }
  }
  const auto values = byName(measureSheet(image));
  EXPECT_NEAR(values.at("A.width.05"), 20, 0.5);
  EXPECT_NEAR(values.at("A.opacity.05"), 1, 0.01);
  EXPECT_NEAR(values.at("B.width.08"), 10, 0.5);
  EXPECT_NEAR(values.at("B.opacity.08"), 0.5, 0.02);
  EXPECT_NEAR(values.at("A.edge"), 0, 1);
}

TEST(StrokeStatisticsTest, FindsTheTextureFrequencyAndDirection) {
  Rgba8Image image = whiteSheet(kSheetSize);
  for (int32_t y = 1500; y < 1820; ++y) {
    for (int32_t x = 800; x < 1250; ++x) {
      paint(image, x, y, (x / 4) % 2 == 0 ? 0 : 255);
    }
  }
  const auto values = byName(measureSheet(image));
  EXPECT_NEAR(values.at("G.textureFrequency"), 1.0 / 8, 0.01);
  EXPECT_GT(values.at("G.textureAnisotropy"), 0.8);
}

TEST(BrushTuningTest, NelderMeadFindsTheMinimumOfABowl) {
  int32_t evaluations = 0;
  const auto best = minimizeNelderMead(
      [](const std::vector<double>& p) { return std::pow(p[0] - 0.3, 2) + std::pow(p[1] - 0.7, 2); },
      {0.9, 0.1}, 400, 0.2, &evaluations);
  EXPECT_NEAR(best[0], 0.3, 1e-3);
  EXPECT_NEAR(best[1], 0.7, 1e-3);
  EXPECT_LE(evaluations, 400);
}

TEST(BrushTuningTest, SameBrushMatchesAndADifferentOneDoesNot) {
  const stroke::LoadedBrush noTextures;
  const auto target = measureSheet(renderTestSheet(roundBrushDocument(0.6), noTextures, 0.25));
  EXPECT_NEAR(compareStatistics(target, measureSheet(renderTestSheet(roundBrushDocument(0.6), noTextures, 0.25))).distance,
              0, 1e-12);
  EXPECT_GT(compareStatistics(target, measureSheet(renderTestSheet(roundBrushDocument(0.1), noTextures, 0.25))).distance,
            kAcceptanceDistance);
}

TEST(BrushTuningTest, RecoversAPressureResponse) {
  const stroke::LoadedBrush noTextures;
  const auto target = measureSheet(renderTestSheet(roundBrushDocument(0.6), noTextures, 0.25));
  const auto result = tuneBrush(roundBrushDocument(0.1), noTextures, target, {{"dynamics.size.min", 0, 1}}, 40, 0.25);
  EXPECT_LT(result.distance, result.initialDistance);
  EXPECT_LT(result.distance, kAcceptanceDistance);
  EXPECT_NEAR(result.brush["dynamics"]["size"]["min"].get<double>(), 0.6, 0.08);
}

}  // namespace
}  // namespace nanquim::reference
