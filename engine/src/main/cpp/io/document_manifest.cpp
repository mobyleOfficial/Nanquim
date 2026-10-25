#include "io/document_manifest.h"

#include <cstdio>
#include <nlohmann/json.hpp>
#include <unordered_map>

namespace nanquim::io {

namespace {

using Json = nlohmann::json;

constexpr int32_t kMaximumCanvasSide = 1 << 17;

struct Converter {
  std::unordered_map<std::string, canvas::LayerId> ids;
  std::vector<DocumentSurface> surfaces;
  canvas::LayerId nextId = 1;
  std::string error;

  std::optional<canvas::LayerId> idFor(const Json& value) {
    if (!value.is_string() || value.get<std::string>().empty()) {
      error = "layer without a string id";
      return std::nullopt;
    }
    const std::string uuid = value.get<std::string>();
    if (ids.count(uuid) != 0) {
      error = "duplicate layer id " + uuid;
      return std::nullopt;
    }
    ids[uuid] = nextId;
    return nextId++;
  }

  std::optional<Json> convert(const Json& node, int32_t depth) {
    if (!node.is_object() || depth > canvas::kMaximumGroupDepth) {
      error = "invalid layer entry";
      return std::nullopt;
    }
    const std::string type = node.value("type", std::string());
    const auto id = idFor(node.contains("id") ? node["id"] : Json());
    if (!id) {
      return std::nullopt;
    }
    Json engine{{"id", *id},
                {"opacity", node.value("opacity", 1.0)},
                {"blend", node.value("blend", std::string(type == "group" ? "passThrough"
                                                                          : "normal"))},
                {"hidden", node.value("hidden", false)}};
    if (type == "group") {
      engine["type"] = "group";
      Json children = Json::array();
      if (node.contains("children") && node["children"].is_array()) {
        for (const Json& child : node["children"]) {
          auto converted = convert(child, depth + 1);
          if (!converted) {
            return std::nullopt;
          }
          children.push_back(std::move(*converted));
        }
      }
      engine["children"] = std::move(children);
      return engine;
    }
    if (type != "raster" && type != "text") {
      error = "unknown layer type '" + type + "'";
      return std::nullopt;
    }
    const std::string uuid = node["id"].get<std::string>();
    surfaces.push_back({*id, uuid, "layers/" + uuid, DocumentTileFormat::Rgba8});
    engine["type"] = "raster";
    engine["alphaLock"] = node.value("alphaLock", false);
    engine["clipping"] = node.value("clipping", false);
    if (node.contains("mask") && node["mask"].is_object()) {
      const Json& mask = node["mask"];
      const auto maskId = idFor(mask.contains("id") ? mask["id"] : Json());
      if (!maskId) {
        return std::nullopt;
      }
      const std::string maskUuid = mask["id"].get<std::string>();
      surfaces.push_back({*maskId, maskUuid, "masks/" + maskUuid, DocumentTileFormat::R8});
      engine["mask"] = Json{{"id", *maskId},
                            {"enabled", mask.value("enabled", true)},
                            {"inverted", mask.value("inverted", false)}};
    }
    return engine;
  }
};

std::string uuidForId(canvas::LayerId id) {
  char text[40];
  std::snprintf(text, sizeof(text), "00000000-0000-4000-8000-%012x", static_cast<unsigned>(id));
  return text;
}

Json manifestNode(const canvas::LayerTreeNode& node,
                  const std::unordered_map<canvas::LayerId, std::string>& uuids) {
  const auto uuid = [&uuids](canvas::LayerId id) {
    auto found = uuids.find(id);
    return found != uuids.end() ? found->second : uuidForId(id);
  };
  Json value{{"type", node.isGroup() ? "group" : "raster"},
             {"id", uuid(node.id)},
             {"name", (node.isGroup() ? "Group " : "Layer ") + std::to_string(node.id)},
             {"opacity", node.opacity},
             {"blend", std::string(canvas::blendModeId(node.blend))},
             {"hidden", node.hidden},
             {"locked", false}};
  if (node.isGroup()) {
    value["collapsed"] = false;
    Json children = Json::array();
    for (const canvas::LayerTreeNode& child : node.children) {
      children.push_back(manifestNode(child, uuids));
    }
    value["children"] = std::move(children);
    return value;
  }
  value["alphaLock"] = node.alphaLock;
  value["clipping"] = node.clipping;
  if (node.mask) {
    value["mask"] = Json{{"id", uuid(node.mask->id)},
                         {"enabled", node.mask->enabled},
                         {"inverted", node.mask->inverted}};
  }
  return value;
}

}  // namespace

std::optional<DocumentManifest> parseDocumentManifest(std::string_view jsonText,
                                                      std::string& error) {
  const Json value = Json::parse(jsonText, nullptr, false);
  if (!value.is_object() || value.value("format", std::string()) != "nanquim") {
    error = "not a Nanquim manifest";
    return std::nullopt;
  }
  if (value.value("minReaderVersion", 1) > kSupportedManifestVersion) {
    error = "the document needs a newer reader";
    return std::nullopt;
  }
  if (!value.contains("canvas") || !value["canvas"].is_object()) {
    error = "manifest without canvas";
    return std::nullopt;
  }
  const Json& canvasValue = value["canvas"];
  DocumentManifest manifest;
  manifest.width = canvasValue.value("width", 0);
  manifest.height = canvasValue.value("height", 0);
  if (manifest.width <= 0 || manifest.height <= 0 || manifest.width > kMaximumCanvasSide ||
      manifest.height > kMaximumCanvasSide) {
    error = "invalid canvas size";
    return std::nullopt;
  }
  const std::optional<canvas::PixelFormat> pixelFormat = canvas::parsePixelFormat(
      canvasValue.value("pixelFormat", std::string("rgba8_premul_srgb")));
  if (canvasValue.value("tileSize", canvas::kTileSizePixels) != canvas::kTileSizePixels ||
      !pixelFormat) {
    error = "unsupported tile size or pixel format";
    return std::nullopt;
  }
  Converter converter;
  Json layers = Json::array();
  if (value.contains("layers") && value["layers"].is_array()) {
    for (const Json& node : value["layers"]) {
      auto converted = converter.convert(node, 1);
      if (!converted) {
        error = converter.error;
        return std::nullopt;
      }
      layers.push_back(std::move(*converted));
    }
  }
  Json engineTree{{"background", Json::array({1, 1, 1, 1})},
                  {"backgroundHidden", false},
                  {"layers", std::move(layers)}};
  if (value.contains("background") && value["background"].is_object()) {
    const Json& background = value["background"];
    if (background.contains("color") && background["color"].is_array() &&
        background["color"].size() == 4) {
      engineTree["background"] = background["color"];
    }
    engineTree["backgroundHidden"] = background.value("hidden", false);
  }
  if (value.contains("activeLayerId") && value["activeLayerId"].is_string()) {
    auto found = converter.ids.find(value["activeLayerId"].get<std::string>());
    if (found != converter.ids.end()) {
      engineTree["activeLayer"] = found->second;
    }
  }
  auto tree = canvas::parseLayerTreeJson(engineTree, error);
  if (!tree) {
    return std::nullopt;
  }
  manifest.tree = std::move(*tree);
  manifest.tree.pixelFormat = *pixelFormat;
  manifest.surfaces = std::move(converter.surfaces);
  for (DocumentSurface& surface : manifest.surfaces) {
    if (surface.format == DocumentTileFormat::Rgba8) {
      surface.format = layerTileFormat(*pixelFormat);
    }
  }
  return manifest;
}

std::vector<DocumentSurface> surfacesOf(const canvas::LayerTree& tree) {
  std::vector<DocumentSurface> surfaces;
  for (const canvas::LayerTreeNode* layer : tree.rasterLayers()) {
    const std::string uuid = uuidForId(layer->id);
    surfaces.push_back({layer->id, uuid, "layers/" + uuid, layerTileFormat(tree.pixelFormat)});
    if (layer->mask) {
      const std::string maskUuid = uuidForId(layer->mask->id);
      surfaces.push_back({layer->mask->id, maskUuid, "masks/" + maskUuid, DocumentTileFormat::R8});
    }
  }
  return surfaces;
}

std::string writeDocumentManifest(const canvas::LayerTree& tree, int32_t width, int32_t height,
                                  const std::vector<DocumentSurface>& surfaces,
                                  const std::string& documentName) {
  std::unordered_map<canvas::LayerId, std::string> uuids;
  for (const DocumentSurface& surface : surfaces) {
    uuids[surface.id] = surface.uuid;
  }
  Json layers = Json::array();
  for (const canvas::LayerTreeNode& node : tree.layers) {
    layers.push_back(manifestNode(node, uuids));
  }
  Json manifest{
      {"format", "nanquim"},
      {"version", kSupportedManifestVersion},
      {"minReaderVersion", 1},
      {"app", {{"name", "nanquim-cli"}, {"version", "0"}}},
      {"id", uuidForId(0)},
      {"name", documentName},
      {"canvas",
       {{"width", width},
        {"height", height},
        {"dpi", 132},
        {"unit", "px"},
        {"tileSize", canvas::kTileSizePixels},
        {"pixelFormat", std::string(canvas::pixelFormatName(tree.pixelFormat))},
        {"colorProfile", {{"name", "sRGB IEC61966-2.1"}, {"icc", nullptr}}}}},
      {"background",
       {{"color", Json::array({tree.background[0], tree.background[1], tree.background[2],
                               tree.background[3]})},
        {"hidden", tree.backgroundHidden}}},
      {"activeLayerId", tree.activeLayer ? Json(uuids.count(*tree.activeLayer) != 0
                                                    ? uuids.at(*tree.activeLayer)
                                                    : uuidForId(*tree.activeLayer))
                                         : Json(nullptr)},
      {"referenceLayerId", nullptr},
      {"layers", std::move(layers)},
      {"extensions", Json::object()}};
  return manifest.dump(2);
}

}  // namespace nanquim::io
