#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "text/font_face.h"
#include "text/shaper.h"

namespace nanquim::text {

struct LayoutStyle {
  std::vector<const FontFace*> fonts;
  float sizePixels = 16;
  float tracking = 0;
  bool kerning = true;
  float baselineShiftPixels = 0;
  bool allCaps = false;
  bool underline = false;
  bool strikethrough = false;
  std::string language;
};

struct LayoutRun {
  std::string text;
  size_t style = 0;
};

enum class Alignment { Left, Center, Right };

struct LayoutInput {
  std::vector<LayoutRun> content;
  std::vector<LayoutStyle> styles;
  Alignment alignment = Alignment::Left;
  float lineHeight = 1.2F;
  bool vertical = false;
  BaseDirection direction = BaseDirection::Auto;
  bool fixedBox = false;
  float boxWidth = 0;
  float boxHeight = 0;
};

struct PlacedGlyph {
  const FontFace* face = nullptr;
  uint32_t id = 0;
  float x = 0;
  float y = 0;
  float sizePixels = 0;
  bool rotated = false;
  size_t style = 0;
  uint32_t cluster = 0;
};

struct Decoration {
  float x0 = 0;
  float y0 = 0;
  float x1 = 0;
  float y1 = 0;
  size_t style = 0;
};

struct Rect {
  float x = 0;
  float y = 0;
  float width = 0;
  float height = 0;
};

struct LayoutLine {
  size_t start = 0;
  size_t end = 0;
  Rect box;
  float baseline = 0;
};

struct ClusterBox {
  size_t start = 0;
  size_t end = 0;
  size_t line = 0;
  float from = 0;
  float to = 0;
  bool rightToLeft = false;
};

class TextLayout {
 public:
  static TextLayout build(const LayoutInput& input);

  [[nodiscard]] const std::u32string& text() const { return text_; }
  [[nodiscard]] const std::vector<PlacedGlyph>& glyphs() const { return glyphs_; }
  [[nodiscard]] const std::vector<Decoration>& decorations() const { return decorations_; }
  [[nodiscard]] const std::vector<LayoutLine>& lines() const { return lines_; }
  [[nodiscard]] float width() const { return width_; }
  [[nodiscard]] float height() const { return height_; }
  [[nodiscard]] bool vertical() const { return vertical_; }
  [[nodiscard]] bool overflows() const { return overflows_; }
  [[nodiscard]] const std::vector<ClusterBox>& clusters() const { return clusters_; }

  [[nodiscard]] std::array<float, 4> caret(size_t index) const;
  [[nodiscard]] std::vector<Rect> selection(size_t start, size_t end) const;
  [[nodiscard]] size_t hitTest(float x, float y) const;

 private:
  [[nodiscard]] float inlinePosition(size_t index, size_t& line) const;
  [[nodiscard]] std::array<float, 4> segmentAcross(size_t line, float position) const;

  std::u32string text_;
  std::vector<PlacedGlyph> glyphs_;
  std::vector<Decoration> decorations_;
  std::vector<LayoutLine> lines_;
  std::vector<ClusterBox> clusters_;
  float width_ = 0;
  float height_ = 0;
  bool vertical_ = false;
  bool overflows_ = false;
};

char32_t toUpperSimple(char32_t codepoint);

}  // namespace nanquim::text
