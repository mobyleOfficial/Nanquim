#pragma once

#include <cstddef>
#include <vector>

#include "core/affine_transform.h"
#include "stroke/dab.h"

namespace nanquim::stroke {

inline constexpr size_t kMaximumSymmetryCopies = 32;

Dab symmetricDab(const Dab& dab, const core::AffineTransform& copy);

void replicateDabs(const std::vector<core::AffineTransform>& copies, std::vector<Dab>& dabs,
                   size_t first);

}  // namespace nanquim::stroke
