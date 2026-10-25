#include "formats/document_import.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <nlohmann/json.hpp>
#include <set>

#include "canvas/layer_tree.h"
#include "color/color_profile.h"
#include "io/file_system.h"

namespace nanquim::formats {

namespace {

using canvas::kTileSizePixels;

size_t bytesPerPixel(io::DocumentTileFormat format) {
  return format == io::DocumentTileFormat::R8 ? 1 : 4;
}

uint8_t premultiply(uint8_t channel, uint8_t alpha) {
  return static_cast<uint8_t>((static_cast<uint32_t>(channel) * alpha + 127U) / 255U);
}

nlohmann::json nodeJson(const ImportedNode& node) {
  nlohmann::json json{
      {"type", node.type == ImportedNode::Type::Group ? "group" : "raster"},
      {"uuid", node.uuid},
      {"name", node.name},
      {"opacity", node.opacity},
      {"blend", node.blend},
      {"hidden", node.hidden},
      {"locked", node.locked},
      {"alphaLock", node.alphaLock},
      {"clipping", node.clipping},
      {"collapsed", node.collapsed},
  };
  if (node.mask) {
    json["mask"] = {{"uuid", node.mask->uuid},
                    {"enabled", node.mask->enabled},
                    {"inverted", node.mask->inverted}};
  } else {
    json["mask"] = nullptr;
  }
  if (node.type == ImportedNode::Type::Group) {
    nlohmann::json children = nlohmann::json::array();
    for (const ImportedNode& child : node.children) {
      children.push_back(nodeJson(child));
    }
    json["children"] = std::move(children);
  }
  return json;
}

bool isSafeUuid(const std::string& uuid) {
  if (uuid.empty() || uuid.size() > 64) {
    return false;
  }
  return std::all_of(uuid.begin(), uuid.end(), [](char character) {
    return (character >= '0' && character <= '9') || (character >= 'a' && character <= 'z') ||
           (character >= 'A' && character <= 'Z') || character == '-' || character == '_';
  });
}

class ImportJsonParser {
 public:
  std::optional<ImportedNode> node(const nlohmann::json& value, int32_t depth) {
    if (!value.is_object() || depth > canvas::kMaximumGroupDepth) {
      return failed("invalid layer entry");
    }
    ImportedNode node;
    const std::string type = text(value, "type");
    if (type == "group") {
      node.type = ImportedNode::Type::Group;
    } else if (type != "raster") {
      return failed("unknown layer type '" + type + "'");
    }
    node.uuid = text(value, "uuid");
    if (!claim(node.uuid)) {
      return std::nullopt;
    }
    node.name = text(value, "name");
    node.opacity = std::clamp(number(value, "opacity", 1.0), 0.0, 1.0);
    node.blend = text(value, "blend");
    if (node.blend.empty()) {
      node.blend = node.type == ImportedNode::Type::Group ? "passThrough" : "normal";
    }
    node.hidden = flag(value, "hidden");
    node.locked = flag(value, "locked");
    node.alphaLock = flag(value, "alphaLock");
    node.clipping = flag(value, "clipping");
    node.collapsed = flag(value, "collapsed");
    if (value.contains("mask") && value["mask"].is_object()) {
      const nlohmann::json& mask = value["mask"];
      ImportedMask imported;
      imported.uuid = text(mask, "uuid");
      if (!claim(imported.uuid)) {
        return std::nullopt;
      }
      imported.enabled = mask.contains("enabled") ? flag(mask, "enabled") : true;
      imported.inverted = flag(mask, "inverted");
      node.mask = imported;
    }
    if (node.type == ImportedNode::Type::Group && value.contains("children") &&
        value["children"].is_array()) {
      for (const nlohmann::json& child : value["children"]) {
        auto converted = this->node(child, depth + 1);
        if (!converted) {
          return std::nullopt;
        }
        node.children.push_back(std::move(*converted));
      }
    }
    return node;
  }

  std::string error;

 private:
  static std::string text(const nlohmann::json& value, const char* key) {
    return value.contains(key) && value[key].is_string() ? value[key].get<std::string>()
                                                         : std::string();
  }
  static double number(const nlohmann::json& value, const char* key, double fallback) {
    return value.contains(key) && value[key].is_number() ? value[key].get<double>() : fallback;
  }
  static bool flag(const nlohmann::json& value, const char* key) {
    return value.contains(key) && value[key].is_boolean() && value[key].get<bool>();
  }
  bool claim(const std::string& uuid) {
    if (!isSafeUuid(uuid) || !uuids_.insert(uuid).second) {
      error = "missing, invalid or duplicate uuid";
      return false;
    }
    return true;
  }
  std::optional<ImportedNode> failed(const std::string& message) {
    error = message;
    return std::nullopt;
  }

  std::set<std::string> uuids_;
};

}  // namespace

SurfaceTileWriter::SurfaceTileWriter(std::string surfaceFolder, io::DocumentTileFormat format,
                                     int32_t canvasWidth, int32_t canvasHeight, uint8_t maskFill)
    : folder_(std::move(surfaceFolder)),
      format_(format),
      canvasWidth_(canvasWidth),
      canvasHeight_(canvasHeight),
      maskFill_(maskFill),
      columns_((canvasWidth + kTileSizePixels - 1) / kTileSizePixels),
      band_(static_cast<size_t>(columns_) * kTileSizePixels * kTileSizePixels *
            bytesPerPixel(format)) {
  clearBand();
}

void SurfaceTileWriter::clearBand() {
  const size_t pixelBytes = bytesPerPixel(format_);
  const size_t rowBytes = static_cast<size_t>(columns_) * kTileSizePixels * pixelBytes;
  std::fill(band_.begin(), band_.end(), 0);
  if (format_ == io::DocumentTileFormat::R8) {
    const int32_t rowsInside =
        std::min(kTileSizePixels, std::max(0, canvasHeight_ - tileRow_ * kTileSizePixels));
    for (int32_t row = 0; row < rowsInside; ++row) {
      std::fill_n(band_.begin() + static_cast<std::ptrdiff_t>(row * rowBytes),
                  static_cast<size_t>(canvasWidth_), maskFill_);
    }
  }
  bandTouched_ = false;
}

bool SurfaceTileWriter::writePixels(int32_t left, int32_t top, int32_t width, int32_t height,
                                    const uint8_t* pixels, size_t rowStride) {
  if (failed_ || width <= 0 || height <= 0) {
    return !failed_;
  }
  const int32_t firstRow = std::max(top, 0);
  if (firstRow < std::min(top + height, canvasHeight_) && firstRow < tileRow_ * kTileSizePixels) {
    failed_ = true;
    return false;
  }
  const size_t pixelBytes = bytesPerPixel(format_);
  const size_t bandRowBytes = static_cast<size_t>(columns_) * kTileSizePixels * pixelBytes;
  const int32_t firstColumn = std::max(left, 0);
  const int32_t endColumn = std::min(left + width, canvasWidth_);
  for (int32_t row = std::max(top, 0); row < std::min(top + height, canvasHeight_); ++row) {
    while (row >= (tileRow_ + 1) * kTileSizePixels) {
      if (!flushTileRow()) {
        return false;
      }
    }
    if (firstColumn >= endColumn) {
      continue;
    }
    const uint8_t* source = pixels + static_cast<size_t>(row - top) * rowStride +
                            static_cast<size_t>(firstColumn - left) * pixelBytes;
    uint8_t* destination = band_.data() +
                           static_cast<size_t>(row - tileRow_ * kTileSizePixels) * bandRowBytes +
                           static_cast<size_t>(firstColumn) * pixelBytes;
    const auto count = static_cast<size_t>(endColumn - firstColumn);
    if (format_ == io::DocumentTileFormat::R8) {
      std::memcpy(destination, source, count);
    } else {
      for (size_t pixel = 0; pixel < count; ++pixel) {
        const uint8_t alpha = source[pixel * 4 + 3];
        destination[pixel * 4] = premultiply(source[pixel * 4], alpha);
        destination[pixel * 4 + 1] = premultiply(source[pixel * 4 + 1], alpha);
        destination[pixel * 4 + 2] = premultiply(source[pixel * 4 + 2], alpha);
        destination[pixel * 4 + 3] = alpha;
      }
    }
    bandTouched_ = true;
  }
  return true;
}

bool SurfaceTileWriter::flushTileRow() {
  const size_t pixelBytes = bytesPerPixel(format_);
  const size_t bandRowBytes = static_cast<size_t>(columns_) * kTileSizePixels * pixelBytes;
  const size_t tileRowBytes = static_cast<size_t>(kTileSizePixels) * pixelBytes;
  const bool isMask = format_ == io::DocumentTileFormat::R8;
  if (bandTouched_ || (isMask && maskFill_ != 255)) {
    std::vector<uint8_t> tile(io::documentTileBytes(format_));
    for (int32_t column = 0; column < columns_; ++column) {
      const canvas::TileCoordinate coordinate{column, tileRow_};
      const io::TilePlacement placement{coordinate, canvasWidth_, canvasHeight_};
      bool implicit = true;
      for (int32_t row = 0; row < kTileSizePixels; ++row) {
        const uint8_t* source =
            band_.data() + static_cast<size_t>(row) * bandRowBytes + column * tileRowBytes;
        std::memcpy(tile.data() + static_cast<size_t>(row) * tileRowBytes, source, tileRowBytes);
        if (!implicit || row >= placement.rowsInside()) {
          continue;
        }
        for (int32_t x = 0; x < placement.columnsInside() && implicit; ++x) {
          implicit = isMask ? source[x] == 255 : source[x * 4 + 3] == 0;
        }
      }
      if (implicit) {
        continue;
      }
      const std::string relative = io::documentTileRelativePath(coordinate);
      const std::string path = io::joinPath(folder_, relative);
      if (!io::writeFile(path, io::encodeDocumentTile(tile.data(), format_))) {
        failed_ = true;
        return false;
      }
      tiles_.push_back(coordinate);
      writtenPaths_.push_back(path);
    }
  }
  ++tileRow_;
  clearBand();
  return true;
}

bool SurfaceTileWriter::finish() {
  const int32_t rows = (canvasHeight_ + kTileSizePixels - 1) / kTileSizePixels;
  while (!failed_ && tileRow_ < rows) {
    if (!flushTileRow()) {
      return false;
    }
  }
  if (failed_) {
    return false;
  }
  if (!writtenPaths_.empty() && !io::syncFiles(folder_, writtenPaths_)) {
    return false;
  }
  io::SurfaceIndex index;
  index.tiles = tiles_;
  index.revision = 1;
  return io::writeFileDurably(io::joinPath(folder_, "index.json"),
                              io::writeSurfaceIndexJson(index));
}

DocumentImportFolder::DocumentImportFolder(std::string folder, int32_t width, int32_t height)
    : folder_(std::move(folder)), width_(width), height_(height), random_(std::random_device{}()) {}

std::string DocumentImportFolder::newUuid() {
  const uint64_t high = (random_() & 0xFFFFFFFFFFFF0FFFULL) | 0x0000000000004000ULL;
  const uint64_t low = (random_() & 0x3FFFFFFFFFFFFFFFULL) | 0x8000000000000000ULL;
  char text[37];
  std::snprintf(text, sizeof(text), "%08x-%04x-%04x-%04x-%012llx",
                static_cast<unsigned>(high >> 32U), static_cast<unsigned>((high >> 16U) & 0xFFFFU),
                static_cast<unsigned>(high & 0xFFFFU), static_cast<unsigned>(low >> 48U),
                static_cast<unsigned long long>(low & 0xFFFFFFFFFFFFULL));
  return text;
}

std::string DocumentImportFolder::layerFolder(const std::string& uuid) const {
  return io::joinPath(io::joinPath(folder_, "layers"), uuid);
}

std::string DocumentImportFolder::maskFolder(const std::string& uuid) const {
  return io::joinPath(io::joinPath(folder_, "masks"), uuid);
}

SurfaceTileWriter DocumentImportFolder::layerWriter(const std::string& uuid) const {
  return {layerFolder(uuid), io::DocumentTileFormat::Rgba8, width_, height_};
}

SurfaceTileWriter DocumentImportFolder::maskWriter(const std::string& uuid, uint8_t fill) const {
  return {maskFolder(uuid), io::DocumentTileFormat::R8, width_, height_, fill};
}

std::string importJsonText(const ImportedDocument& document) {
  nlohmann::json layers = nlohmann::json::array();
  for (const ImportedNode& node : document.layers) {
    layers.push_back(nodeJson(node));
  }
  nlohmann::json json{
      {"version", 1},
      {"source", document.source},
      {"width", document.width},
      {"height", document.height},
      {"dpi", document.dpi},
      {"layers", std::move(layers)},
  };
  nlohmann::json warnings = nlohmann::json::array();
  for (const ImportWarning& warning : document.warnings) {
    warnings.push_back({{"code", warning.code}, {"detail", warning.detail}});
  }
  json["warnings"] = std::move(warnings);
  if (!document.colorProfileName.empty()) {
    json["colorProfile"] = {{"name", document.colorProfileName},
                            {"icc", document.iccProfile.empty() ? nlohmann::json(nullptr)
                                                                : nlohmann::json("profile.icc")}};
  }
  if (!document.cmykProfileName.empty()) {
    json["cmykProfile"] = {{"name", document.cmykProfileName},
                           {"icc", document.cmykProfile.empty() ? nlohmann::json(nullptr)
                                                                : nlohmann::json("cmyk.icc")}};
  }
  if (document.backgroundColor) {
    json["background"] = {{"color", *document.backgroundColor},
                          {"hidden", document.backgroundHidden}};
  } else {
    json["background"] = nullptr;
  }
  return json.dump(2, ' ', false, nlohmann::json::error_handler_t::replace);
}

bool adoptEmbeddedProfile(ImportedDocument& document, const std::vector<uint8_t>& profile) {
  std::string error;
  const auto info = color::readProfile(profile.data(), profile.size(), error);
  if (!info || !info->isDocumentProfile()) {
    return false;
  }
  if (info->description.find("sRGB") != std::string::npos) {
    return true;
  }
  document.colorProfileName = info->description.empty() ? "Embedded profile" : info->description;
  document.iccProfile = profile;
  return true;
}

std::unique_ptr<color::CmykImageTransform> adoptCmykCanvas(ImportedDocument& document,
                                                           const std::vector<uint8_t>& embedded,
                                                           const ImportLimits& limits) {
  std::string error;
  const auto readCmyk = [&error](const std::vector<uint8_t>& bytes) {
    auto info =
        bytes.empty() ? std::nullopt : color::readProfile(bytes.data(), bytes.size(), error);
    return info && info->colorSpace == color::ProfileColorSpace::Cmyk && info->convertsFrom
               ? info
               : std::nullopt;
  };
  const auto embeddedInfo = readCmyk(embedded);
  const auto fallbackInfo = readCmyk(limits.cmykFallbackProfile);
  const std::vector<uint8_t>& source = embeddedInfo ? embedded : limits.cmykFallbackProfile;
  const auto& sourceInfo = embeddedInfo ? embeddedInfo : fallbackInfo;
  if (!sourceInfo) {
    return nullptr;
  }
  auto transform = color::CmykImageTransform::create(source, limits.cmykWorkingProfile, error);
  if (!transform) {
    return nullptr;
  }
  const bool sourceProofs = sourceInfo->convertsTo;
  const bool fallbackProofs = fallbackInfo && fallbackInfo->convertsTo;
  if (sourceProofs || fallbackProofs) {
    const auto& proofInfo = sourceProofs ? sourceInfo : fallbackInfo;
    document.cmykProfile = sourceProofs ? source : limits.cmykFallbackProfile;
    document.cmykProfileName =
        proofInfo->description.empty() ? "Embedded CMYK profile" : proofInfo->description;
  }
  if (!limits.cmykWorkingProfile.empty()) {
    const auto working = color::readProfile(limits.cmykWorkingProfile.data(),
                                            limits.cmykWorkingProfile.size(), error);
    if (working && working->isDocumentProfile()) {
      document.iccProfile = limits.cmykWorkingProfile;
      document.colorProfileName =
          working->description.empty() ? "Working RGB" : working->description;
    }
  }
  document.warnings.push_back({"cmykCanvas", sourceInfo->description.empty()
                                                 ? "Embedded CMYK profile"
                                                 : sourceInfo->description});
  return transform;
}

bool DocumentImportFolder::writeImportJson(const ImportedDocument& document) const {
  if (!document.iccProfile.empty() &&
      !io::writeFileDurably(io::joinPath(folder_, "profile.icc"), document.iccProfile)) {
    return false;
  }
  if (!document.cmykProfile.empty() &&
      !io::writeFileDurably(io::joinPath(folder_, "cmyk.icc"), document.cmykProfile)) {
    return false;
  }
  const std::string text = importJsonText(document);
  return io::writeFileDurably(io::joinPath(folder_, "import.json"),
                              std::vector<uint8_t>(text.begin(), text.end()));
}

std::optional<ImportedDocument> parseImportJson(std::string_view text, std::string& error) {
  const auto integer = [](const nlohmann::json& object, const char* key) {
    return object.contains(key) && object[key].is_number_integer() ? object[key].get<int64_t>()
                                                                   : int64_t{0};
  };
  const nlohmann::json value = nlohmann::json::parse(text, nullptr, false);
  if (!value.is_object() || integer(value, "version") != 1) {
    error = "not an import.json version 1";
    return std::nullopt;
  }
  ImportedDocument document;
  if (value.contains("source") && value["source"].is_string()) {
    document.source = value["source"].get<std::string>();
  }
  const int64_t width = integer(value, "width");
  const int64_t height = integer(value, "height");
  if (width <= 0 || height <= 0 || width > (int64_t{1} << 30) || height > (int64_t{1} << 30)) {
    error = "invalid canvas size";
    return std::nullopt;
  }
  document.width = static_cast<int32_t>(width);
  document.height = static_cast<int32_t>(height);
  const double dpi =
      value.contains("dpi") && value["dpi"].is_number() ? value["dpi"].get<double>() : 72.0;
  document.dpi = std::clamp(dpi, 1.0, 100000.0);
  if (value.contains("background") && value["background"].is_object()) {
    const nlohmann::json& background = value["background"];
    std::array<double, 4> color{1, 1, 1, 1};
    if (background.contains("color") && background["color"].is_array() &&
        background["color"].size() == 4) {
      for (size_t index = 0; index < 4; ++index) {
        const nlohmann::json& component = background["color"][index];
        color[index] = component.is_number() ? std::clamp(component.get<double>(), 0.0, 1.0) : 1.0;
      }
    }
    document.backgroundColor = color;
    document.backgroundHidden = background.contains("hidden") &&
                                background["hidden"].is_boolean() &&
                                background["hidden"].get<bool>();
  }
  ImportJsonParser parser;
  if (value.contains("layers") && value["layers"].is_array()) {
    for (const nlohmann::json& node : value["layers"]) {
      auto converted = parser.node(node, 1);
      if (!converted) {
        error = parser.error;
        return std::nullopt;
      }
      document.layers.push_back(std::move(*converted));
    }
  }
  return document;
}

}  // namespace nanquim::formats
