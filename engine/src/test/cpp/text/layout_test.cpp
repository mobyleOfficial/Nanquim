#include "text/layout.h"

#include <gtest/gtest.h>

#include <fstream>
#include <iterator>
#include <memory>
#include <string>
#include <vector>

namespace nanquim::text {
namespace {

std::vector<uint8_t> readFontFile(const std::string& name) {
  std::ifstream file(std::string(NANQUIM_GOLDENS_DIRECTORY) + "/fonts/" + name, std::ios::binary);
  return {std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
}

class LayoutTest : public ::testing::Test {
 protected:
  void SetUp() override {
    std::string error;
    sans_ = library_.load(readFontFile("NotoSans-Regular-subset.ttf"), 0, error);
    arabic_ = library_.load(readFontFile("NotoSansArabic-Regular-subset.ttf"), 0, error);
    cjk_ = library_.load(readFontFile("NotoSansCJKjp-Regular-subset.otf"), 0, error);
    ASSERT_NE(sans_, nullptr);
    ASSERT_NE(arabic_, nullptr);
    ASSERT_NE(cjk_, nullptr);
  }

  LayoutStyle style(float size = 100) const {
    LayoutStyle result;
    result.fonts = {sans_.get(), arabic_.get(), cjk_.get()};
    result.sizePixels = size;
    return result;
  }

  LayoutInput input(const std::string& text) const {
    LayoutInput result;
    result.content = {{text, 0}};
    result.styles = {style()};
    return result;
  }

  float width(const std::string& text) const { return TextLayout::build(input(text)).width(); }

  FontLibrary library_;
  std::shared_ptr<FontFace> sans_;
  std::shared_ptr<FontFace> arabic_;
  std::shared_ptr<FontFace> cjk_;
};

TEST_F(LayoutTest, AutoBoxFitsOneLine) {
  const TextLayout layout = TextLayout::build(input("Hello"));
  ASSERT_EQ(layout.lines().size(), 1U);
  EXPECT_GT(layout.width(), 200.0F);
  EXPECT_NEAR(layout.height(), 120.0F, 0.01F);
  EXPECT_EQ(layout.glyphs().size(), 5U);
  const float baseline = layout.lines()[0].baseline;
  EXPECT_GT(baseline, 60.0F);
  EXPECT_LT(baseline, 120.0F);
  EXPECT_FLOAT_EQ(layout.glyphs()[0].x, 0.0F);
  EXPECT_FALSE(layout.overflows());
}

TEST_F(LayoutTest, FixedBoxBreaksAtSpaces) {
  LayoutInput text = input("aaa bbb ccc");
  text.fixedBox = true;
  text.boxWidth = width("aaa bbb") + 1;
  text.boxHeight = 1000;
  const TextLayout layout = TextLayout::build(text);
  ASSERT_EQ(layout.lines().size(), 2U);
  EXPECT_EQ(layout.lines()[0].start, 0U);
  EXPECT_EQ(layout.lines()[0].end, 8U);
  EXPECT_EQ(layout.lines()[1].start, 8U);
  EXPECT_EQ(layout.lines()[1].end, 11U);
  EXPECT_FLOAT_EQ(layout.width(), text.boxWidth);
}

TEST_F(LayoutTest, AWordWiderThanTheBoxBreaksInside) {
  LayoutInput text = input("abcdefgh");
  text.fixedBox = true;
  text.boxWidth = width("abc") + 1;
  text.boxHeight = 1000;
  const TextLayout layout = TextLayout::build(text);
  ASSERT_GE(layout.lines().size(), 3U);
  EXPECT_EQ(layout.lines()[0].end, 3U);
}

TEST_F(LayoutTest, LineFeedsMakeParagraphsAndEmptyLines) {
  const TextLayout layout = TextLayout::build(input("ab\n\ncd"));
  ASSERT_EQ(layout.lines().size(), 3U);
  EXPECT_EQ(layout.lines()[1].start, 3U);
  EXPECT_NEAR(layout.height(), 360.0F, 0.01F);
  const auto caret = layout.caret(3);
  EXPECT_NEAR(caret[1], 120.0F, 0.01F);
  EXPECT_NEAR(caret[3], 240.0F, 0.01F);
  EXPECT_NEAR(layout.caret(6)[0], width("cd"), 0.5F);
}

TEST_F(LayoutTest, AlignsLinesInTheBox) {
  LayoutInput text = input("ab");
  text.fixedBox = true;
  text.boxWidth = 1000;
  text.boxHeight = 200;
  const float lineWidth = width("ab");
  text.alignment = Alignment::Center;
  EXPECT_NEAR(TextLayout::build(text).glyphs()[0].x, (1000 - lineWidth) / 2, 0.5F);
  text.alignment = Alignment::Right;
  EXPECT_NEAR(TextLayout::build(text).glyphs()[0].x, 1000 - lineWidth, 0.5F);
}

TEST_F(LayoutTest, TrackingAndBaselineShiftMoveGlyphs) {
  LayoutInput text = input("abc");
  const float plain = TextLayout::build(text).width();
  text.styles[0].tracking = 100;
  EXPECT_NEAR(TextLayout::build(text).width(), plain + 30.0F, 0.5F);
  LayoutInput shifted = input("abc");
  const float baseline = TextLayout::build(shifted).glyphs()[0].y;
  shifted.styles[0].baselineShiftPixels = 15;
  EXPECT_NEAR(TextLayout::build(shifted).glyphs()[0].y, baseline - 15, 0.01F);
}

TEST_F(LayoutTest, AllCapsShapesUppercase) {
  LayoutInput lower = input("caps \xC3\xA9");
  lower.styles[0].allCaps = true;
  const TextLayout capitals = TextLayout::build(lower);
  const TextLayout upper = TextLayout::build(input("CAPS \xC3\x89"));
  ASSERT_EQ(capitals.glyphs().size(), upper.glyphs().size());
  for (size_t index = 0; index < upper.glyphs().size(); ++index) {
    EXPECT_EQ(capitals.glyphs()[index].id, upper.glyphs()[index].id);
  }
  EXPECT_EQ(capitals.text(), U"caps é");
  EXPECT_EQ(toUpperSimple(U'ς'), U'Σ');
  EXPECT_EQ(toUpperSimple(U'ж'), U'Ж');
}

TEST_F(LayoutTest, CjkBreaksBetweenCharactersButNotBeforeClosingPunctuation) {
  LayoutInput text = input(
      "\xE5\x90\x8D\xE5\x89\x8D\xE3\x81\xAF\xE3\x81\xBE\xE3\x81\xA0\xE7\x84\xA1\xE3\x81\x84\xE3\x80"
      "\x82");
  text.fixedBox = true;
  text.boxWidth = 7 * 100 + 1;
  text.boxHeight = 1000;
  const TextLayout layout = TextLayout::build(text);
  ASSERT_EQ(layout.lines().size(), 2U);
  EXPECT_EQ(layout.lines()[0].end, 6U);
}

TEST_F(LayoutTest, VerticalTextStacksColumnsRightToLeft) {
  LayoutInput text = input(
      "\xE5\x90\x8D\xE5\x89\x8D"
      "AB\n\xE7\x84\xA1");
  text.vertical = true;
  const TextLayout layout = TextLayout::build(text);
  ASSERT_EQ(layout.lines().size(), 2U);
  EXPECT_GT(layout.lines()[0].baseline, layout.lines()[1].baseline);
  const auto& glyphs = layout.glyphs();
  ASSERT_GE(glyphs.size(), 5U);
  EXPECT_LT(glyphs[0].y, glyphs[1].y);
  EXPECT_FALSE(glyphs[0].rotated);
  EXPECT_TRUE(glyphs[2].rotated);
  EXPECT_GT(glyphs[3].y, glyphs[2].y);
  EXPECT_NEAR(layout.width(), 240.0F, 0.01F);
  const auto caret = layout.caret(1);
  EXPECT_FLOAT_EQ(caret[1], caret[3]);
  EXPECT_NEAR(caret[2] - caret[0], 120.0F, 0.01F);
}

TEST_F(LayoutTest, CaretsAndHitTestingFollowTheDirection) {
  const TextLayout latin = TextLayout::build(input("abc"));
  for (size_t index = 0; index <= 3; ++index) {
    const auto caret = latin.caret(index);
    EXPECT_EQ(latin.hitTest(caret[0] + 0.1F, 50), index);
  }
  EXPECT_LT(latin.caret(1)[0], latin.caret(2)[0]);
  const TextLayout arabic = TextLayout::build(input("\xD8\xB3\xD9\x84\xD8\xA7\xD9\x85"));
  EXPECT_GT(arabic.caret(0)[0], arabic.caret(2)[0]);
  EXPECT_NEAR(arabic.caret(0)[0], arabic.width(), 0.5F);
}

TEST_F(LayoutTest, SelectionCoversEachLine) {
  LayoutInput text = input("aaa bbb ccc");
  text.fixedBox = true;
  text.boxWidth = width("aaa bbb") + 1;
  text.boxHeight = 1000;
  const TextLayout layout = TextLayout::build(text);
  const std::vector<Rect> rects = layout.selection(5, 10);
  ASSERT_EQ(rects.size(), 2U);
  EXPECT_NEAR(rects[0].y, 0.0F, 0.01F);
  EXPECT_NEAR(rects[1].y, 120.0F, 0.01F);
  EXPECT_TRUE(layout.selection(4, 4).empty());
}

}  // namespace
}  // namespace nanquim::text
