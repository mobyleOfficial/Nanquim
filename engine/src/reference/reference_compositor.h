#pragma once

#include <functional>

#include "canvas/layer_tree.h"
#include "reference_image.h"

namespace nanquim::reference {

using SurfaceLookup = std::function<const Image*(canvas::LayerId)>;

Image composeLayerTree(const canvas::LayerTree& tree, int32_t width, int32_t height,
                       const SurfaceLookup& surfaces);

}  // namespace nanquim::reference
