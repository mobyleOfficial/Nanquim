#include "io/document_tile.h"

#include <lz4frame.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <nlohmann/json.hpp>

#include "canvas/color_conversion.h"
#include "core/half_float.h"
#include "io/file_system.h"

namespace nanquim::io {

namespace {

using Json = nlohmann::json;

constexpr size_t kPixelsPerTile =
    static_cast<size_t>(canvas::kTileSizePixels) * canvas::kTileSizePixels;
constexpr int64_t kMaximumIndexFileBytes = int64_t{16} * 1024 * 1024;
constexpr int32_t kIndexVersion = 1;
constexpr const char* kTileExtension = ".lz4";

static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__, "Half-float tiles are stored as in memory");

constexpr uint16_t kHalfOne = 0x3C00;

uint16_t halfAt(const uint8_t* bytes, size_t index) {
  uint16_t value = 0;
  std::memcpy(&value, bytes + index * 2, sizeof(value));
  return value;
}

void setHalf(uint8_t* bytes, size_t index, uint16_t value) {
  std::memcpy(bytes + index * 2, &value, sizeof(value));
}

uint8_t grayToByte(uint16_t half) {
  const float value = core::halfToFloat(half);
  return static_cast<uint8_t>(std::clamp(std::lround(value * 255.0F), 0L, 255L));
}

bool parseNonNegative(const std::string& text, int32_t& value) {
  if (text.empty() || text.size() > 9 ||
      !std::all_of(text.begin(), text.end(), [](char c) { return c >= '0' && c <= '9'; })) {
    return false;
  }
  value = std::stoi(text);
  return true;
}

}  // namespace

int32_t TilePlacement::columnsInside() const {
  return std::clamp(canvasWidth - coordinate.column * canvas::kTileSizePixels, 0,
                    canvas::kTileSizePixels);
}

int32_t TilePlacement::rowsInside() const {
  return std::clamp(canvasHeight - coordinate.row * canvas::kTileSizePixels, 0,
                    canvas::kTileSizePixels);
}

void engineTileToFile(const uint8_t* engineTile, DocumentTileFormat format,
                      const TilePlacement& placement, uint8_t* fileTile,
                      canvas::PixelFormat engineFormat) {
  const bool half = engineFormat == canvas::PixelFormat::Rgba16fPremulLinear;
  std::memset(fileTile, 0, documentTileBytes(format));
  const int32_t columns = placement.columnsInside();
  const int32_t rows = placement.rowsInside();
  const auto pixelBytes = static_cast<size_t>(canvas::bytesPerPixel(engineFormat));
  for (int32_t row = 0; row < rows; ++row) {
    const size_t rowStart = static_cast<size_t>(row) * canvas::kTileSizePixels;
    if (format == DocumentTileFormat::Rgba8 || format == DocumentTileFormat::Rgba16f) {
      std::memcpy(fileTile + rowStart * pixelBytes, engineTile + rowStart * pixelBytes,
                  static_cast<size_t>(columns) * pixelBytes);
      continue;
    }
    for (int32_t column = 0; column < columns; ++column) {
      const size_t pixel = rowStart + static_cast<size_t>(column);
      fileTile[pixel] = half ? grayToByte(halfAt(engineTile, pixel * 4)) : engineTile[pixel * 4];
    }
  }
}

void fileTileToEngine(const uint8_t* fileTile, DocumentTileFormat format, uint8_t* engineTile,
                      canvas::PixelFormat engineFormat) {
  if (format == DocumentTileFormat::Rgba8 || format == DocumentTileFormat::Rgba16f) {
    std::memcpy(engineTile, fileTile, documentTileBytes(format));
    return;
  }
  const bool half = engineFormat == canvas::PixelFormat::Rgba16fPremulLinear;
  for (size_t pixel = 0; pixel < kPixelsPerTile; ++pixel) {
    const uint8_t value = fileTile[pixel];
    if (half) {
      const uint16_t gray = core::floatToHalf(static_cast<float>(value) / 255.0F);
      setHalf(engineTile, pixel * 4, gray);
      setHalf(engineTile, pixel * 4 + 1, gray);
      setHalf(engineTile, pixel * 4 + 2, gray);
      setHalf(engineTile, pixel * 4 + 3, kHalfOne);
      continue;
    }
    engineTile[pixel * 4] = value;
    engineTile[pixel * 4 + 1] = value;
    engineTile[pixel * 4 + 2] = value;
    engineTile[pixel * 4 + 3] = 255;
  }
}

bool isImplicitEngineTile(const uint8_t* engineTile, DocumentTileFormat format,
                          const TilePlacement& placement, canvas::PixelFormat engineFormat) {
  const bool half = engineFormat == canvas::PixelFormat::Rgba16fPremulLinear;
  const int32_t columns = placement.columnsInside();
  const int32_t rows = placement.rowsInside();
  const bool layer = format == DocumentTileFormat::Rgba8 || format == DocumentTileFormat::Rgba16f;
  const size_t channel = layer ? 3 : 0;
  const uint8_t implicitValue = format == DocumentTileFormat::R8 ? 255 : 0;
  for (int32_t row = 0; row < rows; ++row) {
    const size_t rowStart = static_cast<size_t>(row) * canvas::kTileSizePixels;
    for (int32_t column = 0; column < columns; ++column) {
      const size_t index = (rowStart + static_cast<size_t>(column)) * 4 + channel;
      if (!half) {
        if (engineTile[index] != implicitValue) {
          return false;
        }
      } else if (layer) {
        if ((halfAt(engineTile, index) & 0x7FFFU) != 0) {
          return false;
        }
      } else if (grayToByte(halfAt(engineTile, index)) != implicitValue) {
        return false;
      }
    }
  }
  return true;
}

void fillEmptyEngineTile(uint8_t* engineTile, DocumentTileFormat format,
                         canvas::PixelFormat engineFormat) {
  const auto bytes = static_cast<size_t>(canvas::tileBytes(engineFormat));
  if (format != DocumentTileFormat::R8) {
    std::memset(engineTile, 0, bytes);
    return;
  }
  if (engineFormat == canvas::PixelFormat::Rgba8PremulSrgb) {
    std::memset(engineTile, 255, bytes);
    return;
  }
  for (size_t index = 0; index < kPixelsPerTile * 4; ++index) {
    setHalf(engineTile, index, kHalfOne);
  }
}

std::vector<uint8_t> encodeDocumentTile(const uint8_t* fileTile, DocumentTileFormat format) {
  const size_t byteCount = documentTileBytes(format);
  LZ4F_preferences_t preferences = LZ4F_INIT_PREFERENCES;
  preferences.frameInfo.contentChecksumFlag = LZ4F_contentChecksumEnabled;
  preferences.frameInfo.contentSize = byteCount;
  std::vector<uint8_t> encoded(LZ4F_compressFrameBound(byteCount, &preferences));
  const size_t size =
      LZ4F_compressFrame(encoded.data(), encoded.size(), fileTile, byteCount, &preferences);
  if (LZ4F_isError(size) != 0) {
    return {};
  }
  encoded.resize(size);
  return encoded;
}

bool decodeDocumentTile(const std::vector<uint8_t>& encoded, DocumentTileFormat format,
                        uint8_t* fileTile) {
  const size_t byteCount = documentTileBytes(format);
  LZ4F_dctx* context = nullptr;
  if (LZ4F_isError(LZ4F_createDecompressionContext(&context, LZ4F_VERSION)) != 0) {
    return false;
  }
  size_t sourceOffset = 0;
  size_t destinationOffset = 0;
  bool finished = false;
  bool failed = false;
  while (!finished && !failed) {
    size_t sourceSize = encoded.size() - sourceOffset;
    size_t destinationSize = byteCount - destinationOffset;
    const size_t hint = LZ4F_decompress(context, fileTile + destinationOffset, &destinationSize,
                                        encoded.data() + sourceOffset, &sourceSize, nullptr);
    if (LZ4F_isError(hint) != 0) {
      failed = true;
      break;
    }
    sourceOffset += sourceSize;
    destinationOffset += destinationSize;
    if (hint == 0) {
      finished = true;
    } else if (sourceSize == 0 && destinationSize == 0) {
      failed = true;
    }
  }
  LZ4F_freeDecompressionContext(context);
  return finished && destinationOffset == byteCount && sourceOffset == encoded.size();
}

std::string documentTileRelativePath(canvas::TileCoordinate coordinate) {
  return "tiles/" + std::to_string(coordinate.column) + "_" + std::to_string(coordinate.row) +
         kTileExtension;
}

std::optional<canvas::TileCoordinate> parseDocumentTileFileName(const std::string& name) {
  const size_t extensionLength = std::strlen(kTileExtension);
  if (name.size() <= extensionLength ||
      name.compare(name.size() - extensionLength, extensionLength, kTileExtension) != 0) {
    return std::nullopt;
  }
  const std::string stem = name.substr(0, name.size() - extensionLength);
  const size_t separator = stem.find('_');
  canvas::TileCoordinate coordinate;
  if (separator == std::string::npos ||
      !parseNonNegative(stem.substr(0, separator), coordinate.column) ||
      !parseNonNegative(stem.substr(separator + 1), coordinate.row)) {
    return std::nullopt;
  }
  return coordinate;
}

std::vector<uint8_t> writeSurfaceIndexJson(const SurfaceIndex& index) {
  Json tiles = Json::array();
  for (const canvas::TileCoordinate coordinate : index.tiles) {
    tiles.push_back(Json::array({coordinate.column, coordinate.row}));
  }
  const std::string text =
      Json{{"version", kIndexVersion}, {"tiles", tiles}, {"revision", index.revision}}.dump();
  return {text.begin(), text.end()};
}

SurfaceIndex readSurfaceIndex(const std::string& surfaceFolder, bool& rebuilt) {
  rebuilt = false;
  if (const auto bytes = readFile(joinPath(surfaceFolder, "index.json"), kMaximumIndexFileBytes)) {
    const Json value = Json::parse(bytes->begin(), bytes->end(), nullptr, false);
    if (value.is_object() && value.value("version", 0) == kIndexVersion &&
        value.contains("tiles") && value["tiles"].is_array()) {
      SurfaceIndex index;
      index.revision = value.value("revision", int64_t{0});
      bool valid = true;
      for (const Json& entry : value["tiles"]) {
        if (!entry.is_array() || entry.size() != 2 || !entry[0].is_number_integer() ||
            !entry[1].is_number_integer() || entry[0].get<int64_t>() < 0 ||
            entry[1].get<int64_t>() < 0 || entry[0].get<int64_t>() > INT32_MAX ||
            entry[1].get<int64_t>() > INT32_MAX) {
          valid = false;
          break;
        }
        index.tiles.push_back({entry[0].get<int32_t>(), entry[1].get<int32_t>()});
      }
      if (valid) {
        return index;
      }
    }
  }
  rebuilt = true;
  SurfaceIndex index;
  for (const std::string& name : listFolder(joinPath(surfaceFolder, "tiles"))) {
    if (const auto coordinate = parseDocumentTileFileName(name)) {
      index.tiles.push_back(*coordinate);
    }
  }
  std::sort(index.tiles.begin(), index.tiles.end(),
            [](canvas::TileCoordinate left, canvas::TileCoordinate right) {
              return left.row != right.row ? left.row < right.row : left.column < right.column;
            });
  return index;
}

TileReadResult readDocumentTile(const std::string& surfaceFolder, canvas::TileCoordinate coordinate,
                                DocumentTileFormat format, uint8_t* engineTile,
                                canvas::PixelFormat engineFormat) {
  const std::string path = joinPath(surfaceFolder, documentTileRelativePath(coordinate));
  if (!pathExists(path)) {
    return TileReadResult::Missing;
  }
  const auto encoded = readFile(path, kMaximumDocumentTileFileBytes);
  std::vector<uint8_t> fileTile(documentTileBytes(format));
  if (!encoded) {
    return TileReadResult::Corrupt;
  }
  if (!decodeDocumentTile(*encoded, format, fileTile.data())) {
    if (format != DocumentTileFormat::Rgba8 || engineFormat != canvas::PixelFormat::Rgba8PremulSrgb) {
      return TileReadResult::Corrupt;
    }
    std::vector<uint8_t> halves(documentTileBytes(DocumentTileFormat::Rgba16f));
    if (!decodeDocumentTile(*encoded, DocumentTileFormat::Rgba16f, halves.data())) {
      return TileReadResult::Corrupt;
    }
    for (size_t pixel = 0; pixel < kPixelsPerTile; ++pixel) {
      std::array<float, 4> linear{};
      for (size_t channel = 0; channel < 4; ++channel) {
        linear[channel] = core::halfToFloat(halfAt(halves.data(), pixel * 4 + channel));
      }
      canvas::premultipliedLinearToSrgb8(linear.data(), engineTile + pixel * 4);
    }
    return TileReadResult::Ok;
  }
  fileTileToEngine(fileTile.data(), format, engineTile, engineFormat);
  return TileReadResult::Ok;
}

bool convertLayerFolder(const std::string& sourceFolder, const std::string& destinationFolder,
                        canvas::PixelFormat from, canvas::PixelFormat to, std::string& error,
                        const LayerPixelTransform& transform) {
  const DocumentTileFormat sourceFormat = layerTileFormat(from);
  const DocumentTileFormat destinationFormat = layerTileFormat(to);
  if (!removePath(destinationFolder)) {
    error = "cannot clear " + destinationFolder;
    return false;
  }
  bool rebuilt = false;
  const SurfaceIndex source = readSurfaceIndex(sourceFolder, rebuilt);
  SurfaceIndex destination;
  destination.revision = 1;
  std::vector<uint8_t> sourceTile(documentTileBytes(sourceFormat));
  std::vector<uint8_t> destinationTile(documentTileBytes(destinationFormat));
  std::vector<std::string> written;
  for (const canvas::TileCoordinate coordinate : source.tiles) {
    const std::string relative = documentTileRelativePath(coordinate);
    const auto encoded = readFile(joinPath(sourceFolder, relative), kMaximumDocumentTileFileBytes);
    if (!encoded || !decodeDocumentTile(*encoded, sourceFormat, sourceTile.data())) {
      continue;
    }
    if (sourceFormat == destinationFormat) {
      destinationTile = sourceTile;
    } else if (destinationFormat == DocumentTileFormat::Rgba16f) {
      canvas::premultipliedSrgb8ToLinearHalf(sourceTile.data(), destinationTile.data(),
                                             kPixelsPerTile);
    } else {
      canvas::premultipliedLinearHalfToSrgb8(sourceTile.data(), destinationTile.data(),
                                             kPixelsPerTile);
    }
    if (transform) {
      transform(destinationTile.data(), kPixelsPerTile);
    }
    const TilePlacement whole{coordinate, INT32_MAX, INT32_MAX};
    if (isImplicitEngineTile(destinationTile.data(), destinationFormat, whole, to)) {
      continue;
    }
    const std::string path = joinPath(destinationFolder, relative);
    if (!writeFile(path, encodeDocumentTile(destinationTile.data(), destinationFormat))) {
      error = "cannot write " + path;
      return false;
    }
    written.push_back(path);
    destination.tiles.push_back(coordinate);
  }
  const std::string indexPath = joinPath(destinationFolder, "index.json");
  if (!writeFile(indexPath, writeSurfaceIndexJson(destination))) {
    error = "cannot write " + indexPath;
    return false;
  }
  written.push_back(indexPath);
  if (!syncFiles(destinationFolder, written)) {
    error = "cannot sync " + destinationFolder;
    return false;
  }
  return true;
}

}  // namespace nanquim::io
