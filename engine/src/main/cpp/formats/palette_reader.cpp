#include "formats/palette_reader.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <nlohmann/json.hpp>

#include "formats/byte_reader.h"

namespace nanquim::formats {
namespace {

float clamp01(double value) {
  return static_cast<float>(std::clamp(std::isfinite(value) ? value : 0.0, 0.0, 1.0));
}

void appendUtf8(std::string& out, uint32_t codePoint) {
  if (codePoint < 0x80U) {
    out += static_cast<char>(codePoint);
  } else if (codePoint < 0x800U) {
    out += static_cast<char>(0xC0U | (codePoint >> 6U));
    out += static_cast<char>(0x80U | (codePoint & 0x3FU));
  } else if (codePoint < 0x10000U) {
    out += static_cast<char>(0xE0U | (codePoint >> 12U));
    out += static_cast<char>(0x80U | ((codePoint >> 6U) & 0x3FU));
    out += static_cast<char>(0x80U | (codePoint & 0x3FU));
  } else {
    out += static_cast<char>(0xF0U | (codePoint >> 18U));
    out += static_cast<char>(0x80U | ((codePoint >> 12U) & 0x3FU));
    out += static_cast<char>(0x80U | ((codePoint >> 6U) & 0x3FU));
    out += static_cast<char>(0x80U | (codePoint & 0x3FU));
  }
}

std::string utf16be(ByteReader& reader, uint64_t units) {
  std::string out;
  bool ended = false;
  for (uint64_t index = 0; index < units && reader.ok(); ++index) {
    uint32_t unit = reader.u16be();
    if (ended) {
      continue;
    }
    if (unit == 0) {
      ended = true;
      continue;
    }
    if (unit >= 0xD800U && unit <= 0xDBFFU && index + 1 < units) {
      const uint32_t low = reader.u16be();
      ++index;
      if (low >= 0xDC00U && low <= 0xDFFFU) {
        appendUtf8(out, 0x10000U + ((unit - 0xD800U) << 10U) + (low - 0xDC00U));
      } else {
        appendUtf8(out, 0xFFFDU);
        if (low == 0) {
          ended = true;
        } else {
          appendUtf8(out, low >= 0xD800U && low <= 0xDFFFU ? 0xFFFDU : low);
        }
      }
      continue;
    }
    appendUtf8(out, unit >= 0xD800U && unit <= 0xDFFFU ? 0xFFFDU : unit);
  }
  return out;
}

void hsbToRgb(double hue, double saturation, double brightness, PaletteColor& color) {
  const double h = std::fmod(std::fmod(hue, 360.0) + 360.0, 360.0) / 60.0;
  const double s = std::clamp(saturation, 0.0, 1.0);
  const double v = std::clamp(brightness, 0.0, 1.0);
  const double chroma = v * s;
  const double second = chroma * (1.0 - std::fabs(std::fmod(h, 2.0) - 1.0));
  double r = 0;
  double g = 0;
  double b = 0;
  switch (static_cast<int>(h)) {
    case 0:
      r = chroma, g = second;
      break;
    case 1:
      r = second, g = chroma;
      break;
    case 2:
      g = chroma, b = second;
      break;
    case 3:
      g = second, b = chroma;
      break;
    case 4:
      r = second, b = chroma;
      break;
    default:
      r = chroma, b = second;
      break;
  }
  const double offset = v - chroma;
  color.red = clamp01(r + offset);
  color.green = clamp01(g + offset);
  color.blue = clamp01(b + offset);
}

void addWarning(ImportedPalette& palette, const std::string& code, const std::string& detail) {
  for (const PaletteWarning& warning : palette.warnings) {
    if (warning.code == code && warning.detail == detail) {
      return;
    }
  }
  palette.warnings.push_back({code, detail});
}

void cmykToRgb(double c, double m, double y, double k, PaletteColor& color,
               ImportedPalette& palette, const color::CmykColorConverter* cmyk) {
  if (cmyk != nullptr) {
    const auto rgb = cmyk->toRgb({static_cast<float>(c), static_cast<float>(m),
                                  static_cast<float>(y), static_cast<float>(k)});
    color.red = rgb[0];
    color.green = rgb[1];
    color.blue = rgb[2];
    addWarning(palette, "cmykByProfile", "");
    return;
  }
  addWarning(palette, "approximateCmyk", "");
  const double white = 1.0 - std::clamp(k, 0.0, 1.0);
  color.red = clamp01((1.0 - std::clamp(c, 0.0, 1.0)) * white);
  color.green = clamp01((1.0 - std::clamp(m, 0.0, 1.0)) * white);
  color.blue = clamp01((1.0 - std::clamp(y, 0.0, 1.0)) * white);
}

void addColor(ImportedPalette& palette, PaletteColor color) {
  if (palette.colors.size() >= kMaximumPaletteSwatches) {
    addWarning(palette, "tooManySwatches", std::to_string(kMaximumPaletteSwatches));
    return;
  }
  palette.colors.push_back(std::move(color));
}

constexpr size_t kAcoColorBytes = 10;

bool acoColor(ImportedPalette& palette, uint16_t space, uint16_t w, uint16_t x, uint16_t y,
              uint16_t z, PaletteColor& color, const color::CmykColorConverter* cmyk) {
  switch (space) {
    case 0:
      color.red = clamp01(w / 65535.0);
      color.green = clamp01(x / 65535.0);
      color.blue = clamp01(y / 65535.0);
      return true;
    case 1:
      hsbToRgb(w / 65535.0 * 360.0, x / 65535.0, y / 65535.0, color);
      return true;
    case 2:
      cmykToRgb(1.0 - w / 65535.0, 1.0 - x / 65535.0, 1.0 - y / 65535.0, 1.0 - z / 65535.0, color,
                palette, cmyk);
      return true;
    case 7: {
      labToSrgb(w / 100.0, static_cast<int16_t>(x) / 100.0, static_cast<int16_t>(y) / 100.0,
                color.red, color.green, color.blue);
      addWarning(palette, "approximateLab", "");
      return true;
    }
    case 8: {
      const float gray = clamp01(1.0 - std::min<uint16_t>(w, 10000) / 10000.0);
      color.red = color.green = color.blue = gray;
      return true;
    }
    default:
      addWarning(palette, "unsupportedColorSpace", "aco " + std::to_string(space));
      return false;
  }
}

bool looksLikeAco(const std::vector<uint8_t>& bytes) {
  if (bytes.size() < 4) {
    return false;
  }
  ByteReader reader(bytes.data(), bytes.size());
  const uint16_t version = reader.u16be();
  const uint16_t count = reader.u16be();
  return (version == 1 || version == 2) &&
         (version == 1 ? uint64_t{count} * kAcoColorBytes <= reader.remaining()
                       : uint64_t{count} * (kAcoColorBytes + 4) <= reader.remaining());
}

std::optional<ImportedPalette> readAco(const std::vector<uint8_t>& bytes, std::string& error,
                                       const color::CmykColorConverter* cmyk) {
  ImportedPalette version1;
  version1.format = "aco";
  ByteReader reader(bytes.data(), bytes.size());
  uint16_t version = reader.u16be();
  uint16_t count = reader.u16be();
  const bool hasVersion1 = version == 1;
  if (hasVersion1) {
    for (uint32_t index = 0; index < count && reader.ok(); ++index) {
      const uint16_t space = reader.u16be();
      const uint16_t w = reader.u16be();
      const uint16_t x = reader.u16be();
      const uint16_t y = reader.u16be();
      const uint16_t z = reader.u16be();
      PaletteColor color;
      if (reader.ok() && acoColor(version1, space, w, x, y, z, color, cmyk)) {
        addColor(version1, std::move(color));
      }
    }
    if (!reader.ok()) {
      error = "truncated";
      return std::nullopt;
    }
    if (reader.remaining() < 4) {
      return version1;
    }
    version = reader.u16be();
    count = reader.u16be();
    if (version != 2) {
      return version1;
    }
  } else if (version != 2) {
    error = "notPalette";
    return std::nullopt;
  }
  ImportedPalette version2;
  version2.format = "aco";
  for (uint32_t index = 0; index < count && reader.ok(); ++index) {
    const uint16_t space = reader.u16be();
    const uint16_t w = reader.u16be();
    const uint16_t x = reader.u16be();
    const uint16_t y = reader.u16be();
    const uint16_t z = reader.u16be();
    const uint32_t nameUnits = reader.u32be();
    if (!reader.ok() || uint64_t{nameUnits} * 2 > reader.remaining()) {
      reader.fail();
      break;
    }
    PaletteColor color;
    color.name = utf16be(reader, nameUnits);
    if (reader.ok() && acoColor(version2, space, w, x, y, z, color, cmyk)) {
      addColor(version2, std::move(color));
    }
  }
  if (!reader.ok()) {
    if (hasVersion1) {
      return version1;
    }
    error = "truncated";
    return std::nullopt;
  }
  return version2;
}

std::optional<ImportedPalette> readAse(const std::vector<uint8_t>& bytes, std::string& error,
                                       const color::CmykColorConverter* cmyk) {
  ImportedPalette palette;
  palette.format = "ase";
  ByteReader reader(bytes.data(), bytes.size());
  reader.skip(4);
  const uint16_t major = reader.u16be();
  reader.u16be();
  const uint32_t blocks = reader.u32be();
  if (!reader.ok() || major != 1) {
    error = reader.ok() ? "unsupportedVersion" : "truncated";
    return std::nullopt;
  }
  for (uint32_t index = 0; index < blocks && reader.ok() && !reader.atEnd(); ++index) {
    const uint16_t type = reader.u16be();
    const uint32_t length = reader.u32be();
    ByteReader block = reader.sub(length);
    if (!reader.ok()) {
      break;
    }
    if (type != 0x0001U) {
      continue;
    }
    const uint16_t nameUnits = block.u16be();
    if (uint64_t{nameUnits} * 2 > block.remaining()) {
      continue;
    }
    PaletteColor color;
    color.name = utf16be(block, nameUnits);
    const std::string model = block.string(4);
    if (!block.ok()) {
      continue;
    }
    if (model == "RGB ") {
      color.red = clamp01(block.f32be());
      color.green = clamp01(block.f32be());
      color.blue = clamp01(block.f32be());
    } else if (model == "CMYK") {
      const double c = block.f32be();
      const double m = block.f32be();
      const double y = block.f32be();
      const double k = block.f32be();
      cmykToRgb(c, m, y, k, color, palette, cmyk);
    } else if (model == "LAB ") {
      const double lightness = block.f32be() * 100.0;
      const double a = block.f32be();
      const double b = block.f32be();
      labToSrgb(lightness, a, b, color.red, color.green, color.blue);
      addWarning(palette, "approximateLab", "");
    } else if (model == "Gray") {
      color.red = color.green = color.blue = clamp01(block.f32be());
    } else {
      addWarning(palette, "unsupportedColorSpace", "ase " + model);
      continue;
    }
    if (block.ok()) {
      addColor(palette, std::move(color));
    }
  }
  if (!reader.ok()) {
    error = "truncated";
    return std::nullopt;
  }
  return palette;
}

std::string trim(const std::string& text) {
  const auto first = text.find_first_not_of(" \t\r");
  if (first == std::string::npos) {
    return {};
  }
  const auto last = text.find_last_not_of(" \t\r");
  return text.substr(first, last - first + 1);
}

int readInteger(const std::string& line, size_t& position) {
  while (position < line.size() && (line[position] == ' ' || line[position] == '\t')) {
    ++position;
  }
  const size_t start = position;
  int value = 0;
  while (position < line.size() && line[position] >= '0' && line[position] <= '9') {
    value = std::min(value * 10 + (line[position] - '0'), 100000);
    ++position;
  }
  return position == start ? -1 : value;
}

std::optional<ImportedPalette> readGpl(const std::vector<uint8_t>& bytes, std::string& error) {
  ImportedPalette palette;
  palette.format = "gpl";
  const std::string text(bytes.begin(), bytes.end());
  size_t start = 0;
  bool header = true;
  while (start <= text.size()) {
    size_t end = text.find('\n', start);
    if (end == std::string::npos) {
      end = text.size();
    }
    const std::string line = trim(text.substr(start, end - start));
    start = end + 1;
    if (header) {
      if (line.empty()) {
        continue;
      }
      if (line.rfind("GIMP Palette", 0) != 0) {
        error = "notPalette";
        return std::nullopt;
      }
      header = false;
      continue;
    }
    if (line.empty() || line[0] == '#') {
      continue;
    }
    if (line.rfind("Name:", 0) == 0) {
      palette.name = trim(line.substr(5));
      continue;
    }
    if (line.rfind("Columns:", 0) == 0) {
      size_t position = 8;
      const int columns = readInteger(line, position);
      palette.columns = columns >= 1 && columns <= 30 ? columns : 0;
      continue;
    }
    size_t position = 0;
    const int red = readInteger(line, position);
    const int green = readInteger(line, position);
    const int blue = readInteger(line, position);
    if (red < 0 || green < 0 || blue < 0 || red > 255 || green > 255 || blue > 255) {
      continue;
    }
    PaletteColor color;
    color.red = static_cast<float>(red) / 255.0F;
    color.green = static_cast<float>(green) / 255.0F;
    color.blue = static_cast<float>(blue) / 255.0F;
    color.name = trim(line.substr(position));
    addColor(palette, std::move(color));
  }
  if (header) {
    error = "notPalette";
    return std::nullopt;
  }
  return palette;
}

}  // namespace

void labToSrgb(double lightness, double a, double b, float& red, float& green, float& blue) {
  const double fy = (lightness + 16.0) / 116.0;
  const double fx = fy + a / 500.0;
  const double fz = fy - b / 200.0;
  constexpr double kEpsilon = 216.0 / 24389.0;
  constexpr double kKappa = 24389.0 / 27.0;
  const auto inverse = [&](double t) {
    return t * t * t > kEpsilon ? t * t * t : (116.0 * t - 16.0) / kKappa;
  };
  const double x50 = 0.96422 * inverse(fx);
  const double y50 = lightness > kKappa * kEpsilon ? fy * fy * fy : lightness / kKappa;
  const double z50 = 0.82521 * inverse(fz);
  const double x = 0.9555766 * x50 - 0.0230393 * y50 + 0.0631636 * z50;
  const double y = -0.0282895 * x50 + 1.0099416 * y50 + 0.0210077 * z50;
  const double z = 0.0122982 * x50 - 0.0204830 * y50 + 1.3299098 * z50;
  const double linear[3] = {3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
                            -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
                            0.0556434 * x - 0.2040259 * y + 1.0572252 * z};
  float encoded[3];
  for (int channel = 0; channel < 3; ++channel) {
    const double value = std::clamp(linear[channel], 0.0, 1.0);
    encoded[channel] =
        clamp01(value <= 0.0031308 ? value * 12.92 : 1.055 * std::pow(value, 1.0 / 2.4) - 0.055);
  }
  red = encoded[0];
  green = encoded[1];
  blue = encoded[2];
}

std::optional<ImportedPalette> readPalette(const std::vector<uint8_t>& bytes, std::string& error,
                                           const color::CmykColorConverter* cmyk) {
  if (bytes.size() >= 4 && std::memcmp(bytes.data(), "ASEF", 4) == 0) {
    return readAse(bytes, error, cmyk);
  }
  size_t offset =
      bytes.size() >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF ? 3 : 0;
  while (offset < bytes.size() &&
         (bytes[offset] == ' ' || bytes[offset] == '\r' || bytes[offset] == '\n')) {
    ++offset;
  }
  constexpr char kGimp[] = "GIMP Palette";
  if (bytes.size() - offset >= sizeof(kGimp) - 1 &&
      std::memcmp(bytes.data() + offset, kGimp, sizeof(kGimp) - 1) == 0) {
    return readGpl(
        std::vector<uint8_t>(bytes.begin() + static_cast<std::ptrdiff_t>(offset), bytes.end()),
        error);
  }
  if (looksLikeAco(bytes)) {
    return readAco(bytes, error, cmyk);
  }
  error = "notPalette";
  return std::nullopt;
}

std::optional<std::string> paletteJson(const std::vector<uint8_t>& bytes, std::string& error,
                                       const color::CmykColorConverter* cmyk) {
  const auto palette = readPalette(bytes, error, cmyk);
  if (!palette) {
    return std::nullopt;
  }
  nlohmann::json swatches = nlohmann::json::array();
  for (const PaletteColor& color : palette->colors) {
    swatches.push_back(
        {{"color", {color.red, color.green, color.blue, color.alpha}}, {"name", color.name}});
  }
  nlohmann::json warnings = nlohmann::json::array();
  for (const PaletteWarning& warning : palette->warnings) {
    warnings.push_back({{"code", warning.code}, {"detail", warning.detail}});
  }
  const nlohmann::json root = {{"format", palette->format},
                               {"name", palette->name},
                               {"columns", palette->columns},
                               {"swatches", swatches},
                               {"warnings", warnings}};
  return root.dump(-1, ' ', false, nlohmann::json::error_handler_t::replace);
}

}  // namespace nanquim::formats
