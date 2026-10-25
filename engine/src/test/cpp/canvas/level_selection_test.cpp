#include "canvas/level_selection.h"

#include <gtest/gtest.h>

namespace nanquim::canvas {
namespace {

TEST(LevelSelectionTest, LevelCountStopsWhenOneTileRemains) {
  EXPECT_EQ(pyramidLevelCount(256, 256), 1);
  EXPECT_EQ(pyramidLevelCount(257, 100), 2);
  EXPECT_EQ(pyramidLevelCount(4096, 4096), 5);
  EXPECT_EQ(pyramidLevelCount(2732, 2048), 5);
}

TEST(LevelSelectionTest, LevelDimensionRoundsUp) {
  EXPECT_EQ(levelDimension(2732, 0), 2732);
  EXPECT_EQ(levelDimension(2732, 1), 1366);
  EXPECT_EQ(levelDimension(2732, 3), 342);
  EXPECT_EQ(levelDimension(3, 4), 1);
}

TEST(LevelSelectionTest, ZoomedInUsesLevelZeroBilinear) {
  const LevelChoice choice = chooseLevels(2.0, 5, false);

  EXPECT_EQ(choice.fineLevel, 0);
  EXPECT_FLOAT_EQ(choice.coarseWeight, 0.0F);
  EXPECT_FALSE(choice.nearest);
}

TEST(LevelSelectionTest, ShowPixelsOnlyFromFourHundredPercent) {
  EXPECT_TRUE(chooseLevels(4.0, 5, true).nearest);
  EXPECT_FALSE(chooseLevels(3.9, 5, true).nearest);
  EXPECT_FALSE(chooseLevels(8.0, 5, false).nearest);
}

TEST(LevelSelectionTest, ZoomedOutBlendsTwoAdjacentLevels) {
  const LevelChoice choice = chooseLevels(0.35, 5, false);

  EXPECT_EQ(choice.fineLevel, 1);
  EXPECT_EQ(choice.coarseLevel, 2);
  EXPECT_NEAR(choice.coarseWeight, 0.515F, 0.01F);
}

TEST(LevelSelectionTest, ClampsToTheCoarsestLevel) {
  const LevelChoice choice = chooseLevels(0.01, 5, false);

  EXPECT_EQ(choice.fineLevel, 4);
  EXPECT_EQ(choice.coarseLevel, 4);
  EXPECT_FLOAT_EQ(choice.coarseWeight, 0.0F);
}

}  // namespace
}  // namespace nanquim::canvas
