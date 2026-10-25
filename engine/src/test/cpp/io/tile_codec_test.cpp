#include "io/tile_codec.h"

#include <gtest/gtest.h>

#include <random>
#include <vector>

#include "canvas/tile_coordinate.h"

namespace nanquim::io {
namespace {

std::vector<uint8_t> makeTile(uint32_t seed) {
  constexpr int kBlockSize = 8;
  std::vector<uint8_t> pixels(canvas::kTileBytesRgba8);
  std::mt19937 generator(seed);
  constexpr size_t kBlocksPerRow = canvas::kTileSizePixels / kBlockSize;
  std::vector<uint32_t> blockColors(kBlocksPerRow * kBlocksPerRow);
  for (auto& color : blockColors) {
    color = generator() | 0xFF000000U;
  }
  for (int y = 0; y < canvas::kTileSizePixels; ++y) {
    for (int x = 0; x < canvas::kTileSizePixels; ++x) {
      const uint32_t color =
          blockColors[(y / kBlockSize) * (canvas::kTileSizePixels / kBlockSize) + x / kBlockSize];
      const size_t offset = (static_cast<size_t>(y) * canvas::kTileSizePixels + x) * 4;
      for (int channel = 0; channel < 4; ++channel) {
        pixels[offset + channel] = static_cast<uint8_t>(color >> (8 * channel));
      }
    }
  }
  return pixels;
}

TEST(TileCodecTest, RoundTripsExactly) {
  const std::vector<uint8_t> original = makeTile(7);
  const std::vector<uint8_t> compressed = compressTile(original.data(), original.size());
  std::vector<uint8_t> decoded(original.size());

  ASSERT_FALSE(compressed.empty());
  EXPECT_LT(compressed.size(), original.size());
  ASSERT_TRUE(decompressTile(compressed, decoded.data(), decoded.size()));
  EXPECT_EQ(decoded, original);
}

TEST(TileCodecTest, TransparentTileCompressesToAlmostNothing) {
  const std::vector<uint8_t> transparent(canvas::kTileBytesRgba8, 0);

  EXPECT_LT(compressTile(transparent.data(), transparent.size()).size(), 1100U);
}

TEST(TileCodecTest, RejectsCorruptOrMismatchedPayloads) {
  const std::vector<uint8_t> original = makeTile(3);
  std::vector<uint8_t> compressed = compressTile(original.data(), original.size());
  std::vector<uint8_t> decoded(original.size());

  EXPECT_FALSE(decompressTile({}, decoded.data(), decoded.size()));
  EXPECT_FALSE(decompressTile(compressed, decoded.data(), decoded.size() - 4));
  compressed.resize(compressed.size() / 2);
  EXPECT_FALSE(decompressTile(compressed, decoded.data(), decoded.size()));
}

}  // namespace
}  // namespace nanquim::io
