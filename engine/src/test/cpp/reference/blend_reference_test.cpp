#include "blend_reference.h"

#include <gtest/gtest.h>

#include <cmath>

namespace nanquim::reference {
namespace {

using canvas::BlendMode;

constexpr double kTolerance = 1e-12;

TEST(BlendReferenceTest, SeparableModesMatchTheirFormulas) {
  EXPECT_NEAR(blendSeparable(BlendMode::Multiply, 0.5, 0.4), 0.2, kTolerance);
  EXPECT_NEAR(blendSeparable(BlendMode::Screen, 0.5, 0.4), 0.7, kTolerance);
  EXPECT_NEAR(blendSeparable(BlendMode::Overlay, 0.25, 0.8),
              blendSeparable(BlendMode::HardLight, 0.8, 0.25), kTolerance);
  EXPECT_NEAR(blendSeparable(BlendMode::HardLight, 0.5, 0.25), 0.25, kTolerance);
  EXPECT_NEAR(blendSeparable(BlendMode::HardLight, 0.5, 0.75), 0.75, kTolerance);
  EXPECT_NEAR(blendSeparable(BlendMode::SoftLight, 0.16, 0.75), 0.16 + 0.5 * (0.398336 - 0.16),
              1e-12);
  EXPECT_NEAR(blendSeparable(BlendMode::SoftLight, 0.64, 0.75), 0.64 + 0.5 * (0.8 - 0.64), kTolerance);
  EXPECT_NEAR(blendSeparable(BlendMode::SoftLight, 0.5, 0.25), 0.5 - 0.5 * 0.5 * 0.5, kTolerance);
  EXPECT_EQ(blendSeparable(BlendMode::ColorDodge, 0, 1), 0);
  EXPECT_EQ(blendSeparable(BlendMode::ColorDodge, 0.3, 1), 1);
  EXPECT_NEAR(blendSeparable(BlendMode::ColorDodge, 0.3, 0.5), 0.6, kTolerance);
  EXPECT_EQ(blendSeparable(BlendMode::ColorBurn, 1, 0), 1);
  EXPECT_EQ(blendSeparable(BlendMode::ColorBurn, 0.5, 0), 0);
  EXPECT_NEAR(blendSeparable(BlendMode::ColorBurn, 0.75, 0.5), 0.5, kTolerance);
  EXPECT_NEAR(blendSeparable(BlendMode::Difference, 0.2, 0.7), 0.5, kTolerance);
  EXPECT_NEAR(blendSeparable(BlendMode::Exclusion, 0.5, 0.5), 0.5, kTolerance);
  EXPECT_EQ(blendSeparable(BlendMode::Add, 0.7, 0.6), 1);
  EXPECT_EQ(blendSeparable(BlendMode::Subtract, 0.3, 0.6), 0);
  EXPECT_EQ(blendSeparable(BlendMode::Divide, 0, 0), 0);
  EXPECT_EQ(blendSeparable(BlendMode::Divide, 0.2, 0), 1);
  EXPECT_NEAR(blendSeparable(BlendMode::Divide, 0.2, 0.4), 0.5, kTolerance);
  EXPECT_NEAR(blendSeparable(BlendMode::LinearBurn, 0.7, 0.6), 0.3, kTolerance);
  EXPECT_NEAR(blendSeparable(BlendMode::VividLight, 0.75, 0.25), 0.5, kTolerance);
  EXPECT_NEAR(blendSeparable(BlendMode::LinearLight, 0.5, 0.6), 0.7, kTolerance);
  EXPECT_NEAR(blendSeparable(BlendMode::PinLight, 0.9, 0.25), 0.5, kTolerance);
  EXPECT_NEAR(blendSeparable(BlendMode::PinLight, 0.1, 0.75), 0.5, kTolerance);
  EXPECT_EQ(blendSeparable(BlendMode::HardMix, 0.5, 0.5), 1);
  EXPECT_EQ(blendSeparable(BlendMode::HardMix, 0.5, 0.49), 0);
}

TEST(BlendReferenceTest, NonSeparableModesKeepTheRightComponents) {
  const Rgb gray{0.5, 0.5, 0.5};
  const Rgb red{1, 0, 0};
  const Rgb luminosityResult = blendColor(BlendMode::Luminosity, gray, red);
  EXPECT_NEAR(luminosity(luminosityResult), 0.3, kTolerance);
  EXPECT_NEAR(luminosityResult[0], 0.3, kTolerance);
  const Rgb colorResult = blendColor(BlendMode::Color, gray, red);
  EXPECT_NEAR(luminosity(colorResult), 0.5, kTolerance);
  EXPECT_GT(colorResult[0], colorResult[1]);
  EXPECT_NEAR(colorResult[1], colorResult[2], kTolerance);
  const Rgb saturationResult = blendColor(BlendMode::Saturation, red, gray);
  EXPECT_NEAR(saturationResult[0], saturationResult[1], kTolerance);
  EXPECT_NEAR(saturationResult[1], saturationResult[2], kTolerance);
  EXPECT_NEAR(luminosity(saturationResult), 0.3, kTolerance);
  const Rgb hueResult = blendColor(BlendMode::Hue, red, gray);
  EXPECT_NEAR(luminosity(hueResult), 0.3, kTolerance);
  EXPECT_EQ(blendColor(BlendMode::DarkerColor, gray, red), red);
  EXPECT_EQ(blendColor(BlendMode::LighterColor, gray, red), gray);
}

TEST(BlendReferenceTest, CompositeHandlesTransparentInputs) {
  const Rgba source{0.2, 0.1, 0.0, 0.5};
  const Rgba transparent{};
  for (int32_t code = 0; code < canvas::kLayerBlendModeCount; ++code) {
    const auto mode = static_cast<BlendMode>(code);
    const Rgba onEmpty = compositePixel(mode, transparent, source);
    EXPECT_NEAR(onEmpty.red, source.red, kTolerance) << code;
    EXPECT_NEAR(onEmpty.alpha, source.alpha, kTolerance) << code;
    const Rgba nothing = compositePixel(mode, source, transparent);
    EXPECT_NEAR(nothing.green, source.green, kTolerance) << code;
    EXPECT_NEAR(nothing.alpha, source.alpha, kTolerance) << code;
  }
  const Rgba product = compositePixel(BlendMode::Multiply, {0.5, 0.5, 0.5, 1}, {0.5, 1, 0, 1});
  EXPECT_NEAR(product.red, 0.25, kTolerance);
  EXPECT_NEAR(product.green, 0.5, kTolerance);
  EXPECT_NEAR(product.blue, 0, kTolerance);
  const Rgba behind = compositePixel(BlendMode::Behind, {0.25, 0, 0, 0.5}, {0, 1, 0, 1});
  EXPECT_NEAR(behind.red, 0.25, kTolerance);
  EXPECT_NEAR(behind.green, 0.5, kTolerance);
  EXPECT_NEAR(behind.alpha, 1, kTolerance);
}

TEST(BlendReferenceTest, ClippedCompositeKeepsTheBaseAlpha) {
  const Rgba base{0.5, 0.5, 0.5, 0.5};
  const Rgba source{1, 0, 0, 1};
  const Rgba clipped = clipCompositePixel(BlendMode::Normal, base, source);
  EXPECT_NEAR(clipped.alpha, 0.5, kTolerance);
  EXPECT_NEAR(clipped.red, 0.5, kTolerance);
  EXPECT_NEAR(clipped.green, 0, kTolerance);
  const Rgba nothing = clipCompositePixel(BlendMode::Normal, Rgba{}, source);
  EXPECT_EQ(nothing.alpha, 0);
  EXPECT_EQ(nothing.red, 0);
}

}  // namespace
}  // namespace nanquim::reference
