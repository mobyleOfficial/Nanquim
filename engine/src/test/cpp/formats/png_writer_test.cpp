#include "formats/png_writer.h"

#include <gtest/gtest.h>
#include <miniz.h>

#define STB_IMAGE_STATIC
#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#define STBI_NO_STDIO
#include <stb_image.h>

#include <algorithm>
#include <array>
#include <filesystem>
#include <string>
#include <vector>

#include "formats/icc_profile.h"
#include "io/file_system.h"

namespace nanquim::formats {
namespace {

std::string temporaryFolder(const std::string& name) {
  const auto folder = std::filesystem::temp_directory_path() / ("nanquim_png_" + name);
  std::filesystem::remove_all(folder);
  std::filesystem::create_directories(folder);
  return folder.u8string();
}

class DeepPattern final : public CompositeSource {
 public:
  DeepPattern(int32_t width, bool deep) : width_(width), deep_(deep) {}

  static std::array<uint16_t, 4> at(int32_t x, int32_t y) {
    return {static_cast<uint16_t>(x * 211 + y * 7), static_cast<uint16_t>(y * 333 + 5),
            static_cast<uint16_t>((x * y * 97) & 0xFFFF),
            static_cast<uint16_t>(x < 20 ? 65535 : (x * 977 + y * 131) & 0xFFFF)};
  }
  static uint8_t narrow(uint16_t value) {
    return static_cast<uint8_t>((value * 255U + 32767U) / 65535U);
  }

  bool readRows(int32_t top, int32_t count, uint8_t* rgba) override {
    for (int32_t y = top; y < top + count; ++y) {
      for (int32_t x = 0; x < width_; ++x) {
        const auto pixel = at(x, y);
        for (size_t channel = 0; channel < 4; ++channel) {
          rgba[(static_cast<size_t>(y - top) * width_ + x) * 4 + channel] = narrow(pixel[channel]);
        }
      }
    }
    return true;
  }
  [[nodiscard]] bool hasDeepRows() const override { return deep_; }
  bool readDeepRows(int32_t top, int32_t count, uint16_t* rgba) override {
    for (int32_t y = top; y < top + count; ++y) {
      for (int32_t x = 0; x < width_; ++x) {
        const auto pixel = at(x, y);
        std::copy(pixel.begin(), pixel.end(),
                  rgba + (static_cast<size_t>(y - top) * width_ + x) * 4);
      }
    }
    return true;
  }

 private:
  int32_t width_;
  bool deep_;
};

struct Chunk {
  std::string type;
  std::vector<uint8_t> data;
};

uint32_t bigEndian(const uint8_t* bytes) {
  return (static_cast<uint32_t>(bytes[0]) << 24U) | (static_cast<uint32_t>(bytes[1]) << 16U) |
         (static_cast<uint32_t>(bytes[2]) << 8U) | bytes[3];
}

std::vector<Chunk> chunksOf(const std::vector<uint8_t>& file) {
  std::vector<Chunk> chunks;
  size_t offset = 8;
  while (offset + 12 <= file.size()) {
    const uint32_t length = bigEndian(&file[offset]);
    Chunk chunk;
    chunk.type.assign(reinterpret_cast<const char*>(&file[offset + 4]), 4);
    chunk.data.assign(file.begin() + static_cast<std::ptrdiff_t>(offset + 8),
                      file.begin() + static_cast<std::ptrdiff_t>(offset + 8 + length));
    const mz_ulong crc =
        mz_crc32(mz_crc32(MZ_CRC32_INIT, &file[offset + 4], 4),
                 chunk.data.empty() ? &file[offset + 4] : chunk.data.data(), chunk.data.size());
    EXPECT_EQ(static_cast<uint32_t>(crc), bigEndian(&file[offset + 8 + length])) << chunk.type;
    chunks.push_back(std::move(chunk));
    offset += 12 + length;
  }
  EXPECT_EQ(offset, file.size());
  return chunks;
}

std::vector<uint8_t> exportAndRead(int32_t width, int32_t height, bool deep,
                                   const std::vector<uint8_t>& profile, const std::string& name) {
  DeepPattern composite(width, true);
  const std::string output = io::joinPath(temporaryFolder(name), "out.png");
  const ExportResult result = exportPng(width, height, 300, composite, output, deep, profile);
  EXPECT_TRUE(result.ok) << result.errorCode << " " << result.errorDetail;
  return io::readFile(output, int64_t{1} << 28).value_or(std::vector<uint8_t>{});
}

TEST(PngWriter, EightBitImageDecodesExactly) {
  const int32_t width = 300;
  const int32_t height = 70;
  const std::vector<uint8_t> file = exportAndRead(width, height, false, {}, "eight");
  int decodedWidth = 0;
  int decodedHeight = 0;
  int channels = 0;
  stbi_uc* pixels = stbi_load_from_memory(file.data(), static_cast<int>(file.size()), &decodedWidth,
                                          &decodedHeight, &channels, 4);
  ASSERT_NE(pixels, nullptr) << stbi_failure_reason();
  EXPECT_EQ(decodedWidth, width);
  EXPECT_EQ(decodedHeight, height);
  EXPECT_EQ(channels, 4);
  for (int32_t y = 0; y < height; ++y) {
    for (int32_t x = 0; x < width; ++x) {
      const auto expected = DeepPattern::at(x, y);
      for (size_t channel = 0; channel < 4; ++channel) {
        ASSERT_EQ(pixels[(static_cast<size_t>(y) * width + x) * 4 + channel],
                  DeepPattern::narrow(expected[channel]))
            << x << "," << y << " " << channel;
      }
    }
  }
  stbi_image_free(pixels);
  const auto chunks = chunksOf(file);
  ASSERT_GE(chunks.size(), 4U);
  EXPECT_EQ(chunks.front().type, "IHDR");
  EXPECT_EQ(chunks.front().data[8], 8);
  EXPECT_EQ(chunks.front().data[9], 6);
  EXPECT_EQ(chunks.back().type, "IEND");
}

TEST(PngWriter, SixteenBitImageDecodesExactly) {
  const int32_t width = 129;
  const int32_t height = 40;
  const std::vector<uint8_t> file = exportAndRead(width, height, true, {}, "sixteen");
  int decodedWidth = 0;
  int decodedHeight = 0;
  int channels = 0;
  stbi_us* pixels = stbi_load_16_from_memory(file.data(), static_cast<int>(file.size()),
                                             &decodedWidth, &decodedHeight, &channels, 4);
  ASSERT_NE(pixels, nullptr) << stbi_failure_reason();
  EXPECT_EQ(chunksOf(file).front().data[8], 16);
  for (int32_t y = 0; y < height; ++y) {
    for (int32_t x = 0; x < width; ++x) {
      const auto expected = DeepPattern::at(x, y);
      for (size_t channel = 0; channel < 4; ++channel) {
        ASSERT_EQ(pixels[(static_cast<size_t>(y) * width + x) * 4 + channel], expected[channel])
            << x << "," << y << " " << channel;
      }
    }
  }
  stbi_image_free(pixels);
}

TEST(PngWriter, ProfileAndResolution) {
  std::vector<uint8_t> profile(3000);
  for (size_t index = 0; index < profile.size(); ++index) {
    profile[index] = static_cast<uint8_t>(index * 31 + 7);
  }
  for (const bool custom : {false, true}) {
    const auto chunks = chunksOf(exportAndRead(
        8, 8, false, custom ? profile : std::vector<uint8_t>{}, custom ? "profile" : "srgb"));
    const auto icc = std::find_if(chunks.begin(), chunks.end(),
                                  [](const Chunk& chunk) { return chunk.type == "iCCP"; });
    ASSERT_NE(icc, chunks.end());
    const auto name = std::find(icc->data.begin(), icc->data.end(), 0);
    ASSERT_NE(name, icc->data.end());
    ASSERT_EQ(*(name + 1), 0);
    const size_t start = static_cast<size_t>(name - icc->data.begin()) + 2;
    std::vector<uint8_t> inflated(1 << 20);
    mz_ulong size = static_cast<mz_ulong>(inflated.size());
    ASSERT_EQ(mz_uncompress(inflated.data(), &size, icc->data.data() + start,
                            static_cast<mz_ulong>(icc->data.size() - start)),
              MZ_OK);
    inflated.resize(size);
    EXPECT_EQ(inflated, custom ? profile : srgbIccProfile());
    const auto physical = std::find_if(chunks.begin(), chunks.end(),
                                       [](const Chunk& chunk) { return chunk.type == "pHYs"; });
    ASSERT_NE(physical, chunks.end());
    EXPECT_EQ(bigEndian(physical->data.data()), 11811U);
    EXPECT_EQ(physical->data[8], 1);
    EXPECT_LT(icc - chunks.begin(),
              std::find_if(chunks.begin(), chunks.end(), [](const Chunk& chunk) {
                return chunk.type == "IDAT";
              }) - chunks.begin());
  }
}

TEST(PngWriter, RejectsBadSizesAndPaths) {
  DeepPattern composite(4, false);
  EXPECT_EQ(exportPng(0, 4, 72, composite, "unused.png").errorCode, "invalidDocument");
  EXPECT_EQ(
      exportPng(4, 4, 72, composite, io::joinPath(temporaryFolder("bad_path"), "missing/out.png"))
          .errorCode,
      "writeFailed");
}

}  // namespace
}  // namespace nanquim::formats
