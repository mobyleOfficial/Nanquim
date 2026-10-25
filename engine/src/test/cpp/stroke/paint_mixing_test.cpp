#include "stroke/paint_mixing.h"

#include <gtest/gtest.h>

#include <cmath>

namespace nanquim::stroke {
namespace {

Dab dabWith(double distance, double pressure = 1) {
  Dab dab;
  dab.opacity = 1;
  dab.flow = 1;
  dab.color = {1, 0, 0, 1};
  dab.distance = distance;
  dab.pressure = pressure;
  return dab;
}

TEST(PaintMixingTest, SamplePointsSpanTheBoundingSquare) {
  Dab dab;
  dab.x = 100;
  dab.y = 50;
  const auto first = mixSamplePoint(dab, 16, 0, 0);
  const auto last = mixSamplePoint(dab, 16, kMixSampleGrid - 1, kMixSampleGrid - 1);
  EXPECT_DOUBLE_EQ(first[0], 100 - 15);
  EXPECT_DOUBLE_EQ(first[1], 50 - 15);
  EXPECT_DOUBLE_EQ(last[0], 100 + 15);
}

TEST(PaintMixingTest, ShortLengthLetsGoOfTheCarriedColorSooner) {
  BrushParameters brush;
  brush.smudge.strength = 0.8;
  brush.smudge.pickUp = 0.2;
  brush.smudge.length = 0;
  EXPECT_NEAR(dabMixing(PaintMixing::Smudge, brush, 1, dabWith(100), 100).pickUp, 0.2, 1e-12);
  EXPECT_NEAR(dabMixing(PaintMixing::Smudge, brush, 1, dabWith(120), 100).pickUp,
              1 - 0.8 / std::exp(1.0), 1e-12);
  brush.smudge.length = 1;
  EXPECT_LT(dabMixing(PaintMixing::Smudge, brush, 1, dabWith(120), 100).pickUp, 0.21);
  EXPECT_NEAR(dabMixing(PaintMixing::Smudge, brush, 0.5, dabWith(0), 0).amount, 0.4, 1e-12);
}

TEST(PaintMixingTest, SmudgeCarriesTheFirstSampleAndPicksUpGradually) {
  BrushParameters brush;
  brush.smudge.pickUp = 0.25;
  const DabMixing mixing = dabMixing(PaintMixing::Smudge, brush, 1, dabWith(0), 0);
  MixingState state;
  const MixedPaint first = mixPaint(mixing, {0, 0, 1, 1}, true, state);
  EXPECT_EQ(first.paint, (std::array<double, 4>{0, 0, 1, 1}));
  const MixedPaint second = mixPaint(mixing, {0, 0, 0, 0}, true, state);
  EXPECT_NEAR(second.paint[2], 0.75, 1e-12);
  EXPECT_NEAR(second.paint[3], 0.75, 1e-12);
  EXPECT_FALSE(mixPaint(mixing, {}, false, state).apply);
  double pixel[4] = {1, 1, 1, 1};
  applyMixedPaint(mixing, second, 0.5, pixel);
  EXPECT_NEAR(pixel[0], 1 - 0.4, 1e-12);
  EXPECT_NEAR(pixel[3], 1 - 0.4 * 0.25, 1e-12);
}

TEST(PaintMixingTest, WetMixRunsDryAndPullsTheCanvasColor) {
  BrushParameters brush;
  brush.wetMix.enabled = true;
  brush.wetMix.charge = 0.5;
  brush.wetMix.pull = 0.5;
  brush.wetMix.dilution = 0.5;
  const DabMixing fresh = dabMixing(PaintMixing::WetMix, brush, 1, dabWith(0), 0);
  EXPECT_NEAR(fresh.brushPaint[3], 1, 1e-12);
  EXPECT_NEAR(fresh.amount, 0.5, 1e-12);
  EXPECT_NEAR(dabMixing(PaintMixing::WetMix, brush, 1, dabWith(250), 0).brushPaint[3], 0.5, 1e-12);
  EXPECT_NEAR(dabMixing(PaintMixing::WetMix, brush, 1, dabWith(600), 0).brushPaint[3], 0, 1e-12);
  MixingState state;
  const MixedPaint paint = mixPaint(fresh, {0, 0, 1, 1}, true, state);
  EXPECT_NEAR(paint.paint[0], 0.5, 1e-12);
  EXPECT_NEAR(paint.paint[2], 0.5, 1e-12);
  double pixel[4] = {0, 0, 0, 0};
  applyMixedPaint(fresh, paint, 1, pixel);
  EXPECT_NEAR(pixel[3], 0.5, 1e-12);
  EXPECT_NEAR(pixel[0], 0.25, 1e-12);
}

TEST(PaintMixingTest, GradeMakesPressureScaleAttackAndPull) {
  BrushParameters brush;
  brush.wetMix.enabled = true;
  brush.wetMix.pull = 1;
  brush.wetMix.grade = 1;
  const DabMixing light = dabMixing(PaintMixing::WetMix, brush, 1, dabWith(0, 0.25), 0);
  EXPECT_NEAR(light.amount, 0.25, 1e-12);
  EXPECT_NEAR(light.pull, 0.25, 1e-12);
}

}  // namespace
}  // namespace nanquim::stroke
