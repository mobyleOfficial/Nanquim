#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

struct FT_LibraryRec_;
struct FT_FaceRec_;
struct hb_font_t;

namespace nanquim::text {

inline constexpr size_t kMaximumFontBytes = size_t{64} * 1024 * 1024;

class FontFace;

class FontLibrary {
 public:
  FontLibrary();
  FontLibrary(const FontLibrary&) = delete;
  FontLibrary& operator=(const FontLibrary&) = delete;
  ~FontLibrary();

  std::shared_ptr<FontFace> load(std::vector<uint8_t> data, int32_t faceIndex, std::string& error);
  int32_t faceCount(const std::vector<uint8_t>& data);

 private:
  FT_LibraryRec_* library_ = nullptr;
};

class FontFace {
 public:
  FontFace(const FontFace&) = delete;
  FontFace& operator=(const FontFace&) = delete;
  ~FontFace();

  [[nodiscard]] const std::string& postScriptName() const { return postScriptName_; }
  [[nodiscard]] const std::string& familyName() const { return familyName_; }
  [[nodiscard]] const std::string& styleName() const { return styleName_; }
  [[nodiscard]] int32_t unitsPerEm() const { return unitsPerEm_; }
  [[nodiscard]] int32_t ascender() const { return ascender_; }
  [[nodiscard]] int32_t descender() const { return descender_; }
  [[nodiscard]] int32_t lineGap() const { return lineGap_; }
  [[nodiscard]] int32_t underlinePosition() const { return underlinePosition_; }
  [[nodiscard]] int32_t underlineThickness() const { return underlineThickness_; }
  [[nodiscard]] bool embeddable() const { return embeddable_; }
  [[nodiscard]] bool hasVerticalMetrics() const { return hasVerticalMetrics_; }

  [[nodiscard]] bool hasGlyph(char32_t codepoint) const;

  [[nodiscard]] FT_FaceRec_* freetypeFace() const { return face_; }
  [[nodiscard]] hb_font_t* harfbuzzFont() const { return font_; }

 private:
  friend class FontLibrary;
  FontFace() = default;

  std::vector<uint8_t> data_;
  FT_FaceRec_* face_ = nullptr;
  hb_font_t* font_ = nullptr;
  std::string postScriptName_;
  std::string familyName_;
  std::string styleName_;
  int32_t unitsPerEm_ = 1000;
  int32_t ascender_ = 0;
  int32_t descender_ = 0;
  int32_t lineGap_ = 0;
  int32_t underlinePosition_ = 0;
  int32_t underlineThickness_ = 0;
  bool embeddable_ = false;
  bool hasVerticalMetrics_ = false;
};

}  // namespace nanquim::text
