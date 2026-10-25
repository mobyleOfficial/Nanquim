#include "color/display_lut.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <string>
#include <vector>

#include "color/color_profile.h"
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

std::array<float, 3> entry(const std::vector<uint16_t>& lut, int32_t red, int32_t green,
                           int32_t blue) {
  const size_t index =
      ((static_cast<size_t>(blue) * kDisplayLutSize + green) * kDisplayLutSize + red) * 4;
  return {core::halfToFloat(lut[index]), core::halfToFloat(lut[index + 1]),
          core::halfToFloat(lut[index + 2])};
}

constexpr int32_t kLast = kDisplayLutSize - 1;

TEST(DisplayLut, OnlyNeededAwayFromSrgbOnSrgb) {
  EXPECT_FALSE(needsDisplayLut({}, {}, DisplaySpace::Srgb));
  EXPECT_TRUE(needsDisplayLut({}, {}, DisplaySpace::DisplayP3));
  EXPECT_TRUE(needsDisplayLut(bundled("DisplayP3-v4.icc"), {}, DisplaySpace::Srgb));
}

TEST(DisplayLut, SrgbDocumentOnDisplayP3) {
  std::string error;
  const auto start = std::chrono::steady_clock::now();
  const std::vector<uint16_t> lut = buildDisplayLut({}, {}, DisplaySpace::DisplayP3, error);
  const auto elapsed =
      std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start);
  ASSERT_EQ(lut.size(),
            static_cast<size_t>(kDisplayLutSize * kDisplayLutSize * kDisplayLutSize * 4))
      << error;
  std::printf("33^3 LUT built in %.1f ms\n", elapsed.count());
  const auto red = entry(lut, kLast, 0, 0);
  EXPECT_NEAR(red[0], 0.9175, 0.002);
  EXPECT_NEAR(red[1], 0.2003, 0.002);
  EXPECT_NEAR(red[2], 0.1386, 0.002);
  const auto white = entry(lut, kLast, kLast, kLast);
  const auto black = entry(lut, 0, 0, 0);
  for (size_t channel = 0; channel < 3; ++channel) {
    EXPECT_NEAR(white[channel], 1.0, 0.002);
    EXPECT_NEAR(black[channel], 0.0, 0.002);
  }
  EXPECT_EQ(core::halfToFloat(lut[3]), 1.0F);
}

TEST(DisplayLut, DisplayP3DocumentOnSrgbClips) {
  std::string error;
  const std::vector<uint16_t> lut =
      buildDisplayLut(bundled("DisplayP3-v4.icc"), {}, DisplaySpace::Srgb, error);
  ASSERT_FALSE(lut.empty()) << error;
  const auto red = entry(lut, kLast, 0, 0);
  EXPECT_NEAR(red[0], 1.0, 0.002);
  EXPECT_NEAR(red[1], 0.0, 0.002);
  EXPECT_NEAR(red[2], 0.0, 0.002);
  const auto gray = entry(lut, kLast / 2, kLast / 2, kLast / 2);
  for (const float value : gray) {
    EXPECT_NEAR(value, 0.5, 0.003);
  }
  for (const uint16_t half : lut) {
    const float value = core::halfToFloat(half);
    ASSERT_TRUE(value >= 0.0F && value <= 1.0F);
  }
}

TEST(DisplayLut, DisplayP3DocumentOnDisplayP3IsIdentity) {
  std::string error;
  const std::vector<uint16_t> lut =
      buildDisplayLut(bundled("DisplayP3-v4.icc"), {}, DisplaySpace::DisplayP3, error);
  ASSERT_FALSE(lut.empty()) << error;
  float largest = 0;
  for (int32_t blue = 0; blue < kDisplayLutSize; ++blue) {
    for (int32_t green = 0; green < kDisplayLutSize; ++green) {
      for (int32_t red = 0; red < kDisplayLutSize; ++red) {
        const auto value = entry(lut, red, green, blue);
        const std::array<int32_t, 3> grid{red, green, blue};
        for (size_t channel = 0; channel < 3; ++channel) {
          largest = std::max(largest,
                             std::abs(value[channel] - static_cast<float>(grid[channel]) / kLast));
        }
      }
    }
  }
  EXPECT_LT(largest, 1.0F / 255.0F);
}

TEST(DisplayLut, CmykProofKeepsPaperWhiteAndDullsWhatCannotPrint) {
  std::string error;
  const std::vector<uint8_t> adobe = bundled("AdobeCompat-v4.icc");
  EXPECT_TRUE(needsDisplayLut({}, bundled("NanquimCMYK-v4.icc"), DisplaySpace::Srgb));
  const std::vector<uint16_t> plain = buildDisplayLut(adobe, {}, DisplaySpace::Srgb, error);
  const std::vector<uint16_t> proofed =
      buildDisplayLut(adobe, bundled("NanquimCMYK-v4.icc"), DisplaySpace::Srgb, error);
  ASSERT_EQ(proofed.size(), plain.size()) << error;
  const auto white = entry(proofed, kLast, kLast, kLast);
  const auto gray = entry(proofed, 16, 16, 16);
  const auto plainGray = entry(plain, 16, 16, 16);
  const auto green = entry(proofed, 0, kLast, 0);
  const auto plainGreen = entry(plain, 0, kLast, 0);
  for (size_t channel = 0; channel < 3; ++channel) {
    EXPECT_NEAR(white[channel], 1.0, 0.01);
    EXPECT_NEAR(gray[channel], plainGray[channel], 0.04);
  }
  EXPECT_GT(green[0] + green[2] - plainGreen[0] - plainGreen[2], 0.1);
  EXPECT_TRUE(buildDisplayLut(adobe, bundled("sRGB-v4.icc"), DisplaySpace::Srgb, error).empty());
}

TEST(DisplayLut, RefusesWhatCannotConvert) {
  std::string error;
  EXPECT_TRUE(
      buildDisplayLut(std::vector<uint8_t>(400, 0x5A), {}, DisplaySpace::Srgb, error).empty());
  EXPECT_FALSE(error.empty());
  error.clear();
  EXPECT_TRUE(buildDisplayLut(bundled("CGATS001Compat-v2-micro.icc"), {}, DisplaySpace::Srgb, error)
                  .empty());
  EXPECT_FALSE(error.empty());
}

}  // namespace
}  // namespace nanquim::color
