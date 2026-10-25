#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <functional>

namespace nanquim::canvas {

inline constexpr int32_t kTileSizePixels = 256;
inline constexpr int32_t kTileBytesRgba8 = kTileSizePixels * kTileSizePixels * 4;

constexpr int32_t floorDivide(int32_t value, int32_t divisor) {
  const int32_t quotient = value / divisor;
  return (value % divisor != 0 && ((value < 0) != (divisor < 0))) ? quotient - 1 : quotient;
}

struct TileCoordinate {
  int32_t column = 0;
  int32_t row = 0;

  friend bool operator==(const TileCoordinate& left, const TileCoordinate& right) {
    return left.column == right.column && left.row == right.row;
  }
  friend bool operator!=(const TileCoordinate& left, const TileCoordinate& right) {
    return !(left == right);
  }
};

struct TileCoordinateHash {
  size_t operator()(const TileCoordinate& coordinate) const {
    const auto packed = (static_cast<uint64_t>(static_cast<uint32_t>(coordinate.column)) << 32U) |
                        static_cast<uint32_t>(coordinate.row);
    return std::hash<uint64_t>{}(packed);
  }
};

struct TileRange {
  int32_t firstColumn = 0;
  int32_t firstRow = 0;
  int32_t endColumn = 0;
  int32_t endRow = 0;

  [[nodiscard]] bool isEmpty() const { return endColumn <= firstColumn || endRow <= firstRow; }

  [[nodiscard]] int64_t count() const {
    return isEmpty() ? 0 : static_cast<int64_t>(endColumn - firstColumn) * (endRow - firstRow);
  }

  [[nodiscard]] bool contains(TileCoordinate coordinate) const {
    return coordinate.column >= firstColumn && coordinate.column < endColumn &&
           coordinate.row >= firstRow && coordinate.row < endRow;
  }

  [[nodiscard]] TileRange intersection(const TileRange& other) const {
    return TileRange{std::max(firstColumn, other.firstColumn), std::max(firstRow, other.firstRow),
                     std::min(endColumn, other.endColumn), std::min(endRow, other.endRow)};
  }

  template <typename Visitor>
  void forEach(Visitor&& visitor) const {
    for (int32_t row = firstRow; row < endRow; ++row) {
      for (int32_t column = firstColumn; column < endColumn; ++column) {
        visitor(TileCoordinate{column, row});
      }
    }
  }
};

inline TileRange tileRangeForCanvas(int32_t widthPixels, int32_t heightPixels,
                                    int32_t tileSize = kTileSizePixels) {
  return TileRange{0, 0, (widthPixels + tileSize - 1) / tileSize,
                   (heightPixels + tileSize - 1) / tileSize};
}

inline TileRange tileRangeCoveringRect(double minX, double minY, double maxX, double maxY,
                                       int32_t tileSize = kTileSizePixels) {
  if (!(maxX > minX) || !(maxY > minY)) {
    return TileRange{};
  }
  const auto toTile = [tileSize](double value) {
    return static_cast<int32_t>(std::floor(value / tileSize));
  };
  const auto toEndTile = [tileSize](double value) {
    return static_cast<int32_t>(std::ceil(value / tileSize));
  };
  return TileRange{toTile(minX), toTile(minY), toEndTile(maxX), toEndTile(maxY)};
}

}  // namespace nanquim::canvas
