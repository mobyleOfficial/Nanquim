#include "color/color_profile.h"

#include <gtest/gtest.h>

#include <array>
#include <cstdlib>
#include <string>
#include <vector>

#include "core/half_float.h"
#include "io/file_system.h"

namespace nanquim::color {
namespace {

std::vector<uint8_t> bundled(const std::string& name) {
  const auto bytes =
      io::readFile(io::joinPath(NANQUIM_PROFILES_DIRECTORY, name), kMaximumProfileBytes);
  EXPECT_TRUE(bytes.has_value()) << name;
  return bytes.value_or(std::vector<uint8_t>{});
}

int32_t largestDifference(const std::vector<uint8_t>& left, const std::vector<uint8_t>& right) {
  int32_t largest = 0;
  for (size_t index = 0; index < left.size(); ++index) {
    largest = std::max(largest, std::abs(static_cast<int32_t>(left[index]) - right[index]));
  }
  return largest;
}

TEST(ColorProfile, ReadsTheBundledProfiles) {
  for (const char* name :
       {"sRGB-v4.icc", "DisplayP3-v4.icc", "AdobeCompat-v4.icc", "Rec2020-v4.icc"}) {
    const std::vector<uint8_t> bytes = bundled(name);
    std::string error;
    const auto info = readProfile(bytes.data(), bytes.size(), error);
    ASSERT_TRUE(info) << name << ": " << error;
    EXPECT_TRUE(info->isDocumentProfile()) << name;
    EXPECT_EQ(info->majorVersion, 4) << name;
    EXPECT_FALSE(info->description.empty()) << name;
  }
  const std::vector<uint8_t> cmyk = bundled("CGATS001Compat-v2-micro.icc");
  std::string error;
  const auto info = readProfile(cmyk.data(), cmyk.size(), error);
  ASSERT_TRUE(info) << error;
  EXPECT_EQ(info->colorSpace, ProfileColorSpace::Cmyk);
  EXPECT_TRUE(info->convertsFrom);
  EXPECT_FALSE(info->convertsTo);
  EXPECT_FALSE(info->isDocumentProfile());
}

TEST(ColorProfile, BundledCmykProfileConvertsBothWays) {
  const std::vector<uint8_t> bytes = bundled("NanquimCMYK-v4.icc");
  std::string error;
  const auto info = readProfile(bytes.data(), bytes.size(), error);
  ASSERT_TRUE(info) << error;
  EXPECT_EQ(info->colorSpace, ProfileColorSpace::Cmyk);
  EXPECT_TRUE(info->convertsFrom);
  EXPECT_TRUE(info->convertsTo);
  EXPECT_EQ(info->description, "Nanquim CMYK (CGATS TR 001)");
}

TEST(ColorProfile, CmykColorsRoundTripInsideTheGamut) {
  std::string error;
  const auto converter = CmykColorConverter::create(bundled("AdobeCompat-v4.icc"),
                                                    bundled("NanquimCMYK-v4.icc"), error);
  ASSERT_NE(converter, nullptr) << error;
  const auto paper = converter->toCmyk({1.0F, 1.0F, 1.0F});
  for (const float ink : paper) {
    EXPECT_LT(ink, 0.005F);
  }
  const auto black = converter->toCmyk({0.0F, 0.0F, 0.0F});
  EXPECT_GT(black[3], 0.95F);
  EXPECT_LE(black[0] + black[1] + black[2] + black[3], 3.01F);
  for (const std::array<float, 4>& ink :
       std::vector<std::array<float, 4>>{{0.6F, 0.1F, 0.1F, 0.0F},
                                         {0.1F, 0.6F, 0.1F, 0.0F},
                                         {0.1F, 0.1F, 0.6F, 0.0F},
                                         {0.2F, 0.5F, 0.6F, 0.0F},
                                         {0.0F, 0.0F, 0.0F, 0.5F},
                                         {0.3F, 0.3F, 0.3F, 0.3F}}) {
    const auto rgb = converter->toRgb(ink);
    const auto again = converter->toRgb(converter->toCmyk(rgb));
    for (size_t channel = 0; channel < 3; ++channel) {
      EXPECT_NEAR(again[channel], rgb[channel], 0.02F);
    }
  }
  const auto green = converter->toRgb(converter->toCmyk({0.0F, 1.0F, 0.0F}));
  EXPECT_GT(green[0] + green[2], 0.1F);
  EXPECT_EQ(
      CmykColorConverter::create(bundled("AdobeCompat-v4.icc"), bundled("sRGB-v4.icc"), error),
      nullptr);
  EXPECT_EQ(CmykColorConverter::create(bundled("AdobeCompat-v4.icc"),
                                       bundled("CGATS001Compat-v2-micro.icc"), error),
            nullptr);
}

TEST(ColorProfile, RefusesWhatIsNotAProfile) {
  std::string error;
  const std::vector<uint8_t> garbage(400, 0x5A);
  EXPECT_FALSE(readProfile(garbage.data(), garbage.size(), error));
  EXPECT_FALSE(error.empty());
  EXPECT_FALSE(readProfile(garbage.data(), 10, error));
  const std::vector<uint8_t> profile = bundled("DisplayP3-v4.icc");
  EXPECT_FALSE(readProfile(profile.data(), profile.size() / 2, error));
  EXPECT_EQ(ProfileTransform::create({}, bundled("CGATS001Compat-v2-micro.icc"), error), nullptr);
}

TEST(ColorProfile, ConvertsRgba8BetweenProfiles) {
  std::string error;
  const auto toP3 = ProfileTransform::create({}, bundled("DisplayP3-v4.icc"), error);
  ASSERT_NE(toP3, nullptr) << error;
  const auto toSrgb = ProfileTransform::create(bundled("DisplayP3-v4.icc"), {}, error);
  ASSERT_NE(toSrgb, nullptr) << error;
  const std::vector<uint8_t> srgb{255, 0,   0,   255, 0, 0, 0, 0,   40,  90, 160, 255,
                                  255, 255, 255, 255, 0, 0, 0, 255, 100, 50, 25,  128};
  std::vector<uint8_t> pixels = srgb;
  toP3->convertRgba8(pixels.data(), pixels.size() / 4);
  EXPECT_NEAR(pixels[0], 234, 1);
  EXPECT_NEAR(pixels[1], 51, 1);
  EXPECT_NEAR(pixels[2], 35, 1);
  for (size_t pixel = 0; pixel < pixels.size() / 4; ++pixel) {
    EXPECT_EQ(pixels[pixel * 4 + 3], srgb[pixel * 4 + 3]);
  }
  EXPECT_EQ(pixels[4] + pixels[5] + pixels[6], 0);
  EXPECT_EQ(pixels[12], 255);
  EXPECT_EQ(pixels[16], 0);
  toSrgb->convertRgba8(pixels.data(), pixels.size() / 4);
  EXPECT_LE(largestDifference(pixels, srgb), 2);
}

TEST(ColorProfile, ConvertsLinearHalfBetweenProfiles) {
  std::string error;
  const auto toAdobe = ProfileTransform::create({}, bundled("AdobeCompat-v4.icc"), error);
  ASSERT_NE(toAdobe, nullptr) << error;
  const auto toSrgb = ProfileTransform::create(bundled("AdobeCompat-v4.icc"), {}, error);
  ASSERT_NE(toSrgb, nullptr) << error;
  const std::array<float, 8> linear{0.8F, 0.2F, 0.05F, 1.0F, 0.1F, 0.05F, 0.02F, 0.25F};
  std::vector<uint8_t> halves(16);
  for (size_t index = 0; index < linear.size(); ++index) {
    const uint16_t half = core::floatToHalf(linear[index]);
    std::memcpy(halves.data() + index * 2, &half, 2);
  }
  const std::vector<uint8_t> original = halves;
  toAdobe->convertLinearHalf(halves.data(), 2);
  toSrgb->convertLinearHalf(halves.data(), 2);
  for (size_t index = 0; index < linear.size(); ++index) {
    uint16_t before = 0;
    uint16_t after = 0;
    std::memcpy(&before, original.data() + index * 2, 2);
    std::memcpy(&after, halves.data() + index * 2, 2);
    EXPECT_NEAR(core::halfToFloat(after), core::halfToFloat(before), 2e-3F) << index;
  }
}

}  // namespace
}  // namespace nanquim::color
