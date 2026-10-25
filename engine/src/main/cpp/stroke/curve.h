#pragma once

#include <utility>
#include <vector>

namespace nanquim::stroke {

class Curve {
 public:
  explicit Curve(std::vector<std::pair<double, double>> points);

  static Curve linear();
  static Curve soft();
  static Curve hard();

  [[nodiscard]] double evaluate(double x) const;

  [[nodiscard]] const std::vector<std::pair<double, double>>& points() const { return points_; }

 private:
  std::vector<std::pair<double, double>> points_;
  std::vector<double> tangents_;
};

}  // namespace nanquim::stroke
