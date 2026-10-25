#include "formats/document_import.h"

#include <gtest/gtest.h>

#include <filesystem>
#include <nlohmann/json.hpp>
#include <set>

#include "io/file_system.h"

namespace nanquim::formats {
namespace {

using canvas::kTileBytesRgba8;
using canvas::kTileSizePixels;
using canvas::TileCoordinate;

std::string temporaryFolder(const std::string& name) {
  const auto folder = std::filesystem::temp_directory_path() / ("nanquim_" + name);
  std::filesystem::remove_all(folder);
  std::filesystem::create_directories(folder);
  return folder.u8string();
}

std::array<uint8_t, 4> pixelAt(const std::string& surfaceFolder, io::DocumentTileFormat format,
                               int32_t x, int32_t y) {
  std::vector<uint8_t> tile(kTileBytesRgba8);
  const TileCoordinate coordinate{x / kTileSizePixels, y / kTileSizePixels};
  if (io::readDocumentTile(surfaceFolder, coordinate, format, tile.data()) !=
      io::TileReadResult::Ok) {
    return {0, 0, 0, 0};
  }
  const size_t offset =
      (static_cast<size_t>(y % kTileSizePixels) * kTileSizePixels + x % kTileSizePixels) * 4;
  return {tile[offset], tile[offset + 1], tile[offset + 2], tile[offset + 3]};
}

std::set<std::pair<int32_t, int32_t>> indexedTiles(const std::string& surfaceFolder) {
  bool rebuilt = false;
  const io::SurfaceIndex index = io::readSurfaceIndex(surfaceFolder, rebuilt);
  EXPECT_FALSE(rebuilt);
  std::set<std::pair<int32_t, int32_t>> tiles;
  for (const TileCoordinate coordinate : index.tiles) {
    tiles.emplace(coordinate.column, coordinate.row);
  }
  return tiles;
}

TEST(DocumentImport, LayerRectanglesBecomePremultipliedTiles) {
  const std::string folder = temporaryFolder("import_layer");
  DocumentImportFolder document(folder, 600, 300);
  const std::string uuid = document.newUuid();
  SurfaceTileWriter writer = document.layerWriter(uuid);
  std::vector<uint8_t> band(size_t{20} * 300 * 4);
  for (size_t pixel = 0; pixel < size_t{20} * 300; ++pixel) {
    band[pixel * 4] = 200;
    band[pixel * 4 + 1] = 100;
    band[pixel * 4 + 2] = 0;
    band[pixel * 4 + 3] = 128;
  }
  ASSERT_TRUE(writer.writePixels(250, 0, 20, 300, band.data(), size_t{20} * 4));
  ASSERT_TRUE(writer.finish());

  const std::string surface = document.layerFolder(uuid);
  EXPECT_EQ(indexedTiles(surface),
            (std::set<std::pair<int32_t, int32_t>>{{0, 0}, {1, 0}, {0, 1}, {1, 1}}));
  const auto inside = pixelAt(surface, io::DocumentTileFormat::Rgba8, 260, 280);
  EXPECT_EQ(inside, (std::array<uint8_t, 4>{100, 50, 0, 128}));
  EXPECT_EQ(pixelAt(surface, io::DocumentTileFormat::Rgba8, 249, 10)[3], 0);
  EXPECT_EQ(pixelAt(surface, io::DocumentTileFormat::Rgba8, 270, 10)[3], 0);
}

TEST(DocumentImport, PartsOutsideTheCanvasAreClipped) {
  const std::string folder = temporaryFolder("import_clip");
  DocumentImportFolder document(folder, 100, 100);
  const std::string uuid = document.newUuid();
  SurfaceTileWriter writer = document.layerWriter(uuid);
  std::vector<uint8_t> block(size_t{50} * 50 * 4, 255);
  ASSERT_TRUE(writer.writePixels(-20, -20, 50, 50, block.data(), size_t{50} * 4));
  ASSERT_TRUE(writer.writePixels(80, 80, 50, 50, block.data(), size_t{50} * 4));
  ASSERT_TRUE(writer.finish());
  const std::string surface = document.layerFolder(uuid);
  EXPECT_EQ(pixelAt(surface, io::DocumentTileFormat::Rgba8, 0, 0)[3], 255);
  EXPECT_EQ(pixelAt(surface, io::DocumentTileFormat::Rgba8, 29, 29)[3], 255);
  EXPECT_EQ(pixelAt(surface, io::DocumentTileFormat::Rgba8, 30, 30)[3], 0);
  EXPECT_EQ(pixelAt(surface, io::DocumentTileFormat::Rgba8, 99, 99)[3], 255);
  EXPECT_EQ(pixelAt(surface, io::DocumentTileFormat::Rgba8, 120, 120)[3], 0);
}

TEST(DocumentImport, MasksUseTheirFillAndSkipWhiteTiles) {
  const std::string folder = temporaryFolder("import_mask");
  DocumentImportFolder document(folder, 512, 256);
  const std::string black = document.newUuid();
  SurfaceTileWriter blackWriter = document.maskWriter(black, 0);
  const std::vector<uint8_t> white(size_t{10} * 10, 255);
  ASSERT_TRUE(blackWriter.writePixels(5, 5, 10, 10, white.data(), 10));
  ASSERT_TRUE(blackWriter.finish());
  EXPECT_EQ(indexedTiles(document.maskFolder(black)),
            (std::set<std::pair<int32_t, int32_t>>{{0, 0}, {1, 0}}));
  EXPECT_EQ(pixelAt(document.maskFolder(black), io::DocumentTileFormat::R8, 8, 8)[0], 255);
  EXPECT_EQ(pixelAt(document.maskFolder(black), io::DocumentTileFormat::R8, 300, 8)[0], 0);

  const std::string whiteMask = document.newUuid();
  SurfaceTileWriter whiteWriter = document.maskWriter(whiteMask, 255);
  const std::vector<uint8_t> gray(4, 128);
  ASSERT_TRUE(whiteWriter.writePixels(400, 0, 2, 2, gray.data(), 2));
  ASSERT_TRUE(whiteWriter.finish());
  EXPECT_EQ(indexedTiles(document.maskFolder(whiteMask)),
            (std::set<std::pair<int32_t, int32_t>>{{1, 0}}));
}

TEST(DocumentImport, RectanglesMustNotGoBackAboveTheCurrentTileRow) {
  const std::string folder = temporaryFolder("import_order");
  DocumentImportFolder document(folder, 300, 600);
  SurfaceTileWriter writer = document.layerWriter(document.newUuid());
  const std::vector<uint8_t> pixel{1, 2, 3, 255};
  ASSERT_TRUE(writer.writePixels(0, 300, 1, 1, pixel.data(), 4));
  ASSERT_TRUE(writer.writePixels(5, 260, 1, 1, pixel.data(), 4));
  EXPECT_FALSE(writer.writePixels(0, 10, 1, 1, pixel.data(), 4));
  EXPECT_FALSE(writer.finish());
}

TEST(DocumentImport, UuidsAreVersion4AndDistinct) {
  DocumentImportFolder document(temporaryFolder("import_uuid"), 1, 1);
  std::set<std::string> seen;
  for (int index = 0; index < 1000; ++index) {
    const std::string uuid = document.newUuid();
    ASSERT_EQ(uuid.size(), 36U);
    EXPECT_EQ(uuid[14], '4');
    EXPECT_NE(std::string("89ab").find(uuid[19]), std::string::npos);
    EXPECT_TRUE(seen.insert(uuid).second);
  }
}

TEST(DocumentImport, ImportJsonDescribesTheTree) {
  ImportedDocument imported;
  imported.source = "psd";
  imported.width = 640;
  imported.height = 480;
  imported.dpi = 300;
  imported.backgroundColor = std::array<double, 4>{1, 1, 1, 1};
  ImportedNode layer;
  layer.uuid = "u1";
  layer.name = "Layer \xff bad UTF-8";
  layer.opacity = 0.5;
  layer.blend = "multiply";
  layer.clipping = true;
  layer.mask = ImportedMask{"m1", false, false};
  ImportedNode group;
  group.type = ImportedNode::Type::Group;
  group.uuid = "g1";
  group.name = "Group";
  group.blend = "passThrough";
  group.children.push_back(layer);
  imported.layers.push_back(group);
  imported.warnings.push_back({"adjustmentLayerSkipped", "Levels 1"});

  const nlohmann::json json = nlohmann::json::parse(importJsonText(imported));
  EXPECT_EQ(json["version"], 1);
  EXPECT_EQ(json["source"], "psd");
  EXPECT_EQ(json["width"], 640);
  EXPECT_EQ(json["background"]["color"][3], 1.0);
  const auto& groupJson = json["layers"][0];
  EXPECT_EQ(groupJson["type"], "group");
  EXPECT_EQ(groupJson["blend"], "passThrough");
  const auto& layerJson = groupJson["children"][0];
  EXPECT_EQ(layerJson["type"], "raster");
  EXPECT_EQ(layerJson["opacity"], 0.5);
  EXPECT_EQ(layerJson["clipping"], true);
  EXPECT_EQ(layerJson["mask"]["uuid"], "m1");
  EXPECT_EQ(layerJson["mask"]["enabled"], false);
  EXPECT_FALSE(layerJson.contains("children"));
  EXPECT_EQ(json["warnings"][0]["code"], "adjustmentLayerSkipped");
  EXPECT_EQ(json["warnings"][0]["detail"], "Levels 1");
}

}  // namespace
}  // namespace nanquim::formats
