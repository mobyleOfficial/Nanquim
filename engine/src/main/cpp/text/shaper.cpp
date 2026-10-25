#include "text/shaper.h"

#include <SheenBidi/SheenBidi.h>
#include <hb.h>

#include <algorithm>

namespace nanquim::text {

std::vector<uint8_t> resolveLevels(const std::u32string& text, BaseDirection direction,
                                   uint8_t& baseLevel) {
  const SBLevel requested = direction == BaseDirection::LeftToRight   ? 0
                            : direction == BaseDirection::RightToLeft ? 1
                                                                      : SBLevelDefaultLTR;
  baseLevel = direction == BaseDirection::RightToLeft ? 1 : 0;
  std::vector<uint8_t> levels(text.size(), baseLevel);
  if (text.empty()) {
    return levels;
  }
  const SBCodepointSequence sequence{SBStringEncodingUTF32, text.data(), text.size()};
  SBAlgorithmRef algorithm = SBAlgorithmCreate(&sequence);
  if (algorithm == nullptr) {
    return levels;
  }
  SBParagraphRef paragraph = SBAlgorithmCreateParagraph(algorithm, 0, text.size(), requested);
  if (paragraph != nullptr) {
    baseLevel = SBParagraphGetBaseLevel(paragraph);
    const SBLevel* resolved = SBParagraphGetLevelsPtr(paragraph);
    const size_t length = std::min<size_t>(SBParagraphGetLength(paragraph), text.size());
    std::copy(resolved, resolved + length, levels.begin());
    SBParagraphRelease(paragraph);
  }
  SBAlgorithmRelease(algorithm);
  return levels;
}

namespace {

std::vector<uint32_t> computeScripts(const std::u32string& text) {
  std::vector<uint32_t> scripts(text.size(), HB_SCRIPT_COMMON);
  if (text.empty()) {
    return scripts;
  }
  const SBCodepointSequence sequence{SBStringEncodingUTF32, text.data(), text.size()};
  SBScriptLocatorRef locator = SBScriptLocatorCreate();
  SBScriptLocatorLoadCodepoints(locator, &sequence);
  while (SBScriptLocatorMoveNext(locator) != 0) {
    const SBScriptAgent* agent = SBScriptLocatorGetAgent(locator);
    uint32_t tag = SBScriptGetUnicodeTag(agent->script);
    if (tag == 0) {
      tag = HB_SCRIPT_COMMON;
    }
    const size_t end = std::min<size_t>(agent->offset + agent->length, text.size());
    std::fill(scripts.begin() + static_cast<std::ptrdiff_t>(agent->offset),
              scripts.begin() + static_cast<std::ptrdiff_t>(end), tag);
  }
  SBScriptLocatorRelease(locator);
  return scripts;
}

bool followsPrevious(char32_t codepoint) {
  if ((codepoint >= 0x200C && codepoint <= 0x200D) ||
      (codepoint >= 0xFE00 && codepoint <= 0xFE0F) ||
      (codepoint >= 0xE0100 && codepoint <= 0xE01EF)) {
    return true;
  }
  switch (hb_unicode_general_category(hb_unicode_funcs_get_default(), codepoint)) {
    case HB_UNICODE_GENERAL_CATEGORY_NON_SPACING_MARK:
    case HB_UNICODE_GENERAL_CATEGORY_SPACING_MARK:
    case HB_UNICODE_GENERAL_CATEGORY_ENCLOSING_MARK:
      return true;
    default:
      return false;
  }
}

bool isNeutral(char32_t codepoint) {
  switch (hb_unicode_general_category(hb_unicode_funcs_get_default(), codepoint)) {
    case HB_UNICODE_GENERAL_CATEGORY_SPACE_SEPARATOR:
    case HB_UNICODE_GENERAL_CATEGORY_CONNECT_PUNCTUATION:
    case HB_UNICODE_GENERAL_CATEGORY_DASH_PUNCTUATION:
    case HB_UNICODE_GENERAL_CATEGORY_CLOSE_PUNCTUATION:
    case HB_UNICODE_GENERAL_CATEGORY_FINAL_PUNCTUATION:
    case HB_UNICODE_GENERAL_CATEGORY_INITIAL_PUNCTUATION:
    case HB_UNICODE_GENERAL_CATEGORY_OTHER_PUNCTUATION:
    case HB_UNICODE_GENERAL_CATEGORY_OPEN_PUNCTUATION:
    case HB_UNICODE_GENERAL_CATEGORY_MATH_SYMBOL:
    case HB_UNICODE_GENERAL_CATEGORY_CURRENCY_SYMBOL:
    case HB_UNICODE_GENERAL_CATEGORY_MODIFIER_SYMBOL:
    case HB_UNICODE_GENERAL_CATEGORY_OTHER_SYMBOL:
    case HB_UNICODE_GENERAL_CATEGORY_CONTROL:
    case HB_UNICODE_GENERAL_CATEGORY_FORMAT:
      return true;
    default:
      return false;
  }
}

const FontFace* chooseFace(const ShapeStyle& style, char32_t codepoint, const FontFace* previous) {
  if (previous != nullptr &&
      (followsPrevious(codepoint) || (isNeutral(codepoint) && previous->hasGlyph(codepoint)))) {
    return previous;
  }
  for (const FontFace* face : style.fonts) {
    if (face->hasGlyph(codepoint)) {
      return face;
    }
  }
  return previous != nullptr ? previous : style.fonts.front();
}

void shapeRun(const std::u32string& text, const ShapeStyle& style, GlyphRun& run) {
  hb_buffer_t* buffer = hb_buffer_create();
  hb_buffer_add_utf32(buffer, reinterpret_cast<const uint32_t*>(text.data()),
                      static_cast<int>(text.size()), static_cast<unsigned int>(run.start),
                      static_cast<int>(run.end - run.start));
  run.topToBottom = style.vertical && isVerticalScript(run.script);
  hb_buffer_set_direction(buffer, run.topToBottom         ? HB_DIRECTION_TTB
                                  : (run.level & 1U) != 0 ? HB_DIRECTION_RTL
                                                          : HB_DIRECTION_LTR);
  hb_buffer_set_script(buffer, static_cast<hb_script_t>(run.script));
  if (!style.language.empty()) {
    hb_buffer_set_language(
        buffer,
        hb_language_from_string(style.language.c_str(), static_cast<int>(style.language.size())));
  }
  hb_buffer_guess_segment_properties(buffer);
  std::vector<hb_feature_t> features;
  if (!style.kerning) {
    features.push_back(
        {HB_TAG('k', 'e', 'r', 'n'), 0, HB_FEATURE_GLOBAL_START, HB_FEATURE_GLOBAL_END});
  }
  hb_shape(run.face->harfbuzzFont(), buffer, features.data(),
           static_cast<unsigned int>(features.size()));
  unsigned int count = 0;
  const hb_glyph_info_t* infos = hb_buffer_get_glyph_infos(buffer, &count);
  const hb_glyph_position_t* positions = hb_buffer_get_glyph_positions(buffer, &count);
  const float scale = style.sizePixels / static_cast<float>(run.face->unitsPerEm());
  run.glyphs.resize(count);
  run.advance = 0;
  for (unsigned int index = 0; index < count; ++index) {
    Glyph& glyph = run.glyphs[index];
    glyph.id = infos[index].codepoint;
    glyph.cluster = infos[index].cluster;
    glyph.advanceX = static_cast<float>(positions[index].x_advance) * scale;
    glyph.advanceY = static_cast<float>(positions[index].y_advance) * scale;
    glyph.offsetX = static_cast<float>(positions[index].x_offset) * scale;
    glyph.offsetY = static_cast<float>(positions[index].y_offset) * scale;
    run.advance += run.topToBottom ? -glyph.advanceY : glyph.advanceX;
  }
  hb_buffer_destroy(buffer);
}

}  // namespace

ShapedParagraph shapeParagraph(const std::u32string& text, const std::vector<StyleSpan>& spans,
                               BaseDirection direction) {
  uint8_t baseLevel = 0;
  const std::vector<uint8_t> levels = resolveLevels(text, direction, baseLevel);
  return shapeRange(text, spans, levels, baseLevel, 0, text.size());
}

ShapedParagraph shapeRange(const std::u32string& text, const std::vector<StyleSpan>& spans,
                           const std::vector<uint8_t>& levels, uint8_t baseLevel, size_t start,
                           size_t end) {
  ShapedParagraph shaped;
  shaped.baseLevel = baseLevel;
  shaped.levels = levels;
  if (start >= end || spans.empty()) {
    return shaped;
  }
  const std::vector<uint32_t> scripts = computeScripts(text);
  size_t spanIndex = 0;
  while (spanIndex + 1 < spans.size() && spans[spanIndex].end <= start) {
    ++spanIndex;
  }
  const FontFace* previous = nullptr;
  GlyphRun current;
  bool open = false;
  for (size_t index = start; index < end; ++index) {
    while (spanIndex + 1 < spans.size() && spans[spanIndex].end <= index) {
      ++spanIndex;
      previous = nullptr;
    }
    const ShapeStyle& style = spans[spanIndex].style;
    const FontFace* face = chooseFace(style, text[index], previous);
    previous = face;
    if (open && current.level == levels[index] && current.script == scripts[index] &&
        current.span == spanIndex && current.face == face) {
      current.end = index + 1;
      continue;
    }
    if (open) {
      shapeRun(text, spans[current.span].style, current);
      shaped.runs.push_back(std::move(current));
      current = GlyphRun{};
    }
    current.start = index;
    current.end = index + 1;
    current.level = levels[index];
    current.script = scripts[index];
    current.span = spanIndex;
    current.face = face;
    open = true;
  }
  if (open) {
    shapeRun(text, spans[current.span].style, current);
    shaped.runs.push_back(std::move(current));
  }
  return shaped;
}

std::vector<size_t> visualRunOrder(const std::vector<uint8_t>& levels) {
  std::vector<size_t> order(levels.size());
  for (size_t index = 0; index < order.size(); ++index) {
    order[index] = index;
  }
  if (levels.empty()) {
    return order;
  }
  const uint8_t highest = *std::max_element(levels.begin(), levels.end());
  uint8_t lowestOdd = 0xFF;
  for (const uint8_t level : levels) {
    if ((level & 1U) != 0) {
      lowestOdd = std::min(lowestOdd, level);
    }
  }
  for (int32_t level = highest; level >= lowestOdd && level > 0; --level) {
    size_t index = 0;
    while (index < order.size()) {
      if (levels[order[index]] < level) {
        ++index;
        continue;
      }
      size_t sequenceEnd = index;
      while (sequenceEnd < order.size() && levels[order[sequenceEnd]] >= level) {
        ++sequenceEnd;
      }
      std::reverse(order.begin() + static_cast<std::ptrdiff_t>(index),
                   order.begin() + static_cast<std::ptrdiff_t>(sequenceEnd));
      index = sequenceEnd;
    }
  }
  return order;
}

bool isVerticalScript(uint32_t script) {
  switch (static_cast<hb_script_t>(script)) {
    case HB_SCRIPT_HAN:
    case HB_SCRIPT_HIRAGANA:
    case HB_SCRIPT_KATAKANA:
    case HB_SCRIPT_HANGUL:
    case HB_SCRIPT_BOPOMOFO:
    case HB_SCRIPT_YI:
      return true;
    default:
      return false;
  }
}

std::u32string utf32FromUtf8(const std::string& text) {
  std::u32string result;
  result.reserve(text.size());
  size_t index = 0;
  while (index < text.size()) {
    const auto lead = static_cast<uint8_t>(text[index]);
    size_t length = lead < 0x80           ? 1
                    : (lead >> 5) == 0x6  ? 2
                    : (lead >> 4) == 0xE  ? 3
                    : (lead >> 3) == 0x1E ? 4
                                          : 0;
    char32_t codepoint = length == 1   ? lead
                         : length == 2 ? lead & 0x1FU
                         : length == 3 ? lead & 0x0FU
                                       : lead & 0x07U;
    bool valid = length != 0 && index + length <= text.size();
    for (size_t offset = 1; valid && offset < length; ++offset) {
      const auto next = static_cast<uint8_t>(text[index + offset]);
      valid = (next & 0xC0U) == 0x80U;
      codepoint = (codepoint << 6U) | (next & 0x3FU);
    }
    valid = valid && !(length == 2 && codepoint < 0x80) && !(length == 3 && codepoint < 0x800) &&
            !(length == 4 && codepoint < 0x10000) && codepoint <= 0x10FFFF &&
            !(codepoint >= 0xD800 && codepoint <= 0xDFFF);
    if (!valid) {
      result.push_back(U'�');
      ++index;
      continue;
    }
    result.push_back(codepoint);
    index += length;
  }
  return result;
}

std::string utf8FromUtf32(const std::u32string& text) {
  std::string result;
  result.reserve(text.size());
  for (char32_t codepoint : text) {
    if (codepoint > 0x10FFFF || (codepoint >= 0xD800 && codepoint <= 0xDFFF)) {
      codepoint = U'�';
    }
    if (codepoint < 0x80) {
      result.push_back(static_cast<char>(codepoint));
    } else if (codepoint < 0x800) {
      result.push_back(static_cast<char>(0xC0U | (codepoint >> 6U)));
      result.push_back(static_cast<char>(0x80U | (codepoint & 0x3FU)));
    } else if (codepoint < 0x10000) {
      result.push_back(static_cast<char>(0xE0U | (codepoint >> 12U)));
      result.push_back(static_cast<char>(0x80U | ((codepoint >> 6U) & 0x3FU)));
      result.push_back(static_cast<char>(0x80U | (codepoint & 0x3FU)));
    } else {
      result.push_back(static_cast<char>(0xF0U | (codepoint >> 18U)));
      result.push_back(static_cast<char>(0x80U | ((codepoint >> 12U) & 0x3FU)));
      result.push_back(static_cast<char>(0x80U | ((codepoint >> 6U) & 0x3FU)));
      result.push_back(static_cast<char>(0x80U | (codepoint & 0x3FU)));
    }
  }
  return result;
}

}  // namespace nanquim::text
