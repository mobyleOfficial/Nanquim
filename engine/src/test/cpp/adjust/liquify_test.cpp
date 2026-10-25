#include "adjust/liquify.h"

#include <gtest/gtest.h>

#include <cmath>
#include <vector>

namespace nanquim::adjust {
namespace {

constexpr int32_t kWidth = 128;
constexpr int32_t kHeight = 96;

LiquifyBrush brushOf(LiquifyMode mode, double size = 0.5, double momentum = 0) {
  LiquifyBrush brush;
  brush.mode = mode;
  brush.size = size;
  brush.momentum = momentum;
  return brush;
}

LiquifyField::Change stroke(LiquifyField& field, const LiquifyBrush& brush, double x0, double y0,
                            double x1, double y1, int32_t samples = 20) {
  LiquifyStroke liquify(field, brush);
  for (int32_t index = 0; index < samples; ++index) {
    const double t = static_cast<double>(index) / (samples - 1);
    liquify.add({x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, 1, index * 4.0});
  }
  liquify.end();
  liquify.finishMomentum();
  return liquify.finish();
}

double length(const std::array<double, 2>& value) { return std::hypot(value[0], value[1]); }

TEST(Liquify, AnEmptyFieldChangesNothing) {
  std::vector<double> image(static_cast<size_t>(kWidth) * kHeight * 4);
  for (size_t index = 0; index < image.size(); index += 4) {
    const double alpha = static_cast<double>((index * 13) % 97) / 96.0;
    for (size_t channel = 0; channel < 3; ++channel) {
      image[index + channel] = alpha * static_cast<double>(((index + channel) * 37) % 101) / 100.0;
    }
    image[index + 3] = alpha;
  }
  const LiquifyField field(kWidth, kHeight);
  const std::vector<double> result = liquifyImage(field, image, kWidth, kHeight);
  for (size_t index = 0; index < image.size(); ++index) {
    ASSERT_NEAR(result[index], image[index], 1e-12);
  }
  EXPECT_TRUE(field.bounds().isEmpty());
}

TEST(Liquify, PushMovesContentAlongTheStroke) {
  LiquifyField field(kWidth, kHeight);
  stroke(field, brushOf(LiquifyMode::Push), 30, 48, 70, 48);
  const auto offset = field.displacement(60, 48);
  EXPECT_LT(offset[0], -1);
  EXPECT_NEAR(offset[1], 0, 1e-6);
  EXPECT_FALSE(field.bounds().isEmpty());
}

TEST(Liquify, TwirlRightTurnsClockwise) {
  LiquifyField field(kWidth, kHeight);
  stroke(field, brushOf(LiquifyMode::TwirlRight), 64, 48, 64, 48, 10);
  EXPECT_LT(field.displacement(68, 48)[1], 0);
  LiquifyField left(kWidth, kHeight);
  stroke(left, brushOf(LiquifyMode::TwirlLeft), 64, 48, 64, 48, 10);
  EXPECT_GT(left.displacement(68, 48)[1], 0);
}

TEST(Liquify, PinchPullsInAndExpandPushesOut) {
  LiquifyField pinch(kWidth, kHeight);
  stroke(pinch, brushOf(LiquifyMode::Pinch), 64, 48, 64, 48, 10);
  EXPECT_GT(pinch.displacement(68, 48)[0], 0);
  LiquifyField expand(kWidth, kHeight);
  stroke(expand, brushOf(LiquifyMode::Expand), 64, 48, 64, 48, 10);
  EXPECT_LT(expand.displacement(68, 48)[0], 0);
}

TEST(Liquify, ReconstructRestoresTowardZero) {
  LiquifyField field(kWidth, kHeight);
  stroke(field, brushOf(LiquifyMode::Push), 30, 48, 70, 48);
  const double before = length(field.displacement(60, 48));
  stroke(field, brushOf(LiquifyMode::Reconstruct, 0.8), 20, 48, 100, 48);
  EXPECT_LT(length(field.displacement(60, 48)), before * 0.9);
}

TEST(Liquify, CrystalsAreSeeded) {
  LiquifyField first(kWidth, kHeight);
  LiquifyField second(kWidth, kHeight);
  stroke(first, brushOf(LiquifyMode::Crystals), 64, 48, 64, 48, 5);
  stroke(second, brushOf(LiquifyMode::Crystals), 64, 48, 64, 48, 5);
  EXPECT_EQ(first.data(), second.data());
  EXPECT_GT(length(first.displacement(64, 52)), 0);
}

TEST(Liquify, UndoRestoresTheFieldExactly) {
  LiquifyField field(kWidth, kHeight);
  stroke(field, brushOf(LiquifyMode::Push), 30, 48, 70, 48);
  const std::vector<float> afterFirst = field.data();
  const LiquifyField::Change change =
      stroke(field, brushOf(LiquifyMode::TwirlLeft), 50, 40, 80, 60);
  const std::vector<float> afterSecond = field.data();
  field.apply(change, true);
  EXPECT_EQ(field.data(), afterFirst);
  field.apply(change, false);
  EXPECT_EQ(field.data(), afterSecond);
}

TEST(Liquify, MomentumKeepsGoing) {
  LiquifyField still(kWidth, kHeight);
  LiquifyField moving(kWidth, kHeight);
  stroke(still, brushOf(LiquifyMode::Push, 0.3), 20, 48, 50, 48);
  stroke(moving, brushOf(LiquifyMode::Push, 0.3), 20, 48, 50, 48, 20);
  stroke(moving, brushOf(LiquifyMode::Push, 0.3, 1), 20, 20, 50, 20, 20);
  EXPECT_EQ(length(still.displacement(70, 20)), 0);
  EXPECT_GT(length(moving.displacement(70, 20)), 0);
}

TEST(Liquify, SourcesStayInsideTheCanvas) {
  LiquifyField field(kWidth, kHeight);
  stroke(field, brushOf(LiquifyMode::Push, 1), 0, 48, 127, 48, 40);
  for (int32_t row = 0; row < field.height(); ++row) {
    for (int32_t column = 0; column < field.width(); ++column) {
      const auto value = field.at(column, row);
      if (value[0] == 0 && value[1] == 0) {
        continue;
      }
      const double x = column * kLiquifyFieldSpacing + value[0];
      const double y = row * kLiquifyFieldSpacing + value[1];
      ASSERT_GE(x, -1e-4);
      ASSERT_LE(x, kWidth - 1 + 1e-4);
      ASSERT_GE(y, -1e-4);
      ASSERT_LE(y, kHeight - 1 + 1e-4);
    }
  }
}

}  // namespace
}  // namespace nanquim::adjust
