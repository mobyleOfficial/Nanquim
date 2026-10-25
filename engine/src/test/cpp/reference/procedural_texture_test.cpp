#include "procedural_texture.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cstdlib>
#include <string>
#include <utility>
#include <vector>

namespace nanquim::reference {
namespace {

constexpr int32_t kSize = 128;

ProceduralTexture make(const std::string& kind, double parameter) {
  std::string error;
  auto texture = generateTexture(kind, kSize, 7, parameter, error);
  EXPECT_TRUE(texture.has_value()) << kind << ": " << error;
  return texture.value_or(ProceduralTexture{});
}

uint8_t at(const ProceduralTexture& texture, int32_t x, int32_t y) {
  return texture
      .pixels[static_cast<size_t>(y) * static_cast<size_t>(texture.size) + static_cast<size_t>(x)];
}

double columnDifference(const ProceduralTexture& texture, int32_t a, int32_t b, bool rows) {
  double total = 0;
  for (int32_t index = 0; index < texture.size; ++index) {
    total += rows ? std::abs(at(texture, index, a) - at(texture, index, b))
                  : std::abs(at(texture, a, index) - at(texture, b, index));
  }
  return total / texture.size;
}

struct Kind {
  const char* name;
  double parameter;
};

const std::vector<Kind> kPatterns = {
    {"grid", 4},      {"stripes", 6}, {"dots", 8},      {"hexagons", 5}, {"cells", 6},
    {"polygons", 6},  {"cubes", 4},   {"triangles", 4}, {"bricks", 6},   {"wood", 5},
    {"marble", 3},    {"waves", 6},   {"rosette", 3},   {"ornament", 3}, {"metal", 300},
    {"concrete", 12}, {"fiber", 120}, {"minerals", 6}};
const std::vector<Kind> kTips = {{"spatter", 120}, {"blotch", 0.6}, {"sponge", 8},   {"star", 12},
                                 {"drip", 0.5},    {"stick", 0.4},  {"streak", 0.5}, {"foliage", 7},
                                 {"bokeh", 0.8},   {"fire", 0.6},   {"cloud", 0.6}};

TEST(ProceduralTextureTest, PatternsAreDeterministicVariedAndTileable) {
  for (const Kind& kind : kPatterns) {
    SCOPED_TRACE(kind.name);
    const auto texture = make(kind.name, kind.parameter);
    ASSERT_EQ(texture.pixels.size(), static_cast<size_t>(kSize * kSize));
    EXPECT_EQ(texture.pixels, make(kind.name, kind.parameter).pixels);
    const auto [low, high] = std::minmax_element(texture.pixels.begin(), texture.pixels.end());
    EXPECT_GT(*high - *low, 128);
    for (const bool rows : {false, true}) {
      double inside = 0;
      for (int32_t index = 0; index + 1 < kSize; ++index) {
        inside = std::max(inside, columnDifference(texture, index, index + 1, rows));
      }
      EXPECT_LE(columnDifference(texture, kSize - 1, 0, rows), inside + 1)
          << (rows ? "rows" : "columns");
    }
  }
}

TEST(ProceduralTextureTest, TipsAreCenteredWithClearCorners) {
  for (const Kind& kind : kTips) {
    SCOPED_TRACE(kind.name);
    const auto texture = make(kind.name, kind.parameter);
    EXPECT_EQ(texture.pixels, make(kind.name, kind.parameter).pixels);
    for (const auto [x, y] : {std::pair{0, 0}, std::pair{kSize - 1, 0}, std::pair{0, kSize - 1},
                              std::pair{kSize - 1, kSize - 1}}) {
      EXPECT_EQ(at(texture, x, y), 0);
    }
    int painted = 0;
    for (const uint8_t pixel : texture.pixels) {
      painted += pixel > 64 ? 1 : 0;
    }
    EXPECT_GT(painted, kSize * kSize / 100);
  }
}

TEST(ProceduralTextureTest, UnknownKindFails) {
  std::string error;
  EXPECT_FALSE(generateTexture("plaid", 64, 1, 1, error).has_value());
  EXPECT_NE(error.find("plaid"), std::string::npos);
}

}  // namespace
}  // namespace nanquim::reference
