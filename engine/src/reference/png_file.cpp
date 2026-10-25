#include "png_file.h"

#include <cstring>
#include <fstream>
#include <iterator>

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include <stb_image.h>
#include <stb_image_write.h>

namespace nanquim::reference {

std::optional<Rgba8Image> readPng(const std::string& path) {
  int width = 0;
  int height = 0;
  int channels = 0;
  std::ifstream stream(path, std::ios::binary);
  if (!stream) {
    return std::nullopt;
  }
  const std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(stream)),
                                   std::istreambuf_iterator<char>());
  stbi_uc* data = stbi_load_from_memory(bytes.data(), static_cast<int>(bytes.size()), &width,
                                        &height, &channels, 4);
  if (data == nullptr) {
    return std::nullopt;
  }
  Rgba8Image image;
  image.width = width;
  image.height = height;
  image.pixels.resize(static_cast<size_t>(width) * static_cast<size_t>(height) * 4);
  std::memcpy(image.pixels.data(), data, image.pixels.size());
  stbi_image_free(data);
  return image;
}

bool writePng(const std::string& path, const Rgba8Image& image) {
  return stbi_write_png(path.c_str(), image.width, image.height, 4, image.pixels.data(),
                        image.width * 4) != 0;
}

bool writeGrayPng(const std::string& path, int32_t width, int32_t height,
                  const std::vector<uint8_t>& pixels) {
  return stbi_write_png(path.c_str(), width, height, 1, pixels.data(), width) != 0;
}

}  // namespace nanquim::reference
