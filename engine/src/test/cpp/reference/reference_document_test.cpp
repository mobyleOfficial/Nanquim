#include "reference_document.h"

#include <gtest/gtest.h>

#include <filesystem>

#include "io/document_manifest.h"
#include "io/file_system.h"
#include "reference_compositor.h"

namespace nanquim::reference {
namespace {

namespace fs = std::filesystem;

canvas::LayerTree treeWithMask() {
  std::string error;
  auto tree = canvas::parseLayerTree(R"({
      "background": [0.2, 0.3, 0.4, 1], "backgroundHidden": false, "activeLayer": 1,
      "layers": [
        { "type": "raster", "id": 1, "opacity": 0.8, "blend": "multiply", "hidden": false,
          "mask": { "id": 2, "enabled": true, "inverted": false } },
        { "type": "group", "id": 3, "opacity": 1, "blend": "passThrough", "hidden": false,
          "children": [ { "type": "raster", "id": 4, "opacity": 1, "blend": "normal",
                          "hidden": false, "clipping": true } ] } ] })",
                                     error);
  EXPECT_TRUE(tree.has_value()) << error;
  return *tree;
}

Image gradient(int32_t width, int32_t height, bool mask) {
  Image image(width, height);
  for (int32_t y = 0; y < height; ++y) {
    for (int32_t x = 0; x < width; ++x) {
      double* pixel = image.pixel(x, y);
      if (mask) {
        pixel[0] = pixel[1] = pixel[2] = (x % 256) / 255.0;
        pixel[3] = 1;
      } else if ((x / 37 + y / 41) % 3 != 0) {
        const double alpha = ((x + y) % 200 + 55) / 255.0;
        pixel[0] = alpha * (x % 256) / 255.0;
        pixel[1] = alpha * (y % 256) / 255.0;
        pixel[2] = alpha * 0.5;
        pixel[3] = alpha;
      }
    }
  }
  quantizeToRgba8(image);
  return image;
}

TEST(ReferenceDocument, WriteReadRoundTripIsExact) {
  const fs::path folder = fs::temp_directory_path() / "nanquim_reference_document";
  fs::remove_all(folder);
  const int32_t width = 600;
  const int32_t height = 300;
  const canvas::LayerTree tree = treeWithMask();
  std::map<canvas::LayerId, Image> surfaces;
  surfaces.emplace(1, gradient(width, height, false));
  surfaces.emplace(2, gradient(width, height, true));
  std::string error;
  ASSERT_TRUE(writeDocumentFolder(folder.u8string(), tree, width, height, surfaces, "Test", error))
      << error;

  const auto loaded = readDocumentFolder(folder.u8string(), error);
  ASSERT_TRUE(loaded.has_value()) << error;
  EXPECT_TRUE(loaded->warnings.empty());
  EXPECT_EQ(loaded->width, width);
  EXPECT_EQ(loaded->height, height);
  ASSERT_EQ(loaded->tree.rasterLayers().size(), 2U);
  const canvas::LayerTreeNode* layer = loaded->tree.find(1);
  ASSERT_NE(layer, nullptr);
  EXPECT_EQ(layer->blend, canvas::BlendMode::Multiply);
  EXPECT_FLOAT_EQ(layer->opacity, 0.8F);
  ASSERT_TRUE(layer->mask.has_value());
  EXPECT_EQ(layer->mask->id, 2U);
  EXPECT_FLOAT_EQ(loaded->tree.background[2], 0.4F);
  EXPECT_EQ(loaded->surfaces.at(1).rgba, surfaces.at(1).rgba);
  EXPECT_EQ(loaded->surfaces.at(2).rgba, surfaces.at(2).rgba);

  const auto originalComposite = composeLayerTree(
      tree, width, height, [&surfaces](canvas::LayerId id) -> const Image* {
        auto found = surfaces.find(id);
        return found == surfaces.end() ? nullptr : &found->second;
      });
  Image expected = originalComposite;
  quantizeToRgba8(expected);
  EXPECT_EQ(renderDocument(*loaded).rgba, expected.rgba);
}

TEST(ReferenceDocument, CorruptTileIsEmptyWithAWarning) {
  const fs::path folder = fs::temp_directory_path() / "nanquim_reference_document_corrupt";
  fs::remove_all(folder);
  const canvas::LayerTree tree = treeWithMask();
  std::map<canvas::LayerId, Image> surfaces;
  surfaces.emplace(1, gradient(300, 300, false));
  std::string error;
  ASSERT_TRUE(writeDocumentFolder(folder.u8string(), tree, 300, 300, surfaces, "Test", error));
  const auto manifestSurfaces = io::surfacesOf(tree);
  const fs::path tile = folder / manifestSurfaces[0].folder / "tiles/0_0.lz4";
  ASSERT_TRUE(fs::exists(tile));
  ASSERT_TRUE(io::writeFileDurably(tile.u8string(), {4, 34, 77, 24, 0}));
  fs::remove(folder / manifestSurfaces[0].folder / "index.json");

  const auto loaded = readDocumentFolder(folder.u8string(), error);
  ASSERT_TRUE(loaded.has_value()) << error;
  EXPECT_EQ(loaded->warnings.size(), 2U);
  ASSERT_GT(surfaces.at(1).pixel(40, 10)[3], 0.0);
  EXPECT_EQ(loaded->surfaces.at(1).pixel(40, 10)[3], 0.0);
  EXPECT_EQ(loaded->surfaces.at(1).pixel(299, 299)[3], surfaces.at(1).pixel(299, 299)[3]);
}

TEST(ReferenceDocument, ManifestRejectsNewerReaders) {
  std::string error;
  EXPECT_FALSE(io::parseDocumentManifest(
                   R"({"format":"nanquim","version":3,"minReaderVersion":2,
                       "canvas":{"width":10,"height":10},"layers":[]})",
                   error)
                   .has_value());
  EXPECT_FALSE(io::parseDocumentManifest(R"({"format":"other"})", error).has_value());
  EXPECT_FALSE(io::parseDocumentManifest(
                   R"({"format":"nanquim","canvas":{"width":10,"height":10},
                       "layers":[{"type":"raster","id":"a"},{"type":"raster","id":"a"}]})",
                   error)
                   .has_value());
  EXPECT_TRUE(io::parseDocumentManifest(
                  R"({"format":"nanquim","version":2,"minReaderVersion":1,"future":{"x":1},
                      "canvas":{"width":10,"height":10},
                      "layers":[{"type":"raster","id":"a","newField":true}]})",
                  error)
                  .has_value())
      << error;
}

}  // namespace
}  // namespace nanquim::reference
