#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <vector>

#include "canvas/pixel_format.h"
#include "canvas/tile_coordinate.h"

namespace nanquim::io {

enum class DocumentTileFormat {
  Rgba8,
  Rgba16f,
  R8,
  SelectionR8,
};

inline constexpr size_t documentTileBytes(DocumentTileFormat format) {
  const auto pixels = static_cast<size_t>(canvas::kTileSizePixels) * canvas::kTileSizePixels;
  switch (format) {
    case DocumentTileFormat::Rgba8:
      return pixels * 4;
    case DocumentTileFormat::Rgba16f:
      return pixels * 8;
    default:
      return pixels;
  }
}

inline constexpr DocumentTileFormat layerTileFormat(canvas::PixelFormat format) {
  return format == canvas::PixelFormat::Rgba16fPremulLinear ? DocumentTileFormat::Rgba16f
                                                            : DocumentTileFormat::Rgba8;
}

inline constexpr int64_t kMaximumDocumentTileFileBytes =
    2 * canvas::tileBytes(canvas::PixelFormat::Rgba16fPremulLinear);

struct TilePlacement {
  canvas::TileCoordinate coordinate;
  int32_t canvasWidth = 0;
  int32_t canvasHeight = 0;

  [[nodiscard]] int32_t columnsInside() const;
  [[nodiscard]] int32_t rowsInside() const;
};

void engineTileToFile(const uint8_t* engineTile, DocumentTileFormat format,
                      const TilePlacement& placement, uint8_t* fileTile,
                      canvas::PixelFormat engineFormat = canvas::PixelFormat::Rgba8PremulSrgb);
void fileTileToEngine(const uint8_t* fileTile, DocumentTileFormat format, uint8_t* engineTile,
                      canvas::PixelFormat engineFormat = canvas::PixelFormat::Rgba8PremulSrgb);

[[nodiscard]] bool isImplicitEngineTile(
    const uint8_t* engineTile, DocumentTileFormat format, const TilePlacement& placement,
    canvas::PixelFormat engineFormat = canvas::PixelFormat::Rgba8PremulSrgb);

void fillEmptyEngineTile(uint8_t* engineTile, DocumentTileFormat format,
                         canvas::PixelFormat engineFormat = canvas::PixelFormat::Rgba8PremulSrgb);

std::vector<uint8_t> encodeDocumentTile(const uint8_t* fileTile, DocumentTileFormat format);

bool decodeDocumentTile(const std::vector<uint8_t>& encoded, DocumentTileFormat format,
                        uint8_t* fileTile);

std::string documentTileRelativePath(canvas::TileCoordinate coordinate);

std::optional<canvas::TileCoordinate> parseDocumentTileFileName(const std::string& name);

struct SurfaceIndex {
  std::vector<canvas::TileCoordinate> tiles;
  int64_t revision = 0;
};

std::vector<uint8_t> writeSurfaceIndexJson(const SurfaceIndex& index);

SurfaceIndex readSurfaceIndex(const std::string& surfaceFolder, bool& rebuilt);

enum class TileReadResult { Ok, Missing, Corrupt };

TileReadResult readDocumentTile(
    const std::string& surfaceFolder, canvas::TileCoordinate coordinate, DocumentTileFormat format,
    uint8_t* engineTile, canvas::PixelFormat engineFormat = canvas::PixelFormat::Rgba8PremulSrgb);

using LayerPixelTransform = std::function<void(uint8_t* pixels, size_t pixelCount)>;
bool convertLayerFolder(const std::string& sourceFolder, const std::string& destinationFolder,
                        canvas::PixelFormat from, canvas::PixelFormat to, std::string& error,
                        const LayerPixelTransform& transform = {});

}  // namespace nanquim::io
