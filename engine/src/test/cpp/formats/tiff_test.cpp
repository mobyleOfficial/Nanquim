#include <gtest/gtest.h>
#include <miniz.h>

#include <algorithm>
#include <array>
#include <filesystem>
#include <functional>

#include "formats/icc_profile.h"
#include "formats/source_reader.h"
#include "formats/tiff_reader.h"
#include "formats/tiff_writer.h"
#include "io/document_tile.h"
#include "io/file_system.h"

namespace nanquim::formats {
namespace {

using canvas::kTileBytesRgba8;
using canvas::kTileSizePixels;
using Pixel = std::array<uint8_t, 4>;

std::string temporaryFolder(const std::string& name) {
  const auto folder = std::filesystem::temp_directory_path() / ("nanquim_tiff_" + name);
  std::filesystem::remove_all(folder);
  std::filesystem::create_directories(folder);
  return folder.u8string();
}

struct Imported {
  ImportResult result;
  std::string folder;

  [[nodiscard]] Pixel premultipliedAt(int32_t x, int32_t y) const {
    std::vector<uint8_t> tile(kTileBytesRgba8);
    const std::string surface =
        io::joinPath(io::joinPath(folder, "layers"), result.document.layers.at(0).uuid);
    if (io::readDocumentTile(surface, {x / kTileSizePixels, y / kTileSizePixels},
                             io::DocumentTileFormat::Rgba8,
                             tile.data()) != io::TileReadResult::Ok) {
      return {0, 0, 0, 0};
    }
    const size_t offset =
        (static_cast<size_t>(y % kTileSizePixels) * kTileSizePixels + x % kTileSizePixels) * 4;
    return {tile[offset], tile[offset + 1], tile[offset + 2], tile[offset + 3]};
  }
};

Imported import(const std::vector<uint8_t>& bytes, const std::string& name) {
  Imported imported;
  imported.folder = temporaryFolder(name);
  MemoryInputSource source(bytes);
  imported.result = importTiff(source, imported.folder, {});
  return imported;
}

Imported importSeed(const std::string& name) {
  const auto bytes =
      io::readFile(std::string(NANQUIM_FUZZ_CORPUS_DIRECTORY) + "/tiff/" + name + ".tif", 1 << 20);
  EXPECT_TRUE(bytes.has_value()) << name;
  return import(bytes.value_or(std::vector<uint8_t>()), name);
}

uint8_t premultiply(uint32_t channel, uint32_t alpha) {
  return static_cast<uint8_t>((channel * alpha + 127U) / 255U);
}

TEST(TiffReader, LzwWithPredictorInStrips) {
  const Imported imported = importSeed("rgb_lzw_predictor");
  ASSERT_TRUE(imported.result.ok) << imported.result.errorCode << imported.result.errorDetail;
  EXPECT_EQ(imported.result.document.width, 21);
  EXPECT_EQ(imported.result.document.source, "tiff");
  for (const auto& [x, y] : std::vector<std::pair<int32_t, int32_t>>{{0, 0}, {5, 7}, {20, 12}}) {
    EXPECT_EQ(imported.premultipliedAt(x, y),
              (Pixel{static_cast<uint8_t>(x * 12 + y), static_cast<uint8_t>(y * 19),
                     static_cast<uint8_t>((x ^ y) * 5), 255}))
        << x << "," << y;
  }
}

TEST(TiffReader, SixteenBitBigEndianDeflateWithAlpha) {
  const Imported imported = importSeed("rgba16_deflate_big_endian");
  ASSERT_TRUE(imported.result.ok) << imported.result.errorCode;
  const auto byte = [](uint32_t value) {
    return static_cast<uint8_t>((value * 255U + 32767U) / 65535U);
  };
  const int32_t x = 5;
  const int32_t y = 7;
  const uint8_t alpha = byte((x + y) * 2000);
  EXPECT_EQ(imported.premultipliedAt(x, y),
            (Pixel{premultiply(byte(x * 3000), alpha), premultiply(byte(y * 5000), alpha),
                   premultiply(byte(40000), alpha), alpha}));
}

TEST(TiffReader, GrayPaletteCmykAndBilevel) {
  const Imported gray = importSeed("gray_packbits");
  ASSERT_TRUE(gray.result.ok);
  EXPECT_EQ(gray.premultipliedAt(5, 7), (Pixel{35, 35, 35, 255}));
  EXPECT_EQ(gray.premultipliedAt(20, 12), (Pixel{240, 240, 240, 255}));

  const Imported palette = importSeed("palette4");
  ASSERT_TRUE(palette.result.ok);
  const auto byte = [](uint32_t value) {
    return static_cast<uint8_t>((value * 255U + 32767U) / 65535U);
  };
  EXPECT_EQ(palette.premultipliedAt(6, 3),
            (Pixel{byte(6 * 4096), byte(65535 - 6 * 4096), byte(30000), 255}));

  const Imported cmyk = importSeed("cmyk");
  ASSERT_TRUE(cmyk.result.ok);
  ASSERT_EQ(cmyk.result.document.warnings.size(), 1U);
  EXPECT_EQ(cmyk.result.document.warnings[0].code, "cmykConverted");
  EXPECT_EQ(cmyk.premultipliedAt(5, 7),
            (Pixel{static_cast<uint8_t>((205 * 225 + 127) / 255),
                   static_cast<uint8_t>((185 * 225 + 127) / 255), 225, 255}));

  const Imported bilevel = importSeed("bilevel_white_is_zero");
  ASSERT_TRUE(bilevel.result.ok);
  EXPECT_EQ(bilevel.premultipliedAt(0, 7), (Pixel{0, 0, 0, 255}));
  EXPECT_EQ(bilevel.premultipliedAt(1, 7), (Pixel{255, 255, 255, 255}));
}

class LittleEndianWriter {
 public:
  void u16(uint32_t value) {
    bytes.push_back(static_cast<uint8_t>(value));
    bytes.push_back(static_cast<uint8_t>(value >> 8U));
  }
  void u32(uint32_t value) {
    u16(value & 0xFFFFU);
    u16(value >> 16U);
  }
  std::vector<uint8_t> bytes;
};

struct TestImage {
  int32_t width = 0;
  int32_t height = 0;
  int32_t samples = 3;
  uint32_t photometric = 2;
  std::optional<uint32_t> extraSample;
  bool planar = false;
  int32_t tileSize = 0;
  uint32_t compression = 1;
  std::function<uint8_t(int32_t x, int32_t y, int32_t sample)> sample;
  std::vector<std::pair<uint16_t, uint32_t>> extraShorts;
};

std::vector<uint8_t> compress(std::vector<uint8_t> data, uint32_t compression) {
  if (compression == 8) {
    mz_ulong size = mz_compressBound(static_cast<mz_ulong>(data.size()));
    std::vector<uint8_t> out(size);
    mz_compress(out.data(), &size, data.data(), static_cast<mz_ulong>(data.size()));
    out.resize(size);
    return out;
  }
  if (compression == 32773) {
    std::vector<uint8_t> out;
    encodePackBits(data.data(), data.size(), out);
    return out;
  }
  return data;
}

std::vector<uint8_t> buildTiff(const TestImage& image) {
  const int32_t planes = image.planar ? image.samples : 1;
  const int32_t perPlane = image.planar ? 1 : image.samples;
  const int32_t blockWidth = image.tileSize > 0 ? image.tileSize : image.width;
  const int32_t blockHeight = image.tileSize > 0 ? image.tileSize : image.height;
  const int32_t across = (image.width + blockWidth - 1) / blockWidth;
  const int32_t down = (image.height + blockHeight - 1) / blockHeight;
  std::vector<std::vector<uint8_t>> chunks;
  for (int32_t plane = 0; plane < planes; ++plane) {
    for (int32_t row = 0; row < down; ++row) {
      for (int32_t column = 0; column < across; ++column) {
        std::vector<uint8_t> data;
        for (int32_t y = row * blockHeight; y < (row + 1) * blockHeight; ++y) {
          if (image.tileSize == 0 && y >= image.height) {
            break;
          }
          for (int32_t x = column * blockWidth; x < (column + 1) * blockWidth; ++x) {
            for (int32_t sample = 0; sample < perPlane; ++sample) {
              const bool inside = x < image.width && y < image.height;
              data.push_back(inside ? image.sample(x, y, image.planar ? plane : sample) : 0);
            }
          }
        }
        chunks.push_back(compress(std::move(data), image.compression));
      }
    }
  }
  LittleEndianWriter out;
  out.bytes = {'I', 'I', 42, 0, 0, 0, 0, 0};
  std::vector<uint32_t> offsets;
  std::vector<uint32_t> sizes;
  for (const auto& chunk : chunks) {
    offsets.push_back(static_cast<uint32_t>(out.bytes.size()));
    sizes.push_back(static_cast<uint32_t>(chunk.size()));
    out.bytes.insert(out.bytes.end(), chunk.begin(), chunk.end());
  }
  struct Entry {
    uint16_t tag;
    uint16_t type;
    std::vector<uint32_t> values;
  };
  std::vector<Entry> entries{
      {256, 4, {static_cast<uint32_t>(image.width)}},
      {257, 4, {static_cast<uint32_t>(image.height)}},
      {258, 3, std::vector<uint32_t>(static_cast<size_t>(image.samples), 8)},
      {259, 3, {image.compression}},
      {262, 3, {image.photometric}},
      {277, 3, {static_cast<uint32_t>(image.samples)}},
      {284, 3, {image.planar ? 2U : 1U}},
  };
  if (image.tileSize > 0) {
    entries.push_back({322, 4, {static_cast<uint32_t>(image.tileSize)}});
    entries.push_back({323, 4, {static_cast<uint32_t>(image.tileSize)}});
    entries.push_back({324, 4, offsets});
    entries.push_back({325, 4, sizes});
  } else {
    entries.push_back({273, 4, offsets});
    entries.push_back({278, 4, {static_cast<uint32_t>(image.height)}});
    entries.push_back({279, 4, sizes});
  }
  if (image.extraSample) {
    entries.push_back({338, 3, {*image.extraSample}});
  }
  for (const auto& [tag, value] : image.extraShorts) {
    entries.push_back({tag, 3, {value}});
  }
  std::sort(entries.begin(), entries.end(),
            [](const Entry& a, const Entry& b) { return a.tag < b.tag; });
  std::vector<uint32_t> pointers;
  for (const Entry& entry : entries) {
    const size_t size = entry.values.size() * (entry.type == 3 ? 2 : 4);
    pointers.push_back(static_cast<uint32_t>(out.bytes.size()));
    if (size > 4) {
      for (const uint32_t value : entry.values) {
        entry.type == 3 ? out.u16(value) : out.u32(value);
      }
    }
  }
  if (out.bytes.size() % 2 != 0) {
    out.bytes.push_back(0);
  }
  const auto directory = static_cast<uint32_t>(out.bytes.size());
  out.u16(static_cast<uint32_t>(entries.size()));
  for (size_t index = 0; index < entries.size(); ++index) {
    const Entry& entry = entries[index];
    out.u16(entry.tag);
    out.u16(entry.type);
    out.u32(static_cast<uint32_t>(entry.values.size()));
    const size_t size = entry.values.size() * (entry.type == 3 ? 2 : 4);
    if (size > 4) {
      out.u32(pointers[index]);
    } else {
      const size_t before = out.bytes.size();
      for (const uint32_t value : entry.values) {
        entry.type == 3 ? out.u16(value) : out.u32(value);
      }
      out.bytes.resize(before + 4, 0);
    }
  }
  out.u32(0);
  for (int index = 0; index < 4; ++index) {
    out.bytes[4 + static_cast<size_t>(index)] = static_cast<uint8_t>(directory >> (8 * index));
  }
  return out.bytes;
}

uint8_t pattern(int32_t x, int32_t y, int32_t sample) {
  return static_cast<uint8_t>(x * 3 + y * 7 + sample * 50);
}

TEST(TiffReader, TilesAndPlanarSamples) {
  for (const bool planar : {false, true}) {
    for (const uint32_t compression : {1U, 8U, 32773U}) {
      TestImage image;
      image.width = 300;
      image.height = 70;
      image.samples = 4;
      image.extraSample = 2;
      image.planar = planar;
      image.tileSize = 32;
      image.compression = compression;
      image.sample = [](int32_t x, int32_t y, int32_t sample) {
        return sample == 3 ? uint8_t{255} : pattern(x, y, sample);
      };
      const Imported imported =
          import(buildTiff(image), "tiles" + std::to_string(planar) + std::to_string(compression));
      ASSERT_TRUE(imported.result.ok) << imported.result.errorCode << imported.result.errorDetail;
      for (const auto& [x, y] :
           std::vector<std::pair<int32_t, int32_t>>{{0, 0}, {33, 31}, {299, 69}, {290, 64}}) {
        EXPECT_EQ(imported.premultipliedAt(x, y),
                  (Pixel{pattern(x, y, 0), pattern(x, y, 1), pattern(x, y, 2), 255}))
            << planar << " " << compression << " " << x << "," << y;
      }
    }
  }
}

TEST(TiffReader, AssociatedAlphaIsUnpremultiplied) {
  TestImage image;
  image.width = 4;
  image.height = 2;
  image.samples = 4;
  image.extraSample = 1;
  image.sample = [](int32_t, int32_t, int32_t sample) {
    return sample == 3 ? uint8_t{128} : uint8_t{64};
  };
  const Imported imported = import(buildTiff(image), "associated");
  ASSERT_TRUE(imported.result.ok);
  EXPECT_EQ(imported.premultipliedAt(1, 1), (Pixel{64, 64, 64, 128}));
}

TEST(TiffReader, UnspecifiedExtraSamplesAreNotAlpha) {
  TestImage image;
  image.width = 3;
  image.height = 3;
  image.samples = 4;
  image.extraSample = 0;
  image.sample = [](int32_t, int32_t, int32_t sample) {
    return sample == 3 ? uint8_t{0} : uint8_t{9};
  };
  const Imported imported = import(buildTiff(image), "unspecified");
  ASSERT_TRUE(imported.result.ok);
  EXPECT_EQ(imported.premultipliedAt(2, 2), (Pixel{9, 9, 9, 255}));
}

TEST(TiffReader, WarningsAndRejections) {
  TestImage image;
  image.width = 2;
  image.height = 2;
  image.sample = pattern;
  image.extraShorts = {{274, 3}};
  const Imported rotated = import(buildTiff(image), "orientation");
  ASSERT_TRUE(rotated.result.ok);
  ASSERT_EQ(rotated.result.document.warnings.size(), 1U);
  EXPECT_EQ(rotated.result.document.warnings[0].code, "orientationIgnored");

  image.extraShorts.clear();
  image.compression = 7;
  EXPECT_EQ(import(buildTiff(image), "jpeg").result.errorCode, "unsupportedCompression");
  image.compression = 1;
  image.photometric = 6;
  EXPECT_EQ(import(buildTiff(image), "ycbcr").result.errorCode, "unsupportedColorMode");
  image.photometric = 2;
  image.width = 20000;
  image.sample = [](int32_t, int32_t, int32_t) { return uint8_t{0}; };
  EXPECT_EQ(import(buildTiff(image), "large").result.errorCode, "canvasTooLarge");

  EXPECT_EQ(import({'I', 'I', 43, 0, 8, 0, 0, 0}, "bigtiff").result.errorCode,
            "unsupportedVersion");
  EXPECT_EQ(import({'P', 'K', 3, 4}, "zip").result.errorCode, "notTiff");
  EXPECT_EQ(import({'I', 'I', 42, 0, 200, 0, 0, 0}, "short").result.errorCode, "corrupt");
}

TEST(TiffReader, TruncatedFilesFailCleanly) {
  const std::vector<uint8_t> bytes =
      io::readFile(std::string(NANQUIM_FUZZ_CORPUS_DIRECTORY) + "/tiff/rgb_lzw_predictor.tif",
                   1 << 20)
          .value_or(std::vector<uint8_t>());
  ASSERT_FALSE(bytes.empty());
  for (size_t length = 0; length + 4 < bytes.size(); length += 7) {
    const std::vector<uint8_t> truncated(bytes.begin(),
                                         bytes.begin() + static_cast<std::ptrdiff_t>(length));
    const Imported imported = import(truncated, "truncated");
    EXPECT_FALSE(imported.result.ok) << length;
  }
}

class PatternComposite final : public CompositeSource {
 public:
  explicit PatternComposite(int32_t width) : width_(width) {}
  static Pixel at(int32_t x, int32_t y) {
    return {static_cast<uint8_t>(x), static_cast<uint8_t>(y), static_cast<uint8_t>(x * y),
            static_cast<uint8_t>(x < 50 ? 255 : (x + y) % 256)};
  }
  bool readRows(int32_t top, int32_t count, uint8_t* rgba) override {
    for (int32_t y = top; y < top + count; ++y) {
      for (int32_t x = 0; x < width_; ++x) {
        const Pixel pixel = at(x, y);
        std::copy(pixel.begin(), pixel.end(),
                  rgba + (static_cast<size_t>(y - top) * width_ + x) * 4);
      }
    }
    return true;
  }

 private:
  int32_t width_;
};

TEST(TiffWriter, ExportReadsBackExactly) {
  const int32_t width = 300;
  const int32_t height = 100;
  PatternComposite composite(width);
  const std::string output = io::joinPath(temporaryFolder("export_out"), "out.tif");
  const ExportResult exported = exportTiff(width, height, 300, composite, output);
  ASSERT_TRUE(exported.ok) << exported.errorCode;
  const Imported imported =
      import(io::readFile(output, 1 << 26).value_or(std::vector<uint8_t>()), "export_again");
  ASSERT_TRUE(imported.result.ok) << imported.result.errorCode << imported.result.errorDetail;
  EXPECT_DOUBLE_EQ(imported.result.document.dpi, 300);
  EXPECT_TRUE(imported.result.document.warnings.empty());
  for (int32_t y = 0; y < height; y += 3) {
    for (int32_t x = 0; x < width; x += 7) {
      const Pixel source = PatternComposite::at(x, y);
      ASSERT_EQ(imported.premultipliedAt(x, y),
                (Pixel{premultiply(source[0], source[3]), premultiply(source[1], source[3]),
                       premultiply(source[2], source[3]), source[3]}))
          << x << "," << y;
    }
  }
}

class DeepComposite final : public CompositeSource {
 public:
  explicit DeepComposite(int32_t width) : width_(width) {}
  static std::array<uint16_t, 4> at(int32_t x, int32_t y) {
    return {static_cast<uint16_t>(x * 211 + y * 7), static_cast<uint16_t>(y * 333 + 5),
            static_cast<uint16_t>((x * y * 97) & 0xFFFF),
            static_cast<uint16_t>(x < 50 ? 65535 : 30000 + x * 17)};
  }
  bool readRows(int32_t top, int32_t count, uint8_t* rgba) override {
    for (int32_t y = top; y < top + count; ++y) {
      for (int32_t x = 0; x < width_; ++x) {
        const auto pixel = at(x, y);
        for (size_t channel = 0; channel < 4; ++channel) {
          rgba[(static_cast<size_t>(y - top) * width_ + x) * 4 + channel] =
              static_cast<uint8_t>((pixel[channel] * 255U + 32767U) / 65535U);
        }
      }
    }
    return true;
  }
  [[nodiscard]] bool hasDeepRows() const override { return true; }
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
};

TEST(TiffWriter, SixteenBitExportWithProfile) {
  const int32_t width = 200;
  const int32_t height = 70;
  DeepComposite composite(width);
  std::vector<uint8_t> profile = srgbIccProfile();
  profile[100] ^= 0x5A;
  const std::string output = io::joinPath(temporaryFolder("deep_out"), "out.tif");
  ASSERT_TRUE(exportTiff(width, height, 72, composite, output, true, profile).ok);
  const std::vector<uint8_t> file = io::readFile(output, 1 << 26).value_or(std::vector<uint8_t>());
  EXPECT_NE(std::search(file.begin(), file.end(), profile.begin(), profile.end()), file.end());
  const Imported imported = import(file, "deep_again");
  ASSERT_TRUE(imported.result.ok) << imported.result.errorCode << imported.result.errorDetail;
  for (int32_t y = 0; y < height; y += 3) {
    for (int32_t x = 0; x < width; x += 7) {
      const auto source = DeepComposite::at(x, y);
      const Pixel read = imported.premultipliedAt(x, y);
      const double alpha = source[3] / 65535.0;
      for (size_t channel = 0; channel < 3; ++channel) {
        ASSERT_NEAR(read[channel], source[channel] / 65535.0 * alpha * 255.0, 1.01)
            << x << "," << y << " " << channel;
      }
      ASSERT_NEAR(read[3], alpha * 255.0, 0.51) << x << "," << y;
    }
  }
}

TEST(TiffReader, AdoptsTheEmbeddedRgbProfile) {
  const auto p3 =
      io::readFile(io::joinPath(NANQUIM_PROFILES_DIRECTORY, "DisplayP3-v4.icc"), 1 << 20);
  ASSERT_TRUE(p3.has_value());
  PatternComposite composite(40);
  const std::string output = io::joinPath(temporaryFolder("p3_out"), "out.tif");
  ASSERT_TRUE(exportTiff(40, 20, 72, composite, output, false, *p3).ok);
  const Imported imported =
      import(io::readFile(output, 1 << 26).value_or(std::vector<uint8_t>()), "p3_again");
  ASSERT_TRUE(imported.result.ok);
  EXPECT_EQ(imported.result.document.iccProfile, *p3);
  EXPECT_NE(imported.result.document.colorProfileName.find("P3"), std::string::npos);
  EXPECT_TRUE(imported.result.document.warnings.empty());
  EXPECT_EQ(io::readFile(io::joinPath(imported.folder, "profile.icc"), 1 << 20), p3);
  const std::string json = importJsonText(imported.result.document);
  EXPECT_NE(json.find("\"colorProfile\""), std::string::npos);
  EXPECT_NE(json.find("profile.icc"), std::string::npos);
  const auto cmyk = io::readFile(
      io::joinPath(NANQUIM_PROFILES_DIRECTORY, "CGATS001Compat-v2-micro.icc"), 1 << 20);
  ASSERT_TRUE(cmyk.has_value());
  const std::string cmykOutput = io::joinPath(temporaryFolder("cmyk_profile_out"), "out.tif");
  ASSERT_TRUE(exportTiff(40, 20, 72, composite, cmykOutput, false, *cmyk).ok);
  const Imported ignored =
      import(io::readFile(cmykOutput, 1 << 26).value_or(std::vector<uint8_t>()), "cmyk_again");
  ASSERT_TRUE(ignored.result.ok);
  EXPECT_TRUE(ignored.result.document.iccProfile.empty());
  ASSERT_EQ(ignored.result.document.warnings.size(), 1U);
  EXPECT_EQ(ignored.result.document.warnings[0].code, "colorProfileIgnored");
}

TEST(TiffWriter, RejectsBadSizesAndPaths) {
  PatternComposite composite(4);
  EXPECT_EQ(exportTiff(0, 4, 72, composite, "unused.tif").errorCode, "invalidDocument");
  EXPECT_EQ(
      exportTiff(4, 4, 72, composite, io::joinPath(temporaryFolder("bad_path"), "missing/out.tif"))
          .errorCode,
      "writeFailed");
}

}  // namespace
}  // namespace nanquim::formats
