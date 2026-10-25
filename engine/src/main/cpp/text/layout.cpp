#include "text/layout.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace nanquim::text {
namespace {

bool isParagraphSeparator(char32_t codepoint) { return codepoint == U'\n' || codepoint == U' '; }

bool isWhitespace(char32_t codepoint) {
  return codepoint == U' ' || codepoint == U'\t' || codepoint == U' ' || codepoint == U'　' ||
         (codepoint >= U' ' && codepoint <= U' ');
}

bool isIdeographic(char32_t codepoint) {
  return (codepoint >= 0x2E80 && codepoint <= 0x9FFF) ||
         (codepoint >= 0xAC00 && codepoint <= 0xD7AF) ||
         (codepoint >= 0xF900 && codepoint <= 0xFAFF) ||
         (codepoint >= 0xFF00 && codepoint <= 0xFFEF) ||
         (codepoint >= 0x20000 && codepoint <= 0x3FFFF);
}

bool mayNotStartLine(char32_t codepoint) {
  static constexpr char32_t kClosing[] = {
      U'、', U'。', U'，', U'．', U'・', U'：', U'；', U'？', U'！', U'ー', U'々',
      U'〻', U'）', U'」', U'』', U'】', U'〕', U'〉', U'》', U'〟', U'’',  U'”',
      U'ぁ', U'ぃ', U'ぅ', U'ぇ', U'ぉ', U'っ', U'ゃ', U'ゅ', U'ょ', U'ゎ', U'ァ',
      U'ィ', U'ゥ', U'ェ', U'ォ', U'ッ', U'ャ', U'ュ', U'ョ', U'ヮ', U'ヵ', U'ヶ',
      U')',  U']',  U'}',  U',',  U'.',  U'!',  U'?',  U':',  U';'};
  return std::find(std::begin(kClosing), std::end(kClosing), codepoint) != std::end(kClosing);
}

bool mayNotEndLine(char32_t codepoint) {
  static constexpr char32_t kOpening[] = {U'（', U'「', U'『', U'【', U'〔', U'〈', U'《',
                                          U'〝', U'‘',  U'“',  U'(',  U'[',  U'{'};
  return std::find(std::begin(kOpening), std::end(kOpening), codepoint) != std::end(kOpening);
}

bool canBreakBefore(const std::u32string& text, size_t index) {
  if (index == 0 || index >= text.size()) {
    return false;
  }
  const char32_t before = text[index - 1];
  const char32_t after = text[index];
  if (isWhitespace(after)) {
    return false;
  }
  if (mayNotStartLine(after) || mayNotEndLine(before)) {
    return false;
  }
  if (isWhitespace(before)) {
    return true;
  }
  if (before == U'-' || before == U'‐' || before == U'–') {
    return !isWhitespace(after);
  }
  return isIdeographic(before) || isIdeographic(after);
}

struct LineMetrics {
  float maxSize = 0;
  float ascent = 0;
  float descent = 0;
};

struct PendingLine {
  size_t paragraphStart = 0;
  size_t start = 0;
  size_t end = 0;
  size_t logicalEnd = 0;
  ShapedParagraph shaped;
  std::vector<size_t> order;
  LineMetrics metrics;
  float extent = 0;
  float advance = 0;
  size_t styleWhenEmpty = 0;
};

}  // namespace

char32_t toUpperSimple(char32_t c) {
  if (c >= U'a' && c <= U'z') {
    return c - 32;
  }
  if (c < 0xB5) {
    return c;
  }
  if (c == 0xB5) {
    return 0x39C;
  }
  if ((c >= 0xE0 && c <= 0xFE && c != 0xF7)) {
    return c - 32;
  }
  if (c == 0xFF) {
    return 0x178;
  }
  if (c == 0x131) {
    return U'I';
  }
  if (c == 0x17F) {
    return U'S';
  }
  if ((c >= 0x100 && c <= 0x137) || (c >= 0x14A && c <= 0x177)) {
    return (c & 1U) != 0 ? c - 1 : c;
  }
  if ((c >= 0x139 && c <= 0x148) || (c >= 0x179 && c <= 0x17E)) {
    return (c & 1U) == 0 ? c - 1 : c;
  }
  if (c >= 0x3B1 && c <= 0x3C9) {
    return c == 0x3C2 ? 0x3A3 : c - 32;
  }
  switch (c) {
    case 0x3AC:
      return 0x386;
    case 0x3AD:
    case 0x3AE:
    case 0x3AF:
      return c - 37;
    case 0x3CA:
    case 0x3CB:
      return c - 32;
    case 0x3CC:
      return 0x38C;
    case 0x3CD:
    case 0x3CE:
      return c - 63;
    default:
      break;
  }
  if (c >= 0x430 && c <= 0x44F) {
    return c - 32;
  }
  if (c >= 0x450 && c <= 0x45F) {
    return c - 80;
  }
  if ((c >= 0x460 && c <= 0x481) || (c >= 0x48A && c <= 0x4BF)) {
    return (c & 1U) != 0 ? c - 1 : c;
  }
  if (c >= 0x561 && c <= 0x586) {
    return c - 48;
  }
  return c;
}

TextLayout TextLayout::build(const LayoutInput& input) {
  TextLayout layout;
  layout.vertical_ = input.vertical;
  std::vector<size_t> styleOf;
  for (const LayoutRun& run : input.content) {
    if (run.style >= input.styles.size() || input.styles[run.style].fonts.empty()) {
      continue;
    }
    const std::u32string decoded = utf32FromUtf8(run.text);
    layout.text_ += decoded;
    styleOf.insert(styleOf.end(), decoded.size(), run.style);
  }
  if (input.styles.empty() || input.styles.front().fonts.empty()) {
    return layout;
  }
  const std::u32string& text = layout.text_;
  std::u32string shapingText = text;
  for (size_t index = 0; index < shapingText.size(); ++index) {
    if (input.styles[styleOf[index]].allCaps) {
      shapingText[index] = toUpperSimple(shapingText[index]);
    }
  }
  const bool fixed = input.fixedBox;
  const float maxInline = input.vertical ? input.boxHeight : input.boxWidth;

  std::vector<PendingLine> pending;
  size_t paragraphStart = 0;
  while (paragraphStart <= text.size()) {
    size_t paragraphEnd = paragraphStart;
    while (paragraphEnd < text.size() && !isParagraphSeparator(text[paragraphEnd])) {
      ++paragraphEnd;
    }
    const bool separated = paragraphEnd < text.size();
    const std::u32string paragraph =
        shapingText.substr(paragraphStart, paragraphEnd - paragraphStart);
    const size_t emptyStyle =
        !styleOf.empty() ? styleOf[std::min(paragraphStart, styleOf.size() - 1)] : 0;
    std::vector<StyleSpan> spans;
    for (size_t index = 0; index < paragraph.size(); ++index) {
      const size_t style = styleOf[paragraphStart + index];
      if (spans.empty() || styleOf[paragraphStart + spans.back().start] != style) {
        StyleSpan span;
        span.start = index;
        const LayoutStyle& source = input.styles[style];
        span.style.fonts = source.fonts;
        span.style.sizePixels = source.sizePixels;
        span.style.kerning = source.kerning;
        span.style.vertical = input.vertical;
        span.style.language = source.language;
        spans.push_back(span);
      }
      spans.back().end = index + 1;
    }
    uint8_t baseLevel = 0;
    const std::vector<uint8_t> levels = resolveLevels(paragraph, input.direction, baseLevel);
    ShapedParagraph whole = shapeRange(paragraph, spans, levels, baseLevel, 0, paragraph.size());
    std::vector<float> advances(paragraph.size(), 0.0F);
    std::vector<bool> clusterStarts(paragraph.size() + 1, false);
    clusterStarts[paragraph.size()] = true;
    for (const GlyphRun& run : whole.runs) {
      for (const Glyph& glyph : run.glyphs) {
        advances[glyph.cluster] += run.topToBottom ? -glyph.advanceY : glyph.advanceX;
        clusterStarts[glyph.cluster] = true;
      }
    }
    for (size_t index = 0; index < paragraph.size(); ++index) {
      if (clusterStarts[index]) {
        const LayoutStyle& style = input.styles[styleOf[paragraphStart + index]];
        advances[index] += style.tracking * style.sizePixels / 1000.0F;
      }
    }
    std::vector<std::pair<size_t, size_t>> ranges;
    size_t lineStart = 0;
    while (lineStart < paragraph.size()) {
      size_t lineEnd = paragraph.size();
      if (fixed) {
        float width = 0;
        size_t lastBreak = lineStart;
        for (size_t index = lineStart; index < paragraph.size(); ++index) {
          if (index > lineStart && canBreakBefore(paragraph, index)) {
            lastBreak = index;
          }
          width += advances[index];
          if (width > maxInline && !isWhitespace(paragraph[index]) && index > lineStart) {
            if (lastBreak > lineStart) {
              lineEnd = lastBreak;
            } else {
              lineEnd = index;
              while (lineEnd > lineStart + 1 && !clusterStarts[lineEnd]) {
                --lineEnd;
              }
            }
            break;
          }
        }
      }
      ranges.emplace_back(lineStart, lineEnd);
      lineStart = lineEnd;
    }
    if (ranges.empty()) {
      ranges.emplace_back(0, 0);
    }
    for (size_t rangeIndex = 0; rangeIndex < ranges.size(); ++rangeIndex) {
      PendingLine line;
      line.paragraphStart = paragraphStart;
      line.start = ranges[rangeIndex].first;
      line.end = ranges[rangeIndex].second;
      line.logicalEnd = line.end + (separated && rangeIndex + 1 == ranges.size() ? 1 : 0);
      line.styleWhenEmpty = emptyStyle;
      line.shaped = ranges.size() == 1 && line.start == 0 && line.end == paragraph.size()
                        ? whole
                        : shapeRange(paragraph, spans, levels, baseLevel, line.start, line.end);
      std::vector<uint8_t> runLevels;
      for (const GlyphRun& run : line.shaped.runs) {
        runLevels.push_back(run.level);
      }
      line.order = visualRunOrder(runLevels);
      size_t visibleEnd = line.end;
      while (visibleEnd > line.start && isWhitespace(paragraph[visibleEnd - 1])) {
        --visibleEnd;
      }
      for (size_t index = line.start; index < line.end; ++index) {
        const float advance = index < visibleEnd ? advances[index] : 0.0F;
        line.extent += advance;
      }
      for (const GlyphRun& run : line.shaped.runs) {
        const LayoutStyle& style = input.styles[styleOf[paragraphStart + run.start]];
        const float scale = style.sizePixels / static_cast<float>(run.face->unitsPerEm());
        line.metrics.maxSize = std::max(line.metrics.maxSize, style.sizePixels);
        line.metrics.ascent =
            std::max(line.metrics.ascent, static_cast<float>(run.face->ascender()) * scale);
        line.metrics.descent =
            std::max(line.metrics.descent, -static_cast<float>(run.face->descender()) * scale);
      }
      if (line.shaped.runs.empty()) {
        const LayoutStyle& style = input.styles[emptyStyle];
        const FontFace* face = style.fonts.front();
        const float scale = style.sizePixels / static_cast<float>(face->unitsPerEm());
        line.metrics = {style.sizePixels, static_cast<float>(face->ascender()) * scale,
                        -static_cast<float>(face->descender()) * scale};
      }
      line.advance = input.lineHeight * line.metrics.maxSize;
      pending.push_back(std::move(line));
    }
    if (!separated) {
      break;
    }
    paragraphStart = paragraphEnd + 1;
  }

  float longest = 0;
  float across = 0;
  for (const PendingLine& line : pending) {
    longest = std::max(longest, line.extent);
    across += line.advance;
  }
  const float inlineSize = fixed ? maxInline : longest;
  const float acrossSize = fixed ? (input.vertical ? input.boxWidth : input.boxHeight) : across;
  layout.width_ = input.vertical ? acrossSize : inlineSize;
  layout.height_ = input.vertical ? inlineSize : acrossSize;
  layout.overflows_ = fixed && across > acrossSize + 0.01F;

  float cursor = input.vertical ? layout.width_ : 0;
  for (size_t lineIndex = 0; lineIndex < pending.size(); ++lineIndex) {
    const PendingLine& line = pending[lineIndex];
    const size_t offset = line.paragraphStart;
    LayoutLine placed;
    placed.start = offset + line.start;
    placed.end = offset + line.logicalEnd;
    float start = 0;
    switch (input.alignment) {
      case Alignment::Left:
        break;
      case Alignment::Center:
        start = (inlineSize - line.extent) / 2;
        break;
      case Alignment::Right:
        start = inlineSize - line.extent;
        break;
    }
    float center = 0;
    if (input.vertical) {
      placed.box = {cursor - line.advance, 0, line.advance, inlineSize};
      center = cursor - line.advance / 2;
      placed.baseline = center;
      cursor -= line.advance;
    } else {
      placed.box = {0, cursor, inlineSize, line.advance};
      placed.baseline = cursor + (line.advance - line.metrics.ascent - line.metrics.descent) / 2 +
                        line.metrics.ascent;
      cursor += line.advance;
    }
    float pen = start;
    for (const size_t runIndex : line.order) {
      const GlyphRun& run = line.shaped.runs[runIndex];
      const size_t style = styleOf[offset + run.start];
      const LayoutStyle& source = input.styles[style];
      const float scale = source.sizePixels / static_cast<float>(run.face->unitsPerEm());
      const float tracking = source.tracking * source.sizePixels / 1000.0F;
      const bool rightToLeft = (run.level & 1U) != 0;
      std::vector<uint32_t> starts;
      for (const Glyph& glyph : run.glyphs) {
        starts.push_back(glyph.cluster);
      }
      std::sort(starts.begin(), starts.end());
      starts.erase(std::unique(starts.begin(), starts.end()), starts.end());
      const auto clusterEnd = [&](uint32_t cluster) {
        const auto next = std::upper_bound(starts.begin(), starts.end(), cluster);
        return next != starts.end() ? static_cast<size_t>(*next) : run.end;
      };
      const float rotatedBaseline = center - (static_cast<float>(run.face->ascender()) +
                                              static_cast<float>(run.face->descender())) *
                                                 scale / 2;
      const float runStart = pen;
      for (size_t glyphIndex = 0; glyphIndex < run.glyphs.size(); ++glyphIndex) {
        const Glyph& glyph = run.glyphs[glyphIndex];
        PlacedGlyph out;
        out.face = run.face;
        out.id = glyph.id;
        out.sizePixels = source.sizePixels;
        out.style = style;
        out.cluster = static_cast<uint32_t>(offset + glyph.cluster);
        const float glyphStart = pen;
        float advance = 0;
        if (!input.vertical) {
          out.x = pen + glyph.offsetX;
          out.y = placed.baseline - glyph.offsetY - source.baselineShiftPixels;
          advance = glyph.advanceX;
        } else if (run.topToBottom) {
          out.x = center + glyph.offsetX + source.baselineShiftPixels;
          out.y = pen - glyph.offsetY;
          advance = -glyph.advanceY;
        } else {
          out.rotated = true;
          out.x = rotatedBaseline + glyph.offsetY + source.baselineShiftPixels;
          out.y = pen + glyph.offsetX;
          advance = glyph.advanceX;
        }
        pen += advance;
        const bool lastOfCluster = glyphIndex + 1 == run.glyphs.size() ||
                                   run.glyphs[glyphIndex + 1].cluster != glyph.cluster;
        if (lastOfCluster) {
          pen += tracking;
        }
        layout.glyphs_.push_back(out);
        const size_t clusterStart = offset + glyph.cluster;
        if (!layout.clusters_.empty() && layout.clusters_.back().start == clusterStart &&
            layout.clusters_.back().line == lineIndex) {
          ClusterBox& box = layout.clusters_.back();
          box.from = std::min(box.from, glyphStart);
          box.to = std::max(box.to, pen);
        } else {
          layout.clusters_.push_back({clusterStart, offset + clusterEnd(glyph.cluster), lineIndex,
                                      glyphStart, pen, rightToLeft});
        }
      }
      if (source.underline || source.strikethrough) {
        const float thickness =
            std::max(1.0F, static_cast<float>(run.face->underlineThickness()) * scale);
        const float underline = -static_cast<float>(run.face->underlinePosition()) * scale;
        const float strike = source.sizePixels * 0.3F;
        for (const bool isUnderline : {true, false}) {
          if ((isUnderline && !source.underline) || (!isUnderline && !source.strikethrough)) {
            continue;
          }
          Decoration decoration;
          decoration.style = style;
          if (!input.vertical) {
            const float y =
                placed.baseline - source.baselineShiftPixels + (isUnderline ? underline : -strike);
            decoration = {runStart, y - thickness / 2, pen, y + thickness / 2, style};
          } else {
            const float x = isUnderline ? center + line.metrics.maxSize / 2 : center;
            decoration = {x - thickness / 2, runStart, x + thickness / 2, pen, style};
          }
          layout.decorations_.push_back(decoration);
        }
      }
    }
    if (line.logicalEnd > line.end) {
      const bool rightToLeft = (line.shaped.baseLevel & 1U) != 0;
      const float position = rightToLeft ? start : start + line.extent;
      layout.clusters_.push_back({offset + line.end, offset + line.logicalEnd, lineIndex, position,
                                  position, rightToLeft});
    }
    layout.lines_.push_back(placed);
  }
  std::stable_sort(
      layout.clusters_.begin(), layout.clusters_.end(),
      [](const ClusterBox& first, const ClusterBox& second) { return first.start < second.start; });
  return layout;
}

float TextLayout::inlinePosition(size_t index, size_t& line) const {
  line = 0;
  if (clusters_.empty()) {
    if (!lines_.empty()) {
      const Rect& box = lines_.front().box;
      return vertical_ ? box.y : box.x;
    }
    return 0;
  }
  if (index >= text_.size()) {
    const ClusterBox& last = clusters_.back();
    line = last.line;
    if (last.end <= text_.size() && !text_.empty() && isParagraphSeparator(text_.back()) &&
        line + 1 < lines_.size()) {
      line = lines_.size() - 1;
      const Rect& box = lines_[line].box;
      return vertical_ ? box.y : box.x;
    }
    return last.rightToLeft ? last.from : last.to;
  }
  auto found =
      std::upper_bound(clusters_.begin(), clusters_.end(), index,
                       [](size_t value, const ClusterBox& box) { return value < box.start; });
  if (found == clusters_.begin()) {
    line = clusters_.front().line;
    return clusters_.front().rightToLeft ? clusters_.front().to : clusters_.front().from;
  }
  const ClusterBox& box = *(found - 1);
  line = box.line;
  if (index >= box.end) {
    return box.rightToLeft ? box.from : box.to;
  }
  const float fraction = static_cast<float>(index - box.start) /
                         static_cast<float>(std::max<size_t>(1, box.end - box.start));
  return box.rightToLeft ? box.to - (box.to - box.from) * fraction
                         : box.from + (box.to - box.from) * fraction;
}

std::array<float, 4> TextLayout::segmentAcross(size_t line, float position) const {
  if (line >= lines_.size()) {
    return {position, 0, position, 0};
  }
  const Rect& box = lines_[line].box;
  return vertical_ ? std::array<float, 4>{box.x, position, box.x + box.width, position}
                   : std::array<float, 4>{position, box.y, position, box.y + box.height};
}

std::array<float, 4> TextLayout::caret(size_t index) const {
  size_t line = 0;
  const float position = inlinePosition(index, line);
  return segmentAcross(line, position);
}

std::vector<Rect> TextLayout::selection(size_t start, size_t end) const {
  std::vector<Rect> rects;
  if (start >= end) {
    return rects;
  }
  std::vector<std::vector<std::pair<float, float>>> spans(lines_.size());
  for (const ClusterBox& box : clusters_) {
    if (box.end <= start || box.start >= end) {
      continue;
    }
    float from = box.from;
    float to = box.to;
    if (box.end - box.start > 1 && (box.start < start || box.end > end)) {
      const float length = to - from;
      const auto share = [&](size_t index) {
        return static_cast<float>(index - box.start) / static_cast<float>(box.end - box.start);
      };
      const float first = share(std::max(start, box.start)) * length;
      const float last = share(std::min(end, box.end)) * length;
      from = box.rightToLeft ? box.to - last : box.from + first;
      to = box.rightToLeft ? box.to - first : box.from + last;
    }
    spans[box.line].emplace_back(from, to);
  }
  for (size_t line = 0; line < spans.size(); ++line) {
    auto& extents = spans[line];
    if (extents.empty()) {
      continue;
    }
    std::sort(extents.begin(), extents.end());
    std::vector<std::pair<float, float>> merged{extents.front()};
    for (size_t index = 1; index < extents.size(); ++index) {
      if (extents[index].first <= merged.back().second + 0.5F) {
        merged.back().second = std::max(merged.back().second, extents[index].second);
      } else {
        merged.push_back(extents[index]);
      }
    }
    const Rect& box = lines_[line].box;
    for (const auto& [from, to] : merged) {
      rects.push_back(vertical_ ? Rect{box.x, from, box.width, to - from}
                                : Rect{from, box.y, to - from, box.height});
    }
  }
  return rects;
}

size_t TextLayout::hitTest(float x, float y) const {
  if (lines_.empty()) {
    return 0;
  }
  const float across = vertical_ ? x : y;
  const float along = vertical_ ? y : x;
  size_t best = 0;
  float bestDistance = std::numeric_limits<float>::max();
  for (size_t line = 0; line < lines_.size(); ++line) {
    const Rect& box = lines_[line].box;
    const float low = vertical_ ? box.x : box.y;
    const float high = low + (vertical_ ? box.width : box.height);
    const float distance = across < low ? low - across : across > high ? across - high : 0;
    if (distance < bestDistance) {
      bestDistance = distance;
      best = line;
    }
  }
  const ClusterBox* nearest = nullptr;
  float nearestDistance = std::numeric_limits<float>::max();
  for (const ClusterBox& box : clusters_) {
    if (box.line != best) {
      continue;
    }
    const float distance = along < box.from ? box.from - along
                           : along > box.to ? along - box.to
                                            : 0;
    if (distance < nearestDistance) {
      nearestDistance = distance;
      nearest = &box;
    }
  }
  if (nearest == nullptr) {
    return lines_[best].start;
  }
  const bool firstHalf = along < (nearest->from + nearest->to) / 2;
  const bool before = nearest->rightToLeft ? !firstHalf : firstHalf;
  if (before) {
    return nearest->start;
  }
  if (nearest->end > nearest->start && isParagraphSeparator(text_[nearest->start])) {
    return nearest->start;
  }
  return nearest->end;
}

}  // namespace nanquim::text
