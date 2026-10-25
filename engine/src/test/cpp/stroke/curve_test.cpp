#include "stroke/curve.h"

#include <gtest/gtest.h>

namespace nanquim::stroke {
namespace {

TEST(CurveTest, LinearIsTheIdentity) {
  const Curve curve = Curve::linear();
  for (double x = 0; x <= 1.0; x += 0.125) {
    EXPECT_NEAR(curve.evaluate(x), x, 1e-12);
  }
}

TEST(CurveTest, PassesThroughItsPointsAndClampsInput) {
  const Curve curve = Curve::soft();
  EXPECT_NEAR(curve.evaluate(0.25), 0.45, 1e-12);
  EXPECT_NEAR(curve.evaluate(-1), 0.0, 1e-12);
  EXPECT_NEAR(curve.evaluate(2), 1.0, 1e-12);
}

TEST(CurveTest, StaysMonotoneBetweenPoints) {
  const Curve curve({{0.0, 0.0}, {0.1, 0.9}, {0.2, 0.95}, {1.0, 1.0}});
  double previous = curve.evaluate(0);
  for (int step = 1; step <= 1000; ++step) {
    const double value = curve.evaluate(step / 1000.0);
    ASSERT_GE(value, previous - 1e-12) << "at " << step / 1000.0;
    ASSERT_LE(value, 1.0);
    previous = value;
  }
}

TEST(CurveTest, FlatSegmentsStayFlat) {
  const Curve curve({{0.0, 0.0}, {0.4, 0.5}, {0.6, 0.5}, {1.0, 1.0}});
  EXPECT_NEAR(curve.evaluate(0.5), 0.5, 1e-12);
}

TEST(CurveTest, InvalidPointsFallBackToLinear) {
  const Curve unsorted({{0.0, 0.0}, {0.7, 0.2}, {0.5, 0.4}, {1.0, 1.0}});
  const Curve openEnded({{0.1, 0.0}, {1.0, 1.0}});
  const Curve outOfRange({{0.0, 0.0}, {0.5, 1.5}, {1.0, 1.0}});
  for (const Curve* curve : {&unsorted, &openEnded, &outOfRange}) {
    EXPECT_NEAR(curve->evaluate(0.3), 0.3, 1e-12);
  }
}

}  // namespace
}  // namespace nanquim::stroke
