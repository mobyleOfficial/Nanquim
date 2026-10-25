#include "core/half_float.h"

#include <gtest/gtest.h>

#include <cmath>
#include <limits>

#include "canvas/pixel_format.h"

namespace nanquim::core {
namespace {

TEST(HalfFloat, ExactValuesRoundTrip) {
  for (const float value : {0.0F, 1.0F, -1.0F, 0.5F, 2.0F, 65504.0F, 1.0F / 1024.0F, 6.103515625e-05F,
                            5.9604644775390625e-08F}) {
    EXPECT_EQ(halfToFloat(floatToHalf(value)), value) << value;
  }
  EXPECT_EQ(floatToHalf(1.0F), 0x3C00);
  EXPECT_EQ(floatToHalf(-2.0F), 0xC000);
  EXPECT_EQ(floatToHalf(-0.0F), 0x8000);
  EXPECT_EQ(floatToHalf(5.9604644775390625e-08F), 0x0001);
  EXPECT_EQ(floatToHalf(65504.0F), 0x7BFF);
}

TEST(HalfFloat, RoundsToNearestEven) {
  EXPECT_EQ(floatToHalf(1.0F + std::ldexp(1.0F, -11)), 0x3C00);
  EXPECT_EQ(floatToHalf(1.0F + 3 * std::ldexp(1.0F, -11)), 0x3C02);
  EXPECT_EQ(floatToHalf(1.0F + std::ldexp(1.0F, -11) + std::ldexp(1.0F, -20)), 0x3C01);
  for (int level = 0; level <= 255; ++level) {
    const float value = static_cast<float>(level) / 255.0F;
    const float back = halfToFloat(floatToHalf(value));
    EXPECT_NEAR(back, value, std::max(value, 6.1e-5F) * std::ldexp(1.0F, -11)) << level;
  }
}

TEST(HalfFloat, OutOfRangeAndSpecialValues) {
  EXPECT_EQ(floatToHalf(70000.0F), 0x7C00);
  EXPECT_EQ(floatToHalf(-std::numeric_limits<float>::infinity()), 0xFC00);
  EXPECT_TRUE(std::isnan(halfToFloat(floatToHalf(std::numeric_limits<float>::quiet_NaN()))));
  EXPECT_EQ(floatToHalf(1e-9F), 0x0000);
}

TEST(PixelFormat, SizesAndNames) {
  using canvas::PixelFormat;
  EXPECT_EQ(canvas::tileBytes(PixelFormat::Rgba8PremulSrgb), 256 * 256 * 4);
  EXPECT_EQ(canvas::tileBytes(PixelFormat::Rgba16fPremulLinear), 256 * 256 * 8);
  for (const PixelFormat format : {PixelFormat::Rgba8PremulSrgb, PixelFormat::Rgba16fPremulLinear}) {
    EXPECT_EQ(canvas::parsePixelFormat(canvas::pixelFormatName(format)), format);
  }
  EXPECT_FALSE(canvas::parsePixelFormat("rgba32f").has_value());
}

}  // namespace
}  // namespace nanquim::core
