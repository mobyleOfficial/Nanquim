#include "scene/scene.h"

#include <algorithm>
#include <nlohmann/json.hpp>

#include "stroke/brush_json.h"
#include "stroke/random.h"
#include "stroke/symmetry.h"

namespace nanquim::scene {

namespace {

using Json = nlohmann::json;

constexpr int32_t kMaximumCanvasPixels = 16384;
constexpr int32_t kMaximumLayers = 999;

std::array<double, 4> readColor(const Json& value) {
  std::array<double, 4> color{};
  if (!value.is_array() || value.size() != 4) {
    throw std::invalid_argument("a color must be an array of 4 numbers");
  }
  for (size_t index = 0; index < 4; ++index) {
    color[index] = value.at(index).get<double>();
    if (color[index] < 0 || color[index] > 1) {
      throw std::invalid_argument("color components must be in [0, 1]");
    }
  }
  return color;
}

stroke::BrushParameters readBrush(const Json& value) {
  if (value.contains("format")) {
    std::string error;
    auto brush = stroke::parseBrush(value, error);
    if (!brush) {
      throw std::invalid_argument("invalid brush: " + error);
    }
    return std::move(*brush);
  }
  const std::string preset = value.value("preset", "round");
  if (preset != "round") {
    throw std::invalid_argument("unknown brush preset: " + preset);
  }
  stroke::BrushParameters brush = stroke::roundBrush();
  stroke::PathParameters& path = brush.path;
  path.spacing = value.value("spacing", path.spacing);
  brush.shape.hardness = value.value("hardness", brush.shape.hardness);
  brush.render.flow = value.value("flow", brush.render.flow);
  brush.dynamics.size.minimum = value.value("sizeMinimum", brush.dynamics.size.minimum);
  brush.dynamics.opacity.minimum = value.value("opacityMinimum", brush.dynamics.opacity.minimum);
  path.streamline = value.value("streamline", path.streamline);
  path.stabilization = value.value("stabilization", path.stabilization);
  path.jitterFilter = value.value("jitterFilter", path.jitterFilter);
  path.pressureSmoothing = value.value("pressureSmoothing", path.pressureSmoothing);
  path.jitterFilterExpression = value.value("jitterFilterExpression", path.jitterFilterExpression);
  if (value.contains("blend")) {
    const auto blend = value.at("blend").get<std::string>();
    const auto mode = canvas::blendModeFromId(blend);
    if (!mode || *mode == canvas::BlendMode::PassThrough) {
      throw std::invalid_argument("unknown brush blend mode: " + blend);
    }
    brush.render.blend = *mode;
  }
  if (value.contains("accumulation")) {
    const auto accumulation = value.at("accumulation").get<std::string>();
    if (accumulation == "wash") {
      brush.render.accumulation = stroke::Accumulation::Wash;
    } else if (accumulation == "buildUp") {
      brush.render.accumulation = stroke::Accumulation::BuildUp;
    } else {
      throw std::invalid_argument("unknown accumulation: " + accumulation);
    }
  }
  return brush;
}

std::shared_ptr<const stroke::GrayTexture> readTexture(const std::string& path, int32_t maximumSide,
                                                       const FileReader& readFile,
                                                       bool keepColors = false) {
  const auto bytes = readFile ? readFile(path) : std::nullopt;
  if (!bytes) {
    throw std::invalid_argument("cannot read texture " + path);
  }
  std::string error;
  auto texture =
      stroke::decodeGrayTexture(bytes->data(), bytes->size(), maximumSide, error, keepColors);
  if (!texture) {
    throw std::invalid_argument("texture " + path + ": " + error);
  }
  return std::make_shared<const stroke::GrayTexture>(std::move(*texture));
}

stroke::LoadedBrush readLoadedBrush(const Json& action, const char* packageKey,
                                    const char* inlineKey, const FileReader& readFile) {
  stroke::LoadedBrush loaded;
  std::string directory;
  if (action.contains(packageKey)) {
    directory = action.at(packageKey).get<std::string>() + "/";
    const auto bytes = readFile ? readFile(directory + "brush.json") : std::nullopt;
    if (!bytes) {
      throw std::invalid_argument("cannot read " + directory + "brush.json");
    }
    std::string error;
    auto brush = stroke::parseBrush(
        std::string_view(reinterpret_cast<const char*>(bytes->data()), bytes->size()), error);
    if (!brush) {
      throw std::invalid_argument(directory + "brush.json: " + error);
    }
    loaded.parameters = std::move(*brush);
  } else {
    loaded.parameters = readBrush(action.value(inlineKey, Json::object()));
  }
  if (!loaded.parameters.shape.texture.empty()) {
    loaded.shapeTexture =
        readTexture(directory + loaded.parameters.shape.texture, stroke::kMaximumShapeTexturePixels,
                    readFile, loaded.parameters.stamp.keepColors);
  }
  if (loaded.parameters.grain.enabled()) {
    loaded.grainTexture = readTexture(directory + loaded.parameters.grain.texture,
                                      stroke::kMaximumGrainTexturePixels, readFile);
  }
  return loaded;
}

void readBrushOf(const Json& action, const FileReader& readFile, StrokeAction& stroke) {
  stroke::LoadedBrush brush = readLoadedBrush(action, "brushPackage", "brush", readFile);
  stroke.brush = std::move(brush.parameters);
  stroke.shapeTexture = std::move(brush.shapeTexture);
  stroke.grainTexture = std::move(brush.grainTexture);
  if (action.contains("dualBrushPackage") || action.contains("dualBrush")) {
    stroke.dual = std::make_shared<const stroke::LoadedBrush>(
        readLoadedBrush(action, "dualBrushPackage", "dualBrush", readFile));
  }
}

std::vector<stroke::InputSample> readSampleArray(const Json& entries);

std::vector<stroke::InputSample> readSamples(const Json& action, const FileReader& readFile) {
  std::vector<stroke::InputSample> samples;
  if (action.contains("trace")) {
    const auto name = action.at("trace").get<std::string>();
    const auto bytes = readFile ? readFile(name) : std::nullopt;
    if (!bytes) {
      throw std::invalid_argument("cannot read trace " + name);
    }
    if (!stroke::decodePackedSamples(bytes->data(), bytes->size(), samples)) {
      throw std::invalid_argument("trace " + name + " is not a whole number of samples");
    }
    return samples;
  }
  return readSampleArray(action.at("samples"));
}

std::vector<stroke::InputSample> readSampleArray(const Json& entries) {
  std::vector<stroke::InputSample> samples;
  for (const Json& entry : entries) {
    if (!entry.is_array() || entry.size() < 4 || entry.size() > 7) {
      throw std::invalid_argument(
          "a sample must be [x, y, pressure, timeMilliseconds(, flags(, tilt(, orientation)))]");
    }
    stroke::InputSample sample;
    sample.x = entry.at(0).get<float>();
    sample.y = entry.at(1).get<float>();
    sample.pressure = entry.at(2).get<float>();
    sample.eventTimeNanos = static_cast<int64_t>(entry.at(3).get<double>() * 1e6);
    sample.toolType = stroke::kToolTypeStylus;
    if (entry.size() >= 5) {
      sample.flags = entry.at(4).get<int32_t>();
    }
    if (entry.size() >= 6) {
      sample.tilt = entry.at(5).get<float>();
    }
    if (entry.size() == 7) {
      sample.orientation = entry.at(6).get<float>();
    }
    samples.push_back(sample);
  }
  return samples;
}

}  // namespace

StrokeAction StrokeAction::replaced() const {
  StrokeAction result = *this;
  result.samples = replacement;
  result.replacement.clear();
  result.settings.smoothPositions = false;
  return result;
}

std::array<double, 4> FillAction::colorAt(int32_t x, int32_t y) const {
  if (!toColor) {
    return color;
  }
  const double position = vertical ? (y + 0.5 - rect[1]) / rect[3] : (x + 0.5 - rect[0]) / rect[2];
  const double t = std::clamp(position, 0.0, 1.0);
  std::array<double, 4> result{};
  for (size_t channel = 0; channel < 4; ++channel) {
    result[channel] = color[channel] + ((*toColor)[channel] - color[channel]) * t;
  }
  return result;
}

void Scene::setRasterLayerCount(int32_t count) {
  layers.layers.clear();
  for (int32_t index = 1; index <= count; ++index) {
    canvas::LayerTreeNode node;
    node.id = static_cast<canvas::LayerId>(index);
    layers.layers.push_back(node);
  }
}

double maskLuminance(const std::array<double, 4>& color) {
  return 0.3 * color[0] + 0.59 * color[1] + 0.11 * color[2];
}

std::optional<text::TextModel> TextAction::model(bool linearLight, std::string& error) const {
  const auto find = [this](const std::string& fontId) -> const text::FontFace* {
    for (const auto& [id, face] : fonts) {
      if (id == fontId) {
        return face.get();
      }
    }
    return nullptr;
  };
  std::vector<const text::FontFace*> fallbackFaces;
  for (const std::string& fontId : fallbacks) {
    if (const text::FontFace* face = find(fontId)) {
      fallbackFaces.push_back(face);
    }
  }
  return text::parseTextModel(modelJson, dpi, find, fallbackFaces, linearLight, error);
}

std::optional<Scene> parseScene(std::string_view jsonText, const FileReader& readFile,
                                std::string& error) {
  try {
    const Json root = Json::parse(jsonText);
    if (root.value("format", 0) != 1) {
      throw std::invalid_argument("unsupported scene format");
    }
    Scene scene;
    scene.documentId = root.value("documentId", "scene");
    const Json& canvas = root.at("canvas");
    scene.width = canvas.at("width").get<int32_t>();
    scene.height = canvas.at("height").get<int32_t>();
    if (scene.width < 1 || scene.height < 1 || scene.width > kMaximumCanvasPixels ||
        scene.height > kMaximumCanvasPixels) {
      throw std::invalid_argument("canvas size out of range");
    }
    if (canvas.contains("background")) {
      scene.background = readColor(canvas.at("background"));
    }
    const Json& layers = root.contains("layers") ? root.at("layers") : Json(1);
    if (layers.is_number_integer()) {
      const auto count = layers.get<int32_t>();
      if (count < 1 || count > kMaximumLayers) {
        throw std::invalid_argument("layer count out of range");
      }
      scene.setRasterLayerCount(count);
    } else {
      std::string treeError;
      auto tree = canvas::parseLayerTreeJson(Json{{"layers", layers}}, treeError);
      if (!tree) {
        throw std::invalid_argument("invalid layers: " + treeError);
      }
      scene.layers = std::move(*tree);
    }
    for (size_t channel = 0; channel < 4; ++channel) {
      scene.layers.background[channel] = static_cast<float>(scene.background[channel]);
    }
    if (canvas.contains("pixelFormat")) {
      const Json& format = canvas.at("pixelFormat");
      const auto parsed =
          format.is_string() ? canvas::parsePixelFormat(format.get<std::string>()) : std::nullopt;
      if (!parsed) {
        throw std::invalid_argument("pixelFormat must be rgba8_premul_srgb or rgba16f_premul_linear");
      }
      scene.layers.pixelFormat = *parsed;
    }
    auto targetLayer = [&scene](const Json& action) {
      const int32_t layer = action.value("layer", 1);
      const canvas::LayerTreeNode* node =
          layer > 0 ? scene.layers.find(static_cast<canvas::LayerId>(layer)) : nullptr;
      if (node == nullptr || node->isGroup()) {
        throw std::invalid_argument("action layer " + std::to_string(layer) +
                                    " is not a raster layer");
      }
      if (action.value("mask", false) && !node->mask) {
        throw std::invalid_argument("layer " + std::to_string(layer) + " has no mask");
      }
      return layer;
    };
    uint64_t strokeIndex = 0;
    bool selectionActive = false;
    for (const Json& action : root.value("actions", Json::array())) {
      const std::string type = action.at("type").get<std::string>();
      if (type == "select") {
        SelectAction select;
        const std::string operation = action.at("op").get<std::string>();
        if (operation == "all") {
          select.operation = SelectAction::Operation::All;
        } else if (operation == "none") {
          select.operation = SelectAction::Operation::None;
        } else if (operation == "invert") {
          select.operation = SelectAction::Operation::Invert;
        } else if (operation == "contents") {
          select.operation = SelectAction::Operation::Contents;
          if (action.value("mask", false)) {
            throw std::invalid_argument("select contents takes a layer, not a mask");
          }
          select.layer = targetLayer(action);
        } else if (operation == "rect" || operation == "ellipse" || operation == "polygon") {
          select.operation = SelectAction::Operation::Polygon;
          if (operation == "polygon") {
            for (const Json& point : action.at("points")) {
              if (!point.is_array() || point.size() != 2) {
                throw std::invalid_argument("points must be [[x, y], ...]");
              }
              select.polygon.push_back({point.at(0).get<double>(), point.at(1).get<double>()});
            }
          } else {
            const Json& rect = action.at("rect");
            if (!rect.is_array() || rect.size() != 4) {
              throw std::invalid_argument("rect must be [x, y, width, height]");
            }
            const std::array<double, 4> box{rect.at(0).get<double>(), rect.at(1).get<double>(),
                                            rect.at(2).get<double>(), rect.at(3).get<double>()};
            select.polygon = operation == "rect"
                                 ? canvas::rectanglePolygon(box[0], box[1], box[2], box[3])
                                 : canvas::ellipsePolygon(box[0], box[1], box[2], box[3]);
          }
          const std::string mode = action.value("mode", "replace");
          if (mode == "replace") {
            select.combine = canvas::SelectionCombine::Replace;
          } else if (mode == "add") {
            select.combine = canvas::SelectionCombine::Add;
          } else if (mode == "subtract") {
            select.combine = canvas::SelectionCombine::Subtract;
          } else if (mode == "intersect") {
            select.combine = canvas::SelectionCombine::Intersect;
          } else {
            throw std::invalid_argument("unknown select mode: " + mode);
          }
        } else if (operation == "auto") {
          select.operation = SelectAction::Operation::Auto;
          select.layer = targetLayer(action);
          select.x = action.at("x").get<double>();
          select.y = action.at("y").get<double>();
          select.threshold = action.value("threshold", 0.1);
          select.expand = action.value("expand", true);
          const std::string mode = action.value("mode", "replace");
          const std::array<std::string, 4> modes{"replace", "add", "subtract", "intersect"};
          const auto found = std::find(modes.begin(), modes.end(), mode);
          if (found == modes.end()) {
            throw std::invalid_argument("unknown select mode: " + mode);
          }
          select.combine = static_cast<canvas::SelectionCombine>(found - modes.begin());
        } else if (operation == "feather") {
          select.operation = SelectAction::Operation::Feather;
          select.radius = action.at("radius").get<double>();
          if (!(select.radius >= 0 && select.radius <= canvas::kMaximumFeatherRadius)) {
            throw std::invalid_argument("feather radius must be 0-100");
          }
        } else {
          throw std::invalid_argument("unknown select op: " + operation);
        }
        selectionActive = select.operation != SelectAction::Operation::None;
        scene.actions.push_back(SceneAction::of(select));
        continue;
      }
      if (type == "transform") {
        TransformAction transform;
        transform.layer = targetLayer(action);
        transform.selection = selectionActive;
        const std::string filter = action.value("filter", "bilinear");
        if (filter == "nearest") {
          transform.filter = canvas::TransformFilter::Nearest;
        } else if (filter == "bilinear") {
          transform.filter = canvas::TransformFilter::Bilinear;
        } else if (filter == "bicubic") {
          transform.filter = canvas::TransformFilter::Bicubic;
        } else {
          throw std::invalid_argument("unknown filter: " + filter);
        }
        for (const Json& vertex : action.at("mesh")) {
          if (!vertex.is_array() || vertex.size() != 5) {
            throw std::invalid_argument("mesh vertices are [x, y, w, u, v]");
          }
          transform.mesh.push_back({vertex.at(0).get<float>(), vertex.at(1).get<float>(),
                                    vertex.at(2).get<float>(), vertex.at(3).get<float>(),
                                    vertex.at(4).get<float>()});
        }
        if (transform.mesh.size() % 3 != 0) {
          throw std::invalid_argument("the mesh is a list of triangles");
        }
        scene.actions.push_back(SceneAction::of(std::move(transform)));
        continue;
      }
      if (type == "adjust") {
        if (action.value("mask", false)) {
          throw std::invalid_argument("adjust takes a layer, not a mask");
        }
        AdjustAction adjustment;
        adjustment.layer = targetLayer(action);
        adjustment.selection = selectionActive;
        adjustment.pencil = action.value("pencil", false);
        const std::string name = action.at("filter").get<std::string>();
        const auto filter = adjust::filterFromName(name);
        if (!filter) {
          throw std::invalid_argument("unknown filter: " + name);
        }
        adjustment.parameters.filter = *filter;
        const std::vector<std::string_view> names = adjust::parameterNames(*filter);
        const Json parameters = action.value("parameters", Json::object());
        for (const auto& [key, value] : parameters.items()) {
          const auto found = std::find(names.begin(), names.end(), key);
          if (found == names.end()) {
            throw std::invalid_argument("unknown parameter of " + name + ": " + key);
          }
          adjustment.parameters.values[static_cast<size_t>(found - names.begin())] =
              value.get<float>();
        }
        if (adjust::usesTable(*filter)) {
          std::vector<float> data;
          if (*filter == adjust::Filter::Curves) {
            const Json curves = action.value("curves", Json::object());
            for (const char* channel : {"master", "red", "green", "blue", "alpha"}) {
              const Json points = curves.value(channel, Json::parse("[[0, 0], [1, 1]]"));
              data.push_back(static_cast<float>(points.size()));
              for (const Json& point : points) {
                data.push_back(point.at(0).get<float>());
                data.push_back(point.at(1).get<float>());
              }
            }
          } else {
            const Json stops = action.at("gradient");
            data.push_back(static_cast<float>(stops.size()));
            for (const Json& stop : stops) {
              for (size_t component = 0; component < 4; ++component) {
                data.push_back(stop.at(component).get<float>());
              }
            }
          }
          auto table = adjust::bakeTable(*filter, data);
          if (!table) {
            throw std::invalid_argument("invalid " + name + " control points");
          }
          adjustment.parameters.table = std::make_shared<const adjust::Table>(std::move(*table));
        }
        adjustment.amount = action.value("amount", 1.0);
        if (!(adjustment.amount >= 0 && adjustment.amount <= 1)) {
          throw std::invalid_argument("adjust amount must be 0-1");
        }
        scene.actions.push_back(SceneAction::of(adjustment));
        continue;
      }
      if (type == "liquify") {
        if (action.value("mask", false)) {
          throw std::invalid_argument("liquify takes a layer, not a mask");
        }
        LiquifyAction liquify;
        liquify.layer = targetLayer(action);
        liquify.selection = selectionActive;
        liquify.adjust = action.value("adjust", 1.0);
        uint32_t seed = 0;
        for (const Json& entry : action.at("strokes")) {
          LiquifyAction::Stroke stroke;
          const std::string modeName = entry.value("mode", std::string("push"));
          const auto mode = adjust::liquifyModeFromName(modeName);
          if (!mode) {
            throw std::invalid_argument("unknown liquify mode: " + modeName);
          }
          stroke.brush.mode = *mode;
          stroke.brush.size = entry.value("size", 0.3);
          stroke.brush.pressure = entry.value("pressure", 1.0);
          stroke.brush.distortion = entry.value("distortion", 0.5);
          stroke.brush.momentum = entry.value("momentum", 0.0);
          stroke.brush.seed = seed++;
          for (const Json& sample : entry.at("samples")) {
            stroke.samples.push_back({sample.at(0).get<double>(), sample.at(1).get<double>(),
                                      sample.size() > 2 ? sample.at(2).get<double>() : 1.0,
                                      sample.size() > 3 ? sample.at(3).get<double>() : 0.0});
          }
          liquify.strokes.push_back(std::move(stroke));
        }
        scene.actions.push_back(SceneAction::of(std::move(liquify)));
        continue;
      }
      if (type == "text") {
        TextAction text;
        text.layer = targetLayer(action);
        if (action.value("mask", false)) {
          throw std::invalid_argument("text goes into a layer, not a mask");
        }
        text.dpi = action.value("dpi", 72.0);
        text.modelJson = action.at("model").dump();
        text.library = std::make_shared<text::FontLibrary>();
        const Json fontFiles = action.value("fonts", Json::object());
        for (const auto& [fontId, file] : fontFiles.items()) {
          const auto bytes = readFile ? readFile(file.get<std::string>()) : std::nullopt;
          if (!bytes) {
            throw std::invalid_argument("font file not found: " + file.get<std::string>());
          }
          std::string fontError;
          auto face = text.library->load(*bytes, 0, fontError);
          if (!face) {
            throw std::invalid_argument(file.get<std::string>() + ": " + fontError);
          }
          text.fonts.emplace_back(fontId, std::move(face));
        }
        for (const Json& fallback : action.value("fallbacks", Json::array())) {
          text.fallbacks.push_back(fallback.get<std::string>());
        }
        std::string modelError;
        if (!text.model(false, modelError)) {
          throw std::invalid_argument("text model: " + modelError);
        }
        scene.actions.push_back(SceneAction::of(std::move(text)));
        continue;
      }
      if (type == "copyLayer") {
        CopyLayerAction copy;
        Json from = action;
        from["layer"] = action.value("from", 1);
        copy.from = targetLayer(from);
        Json to = action;
        to["layer"] = action.value("to", 2);
        copy.to = targetLayer(to);
        copy.masked = action.value("masked", false);
        if (copy.masked && !selectionActive) {
          throw std::invalid_argument("copyLayer masked needs a selection");
        }
        scene.actions.push_back(SceneAction::of(copy));
        continue;
      }
      if (type == "floodFill") {
        FloodFillAction fill;
        fill.layer = targetLayer(action);
        fill.mask = action.value("mask", false);
        fill.source = action.value("source", fill.layer);
        const canvas::LayerTreeNode* source =
            scene.layers.find(static_cast<canvas::LayerId>(fill.source));
        if (source == nullptr || source->isGroup()) {
          throw std::invalid_argument("floodFill source is not a raster layer");
        }
        fill.x = action.value("x", 0.0);
        fill.y = action.value("y", 0.0);
        fill.threshold = action.value("threshold", 0.1);
        fill.expand = action.value("expand", true);
        fill.whole = action.value("whole", false);
        fill.color = readColor(action.at("color"));
        fill.selection = selectionActive;
        scene.actions.push_back(SceneAction::of(fill));
        continue;
      }
      if (type == "fill") {
        FillAction fill;
        fill.selection = action.value("selection", false);
        if (fill.selection) {
          selectionActive = true;
        } else {
          fill.layer = targetLayer(action);
          fill.mask = action.value("mask", false);
        }
        fill.rect = {0, 0, scene.width, scene.height};
        if (action.contains("rect")) {
          const Json& rect = action.at("rect");
          if (!rect.is_array() || rect.size() != 4) {
            throw std::invalid_argument("rect must be [x, y, width, height]");
          }
          for (size_t index = 0; index < 4; ++index) {
            fill.rect[index] = rect.at(index).get<int32_t>();
          }
          if (fill.rect[2] < 1 || fill.rect[3] < 1) {
            throw std::invalid_argument("rect size must be positive");
          }
        }
        fill.color = readColor(action.at("color"));
        if (action.contains("toColor")) {
          fill.toColor = readColor(action.at("toColor"));
        }
        const std::string direction = action.value("direction", "horizontal");
        if (direction != "horizontal" && direction != "vertical") {
          throw std::invalid_argument("unknown direction: " + direction);
        }
        fill.vertical = direction == "vertical";
        scene.actions.push_back(SceneAction::of(fill));
        continue;
      }
      if (type != "stroke") {
        throw std::invalid_argument("unknown action type: " + type);
      }
      StrokeAction stroke;
      const std::string tool = action.value("tool", "paint");
      if (tool == "paint") {
        stroke.tool = Tool::Paint;
      } else if (tool == "erase") {
        stroke.tool = Tool::Erase;
      } else if (tool == "smudge") {
        stroke.tool = Tool::Smudge;
      } else {
        throw std::invalid_argument("unknown tool: " + tool);
      }
      stroke.layer = targetLayer(action);
      stroke.mask = action.value("mask", false);
      const std::string target = action.value("target", "layer");
      if (target != "layer" && target != "selection" && target != "adjustmentMask") {
        throw std::invalid_argument("unknown stroke target: " + target);
      }
      stroke.adjustmentMaskTarget = target == "adjustmentMask";
      stroke.selectionTarget = target == "selection" || stroke.adjustmentMaskTarget;
      if (action.contains("clone")) {
        const Json& clone = action.at("clone");
        StrokeAction::Clone source;
        const std::string kind = clone.value("source", "composite");
        if (kind != "composite" && kind != "layer") {
          throw std::invalid_argument("unknown clone source: " + kind);
        }
        source.composite = kind == "composite";
        if (!source.composite) {
          Json layer = clone;
          source.layer = targetLayer(layer);
        }
        const Json& offset = clone.at("offset");
        source.offset = {offset.at(0).get<int32_t>(), offset.at(1).get<int32_t>()};
        stroke.clone = source;
      }
      stroke.selection = selectionActive && !stroke.selectionTarget;
      if (stroke.selectionTarget && !stroke.adjustmentMaskTarget) {
        selectionActive = true;
      }
      readBrushOf(action, readFile, stroke);
      stroke.settings.sizePixels = action.value("size", stroke.settings.sizePixels);
      stroke.settings.opacity = action.value("opacity", stroke.settings.opacity);
      if (action.contains("color")) {
        stroke.settings.color = readColor(action.at("color"));
      }
      if (action.contains("secondaryColor")) {
        const std::array<double, 4> secondary = readColor(action.at("secondaryColor"));
        stroke.settings.secondaryColor = {secondary[0], secondary[1], secondary[2]};
      }
      stroke.settings.seed = stroke::strokeSeed(scene.documentId, strokeIndex++);
      stroke.settings.rollFromOrientation = action.value("rollFromOrientation", false);
      if (action.contains("symmetry")) {
        const Json& copies = action.at("symmetry");
        if (!copies.is_array() || copies.size() > stroke::kMaximumSymmetryCopies) {
          throw std::invalid_argument("symmetry is a list of at most 32 copies");
        }
        if (stroke.tool == Tool::Smudge || stroke.clone || stroke.brush.wetMix.enabled) {
          throw std::invalid_argument("symmetry is not for smudge, wet mix or clone strokes");
        }
        for (const Json& copy : copies) {
          if (!copy.is_array() || copy.size() != 6) {
            throw std::invalid_argument("a symmetry copy is [a, b, c, d, tx, ty]");
          }
          stroke.settings.symmetry.push_back(
              {copy.at(0).get<double>(), copy.at(1).get<double>(), copy.at(2).get<double>(),
               copy.at(3).get<double>(), copy.at(4).get<double>(), copy.at(5).get<double>()});
        }
      }
      stroke.samples = readSamples(action, readFile);
      if (action.contains("replaceWith")) {
        stroke.replacement = readSampleArray(action.at("replaceWith"));
      }
      scene.actions.push_back(SceneAction::of(std::move(stroke)));
    }
    return scene;
  } catch (const std::exception& exception) {
    error = exception.what();
    return std::nullopt;
  }
}

adjust::LiquifyField liquifyField(const LiquifyAction& action, int32_t width, int32_t height) {
  adjust::LiquifyField field(width, height);
  for (const LiquifyAction::Stroke& stroke : action.strokes) {
    adjust::LiquifyStroke liquify(field, stroke.brush);
    for (const adjust::LiquifySample& sample : stroke.samples) {
      liquify.add(sample);
    }
    liquify.end();
    liquify.finishMomentum();
    liquify.finish();
  }
  if (action.adjust != 1) {
    field.scale(std::clamp(action.adjust, 0.0, 1.0));
  }
  return field;
}

}  // namespace nanquim::scene
