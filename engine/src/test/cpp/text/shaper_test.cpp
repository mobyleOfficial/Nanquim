#include "text/shaper.h"

#include <gtest/gtest.h>
#include <hb.h>

#include <fstream>
#include <iterator>
#include <memory>
#include <string>
#include <vector>

#include "text/font_face.h"

namespace nanquim::text {
namespace {

std::vector<uint8_t> readFontFile(const std::string& name) {
  std::ifstream file(std::string(NANQUIM_GOLDENS_DIRECTORY) + "/fonts/" + name, std::ios::binary);
  return {std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
}

class ShaperTest : public ::testing::Test {
 protected:
  std::shared_ptr<FontFace> load(const std::string& name) {
    std::string error;
    auto face = library_.load(readFontFile(name), 0, error);
    EXPECT_NE(face, nullptr) << name << ": " << error;
    return face;
  }

  StyleSpan span(size_t length, std::vector<const FontFace*> fonts, bool kerning = true,
                 bool vertical = false) {
    StyleSpan result;
    result.end = length;
    result.style.fonts = std::move(fonts);
    result.style.sizePixels = 100;
    result.style.kerning = kerning;
    result.style.vertical = vertical;
    return result;
  }

  FontLibrary library_;
};

TEST_F(ShaperTest, ReadsTheFaceNamesAndMetrics) {
  auto sans = load("NotoSans-Regular-subset.ttf");
  ASSERT_NE(sans, nullptr);
  EXPECT_EQ(sans->postScriptName(), "NotoSans-Regular");
  EXPECT_EQ(sans->familyName(), "Noto Sans");
  EXPECT_EQ(sans->unitsPerEm(), 1000);
  EXPECT_GT(sans->ascender(), 0);
  EXPECT_LT(sans->descender(), 0);
  EXPECT_TRUE(sans->embeddable());
  EXPECT_TRUE(sans->hasGlyph(U'A'));
  EXPECT_FALSE(sans->hasGlyph(U'ا'));
}

TEST_F(ShaperTest, RefusesWhatIsNotAFont) {
  std::string error;
  EXPECT_EQ(library_.load({1, 2, 3, 4}, 0, error), nullptr);
  EXPECT_FALSE(error.empty());
  EXPECT_EQ(library_.faceCount({1, 2, 3, 4}), 0);
  EXPECT_EQ(library_.faceCount(readFontFile("NotoSans-Regular-subset.ttf")), 1);
}

TEST_F(ShaperTest, KerningCanBeTurnedOff) {
  auto sans = load("NotoSans-Regular-subset.ttf");
  const std::u32string text = U"AVAV";
  const ShapedParagraph kerned =
      shapeParagraph(text, {span(text.size(), {sans.get()}, true)}, BaseDirection::Auto);
  const ShapedParagraph plain =
      shapeParagraph(text, {span(text.size(), {sans.get()}, false)}, BaseDirection::Auto);
  ASSERT_EQ(kerned.runs.size(), 1U);
  ASSERT_EQ(plain.runs.size(), 1U);
  EXPECT_LT(kerned.runs[0].advance, plain.runs[0].advance - 1.0F);
}

TEST_F(ShaperTest, JoinsArabicLettersRightToLeft) {
  auto arabic = load("NotoSansArabic-Regular-subset.ttf");
  const std::u32string word = U"سلام";
  const ShapedParagraph shaped =
      shapeParagraph(word, {span(word.size(), {arabic.get()})}, BaseDirection::Auto);
  EXPECT_EQ(shaped.baseLevel, 1);
  ASSERT_EQ(shaped.runs.size(), 1U);
  const GlyphRun& run = shaped.runs[0];
  EXPECT_EQ(run.level, 1);
  EXPECT_EQ(run.script, static_cast<uint32_t>(HB_TAG('A', 'r', 'a', 'b')));
  ASSERT_FALSE(run.glyphs.empty());
  EXPECT_EQ(run.glyphs.front().cluster, 3U);
  EXPECT_EQ(run.glyphs.back().cluster, 0U);
  const std::u32string alone = U"س";
  const ShapedParagraph isolated =
      shapeParagraph(alone, {span(alone.size(), {arabic.get()})}, BaseDirection::Auto);
  EXPECT_NE(run.glyphs.back().id, isolated.runs[0].glyphs[0].id);
}

TEST_F(ShaperTest, OrdersHebrewWithNumbersVisually) {
  auto hebrew = load("NotoSansHebrew-Regular-test.ttf");
  auto sans = load("NotoSans-Regular-subset.ttf");
  const std::u32string text = U"שלום 123";
  const ShapedParagraph shaped =
      shapeParagraph(text, {span(text.size(), {hebrew.get(), sans.get()})}, BaseDirection::Auto);
  EXPECT_EQ(shaped.baseLevel, 1);
  EXPECT_EQ(shaped.levels[0], 1);
  EXPECT_EQ(shaped.levels[5], 2);
  std::vector<uint8_t> runLevels;
  for (const GlyphRun& run : shaped.runs) {
    runLevels.push_back(run.level);
  }
  const std::vector<size_t> order = visualRunOrder(runLevels);
  ASSERT_GE(order.size(), 2U);
  EXPECT_EQ(shaped.runs[order.front()].start, 5U);
  EXPECT_EQ(shaped.runs[order.back()].start, 0U);
}

TEST_F(ShaperTest, FallsBackToAFontThatHasTheLetters) {
  auto sans = load("NotoSans-Regular-subset.ttf");
  auto arabic = load("NotoSansArabic-Regular-subset.ttf");
  const std::u32string text = U"abc سلام";
  const ShapedParagraph shaped = shapeParagraph(
      text, {span(text.size(), {sans.get(), arabic.get()})}, BaseDirection::LeftToRight);
  ASSERT_EQ(shaped.runs.size(), 2U);
  EXPECT_EQ(shaped.runs[0].face, sans.get());
  EXPECT_EQ(shaped.runs[0].end, 4U);
  EXPECT_EQ(shaped.runs[1].face, arabic.get());
  EXPECT_EQ(shaped.runs[1].level, 1);
}

TEST_F(ShaperTest, KeepsCombiningMarksWithTheirBase) {
  auto sans = load("NotoSans-Regular-subset.ttf");
  auto arabic = load("NotoSansArabic-Regular-subset.ttf");
  const std::u32string text = U"éx";
  const ShapedParagraph shaped =
      shapeParagraph(text, {span(text.size(), {sans.get(), arabic.get()})}, BaseDirection::Auto);
  ASSERT_EQ(shaped.runs.size(), 1U);
  EXPECT_EQ(shaped.runs[0].face, sans.get());
  for (const Glyph& glyph : shaped.runs[0].glyphs) {
    EXPECT_TRUE(glyph.cluster == 0 || glyph.cluster == 2) << glyph.cluster;
  }
}

TEST_F(ShaperTest, ShapesCjkTopToBottomInVerticalText) {
  auto cjk = load("NotoSansCJKjp-Regular-subset.otf");
  auto sans = load("NotoSans-Regular-subset.ttf");
  const std::u32string text = U"名前。ABC";
  const ShapedParagraph vertical = shapeParagraph(
      text, {span(text.size(), {cjk.get(), sans.get()}, true, true)}, BaseDirection::Auto);
  ASSERT_GE(vertical.runs.size(), 2U);
  const GlyphRun& cjkRun = vertical.runs[0];
  EXPECT_TRUE(cjkRun.topToBottom);
  EXPECT_NEAR(cjkRun.advance, 300.0F, 1.0F);
  EXPECT_FALSE(vertical.runs.back().topToBottom);
  const ShapedParagraph horizontal =
      shapeParagraph(text, {span(text.size(), {cjk.get(), sans.get()})}, BaseDirection::Auto);
  EXPECT_NE(cjkRun.glyphs[2].id, horizontal.runs[0].glyphs[2].id);
}

TEST(TextEncodingTest, ConvertsBetweenUtf8AndUtf32) {
  const std::string text = "a\xC3\xA9\xE5\x90\x8D\xF0\x9F\x98\x80";
  const std::u32string decoded = utf32FromUtf8(text);
  EXPECT_EQ(decoded, U"aé名\U0001F600");
  EXPECT_EQ(utf8FromUtf32(decoded), text);
  EXPECT_EQ(utf32FromUtf8("\xC0\xAFx\xED\xA0\x80"), U"��x���");
}

}  // namespace
}  // namespace nanquim::text
