#pragma once

#include <array>
#include <cstdint>
#include <functional>
#include <nlohmann/json.hpp>
#include <optional>
#include <string>
#include <vector>

#include "canvas/pixel_format.h"
#include "canvas/blend_mode.h"

namespace nanquim::canvas {

using LayerId = uint32_t;

struct LayerMaskInfo {
  LayerId id = 0;
  bool enabled = true;
  bool inverted = false;
};

struct LayerTreeNode {
  enum class Type { Raster, Group };

  Type type = Type::Raster;
  LayerId id = 0;
  float opacity = 1;
  BlendMode blend = BlendMode::Normal;
  bool hidden = false;
  bool alphaLock = false;
  bool clipping = false;
  std::optional<LayerMaskInfo> mask;
  std::optional<std::array<float, 4>> tint;
  std::vector<LayerTreeNode> children;

  [[nodiscard]] bool isGroup() const { return type == Type::Group; }
  [[nodiscard]] bool isPassThrough() const {
    return type == Type::Group && blend == BlendMode::PassThrough;
  }
};

struct LayerTree {
  std::array<float, 4> background{1, 1, 1, 1};
  PixelFormat pixelFormat = PixelFormat::Rgba8PremulSrgb;
  bool backgroundHidden = false;
  std::optional<LayerId> activeLayer;
  std::optional<LayerId> solo;
  std::vector<LayerTreeNode> layers;

  [[nodiscard]] const LayerTreeNode* find(LayerId id) const;
  [[nodiscard]] std::vector<const LayerTreeNode*> rasterLayers() const;
  [[nodiscard]] std::vector<LayerId> surfaceIds() const;
  [[nodiscard]] std::vector<const LayerTreeNode*> ancestors(LayerId id) const;
  [[nodiscard]] const LayerTreeNode* maskOwner(LayerId maskId) const;
};

inline constexpr int32_t kMaximumGroupDepth = 32;

std::optional<LayerTree> parseLayerTreeJson(const nlohmann::json& value, std::string& error);
std::optional<LayerTree> parseLayerTree(std::string_view jsonText, std::string& error);

}  // namespace nanquim::canvas
