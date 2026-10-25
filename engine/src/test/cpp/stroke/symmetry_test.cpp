#include "stroke/symmetry.h"

#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "stroke/dab_generator.h"
#include "stroke/tip_coverage.h"

namespace nanquim::stroke {
namespace {

constexpr double kPi = 3.14159265358979323846;

core::AffineTransform reflection(double angle, double x, double y) {
  const double cosine = std::cos(2 * angle);
  const double sine = std::sin(2 * angle);
  return core::AffineTransform{cosine, sine, sine, -cosine, x - cosine * x - sine * y,
                               y - sine * x + cosine * y};
}

core::AffineTransform rotation(double angle, double x, double y) {
  return core::AffineTransform::translation(-x, -y)
      .then(core::AffineTransform::rotation(angle))
      .then(core::AffineTransform::translation(x, y));
}

GrayTexture slope(int32_t size) {
  std::vector<uint8_t> pixels(static_cast<size_t>(size) * size);
  for (int32_t y = 0; y < size; ++y) {
    for (int32_t x = 0; x < size; ++x) {
      pixels[static_cast<size_t>(y) * size + x] =
          static_cast<uint8_t>(std::lround((2.0 * x + y) * 255 / (3.0 * (size - 1))));
    }
  }
  return *GrayTexture::create(size, size, std::move(pixels));
}

Dab sampleDab() {
  Dab dab;
  dab.x = 40;
  dab.y = 30;
  dab.diameter = 24;
  dab.opacity = 1;
  dab.flow = 1;
  dab.angle = 0.4;
  dab.roundness = 0.6;
  dab.flipX = true;
  dab.gradation = 0.5;
  dab.gradationAngle = 1.1;
  return dab;
}

TEST(SymmetryTest, aCopyCoversTheMappedPixelsLikeTheDab) {
  const GrayTexture shape = slope(64);
  TipStyle style;
  style.shape = &shape;
  style.shapeFiltering = TextureFiltering::Classic;
  const Dab dab = sampleDab();
  const std::vector<core::AffineTransform> copies{
      core::AffineTransform{-1, 0, 0, 1, 200, 0}, reflection(kPi / 6, 50, 60),
      rotation(2 * kPi / 5, 100, 80), rotation(kPi, 100, 100)};
  for (const core::AffineTransform& copy : copies) {
    const Dab mapped = symmetricDab(dab, copy);
    EXPECT_EQ(mapped.flipY, copy.determinant() < 0);
    EXPECT_EQ(mapped.flipX, dab.flipX);
    EXPECT_NEAR(mapped.diameter, dab.diameter, 1e-9);
    double covered = 0;
    for (double dy = -14; dy <= 14; dy += 1.37) {
      for (double dx = -14; dx <= 14; dx += 1.37) {
        const double x = dab.x + dx;
        const double y = dab.y + dy;
        const core::Point image = copy.apply({x, y});
        const double expected = dabCoverage(dab, style, x, y);
        EXPECT_NEAR(dabCoverage(mapped, style, image.x, image.y), expected, 1e-6)
            << "at " << dx << ", " << dy << " det " << copy.determinant();
        covered += expected;
      }
    }
    EXPECT_GT(covered, 10.0);
  }
}

TEST(SymmetryTest, copiesFollowEachDabInPlace) {
  std::vector<Dab> dabs(3);
  for (size_t index = 0; index < dabs.size(); ++index) {
    dabs[index].x = static_cast<double>(index);
    dabs[index].index = static_cast<uint32_t>(index);
  }
  const std::vector<core::AffineTransform> copies{core::AffineTransform{},
                                                  core::AffineTransform::translation(100, 0),
                                                  core::AffineTransform::translation(200, 0)};
  replicateDabs(copies, dabs, 1);
  ASSERT_EQ(dabs.size(), 7U);
  EXPECT_EQ(dabs[0].x, 0);
  const std::vector<double> expected{1, 101, 201, 2, 102, 202};
  for (size_t index = 0; index < expected.size(); ++index) {
    EXPECT_EQ(dabs[1 + index].x, expected[index]) << index;
    EXPECT_EQ(dabs[1 + index].index, index < 3 ? 1U : 2U);
  }
  replicateDabs({}, dabs, 0);
  EXPECT_EQ(dabs.size(), 7U);
}

TEST(SymmetryTest, theGeneratorPutsEveryDabDownOncePerCopy) {
  StrokeSettings plain;
  plain.sizePixels = 16;
  plain.seed = 7;
  StrokeSettings mirrored = plain;
  mirrored.symmetry = {core::AffineTransform{}, core::AffineTransform{-1, 0, 0, 1, 512, 0}};
  BrushParameters brush;
  brush.path.spacing = 0.1;
  auto run = [&brush](const StrokeSettings& settings) {
    DabGenerator generator(brush, settings);
    std::vector<Dab> dabs;
    for (int step = 0; step <= 20; ++step) {
      PathPoint point;
      point.x = 60 + 8.0 * step;
      point.y = 100 + 3.0 * step;
      point.pressure = 0.5 + 0.02 * step;
      point.timeMilliseconds = 4.0 * step;
      generator.addPoint(point, dabs);
    }
    generator.finish(dabs);
    return dabs;
  };
  const std::vector<Dab> alone = run(plain);
  const std::vector<Dab> both = run(mirrored);
  ASSERT_FALSE(alone.empty());
  ASSERT_EQ(both.size(), 2 * alone.size());
  for (size_t index = 0; index < alone.size(); ++index) {
    EXPECT_EQ(both[2 * index].x, alone[index].x);
    EXPECT_EQ(both[2 * index].diameter, alone[index].diameter);
    EXPECT_NEAR(both[2 * index + 1].x, 512 - alone[index].x, 1e-9);
    EXPECT_EQ(both[2 * index + 1].y, alone[index].y);
  }
}

}  // namespace
}  // namespace nanquim::stroke
