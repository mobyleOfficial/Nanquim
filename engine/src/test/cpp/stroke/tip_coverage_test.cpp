#include "stroke/tip_coverage.h"

#include <gtest/gtest.h>

#include <cmath>

namespace nanquim::stroke {
namespace {

constexpr double kPi = 3.14159265358979323846;

Dab dabAt(double x, double y, double diameter) {
  Dab dab;
  dab.x = x;
  dab.y = y;
  dab.diameter = diameter;
  dab.opacity = 1;
  dab.flow = 1;
  return dab;
}

GrayTexture halves(int32_t size) {
  std::vector<uint8_t> pixels(static_cast<size_t>(size * size), 0);
  for (int32_t y = 0; y < size; ++y) {
    for (int32_t x = 0; x < size / 2; ++x) {
      pixels[static_cast<size_t>(y * size + x)] = 255;
    }
  }
  return *GrayTexture::create(size, size, std::move(pixels));
}

TEST(TipCoverageTest, TextureTipCoversItsSquareAndFollowsFlipAndInvert) {
  const GrayTexture shape = halves(64);
  TipStyle style;
  style.shape = &shape;
  Dab dab = dabAt(100, 100, 40);
  EXPECT_NEAR(dabCoverage(dab, style, 85, 100), 1, 1e-9);
  EXPECT_NEAR(dabCoverage(dab, style, 115, 100), 0, 1e-9);
  EXPECT_EQ(dabCoverage(dab, style, 125, 100), 0);
  EXPECT_NEAR(dabCoverage(dab, style, 85, 118), 1, 1e-9);

  dab.flipX = true;
  EXPECT_NEAR(dabCoverage(dab, style, 115, 100), 1, 1e-9);
  dab.flipX = false;
  style.shapeInvert = true;
  EXPECT_NEAR(dabCoverage(dab, style, 115, 100), 1, 1e-9);
  style.shapeInvert = false;

  dab.angle = kPi;
  EXPECT_NEAR(dabCoverage(dab, style, 115, 100), 1, 1e-9);
  dab.angle = 0;
  dab.roundness = 0.5;
  EXPECT_EQ(dabCoverage(dab, style, 85, 112), 0);
  EXPECT_NEAR(dabCoverage(dab, style, 85, 108), 1, 1e-9);
}

TEST(TipCoverageTest, AColorStampTipGivesItsColorWhereItHasAlpha) {
  constexpr int32_t kSize = 16;
  std::vector<uint8_t> pixels;
  for (int32_t y = 0; y < kSize; ++y) {
    for (int32_t x = 0; x < kSize; ++x) {
      const bool left = x < kSize / 2;
      for (const uint8_t value : {left ? 255 : 0, left ? 128 : 0, 0, left ? 255 : 0}) {
        pixels.push_back(value);
      }
    }
  }
  const GrayTexture shape = *GrayTexture::createColor(kSize, kSize, std::move(pixels));
  TipStyle style;
  style.shape = &shape;
  ASSERT_TRUE(style.colored());
  const Dab dab = dabAt(100, 100, 40);
  EXPECT_NEAR(dabCoverage(dab, style, 85, 100), 1, 1e-9);
  EXPECT_NEAR(dabCoverage(dab, style, 115, 100), 0, 1e-9);
  std::array<double, 3> color{};
  ASSERT_TRUE(dabTipColor(dab, style, 85, 100, color));
  EXPECT_NEAR(color[0], 1, 1e-9);
  EXPECT_NEAR(color[1], 128 / 255.0, 1e-9);
  EXPECT_NEAR(color[2], 0, 1e-9);
  EXPECT_FALSE(dabTipColor(dab, style, 115, 100, color));
  EXPECT_FALSE(dabTipColor(dab, style, 125, 100, color));
  ASSERT_TRUE(dabTipColor(dab, style, 100, 100, color));
  EXPECT_NEAR(color[1], 128 / 255.0, 1e-9);
  const GrayTexture gray = halves(kSize);
  style.shape = &gray;
  EXPECT_FALSE(dabTipColor(dab, style, 85, 100, color));
}

TEST(TipCoverageTest, BleedSoftensTheRoundTip) {
  Dab dab = dabAt(50, 50, 40);
  const TipStyle hard{1.0};
  const double sharp = dabCoverage(dab, hard, 50 + 15, 50);
  dab.bleed = 1;
  const double bled = dabCoverage(dab, hard, 50 + 15, 50);
  EXPECT_DOUBLE_EQ(sharp, 1);
  EXPECT_LT(bled, 0.9);
  EXPECT_DOUBLE_EQ(dabCoverage(dab, hard, 50, 50), 1);
}

TEST(TipCoverageTest, TiltGradationFadesAcrossTheTip) {
  Dab dab = dabAt(50, 50, 40);
  dab.gradation = 1;
  dab.gradationAngle = 0;
  const TipStyle round{1.0};
  EXPECT_NEAR(dabCoverage(dab, round, 31, 50), 1 - 0.025, 1e-9);
  EXPECT_NEAR(dabCoverage(dab, round, 50, 50), 0.5, 1e-9);
  EXPECT_LT(dabCoverage(dab, round, 69, 50), 0.05);
}

TEST(TipCoverageTest, TexturizedGrainIsAnchoredToTheCanvas) {
  const GrayTexture grain = halves(16);
  GrainParameters parameters;
  parameters.texture = "grain.png";
  parameters.movement = 0;
  TipStyle style;
  style.grain = &parameters;
  style.grainTexture = &grain;
  Dab first = dabAt(100, 100, 60);
  first.grainDepth = 1;
  first.grainPeriod = 32;
  Dab second = first;
  second.x = 110;
  EXPECT_DOUBLE_EQ(dabCoverage(first, style, 104.5, 100.5),
                   dabCoverage(second, style, 104.5, 100.5));
  EXPECT_NEAR(dabCoverage(first, style, 104.5, 100.5), 1, 1e-9);
  EXPECT_NEAR(dabCoverage(first, style, 120.5, 100.5), 0, 1e-9);
  second.grainOffset = {16, 0};
  EXPECT_NEAR(dabCoverage(second, style, 120.5, 100.5), 1, 1e-9);
}

TEST(TipCoverageTest, MovingGrainFollowsTheDab) {
  const GrayTexture grain = halves(16);
  GrainParameters parameters;
  parameters.texture = "grain.png";
  parameters.filtering = TextureFiltering::None;
  TipStyle style;
  style.grain = &parameters;
  style.grainTexture = &grain;
  Dab first = dabAt(100, 100, 40);
  first.grainDepth = 1;
  Dab second = first;
  second.x = 117;
  EXPECT_DOUBLE_EQ(dabCoverage(first, style, 95.5, 100.5),
                   dabCoverage(second, style, 112.5, 100.5));
}

TEST(TipCoverageTest, GrainBlendModes) {
  EXPECT_DOUBLE_EQ(grainBlend(0.8, 0.5, 1, GrainBlend::Multiply), 0.4);
  EXPECT_DOUBLE_EQ(grainBlend(0.8, 0.5, 0, GrainBlend::Multiply), 0.8);
  EXPECT_NEAR(grainBlend(0.8, 0.5, 1, GrainBlend::Subtract), 0.3, 1e-12);
  EXPECT_NEAR(grainBlend(0.8, 0.5, 1, GrainBlend::LinearBurn), 0.3, 1e-12);
  EXPECT_NEAR(grainBlend(0.8, 0.5, 1, GrainBlend::ColorBurn), 0.6, 1e-12);
  EXPECT_NEAR(grainBlend(0.25, 0.5, 1, GrainBlend::Overlay), 0.25, 1e-12);
  EXPECT_NEAR(grainBlend(0.5, 0.5, 0.5, GrainBlend::Height), 0.25 / 0.505, 1e-12);
  for (const GrainBlend blend : {GrainBlend::Multiply, GrainBlend::Subtract, GrainBlend::LinearBurn,
                                 GrainBlend::ColorBurn, GrainBlend::Overlay, GrainBlend::Height}) {
    EXPECT_DOUBLE_EQ(grainBlend(0, 1, 1, blend), 0) << static_cast<int>(blend);
  }
}

TEST(TipCoverageTest, AdjustGrain) {
  GrainParameters parameters;
  EXPECT_DOUBLE_EQ(adjustGrain(0.3, parameters), 0.3);
  parameters.invert = true;
  EXPECT_DOUBLE_EQ(adjustGrain(0.3, parameters), 0.7);
  parameters.invert = false;
  parameters.brightness = 0.2;
  EXPECT_NEAR(adjustGrain(0.3, parameters), 0.5, 1e-12);
  parameters.brightness = 0;
  parameters.contrast = -1;
  EXPECT_DOUBLE_EQ(adjustGrain(0.9, parameters), 0.5);
  parameters.contrast = 1;
  EXPECT_DOUBLE_EQ(adjustGrain(0.6, parameters), 1);
}

TEST(TipCoverageTest, TextureTipBoundsReachTheCorners) {
  const Dab dab = dabAt(0, 0, 100);
  EXPECT_DOUBLE_EQ(dabBoundingRadius(dab, false), 51);
  EXPECT_NEAR(dabBoundingRadius(dab, true), 71.71, 0.01);
}

}  // namespace
}  // namespace nanquim::stroke
