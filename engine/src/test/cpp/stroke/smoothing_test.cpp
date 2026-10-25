#include "stroke/smoothing.h"

#include <gtest/gtest.h>

#include <cmath>

#include "stroke/dab_generator.h"
#include "stroke/test_traces.h"

namespace nanquim::stroke {
namespace {

PathPoint point(double x, double y, double timeMilliseconds, double pressure = 1) {
  PathPoint result;
  result.x = x;
  result.y = y;
  result.pressure = pressure;
  result.timeMilliseconds = timeMilliseconds;
  return result;
}

std::vector<PathPoint> smooth(const SmoothingParameters& parameters,
                              const std::vector<PathPoint>& input, bool finish = false) {
  StrokeSmoother smoother(parameters);
  std::vector<PathPoint> output;
  for (const PathPoint& sample : input) {
    smoother.addPoint(sample, output);
  }
  if (finish) {
    smoother.finish(1.0, output);
  }
  return output;
}

std::vector<PathPoint> line(double speedPixelsPerSecond, double sampleRateHertz,
                            double durationMilliseconds) {
  std::vector<PathPoint> samples;
  const double interval = 1000.0 / sampleRateHertz;
  for (double time = 0; time <= durationMilliseconds + 1e-9; time += interval) {
    samples.push_back(point(speedPixelsPerSecond * time / 1000.0, 0, time));
  }
  return samples;
}

TEST(SmoothingTest, ZeroParametersPassPointsThrough) {
  const std::vector<PathPoint> input{point(0, 0, 0), point(10, 5, 4), point(30, -2, 8)};
  const auto output = smooth(SmoothingParameters{}, input, true);
  ASSERT_EQ(output.size(), input.size());
  for (size_t index = 0; index < input.size(); ++index) {
    EXPECT_EQ(output[index].x, input[index].x);
    EXPECT_EQ(output[index].y, input[index].y);
  }
}

TEST(SmoothingTest, StreamlineLagsBehindAndCatchesUpOnLift) {
  SmoothingParameters parameters;
  parameters.streamline = 0.8;
  const auto input = line(2000, 240, 200);
  StrokeSmoother smoother(parameters);
  std::vector<PathPoint> output;
  for (const PathPoint& sample : input) {
    smoother.addPoint(sample, output);
  }
  EXPECT_LT(output.back().x, input.back().x - 10);
  smoother.finish(2.0, output);
  EXPECT_NEAR(output.back().x, input.back().x, 1e-9);
  for (size_t index = 1; index < output.size(); ++index) {
    EXPECT_GE(output[index].x, output[index - 1].x - 1e-9);
  }
}

TEST(SmoothingTest, StreamlineDoesNotDependOnTheSampleRate) {
  SmoothingParameters parameters;
  parameters.streamline = 0.6;
  const auto slow = smooth(parameters, line(1500, 120, 300));
  const auto fast = smooth(parameters, line(1500, 480, 300));
  EXPECT_NEAR(slow.back().x, fast.back().x, 5.0);
}

TEST(SmoothingTest, CatchUpCanBeTurnedOff) {
  SmoothingParameters parameters;
  parameters.streamline = 0.8;
  parameters.streamlineCatchUp = false;
  const auto input = line(2000, 240, 200);
  const auto output = smooth(parameters, input, true);
  EXPECT_LT(output.back().x, input.back().x - 10);
}

TEST(SmoothingTest, PressureSmoothingOnlyTouchesPressure) {
  SmoothingParameters parameters;
  parameters.pressureSmoothing = 0.9;
  const std::vector<PathPoint> input{point(0, 0, 0, 0.1), point(10, 0, 4, 1.0)};
  const auto output = smooth(parameters, input);
  EXPECT_EQ(output[1].x, 10);
  EXPECT_LT(output[1].pressure, 0.5);
}

TEST(SmoothingTest, StabilizationAveragesAndDrainsToTheEnd) {
  SmoothingParameters parameters;
  parameters.stabilization = 0.5;
  std::vector<PathPoint> input;
  for (int index = 0; index < 60; ++index) {
    input.push_back(point(index * 4.0, index % 2 == 0 ? 6.0 : -6.0, index * 4.0));
  }
  const auto output = smooth(parameters, input, true);
  double maximumDeviation = 0;
  for (size_t index = 20; index < 60; ++index) {
    maximumDeviation = std::max(maximumDeviation, std::abs(output[index].y));
  }
  EXPECT_LT(maximumDeviation, 1.5);
  EXPECT_EQ(output.size(), input.size() + 16);
  EXPECT_NEAR(output.back().x, input.back().x, 1e-9);
  EXPECT_NEAR(output.back().y, input.back().y, 1e-9);
}

TEST(SmoothingTest, StabilizationWeighsDistanceNotSampleCount) {
  SmoothingParameters parameters;
  parameters.stabilization = 0.4;
  std::vector<PathPoint> dense;
  std::vector<PathPoint> sparse;
  for (int index = 0; index <= 40; ++index) {
    dense.push_back(point(index * 1.0, 0, index * 4.0));
  }
  for (int index = 0; index <= 10; ++index) {
    sparse.push_back(point(index * 4.0, 0, index * 4.0));
  }
  const auto denseOutput = smooth(parameters, dense);
  const auto sparseOutput = smooth(parameters, sparse);
  EXPECT_GT(denseOutput.back().x, 30);
  EXPECT_GT(sparseOutput.back().x, 20);
}

TEST(SmoothingTest, OneEuroFilterSuppressesJitterOfAStillPen) {
  OneEuroFilter filter(0.3, 0.007, 1.0);
  Random random(11);
  double sumOfSquares = 0;
  constexpr int kSamples = 400;
  for (int index = 0; index < kSamples; ++index) {
    const double noisy = 100.0 + random.uniform(-1.0, 1.0);
    const double filtered = filter.filter(noisy, index * 3.0);
    if (index > 50) {
      sumOfSquares += (filtered - 100.0) * (filtered - 100.0);
    }
  }
  EXPECT_LT(std::sqrt(sumOfSquares / (kSamples - 51)), 0.1);
}

TEST(SmoothingTest, OneEuroFilterFollowsFastMovement) {
  OneEuroFilter filter(0.3, 0.007, 1.0);
  double filtered = 0;
  for (int index = 0; index <= 100; ++index) {
    filtered = filter.filter(index * 10.0, index * 3.0);
  }
  EXPECT_GT(filtered, 1000.0 - 60.0);
}

TEST(SmoothingTest, DabGeneratorWithStreamlineEndsWhereThePenLifted) {
  BrushParameters brush = roundBrush();
  brush.path.streamline = 0.9;
  StrokeSettings settings;
  settings.sizePixels = 10;
  const auto samples = testing::wavyStroke(3000, 240, 900);
  DabGenerator generator(brush, settings);
  std::vector<Dab> dabs;
  for (const InputSample& sample : samples) {
    generator.addPoint(pathPointFrom(sample), dabs);
  }
  generator.finish(dabs);
  ASSERT_FALSE(dabs.empty());
  EXPECT_NEAR(dabs.back().x, samples.back().x, 1.0);
  EXPECT_NEAR(dabs.back().y, samples.back().y, 1.0);
}

}  // namespace
}  // namespace nanquim::stroke
