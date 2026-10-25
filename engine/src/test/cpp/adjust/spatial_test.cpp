#include "adjust/spatial.h"

#include <gtest/gtest.h>

#include <array>
#include <cmath>
#include <numeric>
#include <vector>

namespace nanquim::adjust {
namespace {

constexpr int32_t kWidth = 64;
constexpr int32_t kHeight = 48;

Parameters parametersOf(Filter filter, std::initializer_list<float> values) {
  Parameters parameters;
  parameters.filter = filter;
  std::copy(values.begin(), values.end(), parameters.values.begin());
  return parameters;
}

std::vector<double> uniformImage(const std::array<double, 4>& color) {
  std::vector<double> rgba;
  for (int32_t index = 0; index < kWidth * kHeight; ++index) {
    rgba.insert(rgba.end(), color.begin(), color.end());
  }
  return rgba;
}

std::vector<double> dotImage(int32_t x, int32_t y) {
  std::vector<double> rgba(static_cast<size_t>(kWidth) * kHeight * 4, 0.0);
  for (int32_t row = y; row < y + 2; ++row) {
    for (int32_t column = x; column < x + 2; ++column) {
      std::fill_n(rgba.begin() + (static_cast<ptrdiff_t>(row) * kWidth + column) * 4, 4, 1.0);
    }
  }
  return rgba;
}

double alphaAt(const std::vector<double>& rgba, int32_t x, int32_t y) {
  return rgba[(static_cast<size_t>(y) * kWidth + x) * 4 + 3];
}

double totalAlpha(const std::vector<double>& rgba) {
  double total = 0;
  for (size_t offset = 3; offset < rgba.size(); offset += 4) {
    total += rgba[offset];
  }
  return total;
}

const std::vector<Parameters>& everyFilter() {
  static const std::vector<Parameters> filters{
      parametersOf(Filter::GaussianBlur, {0.3f}),
      parametersOf(Filter::GaussianBlur, {1.0f}),
      parametersOf(Filter::MotionBlur, {0.8f, 0.3f}),
      parametersOf(Filter::PerspectiveBlur, {0.7f, 0.3f, 0.6f, 0, 0}),
      parametersOf(Filter::PerspectiveBlur, {0.7f, 0.3f, 0.6f, 1, 0.25f}),
      parametersOf(Filter::Sharpen, {1.0f}),
  };
  return filters;
}

TEST(Spatial, NoStrengthChangesNothing) {
  const std::vector<double> image = dotImage(20, 17);
  for (const Parameters& parameters :
       {parametersOf(Filter::GaussianBlur, {0}), parametersOf(Filter::MotionBlur, {0, 0.4f}),
        parametersOf(Filter::PerspectiveBlur, {0, 0.5f, 0.5f, 0, 0}),
        parametersOf(Filter::Sharpen, {0})}) {
    EXPECT_EQ(filterImage(parameters, image, kWidth, kHeight), image)
        << filterName(parameters.filter);
  }
}

TEST(Spatial, UniformImagesStayUniform) {
  const std::array<double, 4> color{0.2, 0.4, 0.1, 0.8};
  const std::vector<double> image = uniformImage(color);
  for (const Parameters& parameters : everyFilter()) {
    const std::vector<double> result = filterImage(parameters, image, kWidth, kHeight);
    for (size_t offset = 0; offset < result.size(); ++offset) {
      ASSERT_NEAR(result[offset], color[offset % 4], 1e-9) << filterName(parameters.filter);
    }
  }
}

TEST(Spatial, BlursKeepTheContent) {
  const std::vector<double> image = dotImage(30, 22);
  for (const Parameters& parameters : {parametersOf(Filter::GaussianBlur, {0.4f}),
                                       parametersOf(Filter::MotionBlur, {0.9f, 0.2f})}) {
    const std::vector<double> result = filterImage(parameters, image, kWidth, kHeight);
    EXPECT_NEAR(totalAlpha(result), totalAlpha(image), 0.02) << filterName(parameters.filter);
    EXPECT_LT(alphaAt(result, 30, 22), 0.9) << filterName(parameters.filter);
  }
}

TEST(Spatial, GaussianLevelKeepsSigmaSmall) {
  const SpatialPlan plan = spatialPlan(parametersOf(Filter::GaussianBlur, {1}), 4096, 4096, 0);
  EXPECT_EQ(plan.level, 6);
  EXPECT_TRUE(plan.pyramid);
  ASSERT_EQ(plan.passes.size(), 2U);
  EXPECT_LE(plan.passes[0].weights.size(), static_cast<size_t>(kMaximumGaussianRadius) + 1);
  const double sum =
      std::accumulate(plan.passes[0].weights.begin() + 1, plan.passes[0].weights.end(), 0.0) * 2 +
      plan.passes[0].weights[0];
  EXPECT_NEAR(sum, 1, 1e-12);
  EXPECT_GE(plan.reach, 250);
  EXPECT_EQ(spatialPlan(parametersOf(Filter::GaussianBlur, {0.02f}), 4096, 4096, 0).level, 0);
  EXPECT_EQ(spatialPlan(parametersOf(Filter::GaussianBlur, {0.02f}), 4096, 4096, 2).level, 2);
}

TEST(Spatial, MotionBlurStreaksAlongTheAngle) {
  const std::vector<double> horizontal =
      filterImage(parametersOf(Filter::MotionBlur, {1, 0}), dotImage(30, 22), kWidth, kHeight);
  EXPECT_GT(alphaAt(horizontal, 33, 22), 0);
  EXPECT_GT(alphaAt(horizontal, 28, 22), 0);
  EXPECT_EQ(alphaAt(horizontal, 30, 20), 0);
  EXPECT_EQ(alphaAt(horizontal, 30, 25), 0);
  const std::vector<double> vertical =
      filterImage(parametersOf(Filter::MotionBlur, {1, 0.5f}), dotImage(30, 22), kWidth, kHeight);
  EXPECT_GT(alphaAt(vertical, 30, 25), 0);
  EXPECT_NEAR(alphaAt(vertical, 33, 22), 0, 1e-9);
}

TEST(Spatial, LongStreaksTakeSeveralPasses) {
  const SpatialPlan plan = spatialPlan(parametersOf(Filter::MotionBlur, {1, 0}), 4096, 4096, 0);
  ASSERT_EQ(plan.passes.size(), 2U);
  EXPECT_GE(plan.passes[0].taps * plan.passes[1].taps, 251);
  EXPECT_NEAR(plan.passes[1].step[0], plan.passes[0].step[0] * plan.passes[0].taps, 1e-9);
  const double length = plan.passes[0].step[0] * (plan.passes[0].taps * plan.passes[1].taps - 1);
  EXPECT_NEAR(length, 250, 1e-9);
}

TEST(Spatial, PerspectiveBlurKeepsThePuck) {
  std::vector<double> image(static_cast<size_t>(kWidth) * kHeight * 4, 0.0);
  for (int32_t y = 0; y < kHeight; ++y) {
    for (int32_t x = 0; x < kWidth; ++x) {
      const double value = ((x / 4 + y / 4) % 2 == 0) ? 1.0 : 0.0;
      std::fill_n(image.begin() + (static_cast<ptrdiff_t>(y) * kWidth + x) * 4, 3, value);
      image[(static_cast<size_t>(y) * kWidth + x) * 4 + 3] = 1;
    }
  }
  const Parameters parameters =
      parametersOf(Filter::PerspectiveBlur, {1, 16.5f / kWidth, 24.5f / kHeight, 0, 0});
  const std::vector<double> result = filterImage(parameters, image, kWidth, kHeight);
  const size_t puck = (static_cast<size_t>(24) * kWidth + 16) * 4;
  EXPECT_NEAR(result[puck], image[puck], 1e-6);
  const size_t far = (static_cast<size_t>(45) * kWidth + 60) * 4;
  EXPECT_GT(result[far], 0.05);
  EXPECT_LT(result[far], 0.95);
}

TEST(Spatial, SharpenRaisesEdgeContrast) {
  std::vector<double> image(static_cast<size_t>(kWidth) * kHeight * 4, 0.0);
  for (int32_t y = 0; y < kHeight; ++y) {
    for (int32_t x = 0; x < kWidth; ++x) {
      const double value = x < 32 ? 0.3 : 0.7;
      std::fill_n(image.begin() + (static_cast<ptrdiff_t>(y) * kWidth + x) * 4, 3, value);
      image[(static_cast<size_t>(y) * kWidth + x) * 4 + 3] = 1;
    }
  }
  const std::vector<double> result =
      filterImage(parametersOf(Filter::Sharpen, {1}), image, kWidth, kHeight);
  EXPECT_LT(result[(static_cast<size_t>(10) * kWidth + 31) * 4], 0.3);
  EXPECT_GT(result[(static_cast<size_t>(10) * kWidth + 32) * 4], 0.7);
  EXPECT_NEAR(result[(static_cast<size_t>(10) * kWidth + 10) * 4], 0.3, 1e-9);
  const SpatialPlan plan = spatialPlan(parametersOf(Filter::Sharpen, {1}), kWidth, kHeight, 0);
  const std::array<double, 4> combined =
      combineSpatial(plan, {0.5, 0.5, 0.5, 0.5}, {0, 0, 0, 0}, 1);
  EXPECT_LE(combined[0], combined[3]);
  EXPECT_LE(combined[3], 1);
}

TEST(Spatial, BoxDownsampleAveragesInsideTheCanvas) {
  const std::vector<double> rgba{0, 0, 0, 0, 1, 1, 1, 1, 0.5, 0.5, 0.5, 0.5};
  const WorkImage image = boxDownsample(rgba, 3, 1, 1);
  ASSERT_EQ(image.width, 2);
  EXPECT_NEAR(image.rgba[3], 0.5, 1e-12);
  EXPECT_NEAR(image.rgba[7], 0.5, 1e-12);
  EXPECT_NEAR(boxMapping(1).toTexel(0.5), 0, 1e-12);
}

TEST(Spatial, EffectsWithoutStrengthChangeNothing) {
  const std::vector<double> image = dotImage(20, 17);
  for (const Parameters& parameters :
       {parametersOf(Filter::Bloom, {0, 0.5f, 0.5f}),
        parametersOf(Filter::Glitch, {0, 0.3f, 0.5f, 0.5f, 0}),
        parametersOf(Filter::ChromaticAberration, {0, 0.5f, 0.5f, 0, 0, 0.5f, 0})}) {
    EXPECT_EQ(filterImage(parameters, image, kWidth, kHeight), image)
        << filterName(parameters.filter);
  }
}

TEST(Spatial, DisplacementsKeepFlatColors) {
  const std::array<double, 4> color{0.2, 0.4, 0.1, 0.8};
  const std::vector<double> image = uniformImage(color);
  for (const Parameters& parameters :
       {parametersOf(Filter::Glitch, {1, 0.3f, 0.5f, 0.8f, 0}),
        parametersOf(Filter::Glitch, {1, 0.3f, 0.5f, 0.8f, 1}),
        parametersOf(Filter::Glitch, {1, 0.3f, 0.5f, 0.8f, 2}),
        parametersOf(Filter::Glitch, {1, 0.3f, 0.5f, 0.8f, 3}),
        parametersOf(Filter::ChromaticAberration, {1, 0.3f, 0.6f, 0, 0, 0.5f, 0}),
        parametersOf(Filter::ChromaticAberration, {1, 0.3f, 0.6f, 1, 0.3f, 0, 1})}) {
    const std::vector<double> result = filterImage(parameters, image, kWidth, kHeight);
    for (size_t offset = 0; offset < result.size(); ++offset) {
      ASSERT_NEAR(result[offset], color[offset % 4], 1e-9) << filterName(parameters.filter);
    }
  }
}

TEST(Spatial, ChromaticAberrationSplitsEdges) {
  std::vector<double> image(static_cast<size_t>(kWidth) * kHeight * 4, 0.0);
  for (int32_t y = 0; y < kHeight; ++y) {
    for (int32_t x = 0; x < kWidth; ++x) {
      const double value = x >= 24 && x < 40 ? 1.0 : 0.0;
      std::fill_n(image.begin() + (static_cast<ptrdiff_t>(y) * kWidth + x) * 4, 3, value);
      image[(static_cast<size_t>(y) * kWidth + x) * 4 + 3] = 1;
    }
  }
  const std::vector<double> result =
      filterImage(parametersOf(Filter::ChromaticAberration, {1, 0.5f, 0.5f, 1, 0, 0, 0}), image,
                  kWidth, kHeight);
  const size_t left = (static_cast<size_t>(20) * kWidth + 23) * 4;
  EXPECT_GT(result[left], 0.1);
  EXPECT_LT(result[left + 2], 0.01);
}

TEST(Spatial, BloomBrightensAroundBrightParts) {
  std::vector<double> image(static_cast<size_t>(kWidth) * kHeight * 4, 0.0);
  for (size_t offset = 0; offset < image.size(); offset += 4) {
    image[offset] = image[offset + 1] = image[offset + 2] = 0.1;
    image[offset + 3] = 1;
  }
  for (int32_t y = 22; y < 26; ++y) {
    for (int32_t x = 30; x < 34; ++x) {
      std::fill_n(image.begin() + (static_cast<ptrdiff_t>(y) * kWidth + x) * 4, 4, 1.0);
    }
  }
  const std::vector<double> result =
      filterImage(parametersOf(Filter::Bloom, {1, 0.5f, 0.5f}), image, kWidth, kHeight);
  EXPECT_GT(result[(static_cast<size_t>(24) * kWidth + 35) * 4], 0.15);
  EXPECT_NEAR(result[(static_cast<size_t>(4) * kWidth + 4) * 4], 0.1, 1e-6);
}

TEST(Spatial, HalftoneInksFollowTheColor) {
  const std::vector<double> white = uniformImage({1, 1, 1, 1});
  EXPECT_EQ(filterImage(parametersOf(Filter::Halftone, {0.3f, 0}), white, kWidth, kHeight), white);
  const std::vector<double> black = uniformImage({0, 0, 0, 1});
  const std::vector<double> dots =
      filterImage(parametersOf(Filter::Halftone, {0.3f, 2}), black, kWidth, kHeight);
  double sum = 0;
  for (size_t offset = 0; offset < dots.size(); offset += 4) {
    sum += dots[offset];
  }
  EXPECT_LT(sum / (kWidth * kHeight), 0.2);
  const std::vector<double> clear(static_cast<size_t>(kWidth) * kHeight * 4, 0.0);
  EXPECT_EQ(filterImage(parametersOf(Filter::Halftone, {0.3f, 0}), clear, kWidth, kHeight), clear);
}

}  // namespace
}  // namespace nanquim::adjust
