#include "reference_compositor.h"

#include "blend_reference.h"
#include "canvas/color_conversion.h"

namespace nanquim::reference {

using canvas::BlendMode;
using canvas::LayerTreeNode;

namespace {

class TreeCompositor {
 public:
  TreeCompositor(int32_t width, int32_t height, const SurfaceLookup& surfaces, bool linear = false)
      : width_(width), height_(height), surfaces_(surfaces), linear_(linear) {}

  void composeNodes(const std::vector<LayerTreeNode>& nodes, Image& backdrop,
                    double inheritedOpacity) {
    size_t index = 0;
    while (index < nodes.size()) {
      const LayerTreeNode& base = nodes[index];
      size_t end = index + 1;
      while (end < nodes.size() && !nodes[end].isGroup() && nodes[end].clipping) {
        ++end;
      }
      if (!base.hidden) {
        std::vector<const LayerTreeNode*> clipped;
        for (size_t clippedIndex = index + 1; clippedIndex < end; ++clippedIndex) {
          if (!nodes[clippedIndex].hidden && nodes[clippedIndex].opacity > 0) {
            clipped.push_back(&nodes[clippedIndex]);
          }
        }
        if (clipped.empty()) {
          composeNode(base, backdrop, inheritedOpacity);
        } else {
          Image content = isolatedContent(base);
          for (const LayerTreeNode* layer : clipped) {
            Image source = layerSource(*layer);
            blendImage(content, source, layer->blend, layer->opacity, true);
          }
          const BlendMode mode = base.isPassThrough() ? BlendMode::Normal : base.blend;
          blendImage(backdrop, content, mode, base.opacity * inheritedOpacity, false);
        }
      }
      index = end;
    }
  }

  void composeNode(const LayerTreeNode& node, Image& backdrop, double inheritedOpacity) {
    if (node.hidden) {
      return;
    }
    if (!node.isGroup()) {
      Image source = layerSource(node);
      blendImage(backdrop, source, node.blend, node.opacity * inheritedOpacity, false);
    } else if (node.isPassThrough()) {
      composeNodes(node.children, backdrop, inheritedOpacity * node.opacity);
    } else {
      Image group(width_, height_);
      composeNodes(node.children, group, 1);
      blendImage(backdrop, group, node.blend, node.opacity * inheritedOpacity, false);
    }
  }

  Image layerSource(const LayerTreeNode& layer) {
    const Image* pixels = surfaces_ ? surfaces_(layer.id) : nullptr;
    Image source = pixels != nullptr ? *pixels : Image(width_, height_);
    if (layer.mask && layer.mask->enabled) {
      const Image* mask = surfaces_ ? surfaces_(layer.mask->id) : nullptr;
      for (size_t offset = 0; offset < source.rgba.size(); offset += 4) {
        double value = mask != nullptr ? mask->rgba[offset] : 1.0;
        if (layer.mask->inverted) {
          value = 1 - value;
        }
        for (size_t channel = 0; channel < 4; ++channel) {
          source.rgba[offset + channel] *= value;
        }
      }
    }
    if (layer.tint && (*layer.tint)[3] > 0) {
      const auto& tint = *layer.tint;
      for (size_t offset = 0; offset < source.rgba.size(); offset += 4) {
        const double alpha = source.rgba[offset + 3];
        for (size_t channel = 0; channel < 3; ++channel) {
          const double color = linear_ ? canvas::srgbToLinearValue(tint[channel]) : tint[channel];
          source.rgba[offset + channel] += (color * alpha - source.rgba[offset + channel]) * tint[3];
        }
      }
    }
    return source;
  }

 private:
  Image isolatedContent(const LayerTreeNode& node) {
    if (!node.isGroup()) {
      return layerSource(node);
    }
    Image group(width_, height_);
    composeNodes(node.children, group, 1);
    return group;
  }

  static void blendImage(Image& backdrop, const Image& source, BlendMode mode, double opacity,
                         bool clip) {
    if (opacity <= 0) {
      return;
    }
    for (size_t offset = 0; offset < backdrop.rgba.size(); offset += 4) {
      const double* sourcePixel = &source.rgba[offset];
      double* backdropPixel = &backdrop.rgba[offset];
      const Rgba sourceColor{sourcePixel[0] * opacity, sourcePixel[1] * opacity,
                             sourcePixel[2] * opacity, sourcePixel[3] * opacity};
      const Rgba backdropColor{backdropPixel[0], backdropPixel[1], backdropPixel[2],
                               backdropPixel[3]};
      const Rgba result = clip ? clipCompositePixel(mode, backdropColor, sourceColor)
                               : compositePixel(mode, backdropColor, sourceColor);
      backdropPixel[0] = result.red;
      backdropPixel[1] = result.green;
      backdropPixel[2] = result.blue;
      backdropPixel[3] = result.alpha;
    }
  }

  int32_t width_;
  int32_t height_;
  const SurfaceLookup& surfaces_;
  bool linear_ = false;
};

}  // namespace

Image composeLayerTree(const canvas::LayerTree& tree, int32_t width, int32_t height,
                       const SurfaceLookup& surfaces) {
  Image result(width, height);
  if (!tree.backgroundHidden) {
    const double alpha = tree.background[3];
    for (size_t offset = 0; offset < result.rgba.size(); offset += 4) {
      for (size_t channel = 0; channel < 3; ++channel) {
        const double color = canvas::isLinear(tree.pixelFormat)
                                 ? canvas::srgbToLinearValue(tree.background[channel])
                                 : tree.background[channel];
        result.rgba[offset + channel] = color * alpha;
      }
      result.rgba[offset + 3] = alpha;
    }
  }
  TreeCompositor compositor(width, height, surfaces, canvas::isLinear(tree.pixelFormat));
  if (tree.solo) {
    if (const LayerTreeNode* solo = tree.find(*tree.solo)) {
      LayerTreeNode shown = *solo;
      shown.hidden = false;
      compositor.composeNode(shown, result, 1);
    }
    return result;
  }
  compositor.composeNodes(tree.layers, result, 1);
  return result;
}

}  // namespace nanquim::reference
