#pragma once

#include <cmath>
#include <optional>

namespace nanquim::core {

struct Point {
  double x = 0;
  double y = 0;
};

struct AffineTransform {
  double a = 1;
  double b = 0;
  double c = 0;
  double d = 1;
  double tx = 0;
  double ty = 0;

  static AffineTransform translation(double offsetX, double offsetY) {
    return AffineTransform{1, 0, 0, 1, offsetX, offsetY};
  }

  static AffineTransform scale(double factorX, double factorY) {
    return AffineTransform{factorX, 0, 0, factorY, 0, 0};
  }

  static AffineTransform rotation(double radians) {
    const double cosine = std::cos(radians);
    const double sine = std::sin(radians);
    return AffineTransform{cosine, sine, -sine, cosine, 0, 0};
  }

  [[nodiscard]] Point apply(Point point) const {
    return Point{a * point.x + c * point.y + tx, b * point.x + d * point.y + ty};
  }

  [[nodiscard]] AffineTransform then(const AffineTransform& next) const {
    return AffineTransform{
        next.a * a + next.c * b,
        next.b * a + next.d * b,
        next.a * c + next.c * d,
        next.b * c + next.d * d,
        next.a * tx + next.c * ty + next.tx,
        next.b * tx + next.d * ty + next.ty,
    };
  }

  [[nodiscard]] double determinant() const { return a * d - b * c; }

  [[nodiscard]] double scaleFactor() const { return std::sqrt(std::fabs(determinant())); }

  [[nodiscard]] std::optional<AffineTransform> inverted() const {
    const double det = determinant();
    if (std::fabs(det) < 1e-12) {
      return std::nullopt;
    }
    const double inverseA = d / det;
    const double inverseB = -b / det;
    const double inverseC = -c / det;
    const double inverseD = a / det;
    return AffineTransform{inverseA,
                           inverseB,
                           inverseC,
                           inverseD,
                           -(inverseA * tx + inverseC * ty),
                           -(inverseB * tx + inverseD * ty)};
  }
};

}  // namespace nanquim::core
