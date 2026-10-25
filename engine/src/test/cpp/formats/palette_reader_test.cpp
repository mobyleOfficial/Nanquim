#include "formats/palette_reader.h"

#include <gtest/gtest.h>

#include <cstring>
#include <nlohmann/json.hpp>
#include <string>
#include <vector>

#include "io/file_system.h"

namespace nanquim::formats {
namespace {

class Bytes {
 public:
  Bytes& u16(uint16_t value) {
    data.push_back(static_cast<uint8_t>(value >> 8U));
    data.push_back(static_cast<uint8_t>(value & 0xFFU));
    return *this;
  }
  Bytes& u32(uint32_t value) {
    u16(static_cast<uint16_t>(value >> 16U));
    return u16(static_cast<uint16_t>(value & 0xFFFFU));
  }
  Bytes& f32(float value) {
    uint32_t bits = 0;
    std::memcpy(&bits, &value, sizeof(bits));
    return u32(bits);
  }
  Bytes& text(const std::string& value) {
    data.insert(data.end(), value.begin(), value.end());
    return *this;
  }
  Bytes& utf16(const std::u16string& value, bool count32) {
    const auto units = static_cast<uint32_t>(value.size() + 1);
    if (count32) {
      u32(units);
    } else {
      u16(static_cast<uint16_t>(units));
    }
    for (char16_t unit : value) {
      u16(static_cast<uint16_t>(unit));
    }
    return u16(0);
  }
  std::vector<uint8_t> data;
};

ImportedPalette read(const std::vector<uint8_t>& bytes) {
  std::string error;
  auto palette = readPalette(bytes, error);
  EXPECT_TRUE(palette.has_value()) << error;
  return palette.value_or(ImportedPalette{});
}

bool hasWarning(const ImportedPalette& palette, const std::string& code) {
  for (const auto& warning : palette.warnings) {
    if (warning.code == code) {
      return true;
    }
  }
  return false;
}

TEST(PaletteReader, AcoVersion1RgbHsbGrayCmyk) {
  Bytes aco;
  aco.u16(1).u16(4);
  aco.u16(0).u16(65535).u16(32768).u16(0).u16(0);
  aco.u16(1).u16(21845).u16(65535).u16(65535).u16(0);
  aco.u16(8).u16(2500).u16(0).u16(0).u16(0);
  aco.u16(2).u16(65535).u16(0).u16(65535).u16(65535);
  const ImportedPalette palette = read(aco.data);
  EXPECT_EQ(palette.format, "aco");
  ASSERT_EQ(palette.colors.size(), 4U);
  EXPECT_FLOAT_EQ(palette.colors[0].red, 1.0F);
  EXPECT_NEAR(palette.colors[0].green, 0.5, 1e-4);
  EXPECT_NEAR(palette.colors[1].red, 0.0, 1e-3);
  EXPECT_NEAR(palette.colors[1].green, 1.0, 1e-6);
  EXPECT_NEAR(palette.colors[2].red, 0.75, 1e-6);
  EXPECT_NEAR(palette.colors[3].red, 1.0, 1e-6);
  EXPECT_NEAR(palette.colors[3].green, 0.0, 1e-6);
  EXPECT_NEAR(palette.colors[3].blue, 1.0, 1e-6);
  EXPECT_TRUE(hasWarning(palette, "approximateCmyk"));
}

TEST(PaletteReader, AcoVersion2NamesWin) {
  Bytes aco;
  aco.u16(1).u16(1).u16(0).u16(65535).u16(0).u16(0).u16(0);
  aco.u16(2).u16(1).u16(0).u16(65535).u16(0).u16(0).u16(0).utf16(u"Vermelho é", true);
  const ImportedPalette palette = read(aco.data);
  ASSERT_EQ(palette.colors.size(), 1U);
  EXPECT_EQ(palette.colors[0].name, "Vermelho \xc3\xa9");
}

TEST(PaletteReader, AcoBrokenNamesKeepVersion1) {
  Bytes aco;
  aco.u16(1).u16(1).u16(0).u16(0).u16(0).u16(65535).u16(0);
  aco.u16(2).u16(1).u16(0).u16(0).u16(0).u16(65535).u16(0).u32(1000);
  const ImportedPalette palette = read(aco.data);
  ASSERT_EQ(palette.colors.size(), 1U);
  EXPECT_FLOAT_EQ(palette.colors[0].blue, 1.0F);
}

TEST(PaletteReader, AcoUnsupportedSpaceIsReportedAndSkipped) {
  Bytes aco;
  aco.u16(1).u16(2).u16(3).u16(1).u16(2).u16(3).u16(4).u16(0).u16(0).u16(0).u16(0).u16(0);
  const ImportedPalette palette = read(aco.data);
  EXPECT_EQ(palette.colors.size(), 1U);
  EXPECT_TRUE(hasWarning(palette, "unsupportedColorSpace"));
}

TEST(PaletteReader, AseRgbCmykLabGrayAndGroups) {
  Bytes ase;
  ase.text("ASEF").u16(1).u16(0).u32(6);
  auto block = [&](uint16_t type, const Bytes& body) {
    ase.u16(type).u32(static_cast<uint32_t>(body.data.size()));
    ase.data.insert(ase.data.end(), body.data.begin(), body.data.end());
  };
  Bytes group;
  group.utf16(u"Grupo", false);
  block(0xC001, group);
  Bytes rgb;
  rgb.utf16(u"Azul", false).text("RGB ").f32(0.0F).f32(0.25F).f32(1.0F).u16(2);
  block(0x0001, rgb);
  Bytes cmyk;
  cmyk.utf16(u"Ciano", false).text("CMYK").f32(1.0F).f32(0.0F).f32(0.0F).f32(0.0F).u16(2);
  block(0x0001, cmyk);
  Bytes lab;
  lab.utf16(u"Branco", false).text("LAB ").f32(1.0F).f32(0.0F).f32(0.0F).u16(2);
  block(0x0001, lab);
  Bytes gray;
  gray.utf16(u"Cinza", false).text("Gray").f32(0.5F).u16(2);
  block(0x0001, gray);
  block(0xC002, Bytes());
  const ImportedPalette palette = read(ase.data);
  EXPECT_EQ(palette.format, "ase");
  ASSERT_EQ(palette.colors.size(), 4U);
  EXPECT_EQ(palette.colors[0].name, "Azul");
  EXPECT_FLOAT_EQ(palette.colors[0].green, 0.25F);
  EXPECT_NEAR(palette.colors[1].red, 0.0, 1e-6);
  EXPECT_NEAR(palette.colors[1].green, 1.0, 1e-6);
  EXPECT_NEAR(palette.colors[2].red, 1.0, 2e-3);
  EXPECT_NEAR(palette.colors[2].blue, 1.0, 2e-3);
  EXPECT_FLOAT_EQ(palette.colors[3].green, 0.5F);
  EXPECT_TRUE(hasWarning(palette, "approximateCmyk"));
  EXPECT_TRUE(hasWarning(palette, "approximateLab"));
}

TEST(PaletteReader, CmykSwatchesUseTheGivenProfile) {
  Bytes ase;
  ase.text("ASEF").u16(1).u16(0).u32(1);
  Bytes cmyk;
  cmyk.utf16(u"Ciano", false).text("CMYK").f32(1.0F).f32(0.0F).f32(0.0F).f32(0.0F).u16(2);
  ase.u16(0x0001).u32(static_cast<uint32_t>(cmyk.data.size()));
  ase.data.insert(ase.data.end(), cmyk.data.begin(), cmyk.data.end());
  const auto profile = io::readFile(io::joinPath(NANQUIM_PROFILES_DIRECTORY, "NanquimCMYK-v4.icc"),
                                    color::kMaximumProfileBytes);
  ASSERT_TRUE(profile.has_value());
  std::string error;
  const auto converter = color::CmykColorConverter::create({}, *profile, error);
  ASSERT_NE(converter, nullptr) << error;
  const auto palette = readPalette(ase.data, error, converter.get());
  ASSERT_TRUE(palette.has_value()) << error;
  ASSERT_EQ(palette->colors.size(), 1U);
  EXPECT_LT(palette->colors[0].red, 0.3F);
  EXPECT_GT(palette->colors[0].green, 0.4F);
  EXPECT_LT(palette->colors[0].green, 0.9F);
  EXPECT_GT(palette->colors[0].blue, 0.8F);
  EXPECT_TRUE(hasWarning(*palette, "cmykByProfile"));
  EXPECT_FALSE(hasWarning(*palette, "approximateCmyk"));
}

TEST(PaletteReader, GimpPalette) {
  const std::string text =
      "\xEF\xBB\xBFGIMP Palette\r\nName: Minha paleta\nColumns: 4\n# comment\n"
      "255   0   0\tRed\n  0 128 255 Sky blue\nnot a color\n300 0 0 too big\n";
  const ImportedPalette palette = read(std::vector<uint8_t>(text.begin(), text.end()));
  EXPECT_EQ(palette.format, "gpl");
  EXPECT_EQ(palette.name, "Minha paleta");
  EXPECT_EQ(palette.columns, 4);
  ASSERT_EQ(palette.colors.size(), 2U);
  EXPECT_EQ(palette.colors[0].name, "Red");
  EXPECT_EQ(palette.colors[1].name, "Sky blue");
  EXPECT_NEAR(palette.colors[1].green, 128.0 / 255.0, 1e-6);
}

TEST(PaletteReader, LabReferenceValues) {
  float red = 0;
  float green = 0;
  float blue = 0;
  labToSrgb(100, 0, 0, red, green, blue);
  EXPECT_NEAR(red, 1.0, 2e-3);
  EXPECT_NEAR(green, 1.0, 2e-3);
  EXPECT_NEAR(blue, 1.0, 2e-3);
  labToSrgb(50, 0, 0, red, green, blue);
  EXPECT_NEAR(red, 0.4663, 3e-3);
  EXPECT_NEAR(green, 0.4663, 3e-3);
  EXPECT_NEAR(blue, 0.4663, 3e-3);
  labToSrgb(0, 0, 0, red, green, blue);
  EXPECT_FLOAT_EQ(red, 0.0F);
}

TEST(PaletteReader, TooManySwatchesAreCut) {
  Bytes aco;
  aco.u16(1).u16(310);
  for (int index = 0; index < 310; ++index) {
    aco.u16(0).u16(0).u16(0).u16(0).u16(0);
  }
  const ImportedPalette palette = read(aco.data);
  EXPECT_EQ(palette.colors.size(), kMaximumPaletteSwatches);
  EXPECT_TRUE(hasWarning(palette, "tooManySwatches"));
}

TEST(PaletteReader, NotPalettesAreRefused) {
  for (const std::string& text :
       {std::string(), std::string("hello"), std::string("ASEF\x00\x02", 6), std::string("GIMP"),
        std::string("\x00\x05\x00\x09", 4)}) {
    std::string error;
    EXPECT_FALSE(readPalette(std::vector<uint8_t>(text.begin(), text.end()), error).has_value())
        << text.size();
  }
  Bytes aco;
  aco.u16(1).u16(1).u16(0).u16(0).u16(0).u16(0);
  std::string error;
  EXPECT_FALSE(readPalette(aco.data, error).has_value());
}

TEST(PaletteReader, JsonHasSwatchesAndWarnings) {
  Bytes aco;
  aco.u16(1).u16(1).u16(2).u16(0).u16(0).u16(0).u16(0);
  std::string error;
  const auto text = paletteJson(aco.data, error);
  ASSERT_TRUE(text.has_value()) << error;
  const auto json = nlohmann::json::parse(*text);
  EXPECT_EQ(json["format"], "aco");
  EXPECT_EQ(json["swatches"].size(), 1U);
  EXPECT_EQ(json["swatches"][0]["color"].size(), 4U);
  EXPECT_EQ(json["warnings"][0]["code"], "approximateCmyk");
}

}  // namespace
}  // namespace nanquim::formats
