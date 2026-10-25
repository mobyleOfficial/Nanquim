#pragma once

#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "text/font_face.h"
#include "text/layout.h"
#include "text/text_raster.h"

namespace nanquim::text {

using FontResolver = std::function<const FontFace*(const std::string& fontId)>;

struct TextModel {
  LayoutInput layout;
  std::vector<TextPaint> paints;
  Affine transform = kIdentityAffine;
  std::vector<std::string> fontIds;
  std::vector<std::string> missingFonts;
};

std::optional<TextModel> parseTextModel(std::string_view json, double dpi,
                                        const FontResolver& resolve,
                                        const std::vector<const FontFace*>& fallbacks,
                                        bool linearLight, std::string& error);

}  // namespace nanquim::text
