#include "formats/animation_writer.h"

#include <gtest/gtest.h>
#include <miniz.h>

#define STB_IMAGE_STATIC
#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_GIF
#define STBI_NO_STDIO
#include <stb_image.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "formats/color_quantizer.h"

namespace nanquim::formats {
namespace {

std::string temporaryFile(const std::string& name) {
  const auto folder = std::filesystem::temp_directory_path() / "nanquim_animation";
  std::filesystem::create_directories(folder);
  return (folder / name).u8string();
}

std::vector<uint8_t> readFile(const std::string& path) {
  std::ifstream file(std::filesystem::u8path(path), std::ios::binary);
  return {std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
}

using Frame = std::vector<uint8_t>;

Frame flatFrame(int32_t width, int32_t height, int32_t index) {
  Frame frame(static_cast<size_t>(width) * height * 4);
  for (int32_t y = 0; y < height; ++y) {
    for (int32_t x = 0; x < width; ++x) {
      uint8_t* pixel = &frame[(static_cast<size_t>(y) * width + x) * 4];
      const bool square = x >= index * 6 && x < index * 6 + 12 && y >= 10 && y < 22;
      const bool corner = x < 8 && y < 8;
      const std::array<uint8_t, 4> color =
          corner ? std::array<uint8_t, 4>{0, 0, 0, 0}
          : square ? std::array<uint8_t, 4>{200, 40, 30, 255}
                   : std::array<uint8_t, 4>{static_cast<uint8_t>(250 - (y / 8) * 10), 245, 230, 255};
      std::copy(color.begin(), color.end(), pixel);
    }
  }
  return frame;
}

Frame gradientFrame(int32_t width, int32_t height) {
  Frame frame(static_cast<size_t>(width) * height * 4);
  for (int32_t y = 0; y < height; ++y) {
    for (int32_t x = 0; x < width; ++x) {
      uint8_t* pixel = &frame[(static_cast<size_t>(y) * width + x) * 4];
      pixel[0] = static_cast<uint8_t>(x * 255 / (width - 1));
      pixel[1] = static_cast<uint8_t>(y * 255 / (height - 1));
      pixel[2] = static_cast<uint8_t>(128 + std::lround(100 * std::sin(x * 0.05 + y * 0.03)));
      pixel[3] = 255;
    }
  }
  return frame;
}

struct DecodedGif {
  int32_t width = 0;
  int32_t height = 0;
  std::vector<Frame> frames;
  std::vector<int32_t> delays;
};

DecodedGif decodeGif(const std::vector<uint8_t>& bytes) {
  DecodedGif gif;
  int* delays = nullptr;
  int frames = 0;
  int components = 0;
  stbi_uc* pixels = stbi_load_gif_from_memory(bytes.data(), static_cast<int>(bytes.size()), &delays,
                                              &gif.width, &gif.height, &frames, &components, 4);
  if (pixels == nullptr) {
    return gif;
  }
  const size_t frameBytes = static_cast<size_t>(gif.width) * gif.height * 4;
  for (int index = 0; index < frames; ++index) {
    gif.frames.emplace_back(pixels + frameBytes * index, pixels + frameBytes * (index + 1));
    gif.delays.push_back(delays[index]);
  }
  stbi_image_free(pixels);
  stbi_image_free(delays);
  return gif;
}

struct DecodedApng {
  bool valid = false;
  uint32_t frameCount = 0;
  uint32_t plays = 0;
  std::vector<Frame> frames;
  std::vector<int32_t> delays;
};

uint32_t bigEndian(const uint8_t* bytes) {
  return (uint32_t{bytes[0]} << 24U) | (uint32_t{bytes[1]} << 16U) | (uint32_t{bytes[2]} << 8U) | bytes[3];
}

Frame unfilter(const std::vector<uint8_t>& data, uint32_t width, uint32_t height) {
  const size_t rowBytes = size_t{width} * 4;
  Frame image(rowBytes * height);
  std::vector<uint8_t> previous(rowBytes, 0);
  for (uint32_t y = 0; y < height; ++y) {
    const uint8_t type = data[y * (rowBytes + 1)];
    const uint8_t* filtered = &data[y * (rowBytes + 1) + 1];
    uint8_t* row = &image[y * rowBytes];
    for (size_t index = 0; index < rowBytes; ++index) {
      const int32_t left = index >= 4 ? row[index - 4] : 0;
      const int32_t up = previous[index];
      const int32_t upLeft = index >= 4 ? previous[index - 4] : 0;
      int32_t predictor = 0;
      if (type == 1) {
        predictor = left;
      } else if (type == 2) {
        predictor = up;
      } else if (type == 3) {
        predictor = (left + up) / 2;
      } else if (type == 4) {
        const int32_t estimate = left + up - upLeft;
        const int32_t toLeft = std::abs(estimate - left);
        const int32_t toUp = std::abs(estimate - up);
        const int32_t toUpLeft = std::abs(estimate - upLeft);
        predictor = toLeft <= toUp && toLeft <= toUpLeft ? left : toUp <= toUpLeft ? up : upLeft;
      }
      row[index] = static_cast<uint8_t>(filtered[index] + predictor);
    }
    std::copy(row, row + rowBytes, previous.begin());
  }
  return image;
}

DecodedApng decodeApng(const std::vector<uint8_t>& bytes) {
  DecodedApng apng;
  if (bytes.size() < 8 || bytes[1] != 'P') {
    return apng;
  }
  uint32_t width = 0;
  uint32_t height = 0;
  uint32_t expectedSequence = 0;
  std::vector<uint8_t> compressed;
  const auto endFrame = [&]() -> bool {
    if (compressed.empty()) {
      return true;
    }
    std::vector<uint8_t> raw((size_t{width} * 4 + 1) * height);
    mz_ulong size = static_cast<mz_ulong>(raw.size());
    if (mz_uncompress(raw.data(), &size, compressed.data(), static_cast<mz_ulong>(compressed.size())) != MZ_OK ||
        size != raw.size()) {
      return false;
    }
    apng.frames.push_back(unfilter(raw, width, height));
    compressed.clear();
    return true;
  };
  size_t offset = 8;
  while (offset + 12 <= bytes.size()) {
    const uint32_t length = bigEndian(&bytes[offset]);
    const std::string type(reinterpret_cast<const char*>(&bytes[offset + 4]), 4);
    const uint8_t* data = &bytes[offset + 8];
    const uint32_t crc = bigEndian(data + length);
    if (static_cast<uint32_t>(mz_crc32(MZ_CRC32_INIT, &bytes[offset + 4], length + 4)) != crc) {
      return apng;
    }
    if (type == "IHDR") {
      width = bigEndian(data);
      height = bigEndian(data + 4);
    } else if (type == "acTL") {
      apng.frameCount = bigEndian(data);
      apng.plays = bigEndian(data + 4);
    } else if (type == "fcTL") {
      if (!endFrame() || bigEndian(data) != expectedSequence++) {
        return apng;
      }
      apng.delays.push_back(static_cast<int32_t>((uint32_t{data[20]} << 8U) | data[21]) * 1000 /
                            static_cast<int32_t>((uint32_t{data[22]} << 8U) | data[23]));
    } else if (type == "IDAT") {
      compressed.insert(compressed.end(), data, data + length);
    } else if (type == "fdAT") {
      if (bigEndian(data) != expectedSequence++) {
        return apng;
      }
      compressed.insert(compressed.end(), data + 4, data + length);
    } else if (type == "IEND") {
      apng.valid = endFrame();
      return apng;
    }
    offset += 12 + length;
  }
  return apng;
}

constexpr int32_t kWidth = 64;
constexpr int32_t kHeight = 40;

TEST(AnimationWriterTest, GifFlatArtDecodesExactlyWithDelaysAndTransparency) {
  for (const Dither dither : {Dither::None, Dither::Ordered, Dither::FloydSteinberg}) {
    const std::string path = temporaryFile("flat.gif");
    AnimationOptions options{kWidth, kHeight, 0, dither};
    auto writer = createGifWriter(path, options);
    ASSERT_TRUE(writer);
    std::vector<Frame> frames;
    const std::array<int32_t, 4> durations{83, 250, 83, 83};
    for (int32_t index = 0; index < 4; ++index) {
      frames.push_back(flatFrame(kWidth, kHeight, index));
      ASSERT_TRUE(writer->addFrame(frames.back().data(), durations[static_cast<size_t>(index)]));
    }
    ASSERT_TRUE(writer->finish()) << writer->error();
    const DecodedGif gif = decodeGif(readFile(path));
    ASSERT_EQ(gif.frames.size(), 4U);
    EXPECT_EQ(gif.width, kWidth);
    EXPECT_EQ(gif.delays, (std::vector<int32_t>{80, 250, 90, 80}));
    for (size_t index = 0; index < frames.size(); ++index) {
      for (size_t pixel = 0; pixel < frames[index].size() / 4; ++pixel) {
        const uint8_t* expected = &frames[index][pixel * 4];
        const uint8_t* actual = &gif.frames[index][pixel * 4];
        ASSERT_EQ(actual[3], expected[3]) << "frame " << index << " pixel " << pixel;
        if (expected[3] == 255) {
          ASSERT_EQ(std::vector<uint8_t>(actual, actual + 3), std::vector<uint8_t>(expected, expected + 3))
              << "frame " << index << " pixel " << pixel;
        }
      }
    }
  }
}

TEST(AnimationWriterTest, GifLoopsAndPlaysOnce) {
  const Frame frame = flatFrame(kWidth, kHeight, 0);
  const auto write = [&](int32_t plays) {
    const std::string path = temporaryFile("loop.gif");
    auto writer = createGifWriter(path, AnimationOptions{kWidth, kHeight, plays});
    writer->addFrame(frame.data(), 100);
    writer->finish();
    return readFile(path);
  };
  const auto contains = [](const std::vector<uint8_t>& bytes, const std::string& text) {
    return std::search(bytes.begin(), bytes.end(), text.begin(), text.end()) != bytes.end();
  };
  const std::vector<uint8_t> forever = write(0);
  EXPECT_TRUE(contains(forever, "NETSCAPE2.0"));
  const std::string netscape = "NETSCAPE2.0";
  const auto at = std::search(forever.begin(), forever.end(), netscape.begin(), netscape.end()) - forever.begin();
  EXPECT_EQ(forever[static_cast<size_t>(at) + 13], 0);
  EXPECT_EQ(forever[static_cast<size_t>(at) + 14], 0);
  EXPECT_FALSE(contains(write(1), "NETSCAPE2.0"));
  const std::vector<uint8_t> three = write(3);
  const auto threeAt = std::search(three.begin(), three.end(), netscape.begin(), netscape.end()) - three.begin();
  EXPECT_EQ(three[static_cast<size_t>(threeAt) + 13], 2);
}

double blockError(const Frame& expected, const Frame& actual, int32_t width, int32_t height) {
  double total = 0;
  int32_t blocks = 0;
  for (int32_t top = 0; top + 8 <= height; top += 8) {
    for (int32_t left = 0; left + 8 <= width; left += 8) {
      for (size_t channel = 0; channel < 3; ++channel) {
        double wanted = 0;
        double got = 0;
        for (int32_t y = top; y < top + 8; ++y) {
          for (int32_t x = left; x < left + 8; ++x) {
            const size_t index = (static_cast<size_t>(y) * width + x) * 4 + channel;
            wanted += expected[index];
            got += actual[index];
          }
        }
        total += std::abs(wanted - got) / 64;
      }
      ++blocks;
    }
  }
  return total / (blocks * 3);
}

TEST(AnimationWriterTest, GifGradientStaysCloseAndDitheringKeepsAverages) {
  constexpr int32_t kSide = 128;
  const Frame frame = gradientFrame(kSide, kSide);
  std::array<double, 3> errors{};
  const std::array<Dither, 3> modes{Dither::None, Dither::Ordered, Dither::FloydSteinberg};
  for (size_t mode = 0; mode < modes.size(); ++mode) {
    const std::string path = temporaryFile("gradient.gif");
    auto writer = createGifWriter(path, AnimationOptions{kSide, kSide, 0, modes[mode]});
    ASSERT_TRUE(writer->addFrame(frame.data(), 40));
    ASSERT_TRUE(writer->finish());
    const DecodedGif gif = decodeGif(readFile(path));
    ASSERT_EQ(gif.frames.size(), 1U);
    errors[mode] = blockError(frame, gif.frames[0], kSide, kSide);
  }
  EXPECT_LT(errors[0], 4.0);
  EXPECT_LT(errors[2], errors[0]);
  EXPECT_LT(errors[1], 4.0);
}

TEST(AnimationWriterTest, ApngDecodesExactlyWithDelaysAndPlays) {
  const std::string path = temporaryFile("frames.png");
  auto writer = createApngWriter(path, AnimationOptions{kWidth, kHeight, 2});
  ASSERT_TRUE(writer);
  std::vector<Frame> frames{flatFrame(kWidth, kHeight, 0), gradientFrame(kWidth, kHeight),
                            flatFrame(kWidth, kHeight, 3)};
  const std::array<int32_t, 3> durations{42, 125, 1000};
  for (size_t index = 0; index < frames.size(); ++index) {
    ASSERT_TRUE(writer->addFrame(frames[index].data(), durations[index]));
  }
  ASSERT_TRUE(writer->finish()) << writer->error();
  const DecodedApng apng = decodeApng(readFile(path));
  ASSERT_TRUE(apng.valid);
  EXPECT_EQ(apng.frameCount, 3U);
  EXPECT_EQ(apng.plays, 2U);
  EXPECT_EQ(apng.delays, (std::vector<int32_t>{42, 125, 1000}));
  ASSERT_EQ(apng.frames.size(), 3U);
  for (size_t index = 0; index < frames.size(); ++index) {
    EXPECT_EQ(apng.frames[index], frames[index]) << "frame " << index;
  }
}

TEST(AnimationWriterTest, RejectsBadSizesAndEmptyAnimations) {
  EXPECT_FALSE(createGifWriter(temporaryFile("bad.gif"), AnimationOptions{0, 10}));
  EXPECT_FALSE(createApngWriter(temporaryFile("bad.png"), AnimationOptions{10, 70000}));
  auto writer = createGifWriter(temporaryFile("empty.gif"), AnimationOptions{10, 10});
  EXPECT_FALSE(writer->finish());
  EXPECT_EQ(writer->error(), "noFrames");
}

TEST(ColorQuantizerTest, FewColorsAreKeptExactlyAndManyAreLimited) {
  const Frame flat = flatFrame(kWidth, kHeight, 2);
  bool exact = false;
  const auto palette = choosePalette(flat.data(), flat.size() / 4, 255, 32768, &exact);
  EXPECT_TRUE(exact);
  EXPECT_EQ(palette.size(), 6U);
  const Frame gradient = gradientFrame(128, 128);
  const auto many = choosePalette(gradient.data(), gradient.size() / 4, 255, 32768, &exact);
  EXPECT_FALSE(exact);
  EXPECT_LE(many.size(), 255U);
  EXPECT_GT(many.size(), 200U);
  PaletteMapper mapper(many);
  for (size_t index = 0; index < many.size(); ++index) {
    EXPECT_EQ(mapper.nearest(many[index][0], many[index][1], many[index][2]), index);
  }
}

}  // namespace
}  // namespace nanquim::formats
