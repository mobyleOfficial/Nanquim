#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "text/font_face.h"

namespace nanquim::text {

struct ShapeStyle {
  std::vector<const FontFace*> fonts;
  float sizePixels = 16;
  bool kerning = true;
  bool vertical = false;
  std::string language;
};

struct StyleSpan {
  size_t start = 0;
  size_t end = 0;
  ShapeStyle style;
};

enum class BaseDirection { Auto, LeftToRight, RightToLeft };

struct Glyph {
  uint32_t id = 0;
  uint32_t cluster = 0;
  float advanceX = 0;
  float advanceY = 0;
  float offsetX = 0;
  float offsetY = 0;
};

struct GlyphRun {
  size_t start = 0;
  size_t end = 0;
  uint8_t level = 0;
  uint32_t script = 0;
  size_t span = 0;
  bool topToBottom = false;
  const FontFace* face = nullptr;
  std::vector<Glyph> glyphs;
  float advance = 0;
};

struct ShapedParagraph {
  uint8_t baseLevel = 0;
  std::vector<uint8_t> levels;
  std::vector<GlyphRun> runs;
};

std::vector<uint8_t> resolveLevels(const std::u32string& text, BaseDirection direction,
                                   uint8_t& baseLevel);

ShapedParagraph shapeParagraph(const std::u32string& text, const std::vector<StyleSpan>& spans,
                               BaseDirection direction);
ShapedParagraph shapeRange(const std::u32string& text, const std::vector<StyleSpan>& spans,
                           const std::vector<uint8_t>& levels, uint8_t baseLevel, size_t start,
                           size_t end);

std::vector<size_t> visualRunOrder(const std::vector<uint8_t>& levels);

bool isVerticalScript(uint32_t script);

std::u32string utf32FromUtf8(const std::string& text);
std::string utf8FromUtf32(const std::u32string& text);

}  // namespace nanquim::text
