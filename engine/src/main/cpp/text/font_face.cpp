#include "text/font_face.h"

#include <ft2build.h>
#include FT_FREETYPE_H
#include <hb-ot.h>
#include <hb.h>

#include <algorithm>

namespace nanquim::text {

FontLibrary::FontLibrary() {
  if (FT_Init_FreeType(&library_) != 0) {
    library_ = nullptr;
  }
}

FontLibrary::~FontLibrary() {
  if (library_ != nullptr) {
    FT_Done_FreeType(library_);
  }
}

int32_t FontLibrary::faceCount(const std::vector<uint8_t>& data) {
  if (library_ == nullptr || data.empty() || data.size() > kMaximumFontBytes) {
    return 0;
  }
  FT_Face face = nullptr;
  if (FT_New_Memory_Face(library_, data.data(), static_cast<FT_Long>(data.size()), -1, &face) !=
      0) {
    return 0;
  }
  const auto count = static_cast<int32_t>(face->num_faces);
  FT_Done_Face(face);
  return count;
}

std::shared_ptr<FontFace> FontLibrary::load(std::vector<uint8_t> data, int32_t faceIndex,
                                            std::string& error) {
  if (library_ == nullptr) {
    error = "FreeType could not be initialized";
    return nullptr;
  }
  if (data.empty() || data.size() > kMaximumFontBytes) {
    error = "Font file empty or too large";
    return nullptr;
  }
  std::shared_ptr<FontFace> font(new FontFace());
  font->data_ = std::move(data);
  const FT_Error status =
      FT_New_Memory_Face(library_, font->data_.data(), static_cast<FT_Long>(font->data_.size()),
                         faceIndex, &font->face_);
  if (status != 0) {
    font->face_ = nullptr;
    error = "Not a font file (FreeType error " + std::to_string(status) + ")";
    return nullptr;
  }
  FT_Face face = font->face_;
  if (!FT_IS_SCALABLE(face)) {
    error = "Bitmap-only fonts are not supported";
    return nullptr;
  }
  if (FT_Select_Charmap(face, FT_ENCODING_UNICODE) != 0) {
    error = "The font has no Unicode character map";
    return nullptr;
  }
  font->familyName_ = face->family_name != nullptr ? face->family_name : "";
  font->styleName_ = face->style_name != nullptr ? face->style_name : "";
  const char* postScript = FT_Get_Postscript_Name(face);
  if (postScript != nullptr && *postScript != '\0') {
    font->postScriptName_ = postScript;
  } else {
    font->postScriptName_ = font->familyName_ + "-" + font->styleName_;
  }
  font->unitsPerEm_ = face->units_per_EM > 0 ? face->units_per_EM : 1000;
  font->ascender_ = face->ascender;
  font->descender_ = face->descender;
  font->lineGap_ = std::max(0, face->height - (face->ascender - face->descender));
  font->underlinePosition_ = face->underline_position;
  font->underlineThickness_ =
      face->underline_thickness > 0 ? face->underline_thickness : font->unitsPerEm_ / 20;
  const FT_UShort fsType = FT_Get_FSType_Flags(face);
  font->embeddable_ =
      (fsType & (FT_FSTYPE_RESTRICTED_LICENSE_EMBEDDING | FT_FSTYPE_BITMAP_EMBEDDING_ONLY)) == 0;
  font->hasVerticalMetrics_ = FT_HAS_VERTICAL(face);

  hb_blob_t* blob = hb_blob_create(reinterpret_cast<const char*>(font->data_.data()),
                                   static_cast<unsigned int>(font->data_.size()),
                                   HB_MEMORY_MODE_READONLY, nullptr, nullptr);
  hb_face_t* harfbuzzFace = hb_face_create(blob, static_cast<unsigned int>(faceIndex));
  hb_blob_destroy(blob);
  font->font_ = hb_font_create(harfbuzzFace);
  hb_face_destroy(harfbuzzFace);
  hb_font_set_scale(font->font_, font->unitsPerEm_, font->unitsPerEm_);
  hb_ot_font_set_funcs(font->font_);
  return font;
}

FontFace::~FontFace() {
  if (font_ != nullptr) {
    hb_font_destroy(font_);
  }
  if (face_ != nullptr) {
    FT_Done_Face(face_);
  }
}

bool FontFace::hasGlyph(char32_t codepoint) const {
  return FT_Get_Char_Index(face_, static_cast<FT_ULong>(codepoint)) != 0;
}

}  // namespace nanquim::text
