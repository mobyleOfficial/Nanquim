#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "brush_pad.h"
#include "scene/scene.h"
#include "stroke/loaded_brush.h"

namespace nanquim::reference {

enum class SheetItem : int32_t { A, B, C, D, E, F, G, I };

struct SheetRegion {
  SheetItem item;
  PixelRect rect;
};

inline constexpr int32_t kSheetSize = 2048;

std::vector<SheetRegion> sheetRegions(double scale = 1);

scene::Scene testSheetScene(const stroke::LoadedBrush& brush, double scale = 1, double sizePixels = 0,
                            double opacity = 0);

double defaultBrushSize(const stroke::BrushParameters& brush);
double defaultBrushOpacity(const stroke::BrushParameters& brush);

std::vector<uint8_t> renderSheetTemplate(double scale);

}  // namespace nanquim::reference
