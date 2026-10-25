#include "text/text_model.h"

#include <algorithm>
#include <map>
#include <nlohmann/json.hpp>

#include "canvas/color_conversion.h"

namespace nanquim::text {
namespace {

using Json = nlohmann::json;

std::array<float, 4> readColor(const Json& value, bool linearLight) {
  std::array<float, 4> color{0, 0, 0, 1};
  if (!value.is_array() || value.size() < 3) {
    return color;
  }
  for (size_t channel = 0; channel < std::min<size_t>(4, value.size()); ++channel) {
    const double component =
        std::clamp(value[channel].is_number() ? value[channel].get<double>() : 0.0, 0.0, 1.0);
    color[channel] = static_cast<float>(
        channel < 3 && linearLight ? canvas::srgbToLinearValue(component) : component);
  }
  return color;
}

double number(const Json& object, const char* key, double fallback) {
  const auto found = object.find(key);
  return found != object.end() && found->is_number() ? found->get<double>() : fallback;
}

bool boolean(const Json& object, const char* key, bool fallback) {
  const auto found = object.find(key);
  return found != object.end() && found->is_boolean() ? found->get<bool>() : fallback;
}

std::string string(const Json& object, const char* key, const std::string& fallback) {
  const auto found = object.find(key);
  return found != object.end() && found->is_string() ? found->get<std::string>() : fallback;
}

}  // namespace

std::optional<TextModel> parseTextModel(std::string_view json, double dpi,
                                        const FontResolver& resolve,
                                        const std::vector<const FontFace*>& fallbacks,
                                        bool linearLight, std::string& error) {
  const Json root = Json::parse(json, nullptr, false);
  if (!root.is_object() || !root.contains("content") || !root["content"].is_array()) {
    error = "Not a text model";
    return std::nullopt;
  }
  if (number(root, "format", 1) > 1) {
    error = "Text model format " + std::to_string(number(root, "format", 1)) + " is newer";
    return std::nullopt;
  }
  const double pointsToPixels = (dpi > 0 ? dpi : 72.0) / 72.0;
  const Json emptyObject = Json::object();
  const Json& styles =
      root.contains("styles") && root["styles"].is_object() ? root["styles"] : emptyObject;
  TextModel model;
  std::map<std::string, size_t> styleIndices;
  const auto styleIndex = [&](const std::string& key) -> std::optional<size_t> {
    const auto known = styleIndices.find(key);
    if (known != styleIndices.end()) {
      return known->second;
    }
    const Json& source =
        styles.contains(key) && styles[key].is_object() ? styles[key] : emptyObject;
    LayoutStyle style;
    const std::string fontId = string(source, "fontId", "");
    const FontFace* face = fontId.empty() ? nullptr : resolve(fontId);
    if (face == nullptr && !fontId.empty() &&
        std::find(model.missingFonts.begin(), model.missingFonts.end(), fontId) ==
            model.missingFonts.end()) {
      model.missingFonts.push_back(fontId);
    }
    if (face != nullptr) {
      style.fonts.push_back(face);
    }
    for (const FontFace* fallback : fallbacks) {
      if (fallback != nullptr && fallback != face) {
        style.fonts.push_back(fallback);
      }
    }
    if (style.fonts.empty()) {
      return std::nullopt;
    }
    style.sizePixels =
        static_cast<float>(std::max(0.1, number(source, "sizePt", 48)) * pointsToPixels);
    style.tracking = static_cast<float>(number(source, "tracking", 0));
    style.kerning = string(source, "kerning", "auto") != "none";
    style.baselineShiftPixels =
        static_cast<float>(number(source, "baselineShift", 0) * pointsToPixels);
    style.allCaps = boolean(source, "allCaps", false);
    style.underline = boolean(source, "underline", false);
    style.strikethrough = boolean(source, "strikethrough", false);
    style.language = string(source, "language", "");
    TextPaint paint;
    paint.color = readColor(source.contains("color") ? source["color"] : Json(), linearLight);
    if (source.contains("outline") && source["outline"].is_object()) {
      const Json& outline = source["outline"];
      paint.outlineWidth =
          static_cast<float>(std::max(0.0, number(outline, "width", 0)) * pointsToPixels);
      paint.outlineColor =
          readColor(outline.contains("color") ? outline["color"] : Json(), linearLight);
    }
    const size_t index = model.layout.styles.size();
    model.layout.styles.push_back(std::move(style));
    model.paints.push_back(paint);
    model.fontIds.push_back(fontId);
    styleIndices.emplace(key, index);
    return index;
  };
  for (const Json& run : root["content"]) {
    if (!run.is_object()) {
      continue;
    }
    const std::optional<size_t> style = styleIndex(string(run, "style", ""));
    if (!style) {
      error = "No font for the text";
      return std::nullopt;
    }
    model.layout.content.push_back({string(run, "text", ""), *style});
  }
  if (model.layout.styles.empty() && !styleIndex("").has_value()) {
    error = "No font for the text";
    return std::nullopt;
  }
  if (root.contains("paragraph") && root["paragraph"].is_object()) {
    const Json& paragraph = root["paragraph"];
    const std::string alignment = string(paragraph, "alignment", "left");
    model.layout.alignment = alignment == "center"  ? Alignment::Center
                             : alignment == "right" ? Alignment::Right
                                                    : Alignment::Left;
    model.layout.lineHeight =
        static_cast<float>(std::clamp(number(paragraph, "lineHeight", 1.2), 0.1, 10.0));
    model.layout.vertical = boolean(paragraph, "vertical", false);
    const std::string direction = string(paragraph, "direction", "auto");
    model.layout.direction = direction == "ltr"   ? BaseDirection::LeftToRight
                             : direction == "rtl" ? BaseDirection::RightToLeft
                                                  : BaseDirection::Auto;
  }
  if (root.contains("box") && root["box"].is_object()) {
    const Json& box = root["box"];
    model.layout.fixedBox = string(box, "mode", "auto") == "fixed";
    model.layout.boxWidth = static_cast<float>(std::max(0.0, number(box, "width", 0)));
    model.layout.boxHeight = static_cast<float>(std::max(0.0, number(box, "height", 0)));
  }
  if (root.contains("transform") && root["transform"].is_array() && root["transform"].size() == 6) {
    for (size_t index = 0; index < 6; ++index) {
      const Json& value = root["transform"][index];
      model.transform[index] = value.is_number() ? value.get<double>() : kIdentityAffine[index];
    }
  }
  return model;
}

}  // namespace nanquim::text
