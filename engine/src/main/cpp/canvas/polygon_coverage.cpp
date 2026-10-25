#include "canvas/polygon_coverage.h"

#include <algorithm>
#include <cmath>

namespace nanquim::canvas {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kEllipseTolerancePixels = 0.05;

struct Edge {
  double x0, y0, x1, y1;
};

void clipEdgeHorizontally(const Edge& edge, double left, double right, std::vector<Edge>& out) {
  std::vector<Edge> pieces{edge};
  for (const double boundary : {left, right}) {
    std::vector<Edge> next;
    for (const Edge& piece : pieces) {
      const bool crosses = (piece.x0 < boundary) != (piece.x1 < boundary) && piece.x0 != piece.x1;
      if (!crosses) {
        next.push_back(piece);
        continue;
      }
      const double t = (boundary - piece.x0) / (piece.x1 - piece.x0);
      const double y = piece.y0 + t * (piece.y1 - piece.y0);
      next.push_back({piece.x0, piece.y0, boundary, y});
      next.push_back({boundary, y, piece.x1, piece.y1});
    }
    pieces = std::move(next);
  }
  for (Edge piece : pieces) {
    piece.x0 = std::clamp(piece.x0, left, right);
    piece.x1 = std::clamp(piece.x1, left, right);
    out.push_back(piece);
  }
}

void accumulateEdge(const Edge& edge, int32_t rows, int32_t width, std::vector<double>& buffer) {
  if (edge.y0 == edge.y1) {
    return;
  }
  const double direction = edge.y0 < edge.y1 ? 1.0 : -1.0;
  const double topY = std::min(edge.y0, edge.y1);
  const double bottomY = std::max(edge.y0, edge.y1);
  const double topX = edge.y0 < edge.y1 ? edge.x0 : edge.x1;
  const double dxdy = (edge.x1 - edge.x0) / (edge.y1 - edge.y0);
  const int32_t firstRow = std::max(0, static_cast<int32_t>(std::floor(topY)));
  const int32_t endRow = std::min(rows, static_cast<int32_t>(std::ceil(bottomY)));
  const auto stride = static_cast<size_t>(width + 2);
  for (int32_t row = firstRow; row < endRow; ++row) {
    const double segmentTop = std::max(static_cast<double>(row), topY);
    const double segmentBottom = std::min(static_cast<double>(row + 1), bottomY);
    if (segmentBottom <= segmentTop) {
      continue;
    }
    const double height = segmentBottom - segmentTop;
    const double xTop = topX + (segmentTop - topY) * dxdy;
    const double xBottom = topX + (segmentBottom - topY) * dxdy;
    const double amount = height * direction;
    double* line = buffer.data() + static_cast<size_t>(row) * stride;
    const double left = std::clamp(std::min(xTop, xBottom), 0.0, static_cast<double>(width));
    const double right = std::clamp(std::max(xTop, xBottom), 0.0, static_cast<double>(width));
    const double leftFloor = std::floor(left);
    const auto leftIndex = static_cast<int32_t>(leftFloor);
    const double rightCeil = std::ceil(right);
    if (rightCeil <= leftFloor + 1) {
      const double middle = 0.5 * (left + right) - leftFloor;
      line[leftIndex] += amount * (1 - middle);
      line[leftIndex + 1] += amount * middle;
      continue;
    }
    const double inverseWidth = 1.0 / (right - left);
    const double leftFraction = left - leftFloor;
    const double firstArea = 0.5 * inverseWidth * (1 - leftFraction) * (1 - leftFraction);
    const auto rightIndex = static_cast<int32_t>(rightCeil);
    const double rightFraction = right - rightCeil + 1;
    const double lastArea = 0.5 * inverseWidth * rightFraction * rightFraction;
    line[leftIndex] += amount * firstArea;
    if (rightIndex == leftIndex + 2) {
      line[leftIndex + 1] += amount * (1 - firstArea - lastArea);
    } else {
      const double secondArea = inverseWidth * (1.5 - leftFraction);
      line[leftIndex + 1] += amount * (secondArea - firstArea);
      for (int32_t column = leftIndex + 2; column < rightIndex - 1; ++column) {
        line[column] += amount * inverseWidth;
      }
      const double beforeLast = secondArea + (rightIndex - leftIndex - 3) * inverseWidth;
      line[rightIndex - 1] += amount * (1 - beforeLast - lastArea);
    }
    line[rightIndex] += amount * lastArea;
  }
}

}  // namespace

TileCoverageMap rasterizePolygon(const std::vector<PolygonPoint>& points, int32_t canvasWidth,
                                 int32_t canvasHeight) {
  TileCoverageMap tiles;
  if (points.size() < 3 || canvasWidth <= 0 || canvasHeight <= 0) {
    return tiles;
  }
  double minimumX = points[0].x;
  double maximumX = points[0].x;
  double minimumY = points[0].y;
  double maximumY = points[0].y;
  for (const PolygonPoint& point : points) {
    if (!std::isfinite(point.x) || !std::isfinite(point.y)) {
      return tiles;
    }
    minimumX = std::min(minimumX, point.x);
    maximumX = std::max(maximumX, point.x);
    minimumY = std::min(minimumY, point.y);
    maximumY = std::max(maximumY, point.y);
  }
  const int32_t firstColumn =
      std::clamp(static_cast<int32_t>(std::floor(minimumX)), 0, canvasWidth);
  const int32_t endColumn = std::clamp(static_cast<int32_t>(std::ceil(maximumX)), 0, canvasWidth);
  const int32_t firstRow = std::clamp(static_cast<int32_t>(std::floor(minimumY)), 0, canvasHeight);
  const int32_t endRow = std::clamp(static_cast<int32_t>(std::ceil(maximumY)), 0, canvasHeight);
  if (endColumn <= firstColumn || endRow <= firstRow) {
    return tiles;
  }
  const int32_t width = endColumn - firstColumn;
  std::vector<Edge> edges;
  for (size_t index = 0; index < points.size(); ++index) {
    const PolygonPoint& from = points[index];
    const PolygonPoint& to = points[(index + 1) % points.size()];
    clipEdgeHorizontally({from.x - firstColumn, from.y, to.x - firstColumn, to.y}, 0.0,
                         static_cast<double>(width), edges);
  }
  const auto stride = static_cast<size_t>(width + 2);
  std::vector<double> buffer;
  const int32_t firstTileRow = firstRow / kTileSizePixels;
  const int32_t endTileRow = (endRow + kTileSizePixels - 1) / kTileSizePixels;
  const int32_t firstTileColumn = firstColumn / kTileSizePixels;
  const int32_t endTileColumn = (endColumn + kTileSizePixels - 1) / kTileSizePixels;
  for (int32_t tileRow = firstTileRow; tileRow < endTileRow; ++tileRow) {
    const int32_t bandTop = tileRow * kTileSizePixels;
    const int32_t bandRows = std::min(kTileSizePixels, canvasHeight - bandTop);
    buffer.assign(static_cast<size_t>(bandRows) * stride, 0.0);
    for (Edge edge : edges) {
      edge.y0 -= bandTop;
      edge.y1 -= bandTop;
      accumulateEdge(edge, bandRows, width, buffer);
    }
    for (int32_t row = 0; row < bandRows; ++row) {
      const int32_t canvasY = bandTop + row;
      if (canvasY < firstRow || canvasY >= endRow) {
        continue;
      }
      double sum = 0;
      double* line = buffer.data() + static_cast<size_t>(row) * stride;
      for (int32_t column = 0; column < width; ++column) {
        sum += line[column];
        line[column] = std::min(std::abs(sum), 1.0);
      }
    }
    for (int32_t tileColumn = firstTileColumn; tileColumn < endTileColumn; ++tileColumn) {
      TileCoverage coverage(static_cast<size_t>(kTileSizePixels) * kTileSizePixels, 0);
      bool any = false;
      const int32_t tileLeft = tileColumn * kTileSizePixels;
      const int32_t fromX = std::max(tileLeft, firstColumn);
      const int32_t toX = std::min(tileLeft + kTileSizePixels, endColumn);
      for (int32_t row = 0; row < bandRows; ++row) {
        const int32_t canvasY = bandTop + row;
        if (canvasY < firstRow || canvasY >= endRow) {
          continue;
        }
        const double* line = buffer.data() + static_cast<size_t>(row) * stride;
        for (int32_t x = fromX; x < toX; ++x) {
          const auto value = static_cast<uint8_t>(std::lround(line[x - firstColumn] * 255.0));
          coverage[static_cast<size_t>(row) * kTileSizePixels + static_cast<size_t>(x - tileLeft)] =
              value;
          any = any || value != 0;
        }
      }
      if (any) {
        tiles.emplace(TileCoordinate{tileColumn, tileRow}, std::move(coverage));
      }
    }
  }
  return tiles;
}

std::vector<PolygonPoint> rectanglePolygon(double x, double y, double width, double height) {
  return {{x, y}, {x + width, y}, {x + width, y + height}, {x, y + height}};
}

std::vector<PolygonPoint> ellipsePolygon(double x, double y, double width, double height) {
  const double radiusX = std::abs(width) / 2;
  const double radiusY = std::abs(height) / 2;
  const double centerX = x + width / 2;
  const double centerY = y + height / 2;
  const double radius = std::max(radiusX, radiusY);
  int32_t segments = 16;
  if (radius > kEllipseTolerancePixels) {
    const double angle = 2 * std::acos(std::max(1 - kEllipseTolerancePixels / radius, -1.0));
    segments = std::clamp(static_cast<int32_t>(std::ceil(2 * kPi / angle)), 16, 8192);
  }
  std::vector<PolygonPoint> points;
  points.reserve(static_cast<size_t>(segments));
  for (int32_t index = 0; index < segments; ++index) {
    const double angle = 2 * kPi * index / segments;
    points.push_back({centerX + radiusX * std::cos(angle), centerY + radiusY * std::sin(angle)});
  }
  return points;
}

double combineSelection(SelectionCombine mode, double existing, double coverage) {
  switch (mode) {
    case SelectionCombine::Replace:
      return coverage;
    case SelectionCombine::Add:
      return std::max(existing, coverage);
    case SelectionCombine::Subtract:
      return existing * (1 - coverage);
    case SelectionCombine::Intersect:
      return std::min(existing, coverage);
  }
  return coverage;
}

std::vector<float> featherWeights(double radius) {
  radius = std::clamp(radius, 0.0, kMaximumFeatherRadius);
  if (radius <= 0.5) {
    return {1.0F};
  }
  const double sigma = radius / 3;
  const auto halfWidth = static_cast<int32_t>(std::ceil(radius));
  std::vector<double> weights(static_cast<size_t>(halfWidth) + 1);
  double total = 0;
  for (int32_t offset = 0; offset <= halfWidth; ++offset) {
    const double weight = std::exp(-0.5 * offset * offset / (sigma * sigma));
    weights[static_cast<size_t>(offset)] = weight;
    total += offset == 0 ? weight : 2 * weight;
  }
  std::vector<float> normalized;
  normalized.reserve(weights.size());
  for (const double weight : weights) {
    normalized.push_back(static_cast<float>(weight / total));
  }
  return normalized;
}

}  // namespace nanquim::canvas
