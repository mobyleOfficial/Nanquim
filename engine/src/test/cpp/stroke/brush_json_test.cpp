#include "stroke/brush_json.h"

#include <gtest/gtest.h>

namespace nanquim::stroke {
namespace {

BrushParameters parse(std::string_view text) {
  std::string error;
  auto brush = parseBrush(text, error);
  EXPECT_TRUE(brush.has_value()) << error;
  return brush.value_or(BrushParameters{});
}

std::string errorOf(std::string_view text) {
  std::string error;
  EXPECT_FALSE(parseBrush(text, error).has_value());
  return error;
}

TEST(BrushJsonTest, MinimalBrushTakesTheSchemaDefaults) {
  const BrushParameters brush = parse(R"({ "format": 1, "id": "x", "name": "Minimal" })");
  EXPECT_DOUBLE_EQ(brush.path.spacing, 0.1);
  EXPECT_EQ(brush.shape.texture, "shape.png");
  EXPECT_EQ(brush.shape.rotationMode, RotationMode::FollowStroke);
  EXPECT_EQ(brush.shape.filtering, TextureFiltering::Improved);
  EXPECT_FALSE(brush.grain.enabled());
  EXPECT_EQ(brush.render.accumulation, Accumulation::GlazeLight);
  EXPECT_DOUBLE_EQ(brush.limits.minSize, 1);
  EXPECT_DOUBLE_EQ(brush.limits.maxSize, 500);
  EXPECT_FALSE(brush.dynamics.size.pressure.has_value());
  EXPECT_DOUBLE_EQ(brush.taper.stylus.minOpacity, 1);
  EXPECT_TRUE(brush.taper.stylus.pressureMode);
}

TEST(BrushJsonTest, ReadsEverySectionTheEngineRenders) {
  const BrushParameters brush = parse(R"({
    "format": 1,
    "curves": { "gentle": [[0, 0], [0.5, 0.2], [1, 1]] },
    "path": { "spacing": 0.25, "jitterLateral": 0.5, "streamline": 0.3, "falloff": 0.4 },
    "shape": { "texture": null, "hardness": 0.6, "rotationMode": "fixed", "rotation": 0.5,
               "roundness": 0.4, "pressureRoundness": "curve:soft", "pressureRoundnessAmount": 0.5,
               "count": 3, "countJitter": 0.5, "filtering": "none", "randomized": true },
    "grain": { "texture": "grain.png", "mode": "texturized", "scale": 2, "depth": 0.7,
               "pressureDepth": "curve:gentle", "pressureDepthAmount": 1, "blend": "height" },
    "dynamics": {
      "size": { "min": 0.2, "pressure": "curve:gentle", "tilt": "curve:linear", "tiltAmount": 0.5,
                "speed": -0.5, "jitter": 0.1, "tiltSizeCompression": 0.3 },
      "opacity": { "min": 0.4, "pressure": "curve:hard" },
      "bleed": { "pressure": "curve:linear", "pressureAmount": 0.5 },
      "tiltGradation": 0.8,
      "fingerPressure": "speed"
    },
    "taper": { "stylus": { "start": 30, "end": 60, "minSize": 0.1, "shape": 0.5 },
               "finger": { "start": 0.5, "relative": true }, "classic": true },
    "render": { "accumulation": "wash", "flow": 0.6 },
    "limits": { "minSize": 2, "maxSize": 120 },
    "properties": { "orientToScreen": true }
  })");
  EXPECT_DOUBLE_EQ(brush.path.spacing, 0.25);
  EXPECT_DOUBLE_EQ(brush.path.falloff, 0.4);
  EXPECT_TRUE(brush.shape.texture.empty());
  EXPECT_EQ(brush.shape.rotationMode, RotationMode::Fixed);
  EXPECT_EQ(brush.shape.count, 3);
  EXPECT_EQ(brush.shape.filtering, TextureFiltering::None);
  EXPECT_TRUE(brush.shape.randomized);
  ASSERT_TRUE(brush.shape.pressureRoundness.has_value());
  EXPECT_NEAR(brush.shape.pressureRoundness->evaluate(0.25), 0.45, 1e-9);
  EXPECT_EQ(brush.grain.texture, "grain.png");
  EXPECT_DOUBLE_EQ(brush.grain.movement, 0);
  EXPECT_EQ(brush.grain.blend, GrainBlend::Height);
  ASSERT_TRUE(brush.dynamics.size.pressure.has_value());
  EXPECT_NEAR(brush.dynamics.size.pressure->evaluate(0.5), 0.2, 1e-9);
  EXPECT_DOUBLE_EQ(brush.dynamics.size.minimum, 0.2);
  EXPECT_DOUBLE_EQ(brush.dynamics.size.speed, -0.5);
  EXPECT_DOUBLE_EQ(brush.dynamics.tiltSizeCompression, 0.3);
  EXPECT_DOUBLE_EQ(brush.dynamics.tiltGradation, 0.8);
  EXPECT_TRUE(brush.dynamics.fingerPressureFromSpeed);
  EXPECT_DOUBLE_EQ(brush.dynamics.bleed.pressureAmount, 0.5);
  EXPECT_DOUBLE_EQ(brush.taper.stylus.end, 60);
  EXPECT_TRUE(brush.taper.finger.relative);
  EXPECT_TRUE(brush.taper.classic);
  EXPECT_EQ(brush.render.accumulation, Accumulation::Wash);
  EXPECT_DOUBLE_EQ(brush.limits.maxSize, 120);
  EXPECT_TRUE(brush.orientToScreen);
}

TEST(BrushJsonTest, MovementOverridesTheGrainMode) {
  const BrushParameters brush = parse(
      R"({ "format": 1, "grain": { "texture": "g.png", "mode": "moving", "movement": 0.3 } })");
  EXPECT_DOUBLE_EQ(brush.grain.movement, 0.3);
  EXPECT_FALSE(brush.grain.moving());
}

TEST(BrushJsonTest, AzimuthToggleMeansPenAzimuthRotation) {
  const BrushParameters brush = parse(R"({ "format": 1, "shape": { "azimuth": true } })");
  EXPECT_EQ(brush.shape.rotationMode, RotationMode::PenAzimuth);
}

TEST(BrushJsonTest, ClampsNumbersToTheirRanges) {
  const BrushParameters brush = parse(
      R"({ "format": 1, "path": { "spacing": 9 }, "shape": { "count": 40, "roundness": 0 } })");
  EXPECT_DOUBLE_EQ(brush.path.spacing, 2);
  EXPECT_EQ(brush.shape.count, 16);
  EXPECT_DOUBLE_EQ(brush.shape.roundness, 0.05);
}

TEST(BrushJsonTest, ReadsColorDynamics) {
  const BrushParameters brush = parse(R"({
    "format": 1,
    "colorDynamics": { "hueJitter": 0.2, "darknessJitter": 3, "strokeSaturationJitter": 0.4,
                       "pressureBrightness": -0.5, "tiltHue": -2, "secondaryColorByPressure": 0.7,
                       "pressureColorCurve": "curve:soft", "strokeDarknessJitter": 0.3,
                       "tiltSecondaryColor": 0.6, "secondaryColorJitter": 0.1,
                       "strokeSecondaryColorJitter": -1 } })");
  const ColorDynamicsParameters& color = brush.colorDynamics;
  EXPECT_DOUBLE_EQ(color.hueJitter, 0.2);
  EXPECT_DOUBLE_EQ(color.darknessJitter, 1);
  EXPECT_DOUBLE_EQ(color.strokeSaturationJitter, 0.4);
  EXPECT_DOUBLE_EQ(color.pressureBrightness, -0.5);
  EXPECT_DOUBLE_EQ(color.tiltHue, -1);
  EXPECT_DOUBLE_EQ(color.secondaryColorByPressure, 0.7);
  EXPECT_DOUBLE_EQ(color.strokeDarknessJitter, 0.3);
  EXPECT_DOUBLE_EQ(color.tiltSecondaryColor, 0.6);
  EXPECT_DOUBLE_EQ(color.secondaryColorJitter, 0.1);
  EXPECT_DOUBLE_EQ(color.strokeSecondaryColorJitter, 0);
  ASSERT_TRUE(color.pressureCurve.has_value());
  EXPECT_TRUE(color.changesHsb());
  EXPECT_FALSE(parse(R"({ "format": 1 })").colorDynamics.changesHsb());
}

TEST(BrushJsonTest, ReadsSmudgeAndWetMix) {
  const BrushParameters brush = parse(R"({
    "format": 1, "properties": { "smudge": 0.6 },
    "smudge": { "pickUp": 0.3, "length": 0.9, "useBrushColor": 0.2 },
    "wetMix": { "enabled": true, "dilution": 0.4, "charge": 0.7, "attack": 0.5, "pull": 0.6,
                "grade": 0.1, "blur": 0.2, "blurJitter": 0.3, "wetnessJitter": 0.4 } })");
  EXPECT_DOUBLE_EQ(brush.smudge.strength, 0.6);
  EXPECT_DOUBLE_EQ(brush.smudge.pickUp, 0.3);
  EXPECT_DOUBLE_EQ(brush.smudge.length, 0.9);
  EXPECT_DOUBLE_EQ(brush.smudge.useBrushColor, 0.2);
  EXPECT_TRUE(brush.wetMix.enabled);
  EXPECT_DOUBLE_EQ(brush.wetMix.charge, 0.7);
  EXPECT_DOUBLE_EQ(brush.wetMix.wetnessJitter, 0.4);
  EXPECT_DOUBLE_EQ(parse(R"({ "format": 1, "smudge": { "strength": 0.5 } })").smudge.strength, 0.5);
  EXPECT_FALSE(parse(R"({ "format": 1 })").wetMix.enabled);
}

TEST(BrushJsonTest, IgnoresUnknownFieldsAndSections) {
  const BrushParameters brush = parse(
      R"({ "format": 1, "futureField": [1, 2], "particles": { "rate": 0.5 },
           "shape": { "somethingNew": true } })");
  EXPECT_DOUBLE_EQ(brush.path.spacing, 0.1);
}

TEST(BrushJsonTest, RejectsInvalidDocuments) {
  EXPECT_NE(errorOf(R"({ "format": 2 })").find("format"), std::string::npos);
  EXPECT_NE(errorOf(R"({ "format": 1, "path": { "spacing": "wide" } })").find("path.spacing"),
            std::string::npos);
  EXPECT_NE(errorOf(R"({ "format": 1, "dynamics": { "size": { "pressure": "curve:nope" } } })")
                .find("unknown curve"),
            std::string::npos);
  EXPECT_NE(
      errorOf(R"({ "format": 1, "render": { "accumulation": "paint" } })").find("unknown value"),
      std::string::npos);
  EXPECT_FALSE(errorOf("not json").empty());
  EXPECT_FALSE(errorOf("[1, 2]").empty());
}

}  // namespace
}  // namespace nanquim::stroke
