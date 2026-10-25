#include "stroke/random.h"

#include <gtest/gtest.h>

#include <set>

namespace nanquim::stroke {
namespace {

TEST(RandomTest, SameSeedGivesSameSequence) {
  Random first(42);
  Random second(42);
  for (int index = 0; index < 1000; ++index) {
    ASSERT_EQ(first.nextUint32(), second.nextUint32());
  }
}

TEST(RandomTest, DifferentSeedsDiverge) {
  Random first(1);
  Random second(2);
  int equal = 0;
  for (int index = 0; index < 100; ++index) {
    equal += first.nextUint32() == second.nextUint32() ? 1 : 0;
  }
  EXPECT_LT(equal, 3);
}

TEST(RandomTest, RestoringAStateReplaysTheSequence) {
  Random random(7);
  for (int index = 0; index < 10; ++index) {
    random.nextUint32();
  }
  const Random::State checkpoint = random.state();
  const uint32_t expected = random.nextUint32();
  random.nextUint32();
  random.restore(checkpoint);
  EXPECT_EQ(random.nextUint32(), expected);
}

TEST(RandomTest, UnitValuesStayInRangeAndSpread) {
  Random random(3);
  double minimum = 1;
  double maximum = 0;
  double sum = 0;
  constexpr int kCount = 100000;
  for (int index = 0; index < kCount; ++index) {
    const double value = random.nextUnit();
    ASSERT_GE(value, 0.0);
    ASSERT_LT(value, 1.0);
    minimum = std::min(minimum, value);
    maximum = std::max(maximum, value);
    sum += value;
  }
  EXPECT_LT(minimum, 0.001);
  EXPECT_GT(maximum, 0.999);
  EXPECT_NEAR(sum / kCount, 0.5, 0.01);
}

TEST(RandomTest, StrokeSeedDependsOnDocumentAndIndex) {
  std::set<uint64_t> seeds;
  for (uint64_t index = 0; index < 100; ++index) {
    seeds.insert(strokeSeed("document-a", index));
    seeds.insert(strokeSeed("document-b", index));
  }
  EXPECT_EQ(seeds.size(), 200U);
  EXPECT_EQ(strokeSeed("document-a", 5), strokeSeed("document-a", 5));
}

}  // namespace
}  // namespace nanquim::stroke
