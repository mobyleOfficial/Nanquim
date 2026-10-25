#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace nanquim::reference {

struct Rgba8Image {
  int32_t width = 0;
  int32_t height = 0;
  std::vector<uint8_t> pixels;
};

std::optional<Rgba8Image> readPng(const std::string& path);
bool writePng(const std::string& path, const Rgba8Image& image);
bool writeGrayPng(const std::string& path, int32_t width, int32_t height,
                  const std::vector<uint8_t>& pixels);

}  // namespace nanquim::reference
