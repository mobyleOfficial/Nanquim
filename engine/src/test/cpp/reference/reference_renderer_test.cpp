#include "reference_renderer.h"

#include <gtest/gtest.h>

#include <cmath>

#include "reference_scene.h"
#include "stroke/stroke_effects.h"
#include "stroke/test_traces.h"

namespace nanquim::reference {
namespace {

stroke::Dab dabAt(double x, double y, double diameter, double opacity) {
  stroke::Dab dab;
  dab.x = x;
  dab.y = y;
  dab.diameter = diameter;
  dab.opacity = opacity;
  dab.flow = 1;
  dab.color = {1, 0, 0, 1};
  return dab;
}

double alphaAt(const Image& image, int32_t x, int32_t y) { return image.pixel(x, y)[3]; }

TEST(ReferenceRendererTest, HardRoundTipCoversItsDiscWithAntialiasedEdge) {
  Image buffer(64, 64);
  renderDabs(buffer, {dabAt(32, 32, 20, 1)}, stroke::TipStyle{1.0}, stroke::Accumulation::BuildUp);
  EXPECT_DOUBLE_EQ(alphaAt(buffer, 32, 32), 1.0);
  EXPECT_DOUBLE_EQ(alphaAt(buffer, 32, 40), 1.0);
  EXPECT_DOUBLE_EQ(alphaAt(buffer, 32, 44), 0.0);
  const double edge = alphaAt(buffer, 41, 32);
  EXPECT_GT(edge, 0.0);
  EXPECT_LT(edge, 1.0);
  EXPECT_DOUBLE_EQ(buffer.pixel(32, 32)[0], 1.0);
  EXPECT_DOUBLE_EQ(buffer.pixel(32, 32)[1], 0.0);
}

TEST(ReferenceRendererTest, AColorStampPaintsItsOwnColors) {
  const auto shape = stroke::GrayTexture::createColor(2, 1, {0, 255, 0, 255, 0, 0, 0, 0});
  ASSERT_TRUE(shape.has_value());
  stroke::TipStyle style;
  style.shape = &*shape;
  style.shapeFiltering = stroke::TextureFiltering::None;
  Image buffer(64, 64);
  renderDabs(buffer, {dabAt(32, 32, 20, 1)}, style, stroke::Accumulation::BuildUp);
  EXPECT_DOUBLE_EQ(buffer.pixel(26, 32)[0], 0.0);
  EXPECT_DOUBLE_EQ(buffer.pixel(26, 32)[1], 1.0);
  EXPECT_DOUBLE_EQ(alphaAt(buffer, 38, 32), 0.0);
  Image glazed(64, 64);
  renderDabs(glazed, {dabAt(32, 32, 20, 0.5)}, style, stroke::Accumulation::GlazeHeavy);
  EXPECT_DOUBLE_EQ(glazed.pixel(26, 32)[1], alphaAt(glazed, 26, 32));
  EXPECT_DOUBLE_EQ(glazed.pixel(26, 32)[0], 0.0);
}

TEST(ReferenceRendererTest, SoftTipFallsOffTowardsTheEdge) {
  Image buffer(64, 64);
  renderDabs(buffer, {dabAt(32, 32, 40, 1)}, stroke::TipStyle{0.0}, stroke::Accumulation::BuildUp);
  EXPECT_GT(alphaAt(buffer, 32, 32), 0.99);
  EXPECT_GT(alphaAt(buffer, 40, 32), alphaAt(buffer, 45, 32));
  EXPECT_GT(alphaAt(buffer, 45, 32), alphaAt(buffer, 50, 32));
}

TEST(ReferenceRendererTest, BuildUpDarkensOverlapsAndWashDoesNot) {
  const std::vector<stroke::Dab> dabs{dabAt(30, 32, 20, 0.5), dabAt(34, 32, 20, 0.5)};
  Image buildUp(64, 64);
  Image wash(64, 64);
  renderDabs(buildUp, dabs, stroke::TipStyle{1.0}, stroke::Accumulation::BuildUp);
  renderDabs(wash, dabs, stroke::TipStyle{1.0}, stroke::Accumulation::Wash);
  EXPECT_NEAR(alphaAt(buildUp, 32, 32), 0.75, 1e-12);
  EXPECT_NEAR(alphaAt(wash, 32, 32), 0.5, 1e-12);
}

TEST(ReferenceRendererTest, GlazeApproachesTheDabOpacityAndNeverFalls) {
  using stroke::Accumulation;
  std::vector<stroke::Dab> dabs(6, dabAt(32, 32, 20, 0.6));
  for (stroke::Dab& dab : dabs) {
    dab.flow = 0.5;
  }
  Image light(64, 64);
  Image intense(64, 64);
  renderDabs(light, dabs, stroke::TipStyle{1.0}, Accumulation::GlazeUniform);
  renderDabs(intense, dabs, stroke::TipStyle{1.0}, Accumulation::GlazeIntense);
  EXPECT_NEAR(alphaAt(light, 32, 32), 0.6 * (1 - std::pow(0.5, 6)), 1e-12);
  EXPECT_NEAR(light.pixel(32, 32)[0], alphaAt(light, 32, 32), 1e-12);
  EXPECT_NEAR(alphaAt(intense, 32, 32), 0.6, 1e-12);
  renderDabs(intense, {dabAt(32, 32, 20, 0.2)}, stroke::TipStyle{1.0}, Accumulation::GlazeIntense);
  EXPECT_NEAR(alphaAt(intense, 32, 32), 0.6, 1e-12);
}

TEST(ReferenceRendererTest, HeavyGlazeReplacesTheColorWhereCoverageRises) {
  using stroke::Accumulation;
  stroke::Dab blue = dabAt(32, 32, 20, 0.5);
  blue.color = {0, 0, 1, 1};
  Image heavy(64, 64);
  Image intense(64, 64);
  const std::vector<stroke::Dab> dabs{dabAt(32, 32, 20, 0.4), blue};
  renderDabs(heavy, dabs, stroke::TipStyle{1.0}, Accumulation::GlazeHeavy);
  renderDabs(intense, dabs, stroke::TipStyle{1.0}, Accumulation::GlazeIntense);
  EXPECT_NEAR(alphaAt(heavy, 32, 32), 0.5, 1e-12);
  EXPECT_NEAR(heavy.pixel(32, 32)[0], 0, 1e-12);
  EXPECT_NEAR(heavy.pixel(32, 32)[2], 0.5, 1e-12);
  EXPECT_NEAR(intense.pixel(32, 32)[0], 0, 1e-12);
  EXPECT_NEAR(intense.pixel(32, 32)[2], 0.5, 1e-12);
}

TEST(ReferenceRendererTest, IntenseBlendingMixesTheLayerColor) {
  Image layer(64, 64);
  double* under = layer.pixel(32, 32);
  under[2] = 1;
  under[3] = 1;
  Image blended(64, 64);
  Image plain(64, 64);
  renderDabs(blended, {dabAt(32, 32, 20, 1)}, stroke::TipStyle{1.0},
             stroke::Accumulation::BlendIntense, &layer);
  renderDabs(plain, {dabAt(32, 32, 20, 1)}, stroke::TipStyle{1.0},
             stroke::Accumulation::BlendUniform);
  EXPECT_NEAR(blended.pixel(32, 32)[0], 1 - stroke::kLayerMixAmount, 1e-12);
  EXPECT_NEAR(blended.pixel(32, 32)[2], stroke::kLayerMixAmount, 1e-12);
  EXPECT_DOUBLE_EQ(blended.pixel(31, 32)[0], 1);
  EXPECT_DOUBLE_EQ(plain.pixel(32, 32)[0], 1);
}

TEST(ReferenceRendererTest, WetEdgesLightenTheInteriorMoreThanTheRim) {
  Image buffer(128, 128);
  renderDabs(buffer, {dabAt(64, 64, 80, 1)}, stroke::TipStyle{1.0}, stroke::Accumulation::BuildUp);
  stroke::RenderParameters render;
  render.wetEdges = 1;
  applyStrokeEffects(buffer, render, 80);
  EXPECT_NEAR(alphaAt(buffer, 64, 64), 0.6, 0.01);
  EXPECT_GT(alphaAt(buffer, 102, 64), 0.75);
}

TEST(ReferenceRendererTest, GammaCorrectStrokeReturnsToSrgb) {
  Image buffer(64, 64);
  stroke::Dab dab = dabAt(32, 32, 20, 1);
  dab.color = {0.5, 0.25, 1, 1};
  renderDabs(buffer, {dab}, stroke::TipStyle{1.0}, stroke::Accumulation::BuildUp, nullptr, true);
  EXPECT_NEAR(buffer.pixel(32, 32)[0], stroke::srgbToLinear(0.5), 1e-12);
  stroke::RenderParameters render;
  render.gammaCorrect = true;
  applyStrokeEffects(buffer, render, 20);
  EXPECT_NEAR(buffer.pixel(32, 32)[0], 0.5, 1e-12);
  EXPECT_NEAR(buffer.pixel(32, 32)[1], 0.25, 1e-12);
}

TEST(ReferenceRendererTest, LuminanceBlendingKeepsTheLayerHue) {
  Image layer(1, 1);
  double* pixel = layer.pixel(0, 0);
  pixel[2] = 0.5;
  pixel[3] = 1;
  Image stroke(1, 1);
  double* gray = stroke.pixel(0, 0);
  gray[0] = gray[1] = gray[2] = 0.6;
  gray[3] = 1;
  commitPaint(layer, stroke, 1, canvas::BlendMode::Normal, false, true);
  EXPECT_DOUBLE_EQ(layer.pixel(0, 0)[0], layer.pixel(0, 0)[1]);
  EXPECT_GT(layer.pixel(0, 0)[2], layer.pixel(0, 0)[0]);
  EXPECT_DOUBLE_EQ(layer.pixel(0, 0)[3], 1);
}

TEST(ReferenceRendererTest, PaintCommitIsSourceOverWithStrokeOpacity) {
  Image layer(1, 1);
  double* pixel = layer.pixel(0, 0);
  pixel[2] = 1;
  pixel[3] = 1;
  Image stroke(1, 1);
  stroke.pixel(0, 0)[0] = 1;
  stroke.pixel(0, 0)[3] = 1;
  commitPaint(layer, stroke, 0.25);
  EXPECT_NEAR(pixel[0], 0.25, 1e-12);
  EXPECT_NEAR(pixel[2], 0.75, 1e-12);
  EXPECT_NEAR(pixel[3], 1.0, 1e-12);
}

TEST(ReferenceRendererTest, EraseCommitRemovesCoverage) {
  Image layer(1, 1);
  double* pixel = layer.pixel(0, 0);
  pixel[1] = 0.8;
  pixel[3] = 0.8;
  Image stroke(1, 1);
  stroke.pixel(0, 0)[3] = 0.5;
  commitErase(layer, stroke, 1.0);
  EXPECT_NEAR(pixel[1], 0.4, 1e-12);
  EXPECT_NEAR(pixel[3], 0.4, 1e-12);
}

TEST(ReferenceRendererTest, FlattenPutsLayersOverTheBackground) {
  Image layer(1, 1);
  layer.pixel(0, 0)[0] = 0.5;
  layer.pixel(0, 0)[3] = 0.5;
  const Image result = flatten(1, 1, {1, 1, 1, 1}, {CompositeLayer{&layer, 1.0, true}});
  EXPECT_NEAR(result.pixel(0, 0)[0], 1.0, 1e-12);
  EXPECT_NEAR(result.pixel(0, 0)[1], 0.5, 1e-12);
  EXPECT_NEAR(result.pixel(0, 0)[3], 1.0, 1e-12);
  const Image hidden = flatten(1, 1, {1, 1, 1, 1}, {CompositeLayer{&layer, 1.0, false}});
  EXPECT_NEAR(hidden.pixel(0, 0)[1], 1.0, 1e-12);
}

scene::Scene strokeScene(std::vector<stroke::InputSample> samples) {
  scene::Scene scene;
  scene.documentId = "test";
  scene.width = 2400;
  scene.height = 800;
  scene::StrokeAction stroke;
  stroke.brush = stroke::roundBrush();
  stroke.settings.sizePixels = 10;
  stroke.settings.seed = 5;
  stroke.samples = std::move(samples);
  scene.actions.push_back(scene::SceneAction::of(stroke));
  return scene;
}

TEST(ReferenceRendererTest, FastStraightStrokeLeavesNoGapInThePixels) {
  std::vector<stroke::InputSample> samples;
  constexpr double kSampleRate = 120;
  for (int index = 0; index <= 12; ++index) {
    stroke::InputSample sample;
    sample.x =
        static_cast<float>(100 + index * stroke::testing::kTwoMetersPerSecondPixels / kSampleRate);
    sample.y = 400.5F;
    sample.pressure = 0.6F;
    sample.eventTimeNanos = static_cast<int64_t>(index * 1e9 / kSampleRate);
    samples.push_back(sample);
  }
  const Image image = renderScene(strokeScene(samples));
  const auto lastX = static_cast<int32_t>(samples.back().x);
  for (int32_t x = 101; x < lastX; ++x) {
    ASSERT_LT(image.pixel(x, 400)[0], 0.5) << "gap at x = " << x;
  }
}

TEST(ReferenceRendererTest, SameSceneGivesIdenticalPixels) {
  const auto samples =
      stroke::testing::wavyStroke(stroke::testing::kTwoMetersPerSecondPixels, 240, 2000);
  const Image first = renderScene(strokeScene(samples));
  const Image second = renderScene(strokeScene(samples));
  EXPECT_EQ(first.rgba, second.rgba);
}

}  // namespace
}  // namespace nanquim::reference
