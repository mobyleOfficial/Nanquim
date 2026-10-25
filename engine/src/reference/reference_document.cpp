#include "reference_document.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "core/half_float.h"
#include "io/document_manifest.h"
#include "io/document_tile.h"
#include "io/document_transaction.h"
#include "io/file_system.h"
#include "reference_compositor.h"

namespace nanquim::reference {

namespace {

using canvas::kTileSizePixels;
using canvas::TileCoordinate;

uint8_t toByte(double value) {
  return static_cast<uint8_t>(std::lround(std::clamp(value, 0.0, 1.0) * 255.0));
}

std::vector<uint8_t> engineTileOf(const Image& image, TileCoordinate coordinate,
                                  canvas::PixelFormat format) {
  const bool half = canvas::isLinear(format);
  std::vector<uint8_t> tile(static_cast<size_t>(canvas::tileBytes(format)), 0);
  for (int32_t row = 0; row < kTileSizePixels; ++row) {
    const int32_t y = coordinate.row * kTileSizePixels + row;
    if (y >= image.height) {
      break;
    }
    for (int32_t column = 0; column < kTileSizePixels; ++column) {
      const int32_t x = coordinate.column * kTileSizePixels + column;
      if (x >= image.width) {
        break;
      }
      const double* pixel = image.pixel(x, y);
      const size_t index = static_cast<size_t>(row) * kTileSizePixels + column;
      for (size_t channel = 0; channel < 4; ++channel) {
        if (half) {
          const uint16_t value = core::floatToHalf(static_cast<float>(pixel[channel]));
          std::memcpy(&tile[(index * 4 + channel) * 2], &value, 2);
        } else {
          tile[index * 4 + channel] = toByte(pixel[channel]);
        }
      }
    }
  }
  return tile;
}

void copyEngineTile(const uint8_t* tile, TileCoordinate coordinate, Image& image,
                    canvas::PixelFormat format) {
  const bool half = canvas::isLinear(format);
  for (int32_t row = 0; row < kTileSizePixels; ++row) {
    const int32_t y = coordinate.row * kTileSizePixels + row;
    if (y >= image.height) {
      break;
    }
    for (int32_t column = 0; column < kTileSizePixels; ++column) {
      const int32_t x = coordinate.column * kTileSizePixels + column;
      if (x >= image.width) {
        break;
      }
      const size_t index = static_cast<size_t>(row) * kTileSizePixels + column;
      double* pixel = image.pixel(x, y);
      for (size_t channel = 0; channel < 4; ++channel) {
        if (half) {
          uint16_t value = 0;
          std::memcpy(&value, &tile[(index * 4 + channel) * 2], 2);
          pixel[channel] = core::halfToFloat(value);
        } else {
          pixel[channel] = tile[index * 4 + channel] / 255.0;
        }
      }
    }
  }
}

}  // namespace

bool writeDocumentFolder(const std::string& folder, const canvas::LayerTree& tree, int32_t width,
                         int32_t height, const std::map<canvas::LayerId, Image>& surfaces,
                         const std::string& name, std::string& error) {
  const std::vector<io::DocumentSurface> documentSurfaces = io::surfacesOf(tree);
  io::DocumentTransaction transaction(folder);
  const int32_t columns = (width + kTileSizePixels - 1) / kTileSizePixels;
  const int32_t rows = (height + kTileSizePixels - 1) / kTileSizePixels;
  for (const io::DocumentSurface& surface : documentSurfaces) {
    io::SurfaceIndex index;
    index.revision = 1;
    auto found = surfaces.find(surface.id);
    if (found != surfaces.end()) {
      for (int32_t row = 0; row < rows; ++row) {
        for (int32_t column = 0; column < columns; ++column) {
          const TileCoordinate coordinate{column, row};
          const std::vector<uint8_t> tile =
              engineTileOf(found->second, coordinate, tree.pixelFormat);
          const io::TilePlacement placement{coordinate, width, height};
          if (io::isImplicitEngineTile(tile.data(), surface.format, placement, tree.pixelFormat)) {
            continue;
          }
          std::vector<uint8_t> fileTile(io::documentTileBytes(surface.format));
          io::engineTileToFile(tile.data(), surface.format, placement, fileTile.data(),
                               tree.pixelFormat);
          if (!transaction.stageFile(
                  surface.folder + "/" + io::documentTileRelativePath(coordinate),
                  io::encodeDocumentTile(fileTile.data(), surface.format))) {
            error = "cannot write a tile of " + surface.folder;
            return false;
          }
          index.tiles.push_back(coordinate);
        }
      }
    }
    if (!transaction.stageFile(surface.folder + "/index.json", io::writeSurfaceIndexJson(index))) {
      error = "cannot write the index of " + surface.folder;
      return false;
    }
  }
  const std::string manifest =
      io::writeDocumentManifest(tree, width, height, documentSurfaces, name);
  if (!transaction.stageFile("manifest.json", {manifest.begin(), manifest.end()})) {
    error = "cannot write the manifest";
    return false;
  }
  return transaction.commit(error);
}

std::optional<LoadedDocument> readDocumentFolder(const std::string& folder, std::string& error) {
  if (!io::recoverDocumentFolder(folder, error)) {
    return std::nullopt;
  }
  const auto manifestBytes =
      io::readFile(io::joinPath(folder, "manifest.json"), io::kMaximumManifestBytes);
  if (!manifestBytes) {
    error = "no readable manifest.json";
    return std::nullopt;
  }
  const auto manifest = io::parseDocumentManifest(
      std::string_view(reinterpret_cast<const char*>(manifestBytes->data()), manifestBytes->size()),
      error);
  if (!manifest) {
    return std::nullopt;
  }
  LoadedDocument document;
  document.width = manifest->width;
  document.height = manifest->height;
  document.tree = manifest->tree;
  const canvas::TileRange canvasTiles =
      canvas::TileRange{0, 0, (manifest->width + kTileSizePixels - 1) / kTileSizePixels,
                        (manifest->height + kTileSizePixels - 1) / kTileSizePixels};
  const canvas::PixelFormat format = manifest->tree.pixelFormat;
  std::vector<uint8_t> tile(static_cast<size_t>(canvas::tileBytes(format)));
  for (const io::DocumentSurface& surface : manifest->surfaces) {
    const std::string surfaceFolder = io::joinPath(folder, surface.folder);
    bool rebuilt = false;
    const io::SurfaceIndex index = io::readSurfaceIndex(surfaceFolder, rebuilt);
    if (rebuilt && io::pathExists(surfaceFolder)) {
      document.warnings.push_back(surface.folder + ": index rebuilt from tile files");
    }
    Image image(manifest->width, manifest->height);
    if (surface.format == io::DocumentTileFormat::R8) {
      for (size_t pixel = 0; pixel < image.rgba.size(); ++pixel) {
        image.rgba[pixel] = 1.0;
      }
    }
    for (const TileCoordinate coordinate : index.tiles) {
      if (!canvasTiles.contains(coordinate)) {
        continue;
      }
      const io::TileReadResult result =
          io::readDocumentTile(surfaceFolder, coordinate, surface.format, tile.data(), format);
      if (result == io::TileReadResult::Corrupt) {
        document.warnings.push_back(surface.folder + "/" + io::documentTileRelativePath(coordinate) +
                                    ": corrupt, treated as empty");
        continue;
      }
      if (result == io::TileReadResult::Ok) {
        copyEngineTile(tile.data(), coordinate, image, format);
      }
    }
    document.surfaces.emplace(surface.id, std::move(image));
  }
  return document;
}

Image renderDocument(const LoadedDocument& document) {
  Image result = composeLayerTree(document.tree, document.width, document.height,
                                  [&document](canvas::LayerId id) -> const Image* {
                                    auto found = document.surfaces.find(id);
                                    return found == document.surfaces.end() ? nullptr
                                                                            : &found->second;
                                  });
  quantize(result, document.tree.pixelFormat);
  if (canvas::isLinear(document.tree.pixelFormat)) {
    result = encodedToSrgb(result);
    quantizeToRgba8(result);
  }
  return result;
}

}  // namespace nanquim::reference
