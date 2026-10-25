#pragma once

#include <array>
#include <utility>
#include <vector>

namespace nanquim::stroke {

struct PathPoint {
  double x = 0;
  double y = 0;
  double pressure = 0;
  double tilt = 0;
  double orientation = 0;
  double timeMilliseconds = 0;
  double speed = 0;
  bool finger = false;
};

class CatmullRomSegment {
 public:
  CatmullRomSegment(const PathPoint& p0, const PathPoint& p1, const PathPoint& p2,
                    const PathPoint& p3);

  [[nodiscard]] double length() const { return length_; }
  [[nodiscard]] const PathPoint& end() const { return end_; }

  [[nodiscard]] PathPoint pointAtDistance(double distance) const;

  [[nodiscard]] std::array<double, 2> tangentAtDistance(double distance) const;

 private:
  [[nodiscard]] std::array<double, 2> positionAt(double parameter) const;
  [[nodiscard]] double parameterAtDistance(double distance) const;
  void subdivide(double startParameter, std::array<double, 2> start, double endParameter,
                 std::array<double, 2> end, int depth);

  std::array<std::array<double, 2>, 4> controls_{};
  std::array<double, 4> knots_{};
  PathPoint start_;
  PathPoint end_;
  std::vector<std::pair<double, double>> arcTable_;
  double length_ = 0;
};

}  // namespace nanquim::stroke
