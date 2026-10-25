#include "text/text_raster.h"

#include <gtest/gtest.h>

#include <fstream>
#include <iterator>
#include <memory>
#include <numeric>
#include <string>
#include <vector>

#include "text/text_model.h"

namespace nanquim::text {
namespace {

std::vector<uint8_t> readFontFile(const std::string& name) {
  std::ifstream file(std::string(NANQUIM_GOLDENS_DIRECTORY) + "/fonts/" + name, std::ios::binary);
  return {std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
}

class TextRasterTest : public ::testing::Test {
 protected:
  void SetUp() override {
    std::string error;
    sans_ = library_.load(readFontFile("NotoSans-Regular-subset.ttf"), 0, error);
    ASSERT_NE(sans_, nullptr) << error;
  }

  std::optional<TextModel> parse(const std::string& json, bool linearLight = false) {
    std::string error;
    auto model = parseTextModel(
        json, 72,
        [this](const std::string& id) { return id == "NotoSans-Regular" ? sans_.get() : nullptr; },
        {sans_.get()}, linearLight, error);
    EXPECT_TRUE(model.has_value()) << error;
    return model;
  }

  static float alphaSum(const std::vector<float>& pixels) {
    float sum = 0;
    for (size_t index = 3; index < pixels.size(); index += 4) {
      sum += pixels[index];
    }
    return sum;
  }

  FontLibrary library_;
  std::shared_ptr<FontFace> sans_;
};

constexpr const char* kModel = R"({"format": 1,
  "content": [{"text": "Ink", "style": "s0"}],
  "styles": {"s0": {"fontId": "NotoSans-Regular", "sizePt": 48, "color": [0.5, 0, 0, 1]}},
  "transform": [1, 0, 0, 1, 10, 20]})";

TEST_F(TextRasterTest, ReadsTheModel) {
  const auto model = parse(kModel);
  ASSERT_TRUE(model);
  ASSERT_EQ(model->layout.styles.size(), 1U);
  EXPECT_FLOAT_EQ(model->layout.styles[0].sizePixels, 48.0F);
  EXPECT_FLOAT_EQ(model->paints[0].color[0], 0.5F);
  EXPECT_DOUBLE_EQ(model->transform[4], 10.0);
  EXPECT_TRUE(model->missingFonts.empty());
  EXPECT_NEAR(parse(kModel, true)->paints[0].color[0], 0.214F, 0.001F);
}

TEST_F(TextRasterTest, ReportsMissingFontsAndSubstitutes) {
  const auto model = parse(R"({"content": [{"text": "a", "style": "s"}],
      "styles": {"s": {"fontId": "Missing-Font"}}})");
  ASSERT_TRUE(model);
  EXPECT_EQ(model->missingFonts, std::vector<std::string>{"Missing-Font"});
  EXPECT_EQ(model->layout.styles[0].fonts.front(), sans_.get());
  std::string error;
  EXPECT_FALSE(parseTextModel("[1, 2]", 72, nullptr, {}, false, error));
}

TEST_F(TextRasterTest, DrawsInsideItsBounds) {
  const auto model = parse(kModel);
  const TextLayout layout = TextLayout::build(model->layout);
  const auto bounds = textBounds(layout, model->paints, model->transform);
  ASSERT_TRUE(bounds);
  EXPECT_GE(bounds->x, 9);
  EXPECT_GT(bounds->width, 40);
  const std::vector<float> inside = rasterizeText(layout, model->paints, model->transform, *bounds);
  EXPECT_GT(alphaSum(inside), 100.0F);
  for (size_t index = 0; index < inside.size(); index += 4) {
    EXPECT_LE(inside[index], inside[index + 3] * 0.5F + 1e-5F);
  }
  const std::vector<float> beside = rasterizeText(layout, model->paints, model->transform,
                                                  {bounds->x + bounds->width, bounds->y, 64, 64});
  EXPECT_FLOAT_EQ(alphaSum(beside), 0.0F);
}

TEST_F(TextRasterTest, OutlinesAndScaleCoverMore) {
  auto model = parse(kModel);
  const TextLayout layout = TextLayout::build(model->layout);
  const PixelRect area{0, 0, 200, 120};
  const float plain = alphaSum(rasterizeText(layout, model->paints, model->transform, area));
  model->paints[0].outlineWidth = 3;
  const float outlined = alphaSum(rasterizeText(layout, model->paints, model->transform, area));
  EXPECT_GT(outlined, plain * 1.3F);
  model->paints[0].outlineWidth = 0;
  const Affine doubled{2, 0, 0, 2, 0, 0};
  const float scaled = alphaSum(rasterizeText(layout, model->paints, doubled, {0, 0, 300, 200}));
  EXPECT_NEAR(scaled / plain, 4.0F, 0.4F);
}

}  // namespace
}  // namespace nanquim::text
