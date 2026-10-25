#include "formats/procreate_reader.h"

#include <gtest/gtest.h>
#include <lz4.h>

#include <filesystem>
#include <functional>
#include <nlohmann/json.hpp>

#include "formats/binary_plist.h"
#include "io/document_tile.h"
#include "io/file_system.h"

namespace nanquim::formats {
namespace {

using canvas::kTileBytesRgba8;
using canvas::kTileSizePixels;
using Pixel = std::array<uint8_t, 4>;

std::vector<uint8_t> seed(const std::string& relative) {
  return io::readFile(std::string(NANQUIM_FUZZ_CORPUS_DIRECTORY) + "/" + relative, 1 << 24)
      .value_or(std::vector<uint8_t>());
}

std::string temporaryFolder(const std::string& name) {
  const auto folder = std::filesystem::temp_directory_path() / ("nanquim_procreate_" + name);
  std::filesystem::remove_all(folder);
  std::filesystem::create_directories(folder);
  return folder.u8string();
}

struct Imported {
  ImportResult result;
  std::string folder;

  [[nodiscard]] Pixel at(const ImportedNode& node, int32_t x, int32_t y) const {
    std::vector<uint8_t> tile(kTileBytesRgba8);
    if (io::readDocumentTile(io::joinPath(io::joinPath(folder, "layers"), node.uuid),
                             {x / kTileSizePixels, y / kTileSizePixels},
                             io::DocumentTileFormat::Rgba8,
                             tile.data()) != io::TileReadResult::Ok) {
      return {0, 0, 0, 0};
    }
    const size_t offset =
        (static_cast<size_t>(y % kTileSizePixels) * kTileSizePixels + x % kTileSizePixels) * 4;
    return {tile[offset], tile[offset + 1], tile[offset + 2], tile[offset + 3]};
  }
};

Imported import(const std::vector<uint8_t>& bytes, const std::string& name) {
  Imported imported;
  imported.folder = temporaryFolder(name);
  MemoryInputSource source(bytes);
  imported.result = importProcreate(source, imported.folder, {});
  return imported;
}

bool hasWarning(const ImportedDocument& document, const std::string& code) {
  return std::any_of(document.warnings.begin(), document.warnings.end(),
                     [&code](const ImportWarning& warning) { return warning.code == code; });
}

TEST(ProcreateReader, TreePropertiesAndBottomUpTiles) {
  const Imported imported = import(seed("procreate/layers.procreate"), "layers");
  ASSERT_TRUE(imported.result.ok) << imported.result.errorCode << imported.result.errorDetail;
  const ImportedDocument& document = imported.result.document;
  EXPECT_EQ(document.source, "procreate");
  EXPECT_EQ(document.width, 300);
  EXPECT_EQ(document.height, 200);
  EXPECT_DOUBLE_EQ(document.dpi, 300);
  ASSERT_TRUE(document.backgroundColor.has_value());
  EXPECT_NEAR(document.backgroundColor.value()[0], 0.9, 1e-6);
  EXPECT_FALSE(document.backgroundHidden);
  EXPECT_TRUE(document.warnings.empty());

  ASSERT_EQ(document.layers.size(), 2U);
  const ImportedNode& group = document.layers[0];
  const ImportedNode& multiply = document.layers[1];
  EXPECT_EQ(group.type, ImportedNode::Type::Group);
  EXPECT_EQ(group.name, "Group");
  EXPECT_EQ(group.blend, "passThrough");
  EXPECT_TRUE(group.collapsed);
  ASSERT_EQ(group.children.size(), 2U);
  const ImportedNode& base = group.children[0];
  const ImportedNode& clipped = group.children[1];
  EXPECT_EQ(base.name, "Base");
  EXPECT_EQ(clipped.name, "Clipped");
  EXPECT_TRUE(clipped.clipping);
  EXPECT_FALSE(base.clipping);
  EXPECT_EQ(multiply.name, "Multiply");
  EXPECT_EQ(multiply.blend, "multiply");
  EXPECT_DOUBLE_EQ(multiply.opacity, 0.5);

  EXPECT_EQ(imported.at(base, 10, 10), (Pixel{200, 30, 30, 255}));
  EXPECT_EQ(imported.at(base, 299, 199), (Pixel{200, 30, 30, 255}));
  EXPECT_EQ(imported.at(clipped, 10, 10), (Pixel{0, 120, 0, 120}));
  EXPECT_EQ(imported.at(clipped, 200, 10), (Pixel{0, 0, 0, 0}));
  EXPECT_EQ(imported.at(multiply, 50, 30), (Pixel{0, 0, 128, 128}));
  EXPECT_EQ(imported.at(multiply, 50, 150), (Pixel{0, 0, 0, 0}));
}

TEST(ProcreateReader, RotatedCanvasIsTurnedClockwise) {
  const Imported imported = import(seed("procreate/rotated.procreate"), "rotated");
  ASSERT_TRUE(imported.result.ok) << imported.result.errorCode;
  const ImportedDocument& document = imported.result.document;
  EXPECT_EQ(document.width, 100);
  EXPECT_EQ(document.height, 300);
  ASSERT_EQ(document.layers.size(), 1U);
  for (const auto& [outX, outY] :
       std::vector<std::pair<int32_t, int32_t>>{{0, 0}, {10, 270}, {99, 299}, {55, 128}}) {
    const int32_t storedX = outY;
    const int32_t storedY = 99 - outX;
    EXPECT_EQ(imported.at(document.layers[0], outX, outY),
              (Pixel{static_cast<uint8_t>(storedX % 256), static_cast<uint8_t>(storedY), 50, 255}))
        << outX << "," << outY;
  }
}

TEST(ProcreateReader, UnconfirmedPropertiesAreReported) {
  const Imported imported = import(seed("procreate/unconfirmed.procreate"), "unconfirmed");
  ASSERT_TRUE(imported.result.ok);
  const ImportedDocument& document = imported.result.document;
  EXPECT_TRUE(hasWarning(document, "blendModeReplaced"));
  EXPECT_TRUE(hasWarning(document, "provisionalAlphaLock"));
  EXPECT_TRUE(hasWarning(document, "layerTypeUnknown"));
  ASSERT_EQ(document.layers.size(), 1U);
  EXPECT_EQ(document.layers[0].blend, "normal");
  EXPECT_TRUE(document.layers[0].alphaLock);
}

Pixel gradient(int32_t storedX, int32_t storedY) {
  return {static_cast<uint8_t>(storedX % 256), static_cast<uint8_t>(storedY % 256), 50, 255};
}

TEST(ProcreateReader, ProvisionalOrientationsAndFlips) {
  struct Case {
    const char* seed;
    int32_t width;
    int32_t height;
    std::function<std::pair<int32_t, int32_t>(int32_t, int32_t)> stored;
  };
  const std::vector<Case> cases{
      {"orientation2", 300, 100, [](int32_t x, int32_t y) { return std::pair{299 - x, 99 - y}; }},
      {"orientation3", 100, 300, [](int32_t x, int32_t y) { return std::pair{299 - y, x}; }},
      {"flipped", 300, 100, [](int32_t x, int32_t y) { return std::pair{299 - x, 99 - y}; }},
  };
  for (const Case& test : cases) {
    const Imported imported =
        import(seed(std::string("procreate/") + test.seed + ".procreate"), test.seed);
    ASSERT_TRUE(imported.result.ok) << test.seed;
    const ImportedDocument& document = imported.result.document;
    EXPECT_EQ(document.width, test.width) << test.seed;
    EXPECT_EQ(document.height, test.height) << test.seed;
    EXPECT_TRUE(hasWarning(document, "provisionalOrientation")) << test.seed;
    for (const auto& [x, y] : std::vector<std::pair<int32_t, int32_t>>{
             {0, 0}, {test.width - 1, test.height - 1}, {17, 61}, {test.width - 3, 5}}) {
      const auto [storedX, storedY] = test.stored(x, y);
      EXPECT_EQ(imported.at(document.layers.at(0), x, y), gradient(storedX, storedY))
          << test.seed << " " << x << "," << y;
    }
  }
}

TEST(ProcreateReader, ProvisionalLayerMask) {
  const Imported imported = import(seed("procreate/masked.procreate"), "masked");
  ASSERT_TRUE(imported.result.ok) << imported.result.errorCode;
  const ImportedDocument& document = imported.result.document;
  ASSERT_EQ(document.layers.size(), 1U);
  ASSERT_TRUE(document.layers[0].mask.has_value());
  EXPECT_TRUE(document.layers[0].mask->enabled);
  EXPECT_TRUE(hasWarning(document, "provisionalLayerMask"));
  const std::string surface =
      io::joinPath(io::joinPath(imported.folder, "masks"), document.layers[0].mask->uuid);
  std::vector<uint8_t> tile(kTileBytesRgba8);
  ASSERT_EQ(io::readDocumentTile(surface, {0, 0}, io::DocumentTileFormat::R8, tile.data()),
            io::TileReadResult::Ok);
  EXPECT_EQ(tile[(size_t{10} * kTileSizePixels + 50) * 4], 0);
  EXPECT_EQ(tile[(size_t{10} * kTileSizePixels + 150) * 4], 255);
}

TEST(ProcreateReader, CompositeOnlyImport) {
  Imported imported;
  imported.folder = temporaryFolder("composite");
  const std::vector<uint8_t> bytes = seed("procreate/layers.procreate");
  MemoryInputSource source(bytes);
  imported.result = importProcreateComposite(source, imported.folder, {});
  EXPECT_FALSE(imported.result.ok);
  EXPECT_EQ(imported.result.errorCode, "corrupt");
}

TEST(ProcreateReader, Rejections) {
  EXPECT_EQ(import(seed("zip/stored.zip"), "not_procreate").result.errorCode, "notProcreate");
  EXPECT_EQ(import({'P', 'K', 3, 4, 0, 0}, "short").result.errorCode, "notProcreate");
  ImportLimits small;
  small.maximumSide = 100;
  Imported imported;
  imported.folder = temporaryFolder("limits");
  const std::vector<uint8_t> bytes = seed("procreate/layers.procreate");
  MemoryInputSource source(bytes);
  EXPECT_EQ(importProcreate(source, imported.folder, small).errorCode, "canvasTooLarge");
}

std::vector<uint8_t> linkedTile(const std::vector<uint8_t>& data) {
  std::vector<uint8_t> out;
  LZ4_stream_t* stream = LZ4_createStream();
  std::vector<char> block(static_cast<size_t>(LZ4_compressBound(65536)));
  for (size_t start = 0; start < data.size(); start += 65536) {
    const auto size = static_cast<int>(std::min<size_t>(65536, data.size() - start));
    const int encoded =
        LZ4_compress_fast_continue(stream, reinterpret_cast<const char*>(data.data() + start),
                                   block.data(), size, static_cast<int>(block.size()), 1);
    const uint8_t header[12] = {'b',
                                'v',
                                '4',
                                '1',
                                static_cast<uint8_t>(size),
                                static_cast<uint8_t>(size >> 8),
                                static_cast<uint8_t>(size >> 16),
                                static_cast<uint8_t>(size >> 24),
                                static_cast<uint8_t>(encoded),
                                static_cast<uint8_t>(encoded >> 8),
                                static_cast<uint8_t>(encoded >> 16),
                                static_cast<uint8_t>(encoded >> 24)};
    out.insert(out.end(), header, header + 12);
    out.insert(out.end(), block.begin(), block.begin() + encoded);
  }
  LZ4_freeStream(stream);
  out.insert(out.end(), {'b', 'v', '4', '$'});
  return out;
}

TEST(ProcreateTile, LinkedBlocksDecode) {
  std::vector<uint8_t> data(kTileBytesRgba8);
  for (size_t index = 0; index < data.size(); ++index) {
    data[index] = static_cast<uint8_t>((index / 4) % 97 + (index % 4) * 50);
  }
  const std::vector<uint8_t> encoded = linkedTile(data);
  std::vector<uint8_t> decoded(data.size());
  ASSERT_TRUE(decodeProcreateTile(encoded.data(), encoded.size(), decoded.data(), decoded.size()));
  EXPECT_EQ(decoded, data);

  std::vector<uint8_t> small(data.size() - 4);
  EXPECT_FALSE(decodeProcreateTile(encoded.data(), encoded.size(), small.data(), small.size()));
  EXPECT_FALSE(
      decodeProcreateTile(encoded.data(), encoded.size() / 2, decoded.data(), decoded.size()));
  EXPECT_FALSE(
      decodeProcreateTile(encoded.data(), encoded.size() - 4, decoded.data(), decoded.size()));
  const std::vector<uint8_t> garbage{'b', 'v', '4', '1', 255, 255, 0, 0, 10, 0, 0, 0, 1, 2, 3};
  EXPECT_FALSE(decodeProcreateTile(garbage.data(), garbage.size(), decoded.data(), decoded.size()));
}

TEST(BinaryPlist, KeyedArchiveOfASeedDocument) {
  std::string error;
  const auto archive = KeyedArchive::parse(seed("plist/document.archive"), error);
  ASSERT_TRUE(archive.has_value()) << error;
  const auto root = archive->root();
  ASSERT_TRUE(root.has_value());
  EXPECT_EQ(archive->className(*root), "SilicaDocument");
  EXPECT_EQ(archive->string(*root, "size"), "{300, 200}");
  EXPECT_DOUBLE_EQ(archive->number(*root, "tileSize", 0), 256);
  EXPECT_DOUBLE_EQ(archive->number(*root, "SilicaDocumentArchiveDPIKey", 0), 300);
  EXPECT_FALSE(archive->boolean(*root, "backgroundHidden", true));
  EXPECT_EQ(archive->data(*root, "backgroundColor").size(), 16U);
  EXPECT_FALSE(archive->member(*root, "name").has_value());
  const auto layers = archive->array(*root, "layers");
  ASSERT_EQ(layers.size(), 3U);
  EXPECT_EQ(archive->className(layers[0]), "SilicaLayer");
  EXPECT_EQ(archive->string(layers[0], "name"), "Multiply");
}

TEST(BinaryPlist, KeyedArchiveAsJson) {
  std::string error;
  const auto text = keyedArchiveJson(seed("plist/document.archive"), error);
  ASSERT_TRUE(text.has_value()) << error;
  const auto json = nlohmann::json::parse(*text);
  EXPECT_EQ(json["$class"], "SilicaDocument");
  EXPECT_EQ(json["size"], "{300, 200}");
  EXPECT_EQ(json["tileSize"], 256);
  EXPECT_TRUE(json["name"].is_null());
  ASSERT_TRUE(json["layers"].is_array());
  EXPECT_EQ(json["layers"].size(), 3U);
  EXPECT_EQ(json["layers"][0]["$class"], "SilicaLayer");
  EXPECT_EQ(json["layers"][0]["name"], "Multiply");
  EXPECT_EQ(json["backgroundColor"].get<std::string>().size(), 24U);
  EXPECT_FALSE(keyedArchiveJson({1, 2, 3}, error).has_value());
}

TEST(BinaryPlist, MalformedInputsFail) {
  std::string error;
  EXPECT_FALSE(BinaryPlist::parse({}, error).has_value());
  EXPECT_FALSE(BinaryPlist::parse(std::vector<uint8_t>(64, 0), error).has_value());
  std::vector<uint8_t> bytes = seed("plist/document.archive");
  ASSERT_FALSE(bytes.empty());
  for (size_t length = 0; length < bytes.size(); length += 13) {
    EXPECT_FALSE(
        KeyedArchive::parse(std::vector<uint8_t>(
                                bytes.begin(), bytes.begin() + static_cast<std::ptrdiff_t>(length)),
                            error)
            .has_value());
  }
  const auto minimal = KeyedArchive::parse(seed("plist/minimal.bplist"), error);
  ASSERT_TRUE(minimal.has_value()) << error;
  ASSERT_TRUE(minimal->root().has_value());
  EXPECT_EQ(minimal->className(*minimal->root()), "");
}

}  // namespace
}  // namespace nanquim::formats
