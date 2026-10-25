#pragma once

#include <cstdint>
#include <optional>
#include <unordered_map>
#include <vector>

#include "canvas/layer_tree.h"

namespace nanquim::canvas {

struct PlanOperation {
  enum class Kind {
    Draw,
    Push,
    Pop,
  };

  Kind kind = Kind::Draw;
  int32_t level = 0;
  LayerId layer = 0;
  std::optional<LayerMaskInfo> mask;
  float opacity = 1;
  BlendMode mode = BlendMode::Normal;
  bool clip = false;
  std::array<float, 4> tint{0, 0, 0, 0};
  int32_t unit = 0;

  [[nodiscard]] bool isPlainOver() const {
    return kind == Kind::Draw && mode == BlendMode::Normal && !clip && !mask;
  }
};

struct CompositePlan {
  std::vector<PlanOperation> operations;
  int32_t levels = 1;
  int32_t unitCount = 0;
  std::unordered_map<LayerId, int32_t> unitOfSurface;
  std::vector<LayerId> surfaces;

  std::optional<size_t> activeOperation;
  bool aboveIsOver = false;
  bool allPlainOver = true;

  [[nodiscard]] std::optional<int32_t> activeUnit() const {
    return activeOperation ? std::optional<int32_t>(operations[*activeOperation].unit)
                           : std::nullopt;
  }
};

CompositePlan buildCompositePlan(const LayerTree& tree, std::optional<LayerId> activeLayer);

}  // namespace nanquim::canvas
