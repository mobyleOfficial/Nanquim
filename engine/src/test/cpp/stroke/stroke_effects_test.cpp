#include "stroke/stroke_effects.h"

#include <gtest/gtest.h>

#include <numeric>

namespace nanquim::stroke {
namespace {

TEST(StrokeEffectsTest, SrgbTransferRoundTrips) {
  for (double value = 0; value <= 1.0; value += 0.05) {
    EXPECT_NEAR(linearToSrgb(srgbToLinear(value)), value, 1e-12);
  }
  EXPECT_NEAR(srgbToLinear(0.5), 0.214041, 1e-6);
}

TEST(StrokeEffectsTest, BlurWeightsAreNormalizedAndGrowWithTheBrush) {
  for (const double size : {5.0, 20.0, 100.0, 1000.0}) {
    const std::vector<float> weights = edgeBlurWeights(size);
    double total = weights[0];
    for (size_t tap = 1; tap < weights.size(); ++tap) {
      total += 2.0 * weights[tap];
    }
    EXPECT_NEAR(total, 1.0, 1e-6) << size;
    EXPECT_LE(weights.size(), static_cast<size_t>(kMaximumEdgeBlurRadius) + 1);
  }
  EXPECT_LT(edgeBlurWeights(20).size(), edgeBlurWeights(200).size());
  EXPECT_DOUBLE_EQ(edgeBlurRadiusPixels(20), 3);
}

TEST(StrokeEffectsTest, EdgeAmountIsHighWhereTheBlurIsBelowTheCoverage) {
  EXPECT_DOUBLE_EQ(edgeAmount(1, 1), 0);
  EXPECT_DOUBLE_EQ(edgeAmount(1, 0.25), 0.75);
  EXPECT_DOUBLE_EQ(edgeAmount(0.2, 0.5), 0);
  EXPECT_DOUBLE_EQ(edgeAmount(0, 0.5), 0);
}

TEST(StrokeEffectsTest, EffectsDependOnWhatTheStrokePaints) {
  RenderParameters render;
  render.gammaCorrect = true;
  render.luminanceBlending = true;
  render.wetEdges = 0.5;
  render.burntEdges = 0.5;
  const RenderParameters paint = effectiveRender(render, true, false, false);
  EXPECT_TRUE(paint.gammaCorrect);
  EXPECT_TRUE(paint.luminanceBlending);
  EXPECT_EQ(paint.burntEdges, 0.5);
  EXPECT_FALSE(effectiveRender(render, true, true, false).gammaCorrect);
  const RenderParameters erase = effectiveRender(render, false, false, false);
  EXPECT_FALSE(erase.gammaCorrect);
  EXPECT_FALSE(erase.luminanceBlending);
  EXPECT_EQ(erase.burntEdges, 0);
  EXPECT_EQ(erase.wetEdges, 0.5);
  EXPECT_EQ(effectiveRender(render, false, false, true).wetEdges, 0);
}

}  // namespace
}  // namespace nanquim::stroke
