#include "stroke/brush_texture.h"

#include <gtest/gtest.h>

#include <array>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iterator>

#include "png_file.h"

namespace nanquim::stroke {
namespace {

GrayTexture checker(int32_t size) {
  std::vector<uint8_t> pixels(static_cast<size_t>(size) * static_cast<size_t>(size));
  for (int32_t y = 0; y < size; ++y) {
    for (int32_t x = 0; x < size; ++x) {
      pixels[static_cast<size_t>(y * size + x)] = ((x + y) % 2 == 0) ? 255 : 0;
    }
  }
  return *GrayTexture::create(size, size, std::move(pixels));
}

TEST(BrushTextureTest, BuildsTheWholeMipChainWithBoxAverages) {
  const GrayTexture texture = checker(8);
  ASSERT_EQ(texture.levelCount(), 4);
  EXPECT_EQ(texture.levelWidth(3), 1);
  for (const uint8_t value : texture.level(1)) {
    EXPECT_EQ(value, 128);
  }

  const auto odd = GrayTexture::create(3, 1, {0, 100, 200});
  ASSERT_TRUE(odd.has_value());
  ASSERT_EQ(odd->levelCount(), 2);
  EXPECT_EQ(odd->level(1)[0], 50);
  EXPECT_FALSE(GrayTexture::create(2, 2, {1, 2, 3}).has_value());
}

TEST(BrushTextureTest, ColorChainAveragesEachChannelAndMatchesTheAlphaChain) {
  std::vector<uint8_t> pixels;
  std::vector<uint8_t> alpha;
  for (int32_t index = 0; index < 8; ++index) {
    const std::array<uint8_t, 4> texel = index % 4 == 0   ? std::array<uint8_t, 4>{255, 0, 0, 255}
                                         : index % 4 == 1 ? std::array<uint8_t, 4>{0, 0, 0, 0}
                                         : index % 4 == 2 ? std::array<uint8_t, 4>{0, 128, 0, 128}
                                                          : std::array<uint8_t, 4>{0, 0, 255, 255};
    pixels.insert(pixels.end(), texel.begin(), texel.end());
    alpha.push_back(texel[3]);
  }
  const auto color = GrayTexture::createColor(4, 2, pixels);
  const auto gray = GrayTexture::create(4, 2, alpha);
  ASSERT_TRUE(color.has_value());
  ASSERT_EQ(color->levelCount(), gray->levelCount());
  const std::vector<uint8_t> level1{128, 0, 0, 128, 0, 64, 128, 192};
  EXPECT_EQ(color->level(1), level1);
  for (int32_t level = 0; level < color->levelCount(); ++level) {
    for (size_t texel = 0; texel < gray->level(level).size(); ++texel) {
      EXPECT_EQ(color->level(level)[texel * 4 + 3], gray->level(level)[texel]);
    }
  }
  EXPECT_DOUBLE_EQ(color->sample(0.3, 0.6, 0.4, TextureFiltering::Improved, false),
                   gray->sample(0.3, 0.6, 0.4, TextureFiltering::Improved, false));
  EXPECT_FALSE(GrayTexture::createColor(2, 2, {1, 2, 3, 4}).has_value());
}

TEST(BrushTextureTest, SamplesLikeOpenGl) {
  const auto texture = GrayTexture::create(2, 1, {0, 255});
  ASSERT_TRUE(texture.has_value());
  EXPECT_DOUBLE_EQ(texture->sample(0.25, 0.5, 0, TextureFiltering::Classic, false), 0);
  EXPECT_DOUBLE_EQ(texture->sample(0.5, 0.5, 0, TextureFiltering::Classic, false), 0.5);
  EXPECT_DOUBLE_EQ(texture->sample(0.0, 0.5, 0, TextureFiltering::Classic, false), 0);
  EXPECT_DOUBLE_EQ(texture->sample(0.0, 0.5, 0, TextureFiltering::Classic, true), 0.5);
  EXPECT_DOUBLE_EQ(texture->sample(0.49, 0.5, 0, TextureFiltering::None, false), 0);
  EXPECT_DOUBLE_EQ(texture->sample(0.51, 0.5, 0, TextureFiltering::None, false), 1);
  const double level0 = texture->sample(0.25, 0.5, 0, TextureFiltering::Improved, false);
  EXPECT_NEAR(texture->sample(0.25, 0.5, 0.5, TextureFiltering::Improved, false),
              (level0 + 128 / 255.0) / 2, 1e-12);
  EXPECT_NEAR(texture->sample(0.25, 0.5, 9, TextureFiltering::Improved, false), 128 / 255.0, 1e-12);
}

TEST(BrushTextureTest, DecodesPngAsGrayTimesAlpha) {
  const std::filesystem::path path =
      std::filesystem::temp_directory_path() / "nanquim_brush_texture_test.png";
  const reference::Rgba8Image image{
      2, 2, {255, 255, 255, 255, 255, 255, 255, 128, 255, 0, 0, 255, 0, 0, 0, 0}};
  ASSERT_TRUE(reference::writePng(path.string(), image));
  std::ifstream stream(path, std::ios::binary);
  const std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(stream)),
                                   std::istreambuf_iterator<char>());
  stream.close();
  std::filesystem::remove(path);

  std::string error;
  const auto texture = decodeGrayTexture(bytes.data(), bytes.size(), 1024, error);
  ASSERT_TRUE(texture.has_value()) << error;
  const auto& pixels = texture->level(0);
  EXPECT_EQ(pixels[0], 255);
  EXPECT_EQ(pixels[1], 128);
  EXPECT_NEAR(pixels[2], 76, 2);
  EXPECT_EQ(pixels[3], 0);

  EXPECT_FALSE(decodeGrayTexture(bytes.data(), bytes.size(), 1, error).has_value());
  EXPECT_NE(error.find("larger"), std::string::npos);

  const auto color = decodeGrayTexture(bytes.data(), bytes.size(), 1024, error, true);
  ASSERT_TRUE(color.has_value()) << error;
  ASSERT_TRUE(color->hasColor());
  const std::vector<uint8_t> expected{255, 255, 255, 255, 128, 128, 128, 128,
                                      255, 0,   0,   255, 0,   0,   0,   0};
  EXPECT_EQ(color->level(0), expected);
  EXPECT_DOUBLE_EQ(color->sample(0.25, 0.75, 0, TextureFiltering::Classic, false), 1.0);
  EXPECT_DOUBLE_EQ(color->sampleChannel(1, 0.25, 0.75, 0, TextureFiltering::Classic, false), 0.0);
  const uint8_t garbage[] = {1, 2, 3};
  EXPECT_FALSE(decodeGrayTexture(garbage, sizeof garbage, 1024, error).has_value());
}

}  // namespace
}  // namespace nanquim::stroke
