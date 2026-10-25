#include <gtest/gtest.h>

#include <cmath>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <nlohmann/json.hpp>
#include <string>
#include <vector>

#include "formats/palette_reader.h"

namespace nanquim::formats {
namespace {

const std::filesystem::path kFixtures =
    std::filesystem::path(NANQUIM_GOLDENS_DIRECTORY) / "palettes" / "fixtures";

std::vector<uint8_t> readFile(const std::filesystem::path& path) {
  std::ifstream input(path, std::ios::binary);
  return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

uint32_t bigEndian(const std::vector<uint8_t>& bytes, size_t offset, size_t size) {
  uint32_t value = 0;
  for (size_t index = 0; index < size && offset + index < bytes.size(); ++index) {
    value = (value << 8U) | bytes[offset + index];
  }
  return value;
}

std::vector<std::string> storedSpaces(const std::vector<uint8_t>& bytes) {
  std::vector<std::string> spaces;
  if (bytes.size() >= 4 && std::string(bytes.begin(), bytes.begin() + 4) == "ASEF") {
    const uint32_t blocks = bigEndian(bytes, 8, 4);
    size_t offset = 12;
    for (uint32_t block = 0; block < blocks && offset + 6 <= bytes.size(); ++block) {
      const uint32_t type = bigEndian(bytes, offset, 2);
      const uint32_t length = bigEndian(bytes, offset + 2, 4);
      const size_t data = offset + 6;
      if (type == 0x0001) {
        const uint32_t nameUnits = bigEndian(bytes, data, 2);
        const size_t model = data + 2 + static_cast<size_t>(nameUnits) * 2;
        if (model + 4 <= bytes.size()) {
          const std::string tag(bytes.begin() + static_cast<std::ptrdiff_t>(model),
                                bytes.begin() + static_cast<std::ptrdiff_t>(model + 4));
          spaces.push_back(tag == "Gray" ? "gray" : tag);
        }
      }
      offset = data + length;
    }
    return spaces;
  }
  const uint32_t count = bigEndian(bytes, 2, 2);
  for (uint32_t index = 0; index < count; ++index) {
    const uint32_t space = bigEndian(bytes, 4 + static_cast<size_t>(index) * 10, 2);
    spaces.push_back(space == 8 ? "gray" : "space " + std::to_string(space));
  }
  return spaces;
}

void checkFixture(const std::string& name, const nlohmann::json& expected) {
  const std::filesystem::path path = kFixtures / name;
  if (!std::filesystem::exists(path)) {
    GTEST_SKIP() << name << " is not there yet (goldens/palettes/fixtures/README.md)";
  }
  const std::vector<uint8_t> bytes = readFile(path);
  std::string error;
  const auto palette = readPalette(bytes, error);
  ASSERT_TRUE(palette.has_value()) << error;
  const auto& swatches = expected.at("swatches");
  ASSERT_EQ(palette->colors.size(), swatches.size()) << name;
  const std::vector<std::string> spaces = storedSpaces(bytes);
  for (size_t index = 0; index < swatches.size(); ++index) {
    const auto& want = swatches[index];
    const PaletteColor& color = palette->colors[index];
    const std::string label = want.at("name").get<std::string>();
    if (!color.name.empty()) {
      EXPECT_EQ(color.name, label) << name << " swatch " << index;
    }
    const auto& rgb = want.at("rgb");
    const double tolerance = want.at("tolerance").get<double>();
    const float channels[] = {color.red, color.green, color.blue};
    for (size_t channel = 0; channel < 3; ++channel) {
      EXPECT_NEAR(channels[channel], rgb[channel].get<double>(), tolerance)
          << name << " " << label << " channel " << channel;
    }
    if (want.contains("space")) {
      ASSERT_LT(index, spaces.size()) << name;
      EXPECT_EQ(spaces[index], want.at("space").get<std::string>())
          << name << " " << label << " is stored as " << spaces[index] << ", not gray";
    }
  }
}

nlohmann::json expected() {
  std::ifstream input(kFixtures / "expected.json");
  return nlohmann::json::parse(input);
}

TEST(PaletteFixtures, PhotoshopAco) { checkFixture("photoshop.aco", expected()); }

TEST(PaletteFixtures, PhotoshopAse) { checkFixture("photoshop.ase", expected()); }

TEST(PaletteFixtures, IllustratorAse) { checkFixture("illustrator.ase", expected()); }

}  // namespace
}  // namespace nanquim::formats
