#include "stroke/particles.h"

#include <gtest/gtest.h>

namespace nanquim::stroke {
namespace {

std::vector<Dab> line(int count) {
  std::vector<Dab> dabs;
  for (int index = 0; index < count; ++index) {
    Dab dab;
    dab.x = 5.0 * index;
    dab.y = 100;
    dab.distance = 5.0 * index;
    dab.timeMilliseconds = index;
    dab.diameter = 20;
    dab.opacity = 1;
    dabs.push_back(dab);
  }
  return dabs;
}

ParticleParameters particles(double rate) {
  ParticleParameters parameters;
  parameters.rate = rate;
  parameters.lifetimeMs = 100;
  parameters.sizeStart = 0.5;
  parameters.sizeEnd = 0.5;
  return parameters;
}

TEST(ParticlesTest, EmitsByStrokeLengthAndDrawsEveryStepUntilDeath) {
  ParticleSystem system(particles(10), 7);
  system.emit(line(21));
  std::vector<Dab> dabs;
  system.advance(1000, dabs);
  EXPECT_GE(dabs.size(), 11U * 12);
  EXPECT_LE(dabs.size(), 11U * 13);
  EXPECT_DOUBLE_EQ(dabs.front().diameter, 10);
  EXPECT_FALSE(system.active(2000));
}

TEST(ParticlesTest, StepsSpreadOverCallsGiveTheSameDabs) {
  ParticleParameters parameters = particles(20);
  parameters.direction = ParticleDirection::Random;
  parameters.gravity = 500;
  ParticleSystem once(parameters, 3);
  once.emit(line(41));
  std::vector<Dab> all;
  once.advance(500, all);
  ParticleSystem inSteps(parameters, 3);
  inSteps.emit(line(41));
  std::vector<Dab> pieces;
  for (double time = 0; time <= 500; time += 7) {
    inSteps.advance(time, pieces);
  }
  inSteps.advance(500, pieces);
  ASSERT_EQ(all.size(), pieces.size());
  for (size_t index = 0; index < all.size(); ++index) {
    EXPECT_EQ(all[index].x, pieces[index].x);
    EXPECT_EQ(all[index].y, pieces[index].y);
  }
}

TEST(ParticlesTest, GravityPullsDownAndNoRateMeansNoParticles) {
  ParticleParameters parameters = particles(1);
  parameters.initialSpeed = 0;
  parameters.gravity = 1000;
  ParticleSystem system(parameters, 1);
  system.emit(line(1));
  std::vector<Dab> dabs;
  system.advance(100, dabs);
  ASSERT_GT(dabs.size(), 2U);
  EXPECT_GT(dabs.back().y, dabs.front().y);
  ParticleSystem none(particles(0), 1);
  none.emit(line(10));
  EXPECT_TRUE(none.empty());
}

}  // namespace
}  // namespace nanquim::stroke
