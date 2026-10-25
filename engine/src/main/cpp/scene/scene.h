#pragma once

#include <array>
#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "adjust/adjustment.h"
#include "adjust/liquify.h"
#include "canvas/layer_tree.h"
#include "canvas/polygon_coverage.h"
#include "canvas/transform_mesh.h"
#include "stroke/brush_parameters.h"
#include "stroke/brush_texture.h"
#include "stroke/input_sample.h"
#include "stroke/loaded_brush.h"
#include "stroke/tip_coverage.h"
#include "text/font_face.h"
#include "text/text_model.h"

namespace nanquim::scene {

enum class Tool { Paint, Erase, Smudge };

struct StrokeAction {
  Tool tool = Tool::Paint;
  int32_t layer = 1;
  bool mask = false;
  bool selection = false;
  bool selectionTarget = false;
  bool adjustmentMaskTarget = false;
  struct Clone {
    bool composite = true;
    int32_t layer = 0;
    std::array<int32_t, 2> offset{};
  };
  std::optional<Clone> clone;
  stroke::BrushParameters brush;
  std::shared_ptr<const stroke::GrayTexture> shapeTexture;
  std::shared_ptr<const stroke::GrayTexture> grainTexture;
  std::shared_ptr<const stroke::LoadedBrush> dual;
  stroke::StrokeSettings settings;
  std::vector<stroke::InputSample> samples;
  std::vector<stroke::InputSample> replacement;

  [[nodiscard]] StrokeAction replaced() const;

  [[nodiscard]] stroke::TipStyle tipStyle() const {
    return stroke::TipStyle{brush.shape.hardness, shapeTexture.get(), brush.shape.invert,
                            brush.shape.filtering, &brush.grain, grainTexture.get()};
  }
};

struct FillAction {
  int32_t layer = 1;
  bool mask = false;
  bool selection = false;
  std::array<int32_t, 4> rect{};
  std::array<double, 4> color{0, 0, 0, 1};
  std::optional<std::array<double, 4>> toColor;
  bool vertical = false;

  [[nodiscard]] std::array<double, 4> colorAt(int32_t x, int32_t y) const;
};

struct SelectAction {
  enum class Operation { All, None, Invert, Contents, Polygon, Feather, Auto };
  Operation operation = Operation::All;
  int32_t layer = 1;
  std::vector<canvas::PolygonPoint> polygon;
  canvas::SelectionCombine combine = canvas::SelectionCombine::Replace;
  double radius = 0;
  double x = 0;
  double y = 0;
  double threshold = 0.1;
  bool expand = true;
};

inline constexpr canvas::LayerId kSelectionSurface = 0xFFFFFF00U;
inline constexpr canvas::LayerId kAdjustmentMaskSurface = 0xFFFFFF01U;

struct FloodFillAction {
  int32_t layer = 1;
  bool mask = false;
  int32_t source = 1;
  double x = 0;
  double y = 0;
  double threshold = 0.1;
  bool expand = true;
  bool whole = false;
  std::array<double, 4> color{0, 0, 0, 1};
  bool selection = false;
};

struct TransformAction {
  int32_t layer = 1;
  bool selection = false;
  std::vector<canvas::MeshVertex> mesh;
  canvas::TransformFilter filter = canvas::TransformFilter::Bilinear;
};

struct AdjustAction {
  int32_t layer = 1;
  bool selection = false;
  bool pencil = false;
  adjust::Parameters parameters;
  double amount = 1;
};

struct LiquifyAction {
  int32_t layer = 1;
  bool selection = false;
  struct Stroke {
    adjust::LiquifyBrush brush;
    std::vector<adjust::LiquifySample> samples;
  };
  std::vector<Stroke> strokes;
  double adjust = 1;
};

adjust::LiquifyField liquifyField(const LiquifyAction& action, int32_t width, int32_t height);

struct CopyLayerAction {
  int32_t from = 1;
  int32_t to = 2;
  bool masked = false;
};

struct TextAction {
  int32_t layer = 1;
  double dpi = 72;
  std::string modelJson;
  std::shared_ptr<text::FontLibrary> library;
  std::vector<std::pair<std::string, std::shared_ptr<text::FontFace>>> fonts;
  std::vector<std::string> fallbacks;

  [[nodiscard]] std::optional<text::TextModel> model(bool linearLight, std::string& error) const;
};

struct SceneAction {
  enum class Type { Stroke, Fill, Select, FloodFill, CopyLayer, Transform, Adjust, Liquify, Text };
  Type type = Type::Stroke;
  StrokeAction stroke;
  FillAction fill;
  SelectAction select;
  FloodFillAction floodFill;
  CopyLayerAction copyLayer;
  TransformAction transform;
  AdjustAction adjust;
  LiquifyAction liquify;
  TextAction text;

  static SceneAction of(TextAction text) {
    SceneAction action;
    action.type = Type::Text;
    action.text = std::move(text);
    return action;
  }
  static SceneAction of(LiquifyAction liquify) {
    SceneAction action;
    action.type = Type::Liquify;
    action.liquify = std::move(liquify);
    return action;
  }
  static SceneAction of(StrokeAction stroke) {
    SceneAction action;
    action.stroke = std::move(stroke);
    return action;
  }
  static SceneAction of(FillAction fill) {
    SceneAction action;
    action.type = Type::Fill;
    action.fill = fill;
    return action;
  }
  static SceneAction of(TransformAction transform) {
    SceneAction action;
    action.type = Type::Transform;
    action.transform = std::move(transform);
    return action;
  }
  static SceneAction of(AdjustAction adjust) {
    SceneAction action;
    action.type = Type::Adjust;
    action.adjust = adjust;
    return action;
  }
  static SceneAction of(CopyLayerAction copyLayer) {
    SceneAction action;
    action.type = Type::CopyLayer;
    action.copyLayer = copyLayer;
    return action;
  }
  static SceneAction of(FloodFillAction floodFill) {
    SceneAction action;
    action.type = Type::FloodFill;
    action.floodFill = floodFill;
    return action;
  }
  static SceneAction of(SelectAction select) {
    SceneAction action;
    action.type = Type::Select;
    action.select = select;
    return action;
  }
};

struct Scene {
  Scene() { setRasterLayerCount(1); }

  std::string documentId;
  int32_t width = 0;
  int32_t height = 0;
  std::array<double, 4> background{1, 1, 1, 1};
  canvas::LayerTree layers;
  std::vector<SceneAction> actions;

  void setRasterLayerCount(int32_t count);
};

double maskLuminance(const std::array<double, 4>& color);

using FileReader = std::function<std::optional<std::vector<uint8_t>>(const std::string& name)>;

std::optional<Scene> parseScene(std::string_view jsonText, const FileReader& readFile,
                                std::string& error);

}  // namespace nanquim::scene
