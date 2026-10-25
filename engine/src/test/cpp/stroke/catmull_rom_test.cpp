#include "stroke/catmull_rom.h"

#include <gtest/gtest.h>

#include <cmath>

namespace nanquim::stroke {
namespace {

PathPoint point(double x, double y, double pressure = 1) {
  PathPoint result;
  result.x = x;
  result.y = y;
  result.pressure = pressure;
  return result;
}

TEST(CatmullRomTest, CollinearPointsGiveAStraightSegment) {
  const CatmullRomSegment segment(point(0, 0), point(10, 0), point(20, 0), point(30, 0));
  EXPECT_NEAR(segment.length(), 10.0, 1e-9);
  for (double distance = 0; distance <= 10; distance += 2.5) {
    const PathPoint at = segment.pointAtDistance(distance);
    EXPECT_NEAR(at.x, 10 + distance, 1e-6);
    EXPECT_NEAR(at.y, 0, 1e-9);
  }
  const auto tangent = segment.tangentAtDistance(5);
  EXPECT_NEAR(tangent[0], 1, 1e-9);
  EXPECT_NEAR(tangent[1], 0, 1e-9);
}

TEST(CatmullRomTest, InterpolatesItsEndpoints) {
  const CatmullRomSegment segment(point(0, 0), point(10, 5), point(30, -5), point(35, 20));
  const PathPoint start = segment.pointAtDistance(0);
  const PathPoint end = segment.pointAtDistance(segment.length());
  EXPECT_NEAR(start.x, 10, 1e-9);
  EXPECT_NEAR(start.y, 5, 1e-9);
  EXPECT_NEAR(end.x, 30, 1e-9);
  EXPECT_NEAR(end.y, -5, 1e-9);
}

TEST(CatmullRomTest, ArcLengthMatchesAQuarterCircle) {
  constexpr double kRadius = 100;
  constexpr double kPi = 3.14159265358979323846;
  auto onCircle = [](double degrees) {
    const double radians = degrees * kPi / 180;
    return point(kRadius * std::cos(radians), kRadius * std::sin(radians));
  };
  const CatmullRomSegment segment(onCircle(-30), onCircle(0), onCircle(30), onCircle(60));
  const double arc = kRadius * kPi / 6;
  EXPECT_NEAR(segment.length(), arc, arc * 0.01);
}

TEST(CatmullRomTest, ArcLengthParameterizationIsUniform) {
  const CatmullRomSegment segment(point(0, 0), point(10, 40), point(80, 10), point(90, 60));
  const int steps = 50;
  const double step = segment.length() / steps;
  for (int index = 0; index < steps; ++index) {
    const PathPoint from = segment.pointAtDistance(index * step);
    const PathPoint to = segment.pointAtDistance((index + 1) * step);
    EXPECT_NEAR(std::hypot(to.x - from.x, to.y - from.y), step, 0.3);
  }
}

TEST(CatmullRomTest, AttributesAreLinearInArcLength) {
  const CatmullRomSegment segment(point(0, 0, 0), point(10, 0, 0.2), point(20, 0, 0.6),
                                  point(30, 0, 1));
  EXPECT_NEAR(segment.pointAtDistance(5).pressure, 0.4, 1e-9);
}

TEST(CatmullRomTest, DuplicatedEndpointsAreWellDefined) {
  const CatmullRomSegment first(point(0, 0), point(0, 0), point(10, 10), point(20, 0));
  const CatmullRomSegment last(point(0, 0), point(10, 10), point(20, 0), point(20, 0));
  const CatmullRomSegment empty(point(0, 0), point(5, 5), point(5, 5), point(9, 9));
  EXPECT_TRUE(std::isfinite(first.length()));
  EXPECT_GT(first.length(), 14);
  EXPECT_TRUE(std::isfinite(last.length()));
  EXPECT_NEAR(last.pointAtDistance(last.length()).x, 20, 1e-9);
  EXPECT_EQ(empty.length(), 0);
}

}  // namespace
}  // namespace nanquim::stroke
