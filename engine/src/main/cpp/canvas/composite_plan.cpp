#include "canvas/composite_plan.h"

#include <algorithm>

namespace nanquim::canvas {

namespace {

class PlanBuilder {
 public:
  explicit PlanBuilder(CompositePlan& plan) : plan_(plan) {}

  void nodes(const std::vector<LayerTreeNode>& siblings, int32_t level, float inheritedOpacity) {
    size_t index = 0;
    while (index < siblings.size()) {
      const LayerTreeNode& base = siblings[index];
      size_t end = index + 1;
      while (end < siblings.size() && !siblings[end].isGroup() && siblings[end].clipping) {
        ++end;
      }
      if (!base.hidden && base.opacity > 0) {
        std::vector<const LayerTreeNode*> clipped;
        for (size_t clippedIndex = index + 1; clippedIndex < end; ++clippedIndex) {
          if (!siblings[clippedIndex].hidden && siblings[clippedIndex].opacity > 0) {
            clipped.push_back(&siblings[clippedIndex]);
          }
        }
        if (clipped.empty()) {
          node(base, level, inheritedOpacity);
        } else {
          clippingRun(base, clipped, level, inheritedOpacity);
        }
      }
      index = end;
    }
  }

  void node(const LayerTreeNode& node, int32_t level, float inheritedOpacity) {
    if (node.hidden || node.opacity <= 0) {
      return;
    }
    if (!node.isGroup()) {
      draw(node, level, node.opacity * inheritedOpacity, node.blend, false);
    } else if (node.isPassThrough()) {
      nodes(node.children, level, inheritedOpacity * node.opacity);
    } else {
      const bool startsUnit = level == 0;
      push(level + 1, startsUnit);
      nodes(node.children, level + 1, 1);
      pop(level, node.blend, node.opacity * inheritedOpacity);
    }
  }

 private:
  void clippingRun(const LayerTreeNode& base, const std::vector<const LayerTreeNode*>& clipped,
                   int32_t level, float inheritedOpacity) {
    const bool startsUnit = level == 0;
    push(level + 1, startsUnit);
    if (base.isGroup()) {
      nodes(base.children, level + 1, 1);
    } else {
      draw(base, level + 1, 1, BlendMode::Normal, false);
    }
    for (const LayerTreeNode* layer : clipped) {
      draw(*layer, level + 1, layer->opacity, layer->blend, true);
    }
    const BlendMode mode = base.isPassThrough() ? BlendMode::Normal : base.blend;
    pop(level, mode, base.opacity * inheritedOpacity);
  }

  void draw(const LayerTreeNode& layer, int32_t level, float opacity, BlendMode mode, bool clip) {
    PlanOperation operation;
    operation.kind = PlanOperation::Kind::Draw;
    operation.level = level;
    operation.layer = layer.id;
    if (layer.mask && layer.mask->enabled) {
      operation.mask = layer.mask;
    }
    operation.opacity = opacity;
    operation.mode = mode;
    operation.clip = clip;
    operation.tint = layer.tint.value_or(std::array<float, 4>{0, 0, 0, 0});
    operation.unit = level == 0 ? plan_.unitCount++ : currentUnit_;
    addSurface(layer.id, operation.unit);
    if (operation.mask) {
      addSurface(operation.mask->id, operation.unit);
    }
    plan_.operations.push_back(operation);
  }

  void push(int32_t level, bool startsUnit) {
    if (startsUnit) {
      currentUnit_ = plan_.unitCount++;
    }
    PlanOperation operation;
    operation.kind = PlanOperation::Kind::Push;
    operation.level = level;
    operation.unit = currentUnit_;
    plan_.levels = std::max(plan_.levels, level + 1);
    plan_.operations.push_back(operation);
  }

  void pop(int32_t level, BlendMode mode, float opacity) {
    PlanOperation operation;
    operation.kind = PlanOperation::Kind::Pop;
    operation.level = level;
    operation.mode = mode;
    operation.opacity = opacity;
    operation.unit = currentUnit_;
    plan_.operations.push_back(operation);
  }

  void addSurface(LayerId surface, int32_t unit) {
    if (plan_.unitOfSurface.emplace(surface, unit).second) {
      plan_.surfaces.push_back(surface);
    }
  }

  CompositePlan& plan_;
  int32_t currentUnit_ = 0;
};

}  // namespace

CompositePlan buildCompositePlan(const LayerTree& tree, std::optional<LayerId> activeLayer) {
  CompositePlan plan;
  PlanBuilder builder(plan);
  if (tree.solo) {
    if (const LayerTreeNode* solo = tree.find(*tree.solo)) {
      LayerTreeNode shown = *solo;
      shown.hidden = false;
      builder.node(shown, 0, 1);
    }
  } else {
    builder.nodes(tree.layers, 0, 1);
  }
  for (size_t index = 0; index < plan.operations.size(); ++index) {
    const PlanOperation& operation = plan.operations[index];
    if (!operation.isPlainOver() || operation.level != 0) {
      plan.allPlainOver = false;
    }
    if (activeLayer && operation.kind == PlanOperation::Kind::Draw &&
        operation.layer == *activeLayer && operation.level == 0) {
      plan.activeOperation = index;
    }
  }
  if (plan.activeOperation) {
    const int32_t activeUnit = plan.operations[*plan.activeOperation].unit;
    plan.aboveIsOver = true;
    for (const PlanOperation& operation : plan.operations) {
      if (operation.unit <= activeUnit) {
        continue;
      }
      const bool rootStep = operation.level == 0 && operation.kind != PlanOperation::Kind::Push;
      if (rootStep && (operation.mode != BlendMode::Normal || operation.clip)) {
        plan.aboveIsOver = false;
      }
    }
  }
  return plan;
}

}  // namespace nanquim::canvas
