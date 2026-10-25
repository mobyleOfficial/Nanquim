#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <random>
#include <string>
#include <string_view>
#include <vector>

#include "canvas/tile_coordinate.h"
#include "color/color_profile.h"
#include "io/document_tile.h"

namespace nanquim::formats {

struct ImportedMask {
  std::string uuid;
  bool enabled = true;
  bool inverted = false;
};

struct ImportedNode {
  enum class Type { Raster, Group };
  Type type = Type::Raster;
  std::string uuid;
  std::string name;
  double opacity = 1;
  std::string blend = "normal";
  bool hidden = false;
  bool locked = false;
  bool alphaLock = false;
  bool clipping = false;
  bool collapsed = false;
  std::optional<ImportedMask> mask;
  std::vector<ImportedNode> children;
};

struct ImportWarning {
  std::string code;
  std::string detail;
};

struct ImportedDocument {
  std::string source;
  int32_t width = 0;
  int32_t height = 0;
  double dpi = 72;
  std::optional<std::array<double, 4>> backgroundColor;
  bool backgroundHidden = false;
  std::vector<ImportedNode> layers;
  std::vector<ImportWarning> warnings;
  std::string colorProfileName;
  std::vector<uint8_t> iccProfile;
  std::string cmykProfileName;
  std::vector<uint8_t> cmykProfile;
};

bool adoptEmbeddedProfile(ImportedDocument& document, const std::vector<uint8_t>& profile);

struct ImportLimits {
  int32_t maximumSide = 16384;
  int32_t maximumLayers = 10000;
  std::vector<uint8_t> cmykFallbackProfile;
  std::vector<uint8_t> cmykWorkingProfile;
};

std::unique_ptr<color::CmykImageTransform> adoptCmykCanvas(ImportedDocument& document,
                                                           const std::vector<uint8_t>& embedded,
                                                           const ImportLimits& limits);

struct ImportResult {
  bool ok = false;
  std::string errorCode;
  std::string errorDetail;
  ImportedDocument document;
};

class SurfaceTileWriter {
 public:
  SurfaceTileWriter(std::string surfaceFolder, io::DocumentTileFormat format, int32_t canvasWidth,
                    int32_t canvasHeight, uint8_t maskFill = 255);

  bool writePixels(int32_t left, int32_t top, int32_t width, int32_t height, const uint8_t* pixels,
                   size_t rowStride);

  bool finish();

  [[nodiscard]] size_t tilesWritten() const { return tiles_.size(); }

 private:
  bool flushTileRow();
  void clearBand();

  std::string folder_;
  io::DocumentTileFormat format_;
  int32_t canvasWidth_;
  int32_t canvasHeight_;
  uint8_t maskFill_;
  int32_t columns_;
  int32_t tileRow_ = 0;
  bool bandTouched_ = false;
  bool failed_ = false;
  std::vector<uint8_t> band_;
  std::vector<canvas::TileCoordinate> tiles_;
  std::vector<std::string> writtenPaths_;
};

class DocumentImportFolder {
 public:
  DocumentImportFolder(std::string folder, int32_t width, int32_t height);

  std::string newUuid();

  [[nodiscard]] std::string layerFolder(const std::string& uuid) const;
  [[nodiscard]] std::string maskFolder(const std::string& uuid) const;

  [[nodiscard]] SurfaceTileWriter layerWriter(const std::string& uuid) const;
  [[nodiscard]] SurfaceTileWriter maskWriter(const std::string& uuid, uint8_t fill) const;

  [[nodiscard]] bool writeImportJson(const ImportedDocument& document) const;

 private:
  std::string folder_;
  int32_t width_;
  int32_t height_;
  std::mt19937_64 random_;
};

std::string importJsonText(const ImportedDocument& document);

std::optional<ImportedDocument> parseImportJson(std::string_view text, std::string& error);

}  // namespace nanquim::formats
