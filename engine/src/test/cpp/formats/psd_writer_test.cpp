#include "formats/psd_writer.h"

#include <gtest/gtest.h>
#include <miniz.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <functional>

#include "core/half_float.h"
#include "formats/icc_profile.h"
#include "formats/psd_builder.h"
#include "formats/psd_reader.h"
#include "formats/source_reader.h"
#include "io/document_tile.h"
#include "io/file_system.h"

namespace nanquim::formats {
namespace {

using canvas::kTileBytesRgba8;
using canvas::kTileSizePixels;
using testing::buildPsd;
using testing::PsdTestFile;
using testing::PsdTestLayer;

std::string temporaryFolder(const std::string& name) {
  const auto folder = std::filesystem::temp_directory_path() / ("nanquim_psd_export_" + name);
  std::filesystem::remove_all(folder);
  std::filesystem::create_directories(folder);
  return folder.u8string();
}

class FunctionComposite final : public CompositeSource {
 public:
  FunctionComposite(int32_t width, std::function<std::array<uint8_t, 4>(int32_t, int32_t)> pixel)
      : width_(width), pixel_(std::move(pixel)) {}

  bool readRows(int32_t top, int32_t count, uint8_t* rgba) override {
    for (int32_t y = top; y < top + count; ++y) {
      for (int32_t x = 0; x < width_; ++x) {
        const auto value = pixel_(x, y);
        std::copy(value.begin(), value.end(),
                  rgba + (static_cast<size_t>(y - top) * width_ + x) * 4);
      }
    }
    return true;
  }

 private:
  int32_t width_;
  std::function<std::array<uint8_t, 4>(int32_t, int32_t)> pixel_;
};

FunctionComposite transparentComposite(int32_t width) {
  return {width, [](int32_t, int32_t) { return std::array<uint8_t, 4>{0, 0, 0, 0}; }};
}

std::vector<uint8_t> readAll(const std::string& path) {
  return io::readFile(path, int64_t{1} << 30).value_or(std::vector<uint8_t>());
}

struct Imported {
  ImportResult result;
  ImportedDocument document;
  std::string folder;
};

Imported importFile(const std::string& path, const std::string& name) {
  Imported imported;
  imported.folder = temporaryFolder(name);
  MemoryInputSource source(readAll(path));
  imported.result = importPsd(source, imported.folder, {32768, 10000});
  if (!imported.result.ok) {
    ADD_FAILURE() << imported.result.errorCode << " " << imported.result.errorDetail;
    return imported;
  }
  imported.document = imported.result.document;
  return imported;
}

Imported importBytes(const std::vector<uint8_t>& bytes, const std::string& name) {
  const std::string folder = temporaryFolder(name + "_source");
  const std::string path = io::joinPath(folder, "source.psd");
  EXPECT_TRUE(io::writeFileDurably(path, bytes));
  return importFile(path, name);
}

std::vector<uint8_t> surfacePixels(const std::string& documentFolder, const std::string& uuid,
                                   bool mask, int32_t width, int32_t height) {
  const std::string surface =
      io::joinPath(io::joinPath(documentFolder, mask ? "masks" : "layers"), uuid);
  std::vector<uint8_t> pixels;
  std::vector<uint8_t> tile(kTileBytesRgba8);
  for (int32_t row = 0; row * kTileSizePixels < height; ++row) {
    for (int32_t column = 0; column * kTileSizePixels < width; ++column) {
      const auto format = mask ? io::DocumentTileFormat::R8 : io::DocumentTileFormat::Rgba8;
      if (io::readDocumentTile(surface, {column, row}, format, tile.data()) !=
          io::TileReadResult::Ok) {
        std::fill(tile.begin(), tile.end(), mask ? 255 : 0);
      }
      const io::TilePlacement placement{{column, row}, width, height};
      for (int32_t y = 0; y < placement.rowsInside(); ++y) {
        const uint8_t* line = tile.data() + static_cast<size_t>(y) * kTileSizePixels * 4;
        pixels.insert(pixels.end(), line,
                      line + static_cast<size_t>(placement.columnsInside()) * 4);
      }
    }
  }
  return pixels;
}

ExportResult exportTo(const Imported& imported, const std::string& path, CompositeSource& composite,
                      const PsdExportOptions& options = {}) {
  return exportPsd(imported.document, imported.folder, composite, options, path);
}

void expectSameTree(const std::vector<ImportedNode>& expected,
                    const std::vector<ImportedNode>& actual, bool inversionApplied = true,
                    const std::string& path = "") {
  ASSERT_EQ(expected.size(), actual.size()) << path;
  for (size_t index = 0; index < expected.size(); ++index) {
    const ImportedNode& a = expected[index];
    const ImportedNode& b = actual[index];
    const std::string where = path + "/" + a.name;
    EXPECT_EQ(a.type, b.type) << where;
    EXPECT_EQ(a.name, b.name) << where;
    EXPECT_NEAR(a.opacity, b.opacity, 0.5 / 255) << where;
    EXPECT_EQ(a.blend, b.blend) << where;
    EXPECT_EQ(a.hidden, b.hidden) << where;
    EXPECT_EQ(a.locked, b.locked) << where;
    EXPECT_EQ(a.alphaLock, b.alphaLock) << where;
    EXPECT_EQ(a.clipping, b.clipping) << where;
    EXPECT_EQ(a.collapsed, b.collapsed) << where;
    ASSERT_EQ(a.mask.has_value(), b.mask.has_value()) << where;
    if (a.mask.has_value() && b.mask.has_value()) {
      EXPECT_EQ(a.mask->enabled, b.mask->enabled) << where;
      EXPECT_EQ(b.mask->inverted, !inversionApplied && a.mask->inverted) << where;
    }
    expectSameTree(a.children, b.children, inversionApplied, where);
  }
}

void expectSamePixels(const Imported& expected, const std::vector<ImportedNode>& expectedNodes,
                      const Imported& actual, const std::vector<ImportedNode>& actualNodes) {
  const int32_t width = expected.document.width;
  const int32_t height = expected.document.height;
  for (size_t index = 0; index < expectedNodes.size() && index < actualNodes.size(); ++index) {
    const ImportedNode& a = expectedNodes[index];
    const ImportedNode& b = actualNodes[index];
    if (a.type == ImportedNode::Type::Group) {
      expectSamePixels(expected, a.children, actual, b.children);
      continue;
    }
    EXPECT_EQ(surfacePixels(expected.folder, a.uuid, false, width, height),
              surfacePixels(actual.folder, b.uuid, false, width, height))
        << a.name;
    if (a.mask.has_value() && b.mask.has_value()) {
      const std::string expectedUuid = a.mask->uuid;
      const std::string actualUuid = b.mask->uuid;
      const bool inverted = a.mask->inverted;
      std::vector<uint8_t> mask = surfacePixels(expected.folder, expectedUuid, true, width, height);
      if (inverted) {
        for (size_t pixel = 0; pixel < mask.size(); pixel += 4) {
          for (size_t channel = 0; channel < 3; ++channel) {
            mask[pixel + channel] = static_cast<uint8_t>(255 - mask[pixel + channel]);
          }
        }
      }
      EXPECT_EQ(mask, surfacePixels(actual.folder, actualUuid, true, width, height)) << a.name;
    }
  }
}

PsdTestLayer layer(int32_t left, int32_t top, int32_t width, int32_t height,
                   const std::function<std::array<uint8_t, 4>(int32_t, int32_t)>& pixel,
                   const std::string& name) {
  PsdTestLayer result;
  result.left = left;
  result.top = top;
  result.right = left + width;
  result.bottom = top + height;
  result.name = name;
  for (const int16_t id : {int16_t{-1}, int16_t{0}, int16_t{1}, int16_t{2}}) {
    std::vector<uint8_t> samples;
    for (int32_t y = 0; y < height; ++y) {
      for (int32_t x = 0; x < width; ++x) {
        samples.push_back(pixel(x, y)[id == -1 ? 3 : static_cast<size_t>(id)]);
      }
    }
    result.channels.push_back({id, samples});
  }
  return result;
}

PsdTestLayer section(uint32_t type, const std::string& name, const std::string& blend = "pass") {
  PsdTestLayer result;
  result.name = name;
  result.sectionType = type;
  if (type != 3) {
    result.sectionBlendKey = blend;
  }
  result.blendKey = blend;
  for (const int16_t id : {int16_t{-1}, int16_t{0}, int16_t{1}, int16_t{2}}) {
    result.channels.push_back({id, {}});
  }
  return result;
}

PsdTestFile richFile() {
  PsdTestFile file;
  file.width = 600;
  file.height = 520;
  file.negativeLayerCount = true;
  file.channels = 4;
  auto gradient = [](int32_t x, int32_t y) {
    return std::array<uint8_t, 4>{static_cast<uint8_t>(x), static_cast<uint8_t>(y),
                                  static_cast<uint8_t>(x ^ y),
                                  static_cast<uint8_t>((x * 7 + y * 3) % 256)};
  };
  file.layers.push_back(layer(0, 0, 600, 520, gradient, "Base"));
  file.layers.push_back(section(3, "</Layer group>"));
  file.layers.push_back(section(3, "</Layer group>"));
  PsdTestLayer inner = layer(250, 200, 300, 100, gradient, "Inner");
  inner.blendKey = "scrn";
  inner.opacity = 77;
  inner.flags = 0x01;
  file.layers.push_back(inner);
  PsdTestLayer clipped = layer(260, 210, 50, 60, gradient, "Clipped");
  clipped.clipping = 1;
  clipped.flags = 0x02;
  file.layers.push_back(clipped);
  PsdTestLayer group = section(2, "Closed group", "mul ");
  group.opacity = 200;
  file.layers.push_back(group);
  PsdTestLayer masked = layer(10, 300, 200, 200, gradient, "Masked");
  masked.unicodeName = u"Camada çã 🎨";
  masked.hasMask = true;
  masked.maskLeft = 30;
  masked.maskTop = 280;
  masked.maskRight = 330;
  masked.maskBottom = 400;
  masked.maskDefault = 255;
  std::vector<uint8_t> maskSamples;
  for (int32_t y = 0; y < 120; ++y) {
    for (int32_t x = 0; x < 300; ++x) {
      maskSamples.push_back(static_cast<uint8_t>((x + y) % 256));
    }
  }
  masked.channels.push_back({-2, maskSamples});
  file.layers.push_back(masked);
  PsdTestLayer disabledMask = layer(400, 20, 100, 100, gradient, "Disabled mask");
  disabledMask.hasMask = true;
  disabledMask.maskLeft = 400;
  disabledMask.maskTop = 20;
  disabledMask.maskRight = 450;
  disabledMask.maskBottom = 60;
  disabledMask.maskDefault = 0;
  disabledMask.maskFlags = 0x02;
  disabledMask.channels.push_back({-2, std::vector<uint8_t>(size_t{50} * 40, 90)});
  file.layers.push_back(disabledMask);
  file.layers.push_back(section(1, "Open group"));
  return file;
}

TEST(PsdWriter, RoundTripKeepsTreeAndPixels) {
  Imported original = importBytes(buildPsd(richFile()), "rich");
  ASSERT_TRUE(original.result.ok);
  ASSERT_EQ(original.document.layers.size(), 2U);
  ImportedNode& base = original.document.layers[0];
  base.locked = true;
  ImportedNode& open = original.document.layers[1];
  ASSERT_EQ(open.children.size(), 3U);
  ImportedNode& masked = open.children[1];
  ASSERT_TRUE(masked.mask.has_value());
  masked.mask->inverted = true;

  const std::string output = io::joinPath(temporaryFolder("rich_out"), "out.psd");
  FunctionComposite composite = transparentComposite(original.document.width);
  const ExportResult exported = exportTo(original, output, composite);
  ASSERT_TRUE(exported.ok) << exported.errorCode << " " << exported.errorDetail;
  EXPECT_TRUE(exported.warnings.empty());

  const Imported again = importFile(output, "rich_again");
  ASSERT_TRUE(again.result.ok);
  EXPECT_EQ(again.document.width, 600);
  EXPECT_EQ(again.document.height, 520);
  EXPECT_TRUE(again.document.warnings.empty());
  expectSameTree(original.document.layers, again.document.layers);
  expectSamePixels(original, original.document.layers, again, again.document.layers);
}

TEST(PsdWriter, EveryPremultipliedPixelSurvivesExactly) {
  const std::string folder = temporaryFolder("premultiplied");
  const std::string uuid = "00000000-0000-4000-8000-000000000001";
  std::vector<uint8_t> tile(kTileBytesRgba8);
  for (int32_t alpha = 0; alpha < 256; ++alpha) {
    for (int32_t channel = 0; channel < 256; ++channel) {
      uint8_t* pixel = &tile[(static_cast<size_t>(alpha) * kTileSizePixels + channel) * 4];
      const auto value = static_cast<uint8_t>(std::min(channel, alpha));
      pixel[0] = value;
      pixel[1] = static_cast<uint8_t>(alpha - value);
      pixel[2] = static_cast<uint8_t>(value / 2);
      pixel[3] = static_cast<uint8_t>(alpha);
    }
  }
  const std::string surface = io::joinPath(io::joinPath(folder, "layers"), uuid);
  ASSERT_TRUE(
      io::writeFileDurably(io::joinPath(surface, io::documentTileRelativePath({0, 0})),
                           io::encodeDocumentTile(tile.data(), io::DocumentTileFormat::Rgba8)));
  io::SurfaceIndex index;
  index.tiles = {{0, 0}};
  ASSERT_TRUE(
      io::writeFileDurably(io::joinPath(surface, "index.json"), io::writeSurfaceIndexJson(index)));
  Imported document;
  document.folder = folder;
  document.document.width = 256;
  document.document.height = 256;
  ImportedNode node;
  node.uuid = uuid;
  node.name = "All pairs";
  document.document.layers.push_back(node);

  const std::string output = io::joinPath(temporaryFolder("premultiplied_out"), "out.psd");
  FunctionComposite composite = transparentComposite(256);
  ASSERT_TRUE(exportTo(document, output, composite).ok);
  const Imported again = importFile(output, "premultiplied_again");
  ASSERT_EQ(again.document.layers.size(), 1U);
  EXPECT_EQ(surfacePixels(folder, uuid, false, 256, 256),
            surfacePixels(again.folder, again.document.layers[0].uuid, false, 256, 256));
}

std::vector<std::vector<uint8_t>> mergedPlanes(const std::vector<uint8_t>& file, int32_t width,
                                               int32_t height) {
  size_t offset = 26;
  const auto u32 = [&file](size_t at) {
    return (static_cast<uint32_t>(file.at(at)) << 24U) |
           (static_cast<uint32_t>(file.at(at + 1)) << 16U) |
           (static_cast<uint32_t>(file.at(at + 2)) << 8U) | file.at(at + 3);
  };
  for (int section = 0; section < 3; ++section) {
    offset += 4 + u32(offset);
  }
  EXPECT_EQ(file.at(offset), 0);
  EXPECT_EQ(file.at(offset + 1), 1);
  size_t lengths = offset + 2;
  size_t data = lengths + size_t{4} * static_cast<size_t>(height) * 2;
  std::vector<std::vector<uint8_t>> planes(4);
  for (auto& plane : planes) {
    plane.resize(static_cast<size_t>(width) * static_cast<size_t>(height));
    for (int32_t row = 0; row < height; ++row) {
      const size_t length = (static_cast<size_t>(file.at(lengths)) << 8U) | file.at(lengths + 1);
      lengths += 2;
      EXPECT_TRUE(decodePackBits(file.data() + data, length,
                                 plane.data() + static_cast<size_t>(row) * width,
                                 static_cast<size_t>(width)));
      data += length;
    }
  }
  EXPECT_EQ(data, file.size());
  return planes;
}

TEST(PsdWriter, MergedImageIsTheComposite) {
  Imported document;
  document.folder = temporaryFolder("merged");
  document.document.width = 300;
  document.document.height = 280;
  ImportedNode node;
  node.uuid = "layer";
  document.document.layers.push_back(node);
  auto pixel = [](int32_t x, int32_t y) {
    return std::array<uint8_t, 4>{static_cast<uint8_t>(x), static_cast<uint8_t>(y),
                                  static_cast<uint8_t>(x * y), static_cast<uint8_t>(x + 2 * y)};
  };
  FunctionComposite composite(300, pixel);
  const std::string output = io::joinPath(temporaryFolder("merged_out"), "out.psd");
  ASSERT_TRUE(exportTo(document, output, composite).ok);
  const auto planes = mergedPlanes(readAll(output), 300, 280);
  for (int32_t y = 0; y < 280; ++y) {
    for (int32_t x = 0; x < 300; ++x) {
      const size_t index = static_cast<size_t>(y) * 300 + x;
      const auto expected = pixel(x, y);
      ASSERT_EQ((std::array<uint8_t, 4>{planes[0][index], planes[1][index], planes[2][index],
                                        planes[3][index]}),
                expected)
          << x << "," << y;
    }
  }
}

TEST(PsdWriter, VisibleBackgroundBecomesABottomLayer) {
  Imported document;
  document.folder = temporaryFolder("background");
  document.document.width = 40;
  document.document.height = 30;
  document.document.backgroundColor = std::array<double, 4>{0.2, 0.4, 0.6, 1};
  ImportedNode node;
  node.uuid = "empty-layer";
  node.name = "Empty";
  document.document.layers.push_back(node);
  PsdExportOptions options;
  options.backgroundLayerName = "Plano de fundo";
  FunctionComposite composite = transparentComposite(40);
  const std::string output = io::joinPath(temporaryFolder("background_out"), "out.psd");
  ASSERT_TRUE(exportTo(document, output, composite, options).ok);
  const Imported again = importFile(output, "background_again");
  ASSERT_EQ(again.document.layers.size(), 2U);
  EXPECT_EQ(again.document.layers[0].name, "Plano de fundo");
  EXPECT_EQ(again.document.layers[1].name, "Empty");
  const std::vector<uint8_t> pixels =
      surfacePixels(again.folder, again.document.layers[0].uuid, false, 40, 30);
  EXPECT_EQ(pixels[0], 51);
  EXPECT_EQ(pixels[1], 102);
  EXPECT_EQ(pixels[2], 153);
  EXPECT_EQ(pixels[3], 255);
  EXPECT_EQ(pixels.size(), size_t{40} * 30 * 4);
  const std::vector<uint8_t> upper =
      surfacePixels(again.folder, again.document.layers[1].uuid, false, 40, 30);
  EXPECT_TRUE(std::all_of(upper.begin(), upper.end(), [](uint8_t value) { return value == 0; }));
}

TEST(PsdWriter, BlendModesMapBothWays) {
  for (const auto& [key, id] : psdBlendKeys()) {
    EXPECT_EQ(psdBlendKey(id), key);
  }
  EXPECT_EQ(psdBlendKey("behind"), "");

  Imported document;
  document.folder = temporaryFolder("blend");
  document.document.width = 10;
  document.document.height = 10;
  ImportedNode behind;
  behind.uuid = "behind";
  behind.name = "Behind";
  behind.blend = "behind";
  document.document.layers.push_back(behind);
  FunctionComposite composite = transparentComposite(10);
  const std::string output = io::joinPath(temporaryFolder("blend_out"), "out.psd");
  const ExportResult exported = exportTo(document, output, composite);
  ASSERT_TRUE(exported.ok);
  ASSERT_EQ(exported.warnings.size(), 1U);
  EXPECT_EQ(exported.warnings[0].code, "blendModeReplaced");
  EXPECT_EQ(exported.warnings[0].detail, "Behind");
  EXPECT_EQ(importFile(output, "blend_again").document.layers[0].blend, "normal");
}

TEST(PsdWriter, WideCanvasesArePsb) {
  Imported document;
  document.folder = temporaryFolder("psb");
  document.document.width = 30001;
  document.document.height = 3;
  DocumentImportFolder folder(document.folder, 30001, 3);
  ImportedNode node;
  node.uuid = folder.newUuid();
  node.name = "Far right";
  SurfaceTileWriter writer = folder.layerWriter(node.uuid);
  const std::vector<uint8_t> pixels(size_t{11} * 3 * 4, 200);
  ASSERT_TRUE(writer.writePixels(29990, 0, 11, 3, pixels.data(), size_t{11} * 4));
  ASSERT_TRUE(writer.finish());
  document.document.layers.push_back(node);
  FunctionComposite composite(30001, [](int32_t x, int32_t) {
    return std::array<uint8_t, 4>{static_cast<uint8_t>(x), 0, 0, 255};
  });
  const std::string output = io::joinPath(temporaryFolder("psb_out"), "out.psd");
  ASSERT_TRUE(exportTo(document, output, composite).ok);
  const std::vector<uint8_t> bytes = readAll(output);
  ASSERT_GT(bytes.size(), 6U);
  EXPECT_EQ(bytes[4], 0);
  EXPECT_EQ(bytes[5], 2);
  const Imported again = importFile(output, "psb_again");
  ASSERT_TRUE(again.result.ok);
  EXPECT_EQ(again.result.document.source, "psb");
  expectSamePixels(document, document.document.layers, again, again.document.layers);
}

TEST(PsdWriter, SixteenBitDocument) {
  const std::string folder = temporaryFolder("deep");
  const std::string uuid = "00000000-0000-4000-8000-000000000002";
  std::vector<uint8_t> halves(static_cast<size_t>(kTileSizePixels) * kTileSizePixels * 8);
  for (int32_t y = 0; y < kTileSizePixels; ++y) {
    for (int32_t x = 0; x < kTileSizePixels; ++x) {
      const float alpha = static_cast<float>(y) / 255.0F;
      const std::array<float, 4> pixel{static_cast<float>(x) / 255.0F * alpha,
                                       static_cast<float>(255 - x) / 255.0F * alpha * 0.5F,
                                       alpha * 0.25F, alpha};
      for (size_t channel = 0; channel < 4; ++channel) {
        const uint16_t half = core::floatToHalf(pixel[channel]);
        std::memcpy(&halves[((static_cast<size_t>(y) * kTileSizePixels + x) * 4 + channel) * 2],
                    &half, 2);
      }
    }
  }
  const std::string surface = io::joinPath(io::joinPath(folder, "layers"), uuid);
  ASSERT_TRUE(
      io::writeFileDurably(io::joinPath(surface, io::documentTileRelativePath({0, 0})),
                           io::encodeDocumentTile(halves.data(), io::DocumentTileFormat::Rgba16f)));
  io::SurfaceIndex index;
  index.tiles = {{0, 0}};
  ASSERT_TRUE(
      io::writeFileDurably(io::joinPath(surface, "index.json"), io::writeSurfaceIndexJson(index)));
  Imported document;
  document.folder = folder;
  document.document.width = 256;
  document.document.height = 256;
  ImportedNode node;
  node.uuid = uuid;
  node.name = "Deep";
  document.document.layers.push_back(node);

  class DeepComposite final : public CompositeSource {
   public:
    static std::array<uint16_t, 4> at(int32_t x, int32_t y) {
      return {static_cast<uint16_t>(x * 257 + 3), static_cast<uint16_t>(y * 255),
              static_cast<uint16_t>((x * y * 31) & 0xFFFF), 65535};
    }
    bool readRows(int32_t top, int32_t count, uint8_t* rgba) override {
      for (int32_t y = top; y < top + count; ++y) {
        for (int32_t x = 0; x < 256; ++x) {
          const auto pixel = at(x, y);
          for (size_t channel = 0; channel < 4; ++channel) {
            rgba[(static_cast<size_t>(y - top) * 256 + x) * 4 + channel] =
                static_cast<uint8_t>((pixel[channel] * 255U + 32767U) / 65535U);
          }
        }
      }
      return true;
    }
    [[nodiscard]] bool hasDeepRows() const override { return true; }
    bool readDeepRows(int32_t top, int32_t count, uint16_t* rgba) override {
      for (int32_t y = top; y < top + count; ++y) {
        for (int32_t x = 0; x < 256; ++x) {
          const auto pixel = at(x, y);
          std::copy(pixel.begin(), pixel.end(),
                    rgba + (static_cast<size_t>(y - top) * 256 + x) * 4);
        }
      }
      return true;
    }
  } composite;

  std::vector<uint8_t> profile = srgbIccProfile();
  profile[100] ^= 0x5A;
  PsdExportOptions options;
  options.deep = true;
  options.iccProfile = profile;
  const std::string output = io::joinPath(temporaryFolder("deep_out"), "out.psd");
  const ExportResult exported = exportTo(document, output, composite, options);
  ASSERT_TRUE(exported.ok) << exported.errorCode << " " << exported.errorDetail;
  const std::vector<uint8_t> file = readAll(output);
  ASSERT_GT(file.size(), 26U);
  EXPECT_EQ((file[22] << 8U) | file[23], 16);
  EXPECT_NE(std::search(file.begin(), file.end(), profile.begin(), profile.end()), file.end());

  const Imported again = importFile(output, "deep_again");
  ASSERT_EQ(again.document.layers.size(), 1U);
  const auto expected = surfacePixels(folder, uuid, false, 256, 256);
  const auto read = surfacePixels(again.folder, again.document.layers[0].uuid, false, 256, 256);
  ASSERT_EQ(expected.size(), read.size());
  int32_t largest = 0;
  for (size_t index = 0; index < expected.size(); ++index) {
    largest = std::max(largest, std::abs(static_cast<int32_t>(expected[index]) - read[index]));
  }
  EXPECT_LE(largest, 1);

  size_t offset = 26;
  const auto u32 = [&file](size_t at) {
    return (static_cast<uint32_t>(file.at(at)) << 24U) |
           (static_cast<uint32_t>(file.at(at + 1)) << 16U) |
           (static_cast<uint32_t>(file.at(at + 2)) << 8U) | file.at(at + 3);
  };
  for (int section = 0; section < 3; ++section) {
    offset += 4 + u32(offset);
  }
  ASSERT_EQ((file.at(offset) << 8U) | file.at(offset + 1), 3);
  std::vector<uint8_t> planes(size_t{256} * 256 * 2 * 4);
  mz_ulong size = static_cast<mz_ulong>(planes.size());
  ASSERT_EQ(mz_uncompress(planes.data(), &size, file.data() + offset + 2,
                          static_cast<mz_ulong>(file.size() - offset - 2)),
            MZ_OK);
  ASSERT_EQ(size, planes.size());
  for (int32_t plane = 0; plane < 4; ++plane) {
    for (int32_t y = 0; y < 256; y += 5) {
      uint16_t value = 0;
      for (int32_t x = 0; x < 256; ++x) {
        const size_t at = ((static_cast<size_t>(plane) * 256 + y) * 256 + x) * 2;
        value = static_cast<uint16_t>(value + ((planes[at] << 8U) | planes[at + 1]));
        ASSERT_EQ(value, DeepComposite::at(x, y)[static_cast<size_t>(plane)])
            << plane << " " << x << "," << y;
      }
    }
  }
}

TEST(PsdWriter, DocumentProfileRoundTrips) {
  const auto p3 =
      io::readFile(io::joinPath(NANQUIM_PROFILES_DIRECTORY, "DisplayP3-v4.icc"), 1 << 20);
  ASSERT_TRUE(p3.has_value());
  Imported document;
  document.folder = temporaryFolder("p3");
  document.document.width = 8;
  document.document.height = 8;
  FunctionComposite composite = transparentComposite(8);
  PsdExportOptions options;
  options.iccProfile = *p3;
  const std::string output = io::joinPath(temporaryFolder("p3_out"), "out.psd");
  ASSERT_TRUE(exportTo(document, output, composite, options).ok);
  const Imported again = importFile(output, "p3_again");
  EXPECT_EQ(again.document.iccProfile, *p3);
  EXPECT_NE(again.document.colorProfileName.find("P3"), std::string::npos);
  for (const ImportWarning& warning : again.document.warnings) {
    EXPECT_NE(warning.code, "colorProfileIgnored");
  }
}

TEST(PsdWriter, ResolutionAndColorProfile) {
  Imported document;
  document.folder = temporaryFolder("resources");
  document.document.width = 8;
  document.document.height = 8;
  document.document.dpi = 300;
  FunctionComposite composite = transparentComposite(8);
  const std::string output = io::joinPath(temporaryFolder("resources_out"), "out.psd");
  ASSERT_TRUE(exportTo(document, output, composite).ok);
  const Imported again = importFile(output, "resources_again");
  EXPECT_DOUBLE_EQ(again.document.dpi, 300);
  for (const ImportWarning& warning : again.document.warnings) {
    EXPECT_NE(warning.code, "colorProfileIgnored");
  }
}

TEST(IccProfile, SrgbProfileIsWellFormed) {
  const std::vector<uint8_t> profile = srgbIccProfile();
  ASSERT_GT(profile.size(), 128U);
  const auto u32 = [&profile](size_t offset) {
    return (static_cast<uint32_t>(profile[offset]) << 24U) |
           (static_cast<uint32_t>(profile[offset + 1]) << 16U) |
           (static_cast<uint32_t>(profile[offset + 2]) << 8U) | profile[offset + 3];
  };
  EXPECT_EQ(u32(0), profile.size());
  EXPECT_EQ(std::string(profile.begin() + 36, profile.begin() + 40), "acsp");
  EXPECT_EQ(std::string(profile.begin() + 12, profile.begin() + 16), "mntr");
  const uint32_t tagCount = u32(128);
  EXPECT_EQ(tagCount, 9U);
  for (uint32_t tag = 0; tag < tagCount; ++tag) {
    const size_t entry = 132 + static_cast<size_t>(tag) * 12;
    EXPECT_EQ(u32(entry + 4) % 4, 0U);
    EXPECT_LE(static_cast<size_t>(u32(entry + 4)) + u32(entry + 8), profile.size());
  }
  const auto colorants = srgbD50Colorants();
  const std::array<double, 3> d50{0.9642, 1.0, 0.8249};
  for (size_t row = 0; row < 3; ++row) {
    EXPECT_NEAR(colorants[row][0] + colorants[row][1] + colorants[row][2], d50[row], 1e-9);
  }
  EXPECT_NEAR(colorants[1][1], 0.7169, 5e-4);
  EXPECT_NE(std::string(profile.begin(), profile.end()).find("sRGB IEC61966-2.1"),
            std::string::npos);
}

TEST(PsdWriter, ImportJsonTreeIsValidated) {
  std::string error;
  EXPECT_FALSE(parseImportJson(R"({"version": 1, "width": 10, "height": 10,
      "layers": [{"type": "raster", "uuid": "../escape"}]})",
                               error));
  EXPECT_FALSE(parseImportJson(R"({"version": 1, "width": 10, "height": 10,
      "layers": [{"type": "raster", "uuid": "a"}, {"type": "raster", "uuid": "a"}]})",
                               error));
  EXPECT_FALSE(parseImportJson(R"({"version": "1", "width": 10, "height": 10})", error));
  const auto parsed = parseImportJson(R"({"version": 1, "width": 10, "height": 20, "dpi": 300,
      "background": {"color": [0, 0.5, 1, 1], "hidden": false},
      "layers": [{"type": "group", "uuid": "g", "name": "G", "blend": "multiply",
                  "collapsed": true, "children": [
        {"type": "raster", "uuid": "r", "name": "R", "opacity": 0.5, "locked": true,
         "mask": {"uuid": "m", "enabled": false, "inverted": true}}]}]})",
                                      error);
  ASSERT_TRUE(parsed.has_value()) << error;
  EXPECT_EQ(parsed->height, 20);
  EXPECT_DOUBLE_EQ(parsed->dpi, 300);
  ASSERT_TRUE(parsed->backgroundColor.has_value());
  EXPECT_FALSE(parsed->backgroundHidden);
  const ImportedNode& group = parsed->layers.at(0);
  EXPECT_EQ(group.blend, "multiply");
  EXPECT_TRUE(group.collapsed);
  const ImportedNode& raster = group.children.at(0);
  EXPECT_TRUE(raster.locked);
  EXPECT_DOUBLE_EQ(raster.opacity, 0.5);
  ASSERT_TRUE(raster.mask.has_value());
  EXPECT_FALSE(raster.mask->enabled);
  EXPECT_TRUE(raster.mask->inverted);
  const auto again = parseImportJson(importJsonText(*parsed), error);
  ASSERT_TRUE(again.has_value()) << error;
  expectSameTree(parsed->layers, again->layers, false);
}

TEST(PsdWriter, UnwritableOutputFails) {
  Imported document;
  document.folder = temporaryFolder("unwritable");
  document.document.width = 4;
  document.document.height = 4;
  FunctionComposite composite = transparentComposite(4);
  const ExportResult result =
      exportTo(document, io::joinPath(document.folder, "missing/folder/out.psd"), composite);
  EXPECT_FALSE(result.ok);
  EXPECT_EQ(result.errorCode, "writeFailed");
}

}  // namespace
}  // namespace nanquim::formats
