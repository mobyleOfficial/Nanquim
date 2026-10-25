#include "canvas/layer_tree.h"

#include <cmath>
#include <stdexcept>
#include <unordered_set>

namespace nanquim::canvas {

namespace {

using Json = nlohmann::json;

const LayerTreeNode* findIn(const std::vector<LayerTreeNode>& nodes, LayerId id) {
  for (const LayerTreeNode& node : nodes) {
    if (node.id == id) {
      return &node;
    }
    if (const LayerTreeNode* found = findIn(node.children, id)) {
      return found;
    }
  }
  return nullptr;
}

bool collectAncestors(const std::vector<LayerTreeNode>& nodes, LayerId id,
                      std::vector<const LayerTreeNode*>& path) {
  for (const LayerTreeNode& node : nodes) {
    if (node.id == id) {
      return true;
    }
    if (node.isGroup()) {
      path.push_back(&node);
      if (collectAncestors(node.children, id, path)) {
        return true;
      }
      path.pop_back();
    }
  }
  return false;
}

LayerId readId(const Json& value, const char* what) {
  if (!value.is_number_integer() || value.get<int64_t>() <= 0 ||
      value.get<int64_t>() > static_cast<int64_t>(UINT32_MAX)) {
    throw std::invalid_argument(std::string(what) + " must be a positive integer");
  }
  return static_cast<LayerId>(value.get<int64_t>());
}

void useId(LayerId id, std::unordered_set<LayerId>& used) {
  if (!used.insert(id).second) {
    throw std::invalid_argument("duplicate id " + std::to_string(id));
  }
}

LayerTreeNode readNode(const Json& value, int32_t depth, std::unordered_set<LayerId>& used) {
  if (depth > kMaximumGroupDepth) {
    throw std::invalid_argument("groups are nested too deeply");
  }
  if (!value.is_object()) {
    throw std::invalid_argument("a layer must be an object");
  }
  LayerTreeNode node;
  const std::string type = value.value("type", "raster");
  if (type == "group") {
    node.type = LayerTreeNode::Type::Group;
  } else if (type != "raster") {
    throw std::invalid_argument("unknown layer type: " + type);
  }
  node.id = readId(value.at("id"), "a layer id");
  useId(node.id, used);
  const double opacity = value.value("opacity", 1.0);
  if (!std::isfinite(opacity) || opacity < 0 || opacity > 1) {
    throw std::invalid_argument("opacity must be in [0, 1]");
  }
  node.opacity = static_cast<float>(opacity);
  const std::string blend =
      value.value("blend", node.isGroup() ? std::string("passThrough") : std::string("normal"));
  const auto mode = blendModeFromId(blend);
  if (!mode || *mode == BlendMode::Behind ||
      (*mode == BlendMode::PassThrough && !node.isGroup())) {
    throw std::invalid_argument("invalid layer blend mode: " + blend);
  }
  node.blend = *mode;
  node.hidden = value.value("hidden", false);
  if (node.isGroup()) {
    const Json& children = value.contains("children") ? value.at("children") : Json::array();
    if (!children.is_array()) {
      throw std::invalid_argument("children must be an array");
    }
    for (const Json& child : children) {
      node.children.push_back(readNode(child, depth + 1, used));
    }
    return node;
  }
  node.alphaLock = value.value("alphaLock", false);
  node.clipping = value.value("clipping", false);
  if (value.contains("tint") && !value.at("tint").is_null()) {
    const Json& tint = value.at("tint");
    if (!tint.is_array() || tint.size() != 4) {
      throw std::invalid_argument("tint must be [r, g, b, amount]");
    }
    std::array<float, 4> color{};
    for (size_t channel = 0; channel < 4; ++channel) {
      color[channel] = std::clamp(tint.at(channel).get<float>(), 0.0F, 1.0F);
    }
    node.tint = color;
  }
  if (value.contains("mask") && !value.at("mask").is_null()) {
    const Json& mask = value.at("mask");
    LayerMaskInfo info;
    info.id = readId(mask.at("id"), "a mask id");
    useId(info.id, used);
    info.enabled = mask.value("enabled", true);
    info.inverted = mask.value("inverted", false);
    node.mask = info;
  }
  return node;
}

}  // namespace

const LayerTreeNode* LayerTree::find(LayerId id) const { return findIn(layers, id); }

std::vector<const LayerTreeNode*> LayerTree::rasterLayers() const {
  std::vector<const LayerTreeNode*> result;
  std::function<void(const std::vector<LayerTreeNode>&)> visit =
      [&](const std::vector<LayerTreeNode>& nodes) {
        for (const LayerTreeNode& node : nodes) {
          if (node.isGroup()) {
            visit(node.children);
          } else {
            result.push_back(&node);
          }
        }
      };
  visit(layers);
  return result;
}

std::vector<LayerId> LayerTree::surfaceIds() const {
  std::vector<LayerId> ids;
  for (const LayerTreeNode* layer : rasterLayers()) {
    ids.push_back(layer->id);
    if (layer->mask) {
      ids.push_back(layer->mask->id);
    }
  }
  return ids;
}

std::vector<const LayerTreeNode*> LayerTree::ancestors(LayerId id) const {
  std::vector<const LayerTreeNode*> path;
  if (!collectAncestors(layers, id, path)) {
    return {};
  }
  return {path.rbegin(), path.rend()};
}

const LayerTreeNode* LayerTree::maskOwner(LayerId maskId) const {
  for (const LayerTreeNode* layer : rasterLayers()) {
    if (layer->mask && layer->mask->id == maskId) {
      return layer;
    }
  }
  return nullptr;
}

std::optional<LayerTree> parseLayerTreeJson(const Json& value, std::string& error) {
  try {
    if (!value.is_object()) {
      throw std::invalid_argument("the layer tree must be an object");
    }
    LayerTree tree;
    if (value.contains("pixelFormat")) {
      const Json& format = value.at("pixelFormat");
      const auto parsed = format.is_string() ? parsePixelFormat(format.get<std::string>()) : std::nullopt;
      if (!parsed) {
        throw std::invalid_argument("pixelFormat must be rgba8_premul_srgb or rgba16f_premul_linear");
      }
      tree.pixelFormat = *parsed;
    }
    if (value.contains("background")) {
      const Json& color = value.at("background");
      if (!color.is_array() || color.size() != 4) {
        throw std::invalid_argument("background must be an array of 4 numbers");
      }
      for (size_t channel = 0; channel < 4; ++channel) {
        const double component = color.at(channel).get<double>();
        if (!(component >= 0 && component <= 1)) {
          throw std::invalid_argument("background components must be in [0, 1]");
        }
        tree.background[channel] = static_cast<float>(component);
      }
    }
    tree.backgroundHidden = value.value("backgroundHidden", false);
    std::unordered_set<LayerId> used;
    const Json& layers = value.contains("layers") ? value.at("layers") : Json::array();
    if (!layers.is_array()) {
      throw std::invalid_argument("layers must be an array");
    }
    for (const Json& node : layers) {
      tree.layers.push_back(readNode(node, 1, used));
    }
    if (value.contains("activeLayer") && !value.at("activeLayer").is_null()) {
      tree.activeLayer = readId(value.at("activeLayer"), "activeLayer");
    }
    if (value.contains("solo") && !value.at("solo").is_null()) {
      tree.solo = readId(value.at("solo"), "solo");
      const LayerTreeNode* solo = tree.find(*tree.solo);
      if (solo == nullptr || solo->isGroup()) {
        throw std::invalid_argument("the solo layer must be a raster layer of the tree");
      }
    }
    return tree;
  } catch (const std::exception& exception) {
    error = exception.what();
    return std::nullopt;
  }
}

std::optional<LayerTree> parseLayerTree(std::string_view jsonText, std::string& error) {
  try {
    return parseLayerTreeJson(Json::parse(jsonText), error);
  } catch (const std::exception& exception) {
    error = exception.what();
    return std::nullopt;
  }
}

}  // namespace nanquim::canvas
