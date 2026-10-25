#pragma once

#include <array>
#include <cstdint>
#include <map>
#include <vector>

#include "canvas/tile_coordinate.h"

namespace nanquim::canvas {

struct PolygonPoint {
  double x = 0;
  double y = 0;
};

using TileCoverage = std::vector<uint8_t>;

struct TileCoordinateLess {
  bool operator()(const TileCoordinate& left, const TileCoordinate& right) const {
    return left.row != right.row ? left.row < right.row : left.column < right.column;
  }
};
using TileCoverageMap = std::map<TileCoordinate, TileCoverage, TileCoordinateLess>;

TileCoverageMap rasterizePolygon(const std::vector<PolygonPoint>& points, int32_t canvasWidth,
                                 int32_t canvasHeight);

std::vector<PolygonPoint> rectanglePolygon(double x, double y, double width, double height);
std::vector<PolygonPoint> ellipsePolygon(double x, double y, double width, double height);

enum class SelectionCombine : int32_t { Replace = 0, Add = 1, Subtract = 2, Intersect = 3 };

double combineSelection(SelectionCombine mode, double existing, double coverage);

std::vector<float> featherWeights(double radius);
inline constexpr double kMaximumFeatherRadius = 100;

}  // namespace nanquim::canvas
