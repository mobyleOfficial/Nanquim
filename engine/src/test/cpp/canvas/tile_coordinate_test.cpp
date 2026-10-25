#include "canvas/tile_coordinate.h"

#include <gtest/gtest.h>

#include <unordered_set>
#include <vector>

namespace nanquim::canvas {
namespace {

TEST(TileCoordinateTest, FloorDivideRoundsTowardNegativeInfinity) {
  EXPECT_EQ(floorDivide(0, 256), 0);
  EXPECT_EQ(floorDivide(255, 256), 0);
  EXPECT_EQ(floorDivide(256, 256), 1);
  EXPECT_EQ(floorDivide(-1, 256), -1);
  EXPECT_EQ(floorDivide(-256, 256), -1);
  EXPECT_EQ(floorDivide(-257, 256), -2);
}

TEST(TileCoordinateTest, CanvasRangeRoundsPartialTilesUp) {
  const TileRange range = tileRangeForCanvas(2732, 2048);

  EXPECT_EQ(range.endColumn, 11);
  EXPECT_EQ(range.endRow, 8);
  EXPECT_EQ(range.count(), 88);
}

TEST(TileCoordinateTest, RectRangeIncludesPartiallyCoveredTiles) {
  const TileRange range = tileRangeCoveringRect(-10, 255.5, 300, 513);

  EXPECT_EQ(range.firstColumn, -1);
  EXPECT_EQ(range.firstRow, 0);
  EXPECT_EQ(range.endColumn, 2);
  EXPECT_EQ(range.endRow, 3);
  EXPECT_TRUE(tileRangeCoveringRect(5, 5, 5, 10).isEmpty());
}

TEST(TileCoordinateTest, IntersectionAndVisitOrder) {
  const TileRange range = TileRange{0, 0, 4, 4}.intersection(TileRange{2, 1, 10, 3});
  std::vector<TileCoordinate> visited;
  range.forEach([&](TileCoordinate coordinate) { visited.push_back(coordinate); });

  const std::vector<TileCoordinate> expected = {{2, 1}, {3, 1}, {2, 2}, {3, 2}};
  EXPECT_EQ(visited, expected);
  EXPECT_TRUE(range.contains({3, 2}));
  EXPECT_FALSE(range.contains({4, 2}));
}

TEST(TileCoordinateTest, HashDistinguishesNegativeAndSwappedCoordinates) {
  std::unordered_set<TileCoordinate, TileCoordinateHash> coordinates = {
      {1, 2}, {2, 1}, {-1, 2}, {1, -2}};

  EXPECT_EQ(coordinates.size(), 4U);
}

}  // namespace
}  // namespace nanquim::canvas
