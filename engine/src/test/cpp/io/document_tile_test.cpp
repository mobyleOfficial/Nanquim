#include "io/document_tile.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <random>

#include "canvas/color_conversion.h"
#include "core/half_float.h"
#include "io/file_system.h"

namespace nanquim::io {
namespace {

using canvas::kTileBytesRgba8;
using canvas::TileCoordinate;

std::vector<uint8_t> randomBytes(size_t count, uint32_t seed) {
  std::mt19937 random(seed);
  std::vector<uint8_t> bytes(count);
  for (uint8_t& byte : bytes) {
    byte = static_cast<uint8_t>(random() % 7 == 0 ? random() : 0);
  }
  return bytes;
}

std::string temporaryFolder(const std::string& name) {
  const auto folder = std::filesystem::temp_directory_path() / ("nanquim_" + name);
  std::filesystem::remove_all(folder);
  std::filesystem::create_directories(folder);
  return folder.u8string();
}

TEST(DocumentTile, FrameRoundTripBothFormats) {
  for (const DocumentTileFormat format : {DocumentTileFormat::Rgba8, DocumentTileFormat::R8}) {
    const std::vector<uint8_t> pixels = randomBytes(documentTileBytes(format), 7);
    const std::vector<uint8_t> encoded = encodeDocumentTile(pixels.data(), format);
    ASSERT_GE(encoded.size(), 4U);
    EXPECT_EQ(encoded[0], 0x04);
    EXPECT_EQ(encoded[1], 0x22);
    EXPECT_EQ(encoded[2], 0x4D);
    EXPECT_EQ(encoded[3], 0x18);
    std::vector<uint8_t> decoded(documentTileBytes(format));
    ASSERT_TRUE(decodeDocumentTile(encoded, format, decoded.data()));
    EXPECT_EQ(decoded, pixels);
  }
}

TEST(DocumentTile, CorruptionIsDetected) {
  const std::vector<uint8_t> pixels = randomBytes(kTileBytesRgba8, 3);
  std::vector<uint8_t> encoded = encodeDocumentTile(pixels.data(), DocumentTileFormat::Rgba8);
  std::vector<uint8_t> decoded(kTileBytesRgba8);
  std::vector<uint8_t> flipped = encoded;
  flipped[flipped.size() / 2] ^= 0x5A;
  EXPECT_FALSE(decodeDocumentTile(flipped, DocumentTileFormat::Rgba8, decoded.data()));
  std::vector<uint8_t> truncated(encoded.begin(), encoded.end() - 9);
  EXPECT_FALSE(decodeDocumentTile(truncated, DocumentTileFormat::Rgba8, decoded.data()));
  std::vector<uint8_t> trailing = encoded;
  trailing.push_back(0);
  EXPECT_FALSE(decodeDocumentTile(trailing, DocumentTileFormat::Rgba8, decoded.data()));
  const std::vector<uint8_t> mask(documentTileBytes(DocumentTileFormat::R8), 9);
  EXPECT_FALSE(decodeDocumentTile(encodeDocumentTile(mask.data(), DocumentTileFormat::R8),
                                  DocumentTileFormat::Rgba8, decoded.data()));
  EXPECT_FALSE(decodeDocumentTile({}, DocumentTileFormat::Rgba8, decoded.data()));
}

TEST(DocumentTile, MaskConversionAndOutsideCanvasPixels) {
  const TilePlacement placement{{1, 1}, 300, 260};
  EXPECT_EQ(placement.columnsInside(), 44);
  EXPECT_EQ(placement.rowsInside(), 4);
  std::vector<uint8_t> engineTile(kTileBytesRgba8);
  for (size_t pixel = 0; pixel < engineTile.size() / 4; ++pixel) {
    const auto value = static_cast<uint8_t>(pixel % 251);
    engineTile[pixel * 4] = engineTile[pixel * 4 + 1] = engineTile[pixel * 4 + 2] = value;
    engineTile[pixel * 4 + 3] = 255;
  }
  std::vector<uint8_t> fileTile(documentTileBytes(DocumentTileFormat::R8));
  engineTileToFile(engineTile.data(), DocumentTileFormat::R8, placement, fileTile.data());
  EXPECT_EQ(fileTile[0], engineTile[0]);
  EXPECT_EQ(fileTile[43], engineTile[43 * 4]);
  EXPECT_EQ(fileTile[44], 0);
  EXPECT_EQ(fileTile[4 * 256], 0);
  EXPECT_EQ(fileTile[3 * 256 + 2], engineTile[(3 * 256 + 2) * 4]);
  std::vector<uint8_t> back(kTileBytesRgba8);
  fileTileToEngine(fileTile.data(), DocumentTileFormat::R8, back.data());
  EXPECT_EQ(back[(3 * 256 + 2) * 4 + 1], engineTile[(3 * 256 + 2) * 4]);
  EXPECT_EQ(back[(3 * 256 + 2) * 4 + 3], 255);
}

TEST(DocumentTile, ImplicitTilesOnlyLookInsideTheCanvas) {
  const TilePlacement edge{{0, 0}, 10, 10};
  std::vector<uint8_t> transparent(kTileBytesRgba8, 0);
  transparent[(20 * 256 + 20) * 4 + 3] = 255;
  EXPECT_TRUE(isImplicitEngineTile(transparent.data(), DocumentTileFormat::Rgba8, edge));
  transparent[(5 * 256 + 5) * 4 + 3] = 1;
  EXPECT_FALSE(isImplicitEngineTile(transparent.data(), DocumentTileFormat::Rgba8, edge));

  std::vector<uint8_t> white(kTileBytesRgba8, 255);
  const TilePlacement inside{{0, 0}, 1000, 1000};
  EXPECT_TRUE(isImplicitEngineTile(white.data(), DocumentTileFormat::R8, inside));
  white[(100 * 256 + 7) * 4] = 254;
  EXPECT_FALSE(isImplicitEngineTile(white.data(), DocumentTileFormat::R8, inside));
}

TEST(DocumentTile, FileNames) {
  EXPECT_EQ(documentTileRelativePath({3, 12}), "tiles/3_12.lz4");
  const auto parsed = parseDocumentTileFileName("3_12.lz4");
  ASSERT_TRUE(parsed.has_value());
  EXPECT_EQ(*parsed, (TileCoordinate{3, 12}));
  for (const char* invalid : {"3_12.lz4.tmp", "3-12.lz4", "_1.lz4", "1_.lz4", "a_1.lz4", "1_2",
                              "-1_2.lz4", "1_2_3.lz4", "99999999999_1.lz4"}) {
    EXPECT_FALSE(parseDocumentTileFileName(invalid).has_value()) << invalid;
  }
}

TEST(DocumentTile, IndexRoundTripAndRebuild) {
  const std::string folder = temporaryFolder("index");
  SurfaceIndex index;
  index.tiles = {{0, 0}, {4, 2}};
  index.revision = 17;
  ASSERT_TRUE(writeFileDurably(joinPath(folder, "index.json"), writeSurfaceIndexJson(index)));
  bool rebuilt = true;
  SurfaceIndex read = readSurfaceIndex(folder, rebuilt);
  EXPECT_FALSE(rebuilt);
  EXPECT_EQ(read.revision, 17);
  ASSERT_EQ(read.tiles.size(), 2U);
  EXPECT_EQ(read.tiles[1], (TileCoordinate{4, 2}));

  const std::vector<uint8_t> tile(4, 0);
  ASSERT_TRUE(writeFileDurably(joinPath(folder, "index.json"), {'{', 'x'}));
  ASSERT_TRUE(writeFileDurably(joinPath(folder, "tiles/2_1.lz4"), tile));
  ASSERT_TRUE(writeFileDurably(joinPath(folder, "tiles/0_1.lz4"), tile));
  ASSERT_TRUE(writeFileDurably(joinPath(folder, "tiles/5_5.lz4.tmp"), tile));
  read = readSurfaceIndex(folder, rebuilt);
  EXPECT_TRUE(rebuilt);
  ASSERT_EQ(read.tiles.size(), 2U);
  EXPECT_EQ(read.tiles[0], (TileCoordinate{0, 1}));
  EXPECT_EQ(read.tiles[1], (TileCoordinate{2, 1}));
}

TEST(DocumentTile, ReadReportsMissingAndCorruptTiles) {
  const std::string folder = temporaryFolder("read");
  std::vector<uint8_t> engineTile(kTileBytesRgba8);
  EXPECT_EQ(readDocumentTile(folder, {0, 0}, DocumentTileFormat::Rgba8, engineTile.data()),
            TileReadResult::Missing);
  ASSERT_TRUE(writeFileDurably(joinPath(folder, "tiles/0_0.lz4"), {1, 2, 3}));
  EXPECT_EQ(readDocumentTile(folder, {0, 0}, DocumentTileFormat::Rgba8, engineTile.data()),
            TileReadResult::Corrupt);
  const std::vector<uint8_t> pixels = randomBytes(kTileBytesRgba8, 11);
  ASSERT_TRUE(writeFileDurably(joinPath(folder, "tiles/1_0.lz4"),
                               encodeDocumentTile(pixels.data(), DocumentTileFormat::Rgba8)));
  EXPECT_EQ(readDocumentTile(folder, {1, 0}, DocumentTileFormat::Rgba8, engineTile.data()),
            TileReadResult::Ok);
  EXPECT_EQ(engineTile, pixels);
}

TEST(DocumentTile, HalfFloatLayerReadAsSrgbBytes) {
  const std::string folder = temporaryFolder("read_half_as_bytes");
  std::vector<uint8_t> srgb = randomBytes(kTileBytesRgba8, 23);
  for (size_t pixel = 0; pixel < srgb.size() / 4; ++pixel) {
    uint8_t* value = &srgb[pixel * 4];
    value[3] = static_cast<uint8_t>(pixel % 256);
    for (size_t channel = 0; channel < 3; ++channel) {
      value[channel] = std::min(value[channel], value[3]);
    }
  }
  std::vector<uint8_t> halves(documentTileBytes(DocumentTileFormat::Rgba16f));
  canvas::premultipliedSrgb8ToLinearHalf(srgb.data(), halves.data(), srgb.size() / 4);
  ASSERT_TRUE(writeFileDurably(joinPath(folder, "tiles/0_0.lz4"),
                               encodeDocumentTile(halves.data(), DocumentTileFormat::Rgba16f)));
  std::vector<uint8_t> bytes(kTileBytesRgba8);
  ASSERT_EQ(readDocumentTile(folder, {0, 0}, DocumentTileFormat::Rgba8, bytes.data()),
            TileReadResult::Ok);
  int32_t largest = 0;
  for (size_t index = 0; index < bytes.size(); ++index) {
    largest = std::max(largest, std::abs(static_cast<int32_t>(bytes[index]) - srgb[index]));
  }
  EXPECT_LE(largest, 1);
}

TEST(DocumentTile, HalfFloatLayersAndMasks) {
  using canvas::PixelFormat;
  constexpr auto kHalf = PixelFormat::Rgba16fPremulLinear;
  const auto engineBytes = static_cast<size_t>(canvas::tileBytes(kHalf));
  std::vector<uint8_t> layer(engineBytes, 0);
  const uint16_t faint = core::floatToHalf(1.0F / 1024.0F);
  std::memcpy(layer.data() + (5 * canvas::kTileSizePixels + 7) * 8 + 6, &faint, 2);
  const TilePlacement inside{{0, 0}, 1000, 1000};
  EXPECT_FALSE(isImplicitEngineTile(layer.data(), DocumentTileFormat::Rgba16f, inside, kHalf));
  std::vector<uint8_t> file(documentTileBytes(DocumentTileFormat::Rgba16f));
  engineTileToFile(layer.data(), DocumentTileFormat::Rgba16f, inside, file.data(), kHalf);
  const auto encoded = encodeDocumentTile(file.data(), DocumentTileFormat::Rgba16f);
  std::vector<uint8_t> decoded(file.size());
  ASSERT_TRUE(decodeDocumentTile(encoded, DocumentTileFormat::Rgba16f, decoded.data()));
  std::vector<uint8_t> back(engineBytes);
  fileTileToEngine(decoded.data(), DocumentTileFormat::Rgba16f, back.data(), kHalf);
  EXPECT_EQ(back, layer);
  std::vector<uint8_t> negativeZero(engineBytes, 0);
  const uint16_t minusZero = 0x8000;
  std::memcpy(negativeZero.data() + 6, &minusZero, 2);
  EXPECT_TRUE(isImplicitEngineTile(negativeZero.data(), DocumentTileFormat::Rgba16f, inside, kHalf));
  std::vector<uint8_t> mask(documentTileBytes(DocumentTileFormat::R8), 255);
  mask[3] = 128;
  std::vector<uint8_t> maskEngine(engineBytes);
  fileTileToEngine(mask.data(), DocumentTileFormat::R8, maskEngine.data(), kHalf);
  uint16_t gray = 0;
  uint16_t alpha = 0;
  std::memcpy(&gray, maskEngine.data() + 3 * 8, 2);
  std::memcpy(&alpha, maskEngine.data() + 3 * 8 + 6, 2);
  EXPECT_NEAR(core::halfToFloat(gray), 128.0F / 255.0F, 1e-3F);
  EXPECT_EQ(alpha, 0x3C00);
  EXPECT_FALSE(isImplicitEngineTile(maskEngine.data(), DocumentTileFormat::R8, inside, kHalf));
  std::vector<uint8_t> maskBack(mask.size());
  engineTileToFile(maskEngine.data(), DocumentTileFormat::R8, inside, maskBack.data(), kHalf);
  EXPECT_EQ(maskBack, mask);
  std::vector<uint8_t> empty(engineBytes);
  fillEmptyEngineTile(empty.data(), DocumentTileFormat::R8, kHalf);
  EXPECT_TRUE(isImplicitEngineTile(empty.data(), DocumentTileFormat::R8, inside, kHalf));
  fillEmptyEngineTile(empty.data(), DocumentTileFormat::Rgba16f, kHalf);
  EXPECT_TRUE(isImplicitEngineTile(empty.data(), DocumentTileFormat::Rgba16f, inside, kHalf));
}

TEST(DocumentTile, TableConversionsMatchTheExactFunctions) {
  for (uint32_t alpha = 0; alpha < 256; ++alpha) {
    for (uint32_t value = 0; value < 256; ++value) {
      const std::array<uint8_t, 4> pixel{static_cast<uint8_t>(value), 0, 0,
                                         static_cast<uint8_t>(alpha)};
      std::array<uint16_t, 4> half{};
      canvas::premultipliedSrgb8ToLinearHalf(pixel.data(), reinterpret_cast<uint8_t*>(half.data()),
                                             1);
      ASSERT_EQ(half[0], canvas::premultipliedSrgb8ChannelToLinearHalf(pixel[0], pixel[3]));
    }
  }
  std::mt19937 random(5);
  const std::array<uint16_t, 6> alphas{0x0000, 0x8000, 0x3C00, 0x3800, 0x1400, 0x3C01};
  for (int32_t sample = 0; sample < 200000; ++sample) {
    std::array<uint16_t, 4> half{static_cast<uint16_t>(random()), static_cast<uint16_t>(random()),
                                 static_cast<uint16_t>(random()), alphas[sample % alphas.size()]};
    if (sample % 7 == 0) {
      half[3] = static_cast<uint16_t>(random());
    }
    std::array<uint8_t, 4> fast{};
    canvas::premultipliedLinearHalfToSrgb8(reinterpret_cast<const uint8_t*>(half.data()),
                                           fast.data(), 1);
    const std::array<float, 4> linear{core::halfToFloat(half[0]), core::halfToFloat(half[1]),
                                      core::halfToFloat(half[2]), core::halfToFloat(half[3])};
    std::array<uint8_t, 4> exact{};
    canvas::premultipliedLinearToSrgb8(linear.data(), exact.data());
    ASSERT_EQ(fast, exact) << std::hex << half[0] << " " << half[1] << " " << half[2] << " "
                           << half[3];
  }
}

TEST(DocumentTile, ConvertLayerFolderBothWays) {
  const std::string root = temporaryFolder("convert_layer");
  const std::string eight = joinPath(root, "eight");
  std::vector<uint8_t> srgb = randomBytes(kTileBytesRgba8, 31);
  for (size_t pixel = 0; pixel < srgb.size() / 4; ++pixel) {
    uint8_t* value = &srgb[pixel * 4];
    value[3] = pixel % 3 == 0 ? 255 : static_cast<uint8_t>(pixel % 256);
    for (size_t channel = 0; channel < 3; ++channel) {
      value[channel] = std::min(value[channel], value[3]);
    }
  }
  const std::vector<uint8_t> transparent(kTileBytesRgba8, 0);
  ASSERT_TRUE(writeFileDurably(joinPath(eight, "tiles/0_0.lz4"),
                               encodeDocumentTile(srgb.data(), DocumentTileFormat::Rgba8)));
  ASSERT_TRUE(writeFileDurably(joinPath(eight, "tiles/1_0.lz4"),
                               encodeDocumentTile(transparent.data(), DocumentTileFormat::Rgba8)));
  ASSERT_TRUE(writeFileDurably(joinPath(eight, "tiles/2_0.lz4"), {1, 2, 3}));
  SurfaceIndex index;
  index.tiles = {{0, 0}, {1, 0}, {2, 0}, {3, 0}};
  ASSERT_TRUE(writeFileDurably(joinPath(eight, "index.json"), writeSurfaceIndexJson(index)));

  using canvas::PixelFormat;
  const std::string sixteen = joinPath(root, "sixteen");
  ASSERT_TRUE(writeFileDurably(joinPath(sixteen, "tiles/9_9.lz4"), {0}));
  std::string error;
  ASSERT_TRUE(convertLayerFolder(eight, sixteen, PixelFormat::Rgba8PremulSrgb,
                                 PixelFormat::Rgba16fPremulLinear, error))
      << error;
  bool rebuilt = true;
  EXPECT_EQ(readSurfaceIndex(sixteen, rebuilt).tiles, (std::vector<TileCoordinate>{{0, 0}}));
  EXPECT_FALSE(rebuilt);
  EXPECT_FALSE(pathExists(joinPath(sixteen, "tiles/9_9.lz4")));
  std::vector<uint8_t> halves(documentTileBytes(DocumentTileFormat::Rgba16f));
  ASSERT_EQ(readDocumentTile(sixteen, {0, 0}, DocumentTileFormat::Rgba16f, halves.data(),
                             PixelFormat::Rgba16fPremulLinear),
            TileReadResult::Ok);
  std::vector<uint8_t> expected(halves.size());
  canvas::premultipliedSrgb8ToLinearHalf(srgb.data(), expected.data(), srgb.size() / 4);
  EXPECT_EQ(halves, expected);

  const std::string back = joinPath(root, "back");
  ASSERT_TRUE(convertLayerFolder(sixteen, back, PixelFormat::Rgba16fPremulLinear,
                                 PixelFormat::Rgba8PremulSrgb, error))
      << error;
  std::vector<uint8_t> bytes(kTileBytesRgba8);
  ASSERT_EQ(readDocumentTile(back, {0, 0}, DocumentTileFormat::Rgba8, bytes.data()),
            TileReadResult::Ok);
  int32_t largest = 0;
  for (size_t byte = 0; byte < bytes.size(); ++byte) {
    largest = std::max(largest, std::abs(static_cast<int32_t>(bytes[byte]) - srgb[byte]));
    if (byte % 4 == 3) {
      ASSERT_EQ(bytes[byte], srgb[byte]);
    }
  }
  EXPECT_LE(largest, 1);
}

}  // namespace
}  // namespace nanquim::io
