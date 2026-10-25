#include "core/affine_transform.h"

#include <gtest/gtest.h>

#include <cmath>

namespace nanquim::core {
namespace {

constexpr double kPi = 3.14159265358979323846;

void expectNear(Point actual, Point expected) {
  EXPECT_NEAR(actual.x, expected.x, 1e-9);
  EXPECT_NEAR(actual.y, expected.y, 1e-9);
}

TEST(AffineTransformTest, AppliesTranslationScaleAndRotation) {
  expectNear(AffineTransform::translation(3, -2).apply({1, 1}), {4, -1});
  expectNear(AffineTransform::scale(2, 3).apply({1, 1}), {2, 3});
  expectNear(AffineTransform::rotation(kPi / 2).apply({1, 0}), {0, 1});
}

TEST(AffineTransformTest, ThenAppliesLeftTransformFirst) {
  const AffineTransform scaleThenMove =
      AffineTransform::scale(2, 2).then(AffineTransform::translation(10, 0));

  expectNear(scaleThenMove.apply({1, 1}), {12, 2});
}

TEST(AffineTransformTest, InverseUndoesTheTransform) {
  const AffineTransform transform = AffineTransform::scale(-1.5, 2)
                                        .then(AffineTransform::rotation(0.7))
                                        .then(AffineTransform::translation(100, -40));
  const auto inverse = transform.inverted();

  ASSERT_TRUE(inverse.has_value());
  if (inverse) {
    expectNear(inverse->apply(transform.apply({13, -7})), {13, -7});
  }
  EXPECT_NEAR(transform.scaleFactor(), std::sqrt(3.0), 1e-9);
}

TEST(AffineTransformTest, SingularTransformHasNoInverse) {
  EXPECT_FALSE(AffineTransform::scale(0, 1).inverted().has_value());
}

}  // namespace
}  // namespace nanquim::core
