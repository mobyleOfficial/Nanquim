#include "stroke/color_dynamics.h"

#include <gtest/gtest.h>

namespace nanquim::stroke {
namespace {

void expectColor(const std::array<double, 4>& actual, const std::array<double, 4>& expected) {
  for (size_t channel = 0; channel < 4; ++channel) {
    EXPECT_NEAR(actual[channel], expected[channel], 1e-9) << "channel " << channel;
  }
}

TEST(ColorDynamicsTest, HsbRoundTrip) {
  const std::array<std::array<double, 3>, 6> colors{{{1, 0, 0},
                                                     {0.2, 0.7, 0.4},
                                                     {0.1, 0.3, 0.9},
                                                     {0.9, 0.2, 0.6},
                                                     {0.5, 0.5, 0.5},
                                                     {0, 0, 0}}};
  for (const auto& color : colors) {
    const std::array<double, 3> back = rgbFromHsb(hsbFromRgb(color));
    for (size_t channel = 0; channel < 3; ++channel) {
      EXPECT_NEAR(back[channel], color[channel], 1e-12);
    }
  }
  const Hsb green = hsbFromRgb({0, 1, 0});
  EXPECT_NEAR(green.hue, 1.0 / 3.0, 1e-12);
  EXPECT_DOUBLE_EQ(green.saturation, 1);
  EXPECT_DOUBLE_EQ(green.brightness, 1);
}

TEST(ColorDynamicsTest, WithoutDynamicsThePrimaryIsUnchanged) {
  const std::array<double, 4> primary{0.123, 0.456, 0.789, 1};
  expectColor(dynamicColor({}, primary, {1, 1, 1}, 0.7, 0.3, {0.5, 0.5, 0.5, 0.5},
                           {-0.5, 0.5, 0.5, 0.5}),
              primary);
}

TEST(ColorDynamicsTest, SecondaryColorByPressureMixesTowardTheSecondary) {
  ColorDynamicsParameters dynamics;
  dynamics.secondaryColorByPressure = 1;
  const std::array<double, 4> primary{1, 0, 0, 1};
  expectColor(dynamicColor(dynamics, primary, {0, 0, 1}, 0, 0, {}, {}), primary);
  expectColor(dynamicColor(dynamics, primary, {0, 0, 1}, 0.5, 0, {}, {}), {0.5, 0, 0.5, 1});
  expectColor(dynamicColor(dynamics, primary, {0, 0, 1}, 1, 0, {}, {}), {0, 0, 1, 1});
  dynamics.pressureCurve = Curve({{0, 0}, {1, 0}});
  expectColor(dynamicColor(dynamics, primary, {0, 0, 1}, 1, 0, {}, {}), primary);
}

TEST(ColorDynamicsTest, HueWrapsAndSaturationAndBrightnessClamp) {
  ColorDynamicsParameters dynamics;
  dynamics.pressureHue = 1;
  expectColor(dynamicColor(dynamics, {1, 0, 0, 1}, {}, 1, 0, {}, {}), {0, 1, 1, 1});
  dynamics = {};
  dynamics.tiltSaturation = -1;
  dynamics.brightnessJitter = 1;
  expectColor(dynamicColor(dynamics, {0.5, 0, 0, 0.8}, {}, 0, 1, {}, {0, 0, 1, 0}),
              {1, 1, 1, 0.8});
}

TEST(ColorDynamicsTest, DarknessJitterOnlyDarkens) {
  ColorDynamicsParameters dynamics;
  dynamics.darknessJitter = 0.5;
  expectColor(dynamicColor(dynamics, {0.8, 0.4, 0, 1}, {}, 1, 0, {}, {0, 0, 0, 0}),
              {0.8, 0.4, 0, 1});
  expectColor(dynamicColor(dynamics, {0.8, 0.4, 0, 1}, {}, 1, 0, {}, {0, 0, 0, 1}),
              {0.4, 0.2, 0, 1});
}

TEST(ColorDynamicsTest, TiltAndJitterMixTowardTheSecondary) {
  ColorDynamicsParameters dynamics;
  dynamics.tiltSecondaryColor = 0.5;
  dynamics.secondaryColorJitter = 0.5;
  dynamics.strokeSecondaryColorJitter = 1;
  const std::array<double, 4> primary{1, 0, 0, 1};
  expectColor(dynamicColor(dynamics, primary, {0, 0, 1}, 0, 1, {}, {0, 0, 0, 0, 0.5}),
              {0.25, 0, 0.75, 1});
  expectColor(dynamicColor(dynamics, primary, {0, 0, 1}, 0, 1, {0, 0, 0, 0, 1}, {}),
              {0, 0, 1, 1});
}

TEST(ColorDynamicsTest, StrokeDarknessScalesTheBrightness) {
  ColorDynamicsParameters dynamics;
  dynamics.strokeDarknessJitter = 1;
  dynamics.darknessJitter = 1;
  expectColor(dynamicColor(dynamics, {0.8, 0.4, 0, 1}, {}, 1, 0, {0, 0, 0, 0.5, 0},
                           {0, 0, 0, 0.5, 0}),
              {0.2, 0.1, 0, 1});
}

TEST(ColorDynamicsTest, StrokeJitterShiftsTheHue) {
  ColorDynamicsParameters dynamics;
  dynamics.strokeHueJitter = 1;
  expectColor(dynamicColor(dynamics, {1, 0, 0, 1}, {}, 1, 0, {-1, 0, 0, 0}, {}), {0, 1, 1, 1});
}

}  // namespace
}  // namespace nanquim::stroke
