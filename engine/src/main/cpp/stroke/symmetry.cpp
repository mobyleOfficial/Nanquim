#include "stroke/symmetry.h"

#include <cmath>

namespace nanquim::stroke {

namespace {

double mappedAngle(const core::AffineTransform& copy, double angle) {
  const double x = std::cos(angle);
  const double y = std::sin(angle);
  return std::atan2(copy.b * x + copy.d * y, copy.a * x + copy.c * y);
}

}  // namespace

Dab symmetricDab(const Dab& dab, const core::AffineTransform& copy) {
  Dab result = dab;
  const core::Point center = copy.apply({dab.x, dab.y});
  result.x = center.x;
  result.y = center.y;
  result.diameter = dab.diameter * copy.scaleFactor();
  result.angle = mappedAngle(copy, dab.angle);
  if (copy.determinant() < 0) {
    result.flipY = !dab.flipY;
  }
  result.gradationAngle = mappedAngle(copy, dab.gradationAngle);
  return result;
}

void replicateDabs(const std::vector<core::AffineTransform>& copies, std::vector<Dab>& dabs,
                   size_t first) {
  if (copies.empty() || first >= dabs.size()) {
    return;
  }
  const size_t count = dabs.size() - first;
  const size_t n = copies.size();
  dabs.resize(first + count * n);
  for (size_t index = count; index-- > 0;) {
    const Dab dab = dabs[first + index];
    for (size_t copy = n; copy-- > 0;) {
      dabs[first + index * n + copy] = symmetricDab(dab, copies[copy]);
    }
  }
}

}  // namespace nanquim::stroke
