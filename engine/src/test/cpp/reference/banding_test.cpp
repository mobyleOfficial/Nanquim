#include <gtest/gtest.h>

#include <cmath>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>

#include "canvas/color_conversion.h"
#include "reference_scene.h"
#include "scene/scene.h"

namespace nanquim::reference {
namespace {

constexpr int32_t kStrip = 5;
constexpr int32_t kSteps = 200;
constexpr double kAlpha = 0.03;

double largestError(const std::string& sceneName, bool linear) {
  const std::filesystem::path path =
      std::filesystem::path(NANQUIM_GOLDENS_DIRECTORY) / "scenes" / (sceneName + ".json");
  std::ifstream input(path, std::ios::binary);
  const std::string text{std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
  std::string error;
  const auto scene = scene::parseScene(
      text, [](const std::string&) { return std::nullopt; }, error);
  EXPECT_TRUE(scene.has_value()) << error;
  if (!scene) {
    return 255;
  }
  const Image image = renderScene(*scene);
  double largest = 0;
  for (int32_t step = 0; step <= kSteps; ++step) {
    const double exact = std::pow(1 - kAlpha, step);
    const double expected = (linear ? canvas::linearToSrgbValue(exact) : exact) * 255;
    const double actual = image.pixel(step * kStrip + kStrip / 2, image.height / 2)[0] * 255;
    largest = std::max(largest, std::abs(actual - expected));
  }
  return largest;
}

TEST(Banding, SixteenBitDocumentStaysWithinOneLevel) {
  EXPECT_LE(largestError("deep_banding", true), 1.0);
}

TEST(Banding, EightBitDocumentBands) { EXPECT_GT(largestError("banding", false), 4.0); }

}  // namespace
}  // namespace nanquim::reference
