#include "formats/psd_reader.h"

#include <gtest/gtest.h>

#include <filesystem>
#include <functional>
#include <nlohmann/json.hpp>

#include "formats/psd_builder.h"
#include "io/document_tile.h"
#include "io/file_system.h"

namespace nanquim::formats {
namespace {

using canvas::kTileBytesRgba8;
using canvas::kTileSizePixels;
using testing::buildPsd;
using testing::PsdTestChannel;
using testing::PsdTestFile;
using testing::PsdTestLayer;

std::string temporaryFolder(const std::string& name) {
  const auto folder = std::filesystem::temp_directory_path() / ("nanquim_psd_" + name);
  std::filesystem::remove_all(folder);
  std::filesystem::create_directories(folder);
  return folder.u8string();
}

std::vector<uint8_t> plane(int32_t width, int32_t height,
                           const std::function<uint16_t(int32_t, int32_t)>& value, int depth = 8) {
  std::vector<uint8_t> samples;
  for (int32_t y = 0; y < height; ++y) {
    for (int32_t x = 0; x < width; ++x) {
      const uint16_t sample = value(x, y);
      if (depth == 16) {
        samples.push_back(static_cast<uint8_t>(sample >> 8U));
      }
      samples.push_back(static_cast<uint8_t>(sample & 0xFFU));
    }
  }
  return samples;
}

std::vector<uint8_t> constant(int32_t width, int32_t height, uint16_t value, int depth = 8) {
  return plane(width, height, [value](int32_t, int32_t) { return value; }, depth);
}

PsdTestLayer rgbaLayer(int32_t left, int32_t top, int32_t width, int32_t height, uint8_t red,
                       uint8_t green, uint8_t blue, uint8_t alpha, const std::string& name) {
  PsdTestLayer layer;
  layer.left = left;
  layer.top = top;
  layer.right = left + width;
  layer.bottom = top + height;
  layer.name = name;
  layer.channels = {{-1, constant(width, height, alpha)},
                    {0, constant(width, height, red)},
                    {1, constant(width, height, green)},
                    {2, constant(width, height, blue)}};
  return layer;
}

struct Imported {
  ImportResult result;
  nlohmann::json json;
  std::string folder;
};

Imported import(const std::vector<uint8_t>& bytes, const std::string& name,
                const ImportLimits& limits = {}) {
  Imported imported;
  imported.folder = temporaryFolder(name);
  MemoryInputSource source(bytes);
  imported.result = importPsd(source, imported.folder, limits);
  if (imported.result.ok) {
    const auto text = io::readFile(io::joinPath(imported.folder, "import.json"), 1 << 20);
    if (text) {
      imported.json = nlohmann::json::parse(text->begin(), text->end());
    } else {
      ADD_FAILURE() << "no import.json";
    }
  }
  return imported;
}

std::array<uint8_t, 4> pixelAt(const Imported& imported, const std::string& uuid, int32_t x,
                               int32_t y, bool mask = false) {
  std::vector<uint8_t> tile(kTileBytesRgba8);
  const std::string surface =
      io::joinPath(io::joinPath(imported.folder, mask ? "masks" : "layers"), uuid);
  const io::TileReadResult read = io::readDocumentTile(
      surface, {x / kTileSizePixels, y / kTileSizePixels},
      mask ? io::DocumentTileFormat::R8 : io::DocumentTileFormat::Rgba8, tile.data());
  if (read != io::TileReadResult::Ok) {
    return mask ? std::array<uint8_t, 4>{255, 255, 255, 255} : std::array<uint8_t, 4>{0, 0, 0, 0};
  }
  const size_t offset =
      (static_cast<size_t>(y % kTileSizePixels) * kTileSizePixels + x % kTileSizePixels) * 4;
  return {tile[offset], tile[offset + 1], tile[offset + 2], tile[offset + 3]};
}

bool hasWarning(const Imported& imported, const std::string& code) {
  for (const auto& warning : imported.json["warnings"]) {
    if (warning["code"] == code) {
      return true;
    }
  }
  return false;
}

PsdTestFile twoLayerFile(uint16_t compression) {
  PsdTestFile file;
  file.width = 300;
  file.height = 300;
  file.compression = compression;
  file.layers.push_back(rgbaLayer(0, 0, 300, 300, 255, 0, 0, 255, "Red"));
  PsdTestLayer top = rgbaLayer(240, 250, 40, 20, 0, 200, 0, 128, "Green");
  top.blendKey = "mul ";
  top.opacity = 128;
  file.layers.push_back(top);
  return file;
}

TEST(PsdReader, RgbLayersWithEveryCompression) {
  for (uint16_t compression = 0; compression <= 3; ++compression) {
    const Imported imported =
        import(buildPsd(twoLayerFile(compression)), "rgb" + std::to_string(compression));
    ASSERT_TRUE(imported.result.ok)
        << imported.result.errorCode << " " << imported.result.errorDetail;
    const auto& layers = imported.json["layers"];
    ASSERT_EQ(layers.size(), 2U);
    EXPECT_EQ(imported.json["width"], 300);
    EXPECT_EQ(imported.json["source"], "psd");
    EXPECT_EQ(layers[0]["name"], "Red");
    EXPECT_EQ(layers[0]["blend"], "normal");
    EXPECT_EQ(layers[1]["name"], "Green");
    EXPECT_EQ(layers[1]["blend"], "multiply");
    EXPECT_NEAR(layers[1]["opacity"].get<double>(), 128 / 255.0, 1e-9);
    const std::string red = layers[0]["uuid"];
    const std::string green = layers[1]["uuid"];
    EXPECT_EQ(pixelAt(imported, red, 299, 299), (std::array<uint8_t, 4>{255, 0, 0, 255}));
    EXPECT_EQ(pixelAt(imported, green, 260, 260), (std::array<uint8_t, 4>{0, 100, 0, 128}));
    EXPECT_EQ(pixelAt(imported, green, 239, 260)[3], 0);
    EXPECT_EQ(pixelAt(imported, green, 255, 255)[3], 128);
    EXPECT_EQ(pixelAt(imported, green, 256, 256)[3], 128);
  }
}

TEST(PsdReader, SixteenBitLayersInLr16WithPrediction) {
  PsdTestFile file;
  file.width = 64;
  file.height = 32;
  file.depth = 16;
  file.compression = 3;
  file.layersInLr16 = true;
  PsdTestLayer layer;
  layer.right = 64;
  layer.bottom = 32;
  layer.name = "Gradient";
  layer.channels = {
      {-1, constant(64, 32, 65535, 16)},
      {0, plane(
              64, 32, [](int32_t x, int32_t) { return static_cast<uint16_t>(x * 1000); }, 16)},
      {1, constant(64, 32, 32768, 16)},
      {2, constant(64, 32, 0, 16)}};
  file.layers.push_back(layer);
  const Imported imported = import(buildPsd(file), "sixteen");
  ASSERT_TRUE(imported.result.ok) << imported.result.errorCode << " "
                                  << imported.result.errorDetail;
  const std::string uuid = imported.json["layers"][0]["uuid"];
  EXPECT_EQ(pixelAt(imported, uuid, 63, 5), (std::array<uint8_t, 4>{245, 128, 0, 255}));
  EXPECT_EQ(pixelAt(imported, uuid, 1, 5)[0], 4);
}

TEST(PsdReader, GrayscaleAndCmykAreConverted) {
  PsdTestFile gray;
  gray.width = gray.height = 8;
  gray.colorMode = 1;
  gray.channels = 1;
  PsdTestLayer grayLayer;
  grayLayer.right = grayLayer.bottom = 8;
  grayLayer.channels = {{0, constant(8, 8, 90)}};
  gray.layers.push_back(grayLayer);
  const Imported grayImported = import(buildPsd(gray), "gray");
  ASSERT_TRUE(grayImported.result.ok);
  EXPECT_EQ(pixelAt(grayImported, grayImported.json["layers"][0]["uuid"], 3, 3),
            (std::array<uint8_t, 4>{90, 90, 90, 255}));

  PsdTestFile cmyk;
  cmyk.width = cmyk.height = 8;
  cmyk.colorMode = 4;
  cmyk.channels = 4;
  PsdTestLayer cmykLayer;
  cmykLayer.right = cmykLayer.bottom = 8;
  cmykLayer.channels = {{0, constant(8, 8, 0)},
                        {1, constant(8, 8, 255)},
                        {2, constant(8, 8, 255)},
                        {3, constant(8, 8, 128)}};
  cmyk.layers.push_back(cmykLayer);
  const Imported cmykImported = import(buildPsd(cmyk), "cmyk");
  ASSERT_TRUE(cmykImported.result.ok);
  EXPECT_EQ(pixelAt(cmykImported, cmykImported.json["layers"][0]["uuid"], 3, 3),
            (std::array<uint8_t, 4>{0, 128, 128, 255}));
  EXPECT_TRUE(hasWarning(cmykImported, "cmykConverted"));
}

TEST(PsdReader, GroupsFlagsAndNames) {
  PsdTestFile file;
  file.width = file.height = 16;
  PsdTestLayer end;
  end.sectionType = 3;
  end.name = "</Layer group>";
  PsdTestLayer inside = rgbaLayer(0, 0, 4, 4, 1, 2, 3, 255, "inside");
  inside.clipping = 1;
  inside.flags = 0x01;
  PsdTestLayer group;
  group.sectionType = 2;
  group.sectionBlendKey = "pass";
  group.blendKey = "pass";
  group.flags = 0x02;
  group.name = "Group";
  group.unicodeName = u"Grupo é \U0001F3A8";
  group.opacity = 51;
  PsdTestLayer outside = rgbaLayer(0, 0, 2, 2, 9, 9, 9, 255, "outside");
  outside.fillOpacity = 128;
  file.layers = {end, inside, group, outside};
  const Imported imported = import(buildPsd(file), "groups");
  ASSERT_TRUE(imported.result.ok) << imported.result.errorCode;
  const auto& layers = imported.json["layers"];
  ASSERT_EQ(layers.size(), 2U);
  EXPECT_EQ(layers[0]["type"], "group");
  EXPECT_EQ(layers[0]["name"], "Grupo \xc3\xa9 \xf0\x9f\x8e\xa8");
  EXPECT_EQ(layers[0]["blend"], "passThrough");
  EXPECT_EQ(layers[0]["hidden"], true);
  EXPECT_EQ(layers[0]["collapsed"], true);
  EXPECT_NEAR(layers[0]["opacity"].get<double>(), 0.2, 1e-9);
  ASSERT_EQ(layers[0]["children"].size(), 1U);
  EXPECT_EQ(layers[0]["children"][0]["name"], "inside");
  EXPECT_EQ(layers[0]["children"][0]["clipping"], true);
  EXPECT_EQ(layers[0]["children"][0]["alphaLock"], true);
  EXPECT_EQ(layers[1]["name"], "outside");
  EXPECT_NEAR(layers[1]["opacity"].get<double>(), 128 / 255.0, 1e-9);
}

TEST(PsdReader, UserMask) {
  PsdTestFile file;
  file.width = file.height = 300;
  PsdTestLayer layer = rgbaLayer(0, 0, 300, 300, 10, 20, 30, 255, "masked");
  layer.hasMask = true;
  layer.maskLeft = 10;
  layer.maskTop = 20;
  layer.maskRight = 30;
  layer.maskBottom = 40;
  layer.maskDefault = 0;
  layer.maskFlags = 0x02;
  layer.channels.push_back({-2, constant(20, 20, 200)});
  file.layers.push_back(layer);
  const Imported imported = import(buildPsd(file), "mask");
  ASSERT_TRUE(imported.result.ok) << imported.result.errorCode;
  const auto& mask = imported.json["layers"][0]["mask"];
  ASSERT_FALSE(mask.is_null());
  EXPECT_EQ(mask["enabled"], false);
  const std::string uuid = mask["uuid"];
  EXPECT_EQ(pixelAt(imported, uuid, 15, 25, true)[0], 200);
  EXPECT_EQ(pixelAt(imported, uuid, 5, 5, true)[0], 0);
  EXPECT_EQ(pixelAt(imported, uuid, 290, 290, true)[0], 0);
}

TEST(PsdReader, UnsupportedContentIsReported) {
  PsdTestFile file;
  file.width = file.height = 8;
  PsdTestLayer adjustment = rgbaLayer(0, 0, 8, 8, 0, 0, 0, 255, "Levels 1");
  adjustment.extraKeys = {"levl"};
  PsdTestLayer effects = rgbaLayer(0, 0, 8, 8, 0, 0, 0, 255, "Shadowed");
  effects.extraKeys = {"lfx2"};
  PsdTestLayer text = rgbaLayer(0, 0, 8, 8, 0, 0, 0, 255, "Title");
  text.extraKeys = {"TySh"};
  PsdTestLayer dissolve = rgbaLayer(0, 0, 8, 8, 0, 0, 0, 255, "Noise");
  dissolve.blendKey = "diss";
  file.layers = {adjustment, effects, text, dissolve};
  const Imported imported = import(buildPsd(file), "unsupported");
  ASSERT_TRUE(imported.result.ok);
  EXPECT_EQ(imported.json["layers"].size(), 3U);
  EXPECT_TRUE(hasWarning(imported, "adjustmentLayerSkipped"));
  EXPECT_TRUE(hasWarning(imported, "layerEffectsDropped"));
  EXPECT_TRUE(hasWarning(imported, "textRasterized"));
  EXPECT_TRUE(hasWarning(imported, "blendModeReplaced"));
  EXPECT_EQ(imported.json["layers"][2]["blend"], "normal");
}

TEST(PsdReader, MergedImageWhenThereAreNoLayers) {
  for (uint16_t compression : {0, 1}) {
    PsdTestFile file;
    file.width = 20;
    file.height = 10;
    file.compression = compression;
    file.dpi = 300;
    file.merged = {plane(20, 10, [](int32_t x, int32_t) { return static_cast<uint16_t>(x * 10); }),
                   constant(20, 10, 7), constant(20, 10, 9)};
    const Imported imported = import(buildPsd(file), "merged" + std::to_string(compression));
    ASSERT_TRUE(imported.result.ok) << imported.result.errorCode << imported.result.errorDetail;
    ASSERT_EQ(imported.json["layers"].size(), 1U);
    EXPECT_EQ(imported.json["layers"][0]["name"], "");
    EXPECT_NEAR(imported.json["dpi"].get<double>(), 300, 1e-6);
    EXPECT_TRUE(hasWarning(imported, "mergedImageUsed"));
    EXPECT_EQ(pixelAt(imported, imported.json["layers"][0]["uuid"], 19, 9),
              (std::array<uint8_t, 4>{190, 7, 9, 255}));
  }
}

TEST(PsdReader, Psb) {
  PsdTestFile file = twoLayerFile(1);
  file.version = 2;
  const Imported imported = import(buildPsd(file), "psb");
  ASSERT_TRUE(imported.result.ok) << imported.result.errorCode << imported.result.errorDetail;
  EXPECT_EQ(imported.json["source"], "psb");
  EXPECT_EQ(pixelAt(imported, imported.json["layers"][1]["uuid"], 260, 260)[3], 128);
}

TEST(PsdReader, RejectionsAndCorruption) {
  EXPECT_EQ(import({'n', 'o', 'p', 'e'}, "not").result.errorCode, "notPsd");
  std::vector<uint8_t> bitmap = buildPsd(twoLayerFile(1));
  bitmap[25] = 0;
  EXPECT_EQ(import(bitmap, "bitmap").result.errorCode, "unsupportedColorMode");
  std::vector<uint8_t> thirtyTwo = buildPsd(twoLayerFile(1));
  thirtyTwo[23] = 32;
  EXPECT_EQ(import(thirtyTwo, "float").result.errorCode, "unsupportedDepth");
  ImportLimits small;
  small.maximumSide = 100;
  EXPECT_EQ(import(buildPsd(twoLayerFile(1)), "large", small).result.errorCode, "canvasTooLarge");
  const std::vector<uint8_t> whole = buildPsd(twoLayerFile(0));
  const size_t layerDataEnd = whole.size() - (2 + 3 * 300 * 300);
  for (size_t length = 0; length < whole.size(); length += 997) {
    const std::vector<uint8_t> truncated(whole.begin(),
                                         whole.begin() + static_cast<std::ptrdiff_t>(length));
    const bool ok = import(truncated, "truncated").result.ok;
    if (length < layerDataEnd) {
      EXPECT_FALSE(ok) << length;
    }
  }
}

}  // namespace
}  // namespace nanquim::formats
