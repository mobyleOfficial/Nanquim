#include "stroke/dab_generator.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>

#include "stroke/test_traces.h"

namespace nanquim::stroke {
namespace {

PathPoint point(double x, double y, double pressure = 1) {
  PathPoint result;
  result.x = x;
  result.y = y;
  result.pressure = pressure;
  return result;
}

StrokeSettings settings(double sizePixels = 20, uint64_t seed = 1) {
  StrokeSettings result;
  result.sizePixels = sizePixels;
  result.seed = seed;
  return result;
}

std::vector<Dab> run(const BrushParameters& brush, const StrokeSettings& strokeSettings,
                     const std::vector<InputSample>& samples) {
  DabGenerator generator(brush, strokeSettings);
  std::vector<Dab> dabs;
  for (const InputSample& sample : samples) {
    generator.addPoint(pathPointFrom(sample), dabs);
  }
  generator.finish(dabs);
  return dabs;
}

double spacing(const BrushParameters& brush, double diameter) {
  return std::max(0.5, brush.path.spacing * diameter);
}

TEST(DabGeneratorTest, FirstDabIsAtTheStrokeStart) {
  DabGenerator generator(roundBrush(), settings());
  std::vector<Dab> dabs;
  generator.addPoint(point(50, 60), dabs);
  ASSERT_EQ(dabs.size(), 1U);
  EXPECT_EQ(dabs[0].x, 50);
  EXPECT_EQ(dabs[0].y, 60);
  EXPECT_EQ(dabs[0].index, 0U);
}

TEST(DabGeneratorTest, ATapGivesOneDab) {
  DabGenerator generator(roundBrush(), settings());
  std::vector<Dab> dabs;
  generator.addPoint(point(5, 5), dabs);
  generator.addPoint(point(5.01, 5), dabs);
  generator.finish(dabs);
  EXPECT_EQ(dabs.size(), 1U);
}

TEST(DabGeneratorTest, SpacingIsUniformAcrossSegmentsAndCalls) {
  BrushParameters brush;
  brush.path.spacing = 0.25;
  DabGenerator generator(brush, settings(20));
  std::vector<Dab> dabs;
  for (int index = 0; index <= 10; ++index) {
    generator.addPoint(point(index * 7.3, 0), dabs);
  }
  generator.finish(dabs);
  ASSERT_GT(dabs.size(), 10U);
  for (size_t index = 1; index < dabs.size(); ++index) {
    EXPECT_NEAR(dabs[index].x - dabs[index - 1].x, 5.0, 0.01) << "dab " << index;
  }
  EXPECT_EQ(dabs.size(), static_cast<size_t>(73 / 5) + 1);
}

TEST(DabGeneratorTest, PressureDrivesSizeAndOpacity) {
  const BrushParameters brush = roundBrush();
  DabGenerator generator(brush, settings(40));
  std::vector<Dab> dabs;
  generator.addPoint(point(0, 0, 0), dabs);
  generator.addPoint(point(100, 0, 1), dabs);
  generator.finish(dabs);
  ASSERT_GE(dabs.size(), 2U);
  EXPECT_NEAR(dabs.front().diameter, 40 * brush.dynamics.size.minimum, 1e-9);
  EXPECT_NEAR(dabs.front().opacity, brush.dynamics.opacity.minimum, 1e-9);
  EXPECT_GT(dabs.back().diameter, 39);
  EXPECT_GT(dabs.back().opacity, 0.97);
}

TEST(DabGeneratorTest, FastStrokeAtTwoMetersPerSecondHasNoGaps) {
  for (const double sampleRate : {120.0, 240.0}) {
    const auto samples = testing::wavyStroke(testing::kTwoMetersPerSecondPixels, sampleRate, 2000);
    const BrushParameters brush = roundBrush();
    const auto dabs = run(brush, settings(12), samples);
    ASSERT_GT(dabs.size(), 100U);
    for (size_t index = 1; index < dabs.size(); ++index) {
      const double gap =
          std::hypot(dabs[index].x - dabs[index - 1].x, dabs[index].y - dabs[index - 1].y);
      ASSERT_LE(gap, spacing(brush, dabs[index - 1].diameter) + 0.02)
          << "dab " << index << " of " << dabs.size() << " at " << sampleRate << " Hz: ("
          << dabs[index - 1].x << ", " << dabs[index - 1].y << ") -> (" << dabs[index].x << ", "
          << dabs[index].y << ") d=" << dabs[index - 1].diameter;
    }
    for (const InputSample& sample : samples) {
      const auto nearest =
          std::min_element(dabs.begin(), dabs.end(), [&](const Dab& a, const Dab& b) {
            return std::hypot(a.x - sample.x, a.y - sample.y) <
                   std::hypot(b.x - sample.x, b.y - sample.y);
          });
      EXPECT_LT(std::hypot(nearest->x - sample.x, nearest->y - sample.y), nearest->diameter / 2);
    }
  }
}

TEST(DabGeneratorTest, SameInputGivesIdenticalDabs) {
  BrushParameters brush = roundBrush();
  brush.dynamics.size.jitter = 0.3;
  brush.shape.scatter = 0.5;
  brush.shape.rotationMode = RotationMode::Random;
  const auto samples = testing::wavyStroke(3000, 240, 800);
  const auto first = run(brush, settings(16, 99), samples);
  const auto second = run(brush, settings(16, 99), samples);
  ASSERT_EQ(first.size(), second.size());
  for (size_t index = 0; index < first.size(); ++index) {
    ASSERT_EQ(first[index].x, second[index].x);
    ASSERT_EQ(first[index].y, second[index].y);
    ASSERT_EQ(first[index].diameter, second[index].diameter);
    ASSERT_EQ(first[index].angle, second[index].angle);
  }
  const auto otherSeed = run(brush, settings(16, 100), samples);
  EXPECT_NE(first[1].diameter, otherSeed[1].diameter);
}

TEST(DabGeneratorTest, BatchingOfPointsDoesNotChangeTheDabs) {
  const auto samples = testing::wavyStroke(5000, 240, 600);
  const auto all = run(roundBrush(), settings(), samples);
  DabGenerator generator(roundBrush(), settings());
  std::vector<Dab> incremental;
  for (const InputSample& sample : samples) {
    std::vector<Dab> frame;
    generator.addPoint(pathPointFrom(sample), frame);
    incremental.insert(incremental.end(), frame.begin(), frame.end());
  }
  generator.finish(incremental);
  ASSERT_EQ(all.size(), incremental.size());
  EXPECT_EQ(all.back().x, incremental.back().x);
}

TEST(DabGeneratorTest, RecordsARandomCheckpointEvery64Dabs) {
  BrushParameters brush;
  brush.path.spacing = 0.1;
  DabGenerator generator(brush, settings(10));
  std::vector<Dab> dabs;
  generator.addPoint(point(0, 0), dabs);
  generator.addPoint(point(200, 0), dabs);
  generator.finish(dabs);
  ASSERT_EQ(dabs.size(), 201U);
  EXPECT_EQ(generator.randomCheckpoints().size(), 4U);

  Random replay(0);
  replay.restore(generator.randomCheckpoints()[1]);
  Random original(settings(10).seed);
  for (int number = 0; number < 64 * DabGenerator::kRandomNumbersPerDab; ++number) {
    original.nextUint32();
  }
  EXPECT_EQ(replay.nextUint32(), original.nextUint32());
}

TEST(DabGeneratorTest, CountEmitsSeveralDabsPerStep) {
  BrushParameters brush;
  brush.shape.count = 3;
  brush.path.spacing = 1;
  DabGenerator generator(brush, settings(10));
  std::vector<Dab> dabs;
  generator.addPoint(point(0, 0), dabs);
  generator.addPoint(point(20, 0), dabs);
  generator.finish(dabs);
  EXPECT_EQ(dabs.size(), 9U);
}

std::vector<Dab> straightStroke(const BrushParameters& brush, double length, double pressure = 1,
                                double sizePixels = 20) {
  DabGenerator generator(brush, settings(sizePixels));
  std::vector<Dab> dabs;
  for (double x = 0; x <= length; x += 2) {
    PathPoint sample = point(x, 0, pressure);
    sample.timeMilliseconds = x;
    generator.addPoint(sample, dabs);
  }
  generator.finish(dabs);
  return dabs;
}

TEST(DabGeneratorTest, TaperNarrowsBothEndsOfTheStroke) {
  BrushParameters brush;
  brush.path.spacing = 0.05;
  brush.taper.stylus.start = 40;
  brush.taper.stylus.end = 60;
  brush.taper.stylus.minSize = 0.1;
  const auto dabs = straightStroke(brush, 300);
  ASSERT_GT(dabs.size(), 100U);
  EXPECT_NEAR(dabs.front().diameter, 2, 1e-6);
  EXPECT_NEAR(dabs.back().diameter, 2, 0.5);
  const auto middle =
      std::find_if(dabs.begin(), dabs.end(), [](const Dab& dab) { return dab.x >= 150; });
  EXPECT_NEAR(middle->diameter, 20, 1e-6);
  for (size_t index = 1; index < dabs.size(); ++index) {
    if (dabs[index].x < 40) {
      EXPECT_GE(dabs[index].diameter, dabs[index - 1].diameter - 1e-9);
    } else if (dabs[index].x > 240) {
      EXPECT_LE(dabs[index].diameter, dabs[index - 1].diameter + 1e-9);
    }
  }
  for (size_t index = 0; index < dabs.size(); ++index) {
    EXPECT_EQ(dabs[index].index, index);
  }
}

TEST(DabGeneratorTest, EndTaperHoldsTheTailUntilThePenLifts) {
  BrushParameters brush;
  brush.taper.stylus.end = 50;
  DabGenerator generator(brush, settings(20));
  std::vector<Dab> dabs;
  for (double x = 0; x <= 200; x += 2) {
    generator.addPoint(point(x, 0), dabs);
  }
  ASSERT_FALSE(dabs.empty());
  EXPECT_LT(dabs.back().x, 200 - 50 + 1e-6);
  for (const Dab& dab : dabs) {
    EXPECT_DOUBLE_EQ(dab.diameter, 20);
  }
  DabGenerator copy = generator;
  std::vector<Dab> provisional;
  copy.finish(provisional);
  std::vector<Dab> final;
  generator.finish(final);
  ASSERT_EQ(provisional.size(), final.size());
  EXPECT_LT(final.back().diameter, 20);
}

TEST(DabGeneratorTest, ATapIsNotTapered) {
  BrushParameters brush;
  brush.taper.stylus.start = 30;
  brush.taper.stylus.end = 30;
  DabGenerator generator(brush, settings(20));
  std::vector<Dab> dabs;
  generator.addPoint(point(5, 5), dabs);
  EXPECT_TRUE(dabs.empty());
  generator.finish(dabs);
  ASSERT_EQ(dabs.size(), 1U);
  EXPECT_DOUBLE_EQ(dabs[0].diameter, 20);
}

TEST(DabGeneratorTest, TaperWithoutPressureModeIgnoresPressureAtTheTip) {
  BrushParameters brush;
  brush.dynamics.size.pressure = Curve::linear();
  brush.taper.stylus.start = 100;
  brush.taper.stylus.minSize = 0.5;
  brush.taper.stylus.pressureMode = false;
  const auto light = straightStroke(brush, 300, 0.2);
  brush.taper.stylus.pressureMode = true;
  const auto multiplied = straightStroke(brush, 300, 0.2);
  EXPECT_NEAR(light.front().diameter, 10, 1e-6);
  EXPECT_NEAR(multiplied.front().diameter, 2, 1e-6);
  EXPECT_NEAR(light.back().diameter, 4, 1e-6);
}

TEST(DabGeneratorTest, SpeedAndTiltDynamicsScaleTheSize) {
  BrushParameters brush;
  brush.dynamics.size.speed = 1;
  DabGenerator generator(brush, settings(20));
  std::vector<Dab> slow;
  std::vector<Dab> fast;
  for (int index = 0; index <= 100; ++index) {
    PathPoint sample = point(index * 0.5, 0);
    sample.timeMilliseconds = index * 4.0;
    generator.addPoint(sample, slow);
  }
  DabGenerator fastGenerator(brush, settings(20));
  for (int index = 0; index <= 100; ++index) {
    PathPoint sample = point(index * 10.0, 0);
    sample.timeMilliseconds = index * 4.0;
    fastGenerator.addPoint(sample, fast);
  }
  ASSERT_FALSE(slow.empty());
  ASSERT_FALSE(fast.empty());
  EXPECT_LT(slow.back().diameter, 4);
  EXPECT_NEAR(fast.back().diameter, 40, 0.5);

  BrushParameters tilted;
  tilted.dynamics.size.tilt = Curve::linear();
  tilted.dynamics.size.tiltAmount = 1;
  tilted.dynamics.tiltGradation = 1;
  DabGenerator tiltGenerator(tilted, settings(20));
  std::vector<Dab> dabs;
  PathPoint sample = point(0, 0);
  sample.tilt = 3.14159265358979323846 / 4;
  tiltGenerator.addPoint(sample, dabs);
  ASSERT_EQ(dabs.size(), 1U);
  EXPECT_NEAR(dabs[0].diameter, 10, 1e-6);
  EXPECT_NEAR(dabs[0].gradation, 0.5, 1e-6);
}

TEST(DabGeneratorTest, GlobalPressureCurveAppliesToTheStylusOnly) {
  BrushParameters brush;
  brush.dynamics.size.pressure = Curve::linear();
  StrokeSettings curved = settings(20);
  curved.pressureCurve = Curve({{0, 0}, {0.5, 0.25}, {1, 1}});
  std::vector<Dab> dabs;
  DabGenerator stylus(brush, curved);
  stylus.addPoint(point(0, 0, 0.5), dabs);
  ASSERT_EQ(dabs.size(), 1U);
  EXPECT_NEAR(dabs[0].diameter, 5, 1e-6);

  dabs.clear();
  DabGenerator finger(brush, curved);
  PathPoint touch = point(0, 0, 0.5);
  touch.finger = true;
  finger.addPoint(touch, dabs);
  ASSERT_EQ(dabs.size(), 1U);
  EXPECT_NEAR(dabs[0].diameter, 20, 1e-6);
}

TEST(DabGeneratorTest, CountJitterVariesTheDabsPerStep) {
  BrushParameters brush;
  brush.shape.count = 8;
  brush.shape.countJitter = 1;
  brush.path.spacing = 1;
  const auto dabs = straightStroke(brush, 400, 1, 10);
  EXPECT_GT(dabs.size(), 41U);
  EXPECT_LT(dabs.size(), 41U * 8);
}

TEST(DabGeneratorTest, FalloffAndGlazeFlowScaleTheDabs) {
  BrushParameters brush;
  brush.path.falloff = 1;
  brush.render.accumulation = Accumulation::GlazeLight;
  const auto dabs = straightStroke(brush, 1000);
  EXPECT_NEAR(dabs.front().opacity, 1, 1e-9);
  EXPECT_NEAR(dabs.back().opacity, 0.5, 0.01);
  EXPECT_NEAR(dabs.front().flow, 0.5, 1e-9);
}

TEST(DabGeneratorTest, ColorJitterVariesTheDabColors) {
  BrushParameters brush;
  const auto plain = straightStroke(brush, 200);
  for (const Dab& dab : plain) {
    EXPECT_EQ(dab.color, (std::array<double, 4>{0, 0, 0, 1}));
  }
  brush.colorDynamics.hueJitter = 1;
  brush.colorDynamics.brightnessJitter = 1;
  const auto jittered = straightStroke(brush, 200);
  ASSERT_EQ(jittered.size(), plain.size());
  size_t distinct = 0;
  for (size_t index = 1; index < jittered.size(); ++index) {
    distinct += jittered[index].color != jittered[index - 1].color ? 1 : 0;
    EXPECT_EQ(jittered[index].x, plain[index].x);
    EXPECT_EQ(jittered[index].diameter, plain[index].diameter);
  }
  EXPECT_GT(distinct, jittered.size() / 2);
}

TEST(DabGeneratorTest, StrokeColorJitterIsConstantWithinAStroke) {
  BrushParameters brush;
  brush.colorDynamics.strokeBrightnessJitter = 1;
  const auto dabs = straightStroke(brush, 200);
  ASSERT_GT(dabs.size(), 2U);
  for (const Dab& dab : dabs) {
    EXPECT_EQ(dab.color, dabs.front().color);
  }
}

TEST(DabGeneratorTest, AStampIsOneDabAtTheTap) {
  BrushParameters brush;
  brush.stamp.enabled = true;
  brush.taper.stylus.start = 50;
  const auto dabs = straightStroke(brush, 300);
  ASSERT_EQ(dabs.size(), 1U);
  EXPECT_EQ(dabs[0].x, 0);
  EXPECT_NEAR(dabs[0].diameter, 20, 1e-9);
}

TEST(DabGeneratorTest, AStampPlacedOnReleaseTurnsWithTheDrag) {
  BrushParameters brush;
  brush.stamp.enabled = true;
  brush.stamp.placeOnRelease = true;
  brush.shape.rotationMode = RotationMode::Fixed;
  DabGenerator generator(brush, settings());
  std::vector<Dab> dabs;
  generator.addPoint(point(100, 100), dabs);
  generator.addPoint(point(100, 140), dabs);
  EXPECT_TRUE(dabs.empty());
  generator.finish(dabs);
  ASSERT_EQ(dabs.size(), 1U);
  EXPECT_EQ(dabs[0].x, 100);
  EXPECT_EQ(dabs[0].y, 100);
  EXPECT_NEAR(dabs[0].angle, 3.14159265358979 / 2, 1e-9);
}

PathPoint timed(double x, double y, double milliseconds) {
  PathPoint result = point(x, y);
  result.timeMilliseconds = milliseconds;
  return result;
}

TEST(DabGeneratorTest, BarrelRollTurnsTheTipOnlyWhenTheStylusReportsIt) {
  BrushParameters brush;
  brush.shape.rotationMode = RotationMode::Fixed;
  brush.roll.enabled = true;
  brush.roll.amount = 0.5;
  PathPoint turned = point(10, 10);
  turned.orientation = 1.0;
  for (const bool reported : {false, true}) {
    StrokeSettings stroke = settings();
    stroke.rollFromOrientation = reported;
    DabGenerator generator(brush, stroke);
    std::vector<Dab> dabs;
    generator.addPoint(turned, dabs);
    generator.finish(dabs);
    ASSERT_FALSE(dabs.empty());
    EXPECT_NEAR(dabs.front().angle, reported ? 0.5 : 0.0, 1e-12);
  }
}

TEST(DabGeneratorTest, TipAnimationGrowsAHeldTipAndKeepsItsSize) {
  BrushParameters brush;
  brush.taper.stylus.start = 40;
  brush.taper.stylus.minSize = 0.2;
  brush.taper.stylus.tipAnimation = true;
  DabGenerator generator(brush, settings());
  std::vector<Dab> dabs;
  for (int32_t step = 0; step <= 30; ++step) {
    generator.addPoint(timed(50, 50, step * 16.0), dabs);
  }
  generator.addPoint(timed(60, 50, 500), dabs);
  generator.addPoint(timed(70, 50, 510), dabs);
  generator.addPoint(timed(80, 50, 520), dabs);
  generator.finish(dabs);
  std::vector<double> atStart;
  for (const Dab& dab : dabs) {
    if (dab.x == 50 && dab.y == 50) {
      atStart.push_back(dab.diameter);
    }
  }
  ASSERT_GT(atStart.size(), 10U);
  EXPECT_NEAR(atStart.front(), 4, 1e-9);
  EXPECT_NEAR(atStart.back(), 20, 1e-9);
  for (size_t index = 1; index < atStart.size(); ++index) {
    EXPECT_GE(atStart[index], atStart[index - 1]);
  }
  for (const Dab& dab : dabs) {
    if (dab.x > 50) {
      EXPECT_NEAR(dab.diameter, 20, 1e-9);
    }
  }

  brush.taper.stylus.tipAnimation = false;
  DabGenerator plain(brush, settings());
  std::vector<Dab> plainDabs;
  for (int32_t step = 0; step <= 30; ++step) {
    plain.addPoint(timed(50, 50, step * 16.0), plainDabs);
  }
  plain.addPoint(timed(60, 50, 500), plainDabs);
  plain.addPoint(timed(80, 50, 520), plainDabs);
  plain.finish(plainDabs);
  EXPECT_NEAR(plainDabs.front().diameter, 4, 1e-9);
  EXPECT_LT(plainDabs[1].diameter, 20);
}

TEST(DabGeneratorTest, AnAnimatedTapKeepsTheSizeItReached) {
  BrushParameters brush;
  brush.taper.stylus.start = 40;
  brush.taper.stylus.minSize = 0;
  brush.taper.stylus.tipAnimation = true;
  DabGenerator generator(brush, settings());
  std::vector<Dab> dabs;
  generator.addPoint(timed(50, 50, 0), dabs);
  generator.addPoint(timed(50, 50, 200), dabs);
  EXPECT_TRUE(dabs.empty());
  generator.finish(dabs);
  ASSERT_FALSE(dabs.empty());
  EXPECT_LT(dabs.back().diameter, 20);
  EXPECT_GT(dabs.back().diameter, 2);
}

TEST(DabGeneratorTest, TipFootprintIsTheDabAtTheHoverPressure) {
  BrushParameters brush;
  brush.dynamics.size.pressure = Curve::linear();
  brush.dynamics.size.jitter = 1;
  brush.shape.scatter = 2;
  brush.shape.rotationMode = RotationMode::Fixed;
  brush.shape.rotation = 0.3;
  brush.shape.roundness = 0.5;
  brush.taper.stylus.start = 100;
  const TipFootprint footprint = tipFootprint(brush, settings(40), 0.5);
  EXPECT_NEAR(footprint.diameter, 20, 1e-9);
  EXPECT_NEAR(footprint.angle, 0.3, 1e-12);
  EXPECT_NEAR(footprint.roundness, 0.5, 1e-12);
}

TEST(DabGeneratorTest, AReplacementRedrawsTheStrokeUnsmoothedWithTheSameSeed) {
  BrushParameters brush = roundBrush();
  brush.path.streamline = 0.8;
  brush.path.stabilization = 0.5;
  brush.path.jitterFilter = 0.5;
  brush.path.jitterLateral = 0.3;
  std::vector<PathPoint> wobbly;
  std::vector<PathPoint> shape;
  for (int step = 0; step <= 40; ++step) {
    PathPoint sample = point(10 + step * 5, 10 + 3 * std::sin(step), 0.5 + step / 100.0);
    sample.timeMilliseconds = step * 8;
    wobbly.push_back(sample);
    PathPoint clean = point(step <= 20 ? 10 + step * 10 : 210,
                            step <= 20 ? 10 : 10 + (step - 20) * 10, 0.5 + step / 100.0);
    clean.timeMilliseconds = step * 8;
    shape.push_back(clean);
  }
  DabGenerator generator(brush, settings(12, 77));
  std::vector<Dab> drawn;
  for (const PathPoint& sample : wobbly) {
    generator.addPoint(sample, drawn);
  }
  generator = generator.replacement();
  std::vector<Dab> replaced;
  for (const PathPoint& sample : shape) {
    generator.addPoint(sample, replaced);
  }
  generator.finish(replaced);

  StrokeSettings unsmoothed = settings(12, 77);
  unsmoothed.smoothPositions = false;
  DabGenerator fresh(brush, unsmoothed);
  std::vector<Dab> expected;
  for (const PathPoint& sample : shape) {
    fresh.addPoint(sample, expected);
  }
  fresh.finish(expected);
  ASSERT_EQ(replaced.size(), expected.size());
  for (size_t index = 0; index < expected.size(); ++index) {
    EXPECT_EQ(replaced[index].x, expected[index].x) << "dab " << index;
    EXPECT_EQ(replaced[index].y, expected[index].y) << "dab " << index;
    EXPECT_EQ(replaced[index].diameter, expected[index].diameter) << "dab " << index;
    EXPECT_EQ(replaced[index].index, expected[index].index) << "dab " << index;
  }
  auto nearestToCorner = [](const std::vector<Dab>& dabs) {
    double nearest = 1e9;
    for (const Dab& dab : dabs) {
      nearest = std::min(nearest, std::hypot(dab.x - 210, dab.y - 10));
    }
    return nearest;
  };
  brush.path.jitterLateral = 0;
  std::vector<Dab> straight;
  DabGenerator corner(brush, unsmoothed);
  for (const PathPoint& sample : shape) {
    corner.addPoint(sample, straight);
  }
  corner.finish(straight);
  EXPECT_LT(nearestToCorner(straight), 1.0);
  std::vector<Dab> smoothed;
  DabGenerator smoothing(brush, settings(12, 77));
  for (const PathPoint& sample : shape) {
    smoothing.addPoint(sample, smoothed);
  }
  smoothing.finish(smoothed);
  EXPECT_GT(nearestToCorner(smoothed), 3.0);
}

}  // namespace
}  // namespace nanquim::stroke
