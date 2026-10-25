#include "formats/pdf_writer.h"

#include <gtest/gtest.h>
#include <miniz.h>

#define STB_IMAGE_STATIC
#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_JPEG
#define STBI_NO_STDIO
#include <stb_image.h>

#include <cstdlib>
#include <filesystem>
#include <functional>
#include <map>
#include <regex>
#include <sstream>

#include "io/file_system.h"

namespace nanquim::formats {
namespace {

using Pixel = std::array<uint8_t, 4>;

std::string temporaryFolder(const std::string& name) {
  const auto folder = std::filesystem::temp_directory_path() / ("nanquim_pdf_" + name);
  std::filesystem::remove_all(folder);
  std::filesystem::create_directories(folder);
  return folder.u8string();
}

class FunctionPage final : public CompositeSource {
 public:
  FunctionPage(int32_t width, std::function<Pixel(int32_t, int32_t)> pixel)
      : width_(width), pixel_(std::move(pixel)) {}
  bool readRows(int32_t top, int32_t count, uint8_t* rgba) override {
    for (int32_t y = top; y < top + count; ++y) {
      for (int32_t x = 0; x < width_; ++x) {
        const Pixel value = pixel_(x, y);
        std::copy(value.begin(), value.end(),
                  rgba + (static_cast<size_t>(y - top) * width_ + x) * 4);
      }
    }
    return true;
  }

 private:
  int32_t width_;
  std::function<Pixel(int32_t, int32_t)> pixel_;
};

struct PdfObject {
  std::string dictionary;
  std::vector<uint8_t> stream;
};

std::map<int, PdfObject> readPdf(const std::vector<uint8_t>& bytes) {
  const std::string text(bytes.begin(), bytes.end());
  EXPECT_EQ(text.rfind("%PDF-1.4", 0), 0U);
  const size_t startxref = text.rfind("startxref\n");
  EXPECT_NE(startxref, std::string::npos);
  const size_t xref = std::stoull(text.substr(startxref + 10));
  EXPECT_EQ(text.compare(xref, 5, "xref\n"), 0);
  std::istringstream table(text.substr(xref + 5));
  int first = 0;
  int count = 0;
  table >> first >> count;
  std::map<int, size_t> offsets;
  for (int object = 0; object < count; ++object) {
    std::string offset;
    std::string generation;
    std::string kind;
    table >> offset >> generation >> kind;
    if (kind == "n") {
      offsets[object] = std::stoull(offset);
    }
  }
  std::map<int, std::string> bodies;
  for (const auto& [object, offset] : offsets) {
    const std::string header = std::to_string(object) + " 0 obj\n";
    EXPECT_EQ(text.compare(offset, header.size(), header), 0) << "object " << object;
    const size_t begin = offset + header.size();
    bodies[object] = text.substr(begin, text.find("endobj", begin) - begin);
  }
  std::map<int, PdfObject> objects;
  for (const auto& [object, offset] : offsets) {
    PdfObject result;
    const std::string& body = bodies[object];
    const size_t streamAt = body.find(">>\nstream\n");
    result.dictionary = body.substr(0, streamAt == std::string::npos ? body.size() : streamAt + 2);
    if (streamAt != std::string::npos) {
      std::smatch match;
      size_t length = 0;
      if (std::regex_search(result.dictionary, match, std::regex(R"(/Length (\d+) 0 R)"))) {
        length = std::stoull(bodies[std::stoi(match[1])]);
      } else if (std::regex_search(result.dictionary, match, std::regex(R"(/Length (\d+))"))) {
        length = std::stoull(match[1]);
      }
      const size_t data = offset + std::to_string(object).size() + 7 + streamAt + 10;
      result.stream.assign(bytes.begin() + static_cast<std::ptrdiff_t>(data),
                           bytes.begin() + static_cast<std::ptrdiff_t>(data + length));
      EXPECT_EQ(text.compare(data + length, 11, "\nendstream\n"), 0) << "object " << object;
    }
    objects[object] = result;
  }
  return objects;
}

std::vector<uint8_t> flateSamples(const std::vector<uint8_t>& stream, size_t size, int32_t width,
                                  int32_t components) {
  std::vector<uint8_t> samples(size);
  auto length = static_cast<mz_ulong>(size);
  EXPECT_EQ(
      mz_uncompress(samples.data(), &length, stream.data(), static_cast<mz_ulong>(stream.size())),
      MZ_OK);
  EXPECT_EQ(length, size);
  const size_t row = static_cast<size_t>(width) * static_cast<size_t>(components);
  for (size_t start = 0; start < size; start += row) {
    for (auto index = static_cast<size_t>(components); index < row; ++index) {
      samples[start + index] =
          static_cast<uint8_t>(samples[start + index] + samples[start + index - components]);
    }
  }
  return samples;
}

std::vector<int> imagesOf(const std::map<int, PdfObject>& objects, bool masks) {
  std::vector<int> found;
  for (const auto& [number, object] : objects) {
    if (object.dictionary.find("/Subtype /Image") != std::string::npos &&
        (object.dictionary.find("/DeviceGray") != std::string::npos) == masks) {
      found.push_back(number);
    }
  }
  return found;
}

Pixel gradient(int32_t x, int32_t y) {
  return {static_cast<uint8_t>(x), static_cast<uint8_t>(y), static_cast<uint8_t>(x ^ y),
          static_cast<uint8_t>(x < 40 ? 255 : (x * 3 + y) % 256)};
}

TEST(PdfWriter, FlattenedPageWithFlateAndTransparency) {
  const int32_t width = 150;
  const int32_t height = 90;
  FunctionPage page(width, gradient);
  const std::string output = io::joinPath(temporaryFolder("flate"), "out.pdf");
  PdfExportOptions options;
  options.title = "Desenho ação 🎨";
  const ExportResult result = exportPdf(width, height, 300, {&page}, options, output);
  ASSERT_TRUE(result.ok) << result.errorCode << result.errorDetail;
  const auto objects = readPdf(io::readFile(output, 1 << 26).value_or(std::vector<uint8_t>()));

  const std::vector<int> images = imagesOf(objects, false);
  const std::vector<int> masks = imagesOf(objects, true);
  ASSERT_EQ(images.size(), 1U);
  ASSERT_EQ(masks.size(), 1U);
  const PdfObject& image = objects.at(images[0]);
  EXPECT_NE(image.dictionary.find("/SMask " + std::to_string(masks[0]) + " 0 R"),
            std::string::npos);
  EXPECT_NE(image.dictionary.find("/ICCBased"), std::string::npos);
  const auto rgb = flateSamples(image.stream, size_t{150} * 90 * 3, width, 3);
  const auto alpha = flateSamples(objects.at(masks[0]).stream, size_t{150} * 90, width, 1);
  for (int32_t y = 0; y < height; ++y) {
    for (int32_t x = 0; x < width; ++x) {
      const size_t index = static_cast<size_t>(y) * width + x;
      const Pixel expected = gradient(x, y);
      ASSERT_EQ((Pixel{rgb[index * 3], rgb[index * 3 + 1], rgb[index * 3 + 2], alpha[index]}),
                expected)
          << x << "," << y;
    }
  }
  bool foundPage = false;
  for (const auto& [number, object] : objects) {
    if (object.dictionary.find("/Type /Page ") != std::string::npos) {
      EXPECT_NE(object.dictionary.find("/MediaBox [0 0 36.0000 21.6000]"), std::string::npos);
      foundPage = true;
    }
    if (object.dictionary.find("/Producer") != std::string::npos) {
      EXPECT_NE(object.dictionary.find("/Title <FEFF00440065007300650"), std::string::npos);
      EXPECT_NE(object.dictionary.find("D83CDFA8>"), std::string::npos);
    }
  }
  EXPECT_TRUE(foundPage);
}

TEST(PdfWriter, OpaquePagesHaveNoMaskAndJpegDecodes) {
  const int32_t width = 64;
  const int32_t height = 48;
  FunctionPage page(width, [](int32_t x, int32_t y) {
    return Pixel{static_cast<uint8_t>(x * 4), static_cast<uint8_t>(y * 5), 128, 255};
  });
  const std::string output = io::joinPath(temporaryFolder("jpeg"), "out.pdf");
  PdfExportOptions options;
  options.jpeg = true;
  ASSERT_TRUE(exportPdf(width, height, 72, {&page, &page}, options, output).ok);
  const auto objects = readPdf(io::readFile(output, 1 << 26).value_or(std::vector<uint8_t>()));
  const std::vector<int> images = imagesOf(objects, false);
  ASSERT_EQ(images.size(), 2U);
  for (const int number : images) {
    const PdfObject& image = objects.at(number);
    EXPECT_EQ(image.dictionary.find("/SMask"), std::string::npos);
    EXPECT_NE(image.dictionary.find("/DCTDecode"), std::string::npos);
    int decodedWidth = 0;
    int decodedHeight = 0;
    int channels = 0;
    stbi_uc* decoded =
        stbi_load_from_memory(image.stream.data(), static_cast<int>(image.stream.size()),
                              &decodedWidth, &decodedHeight, &channels, 3);
    ASSERT_NE(decoded, nullptr);
    EXPECT_EQ(decodedWidth, width);
    EXPECT_EQ(decodedHeight, height);
    int worst = 0;
    for (int32_t y = 0; y < height; ++y) {
      for (int32_t x = 0; x < width; ++x) {
        const stbi_uc* pixel = decoded + (static_cast<size_t>(y) * width + x) * 3;
        worst = std::max({worst, std::abs(pixel[0] - x * 4), std::abs(pixel[1] - y * 5),
                          std::abs(pixel[2] - 128)});
      }
    }
    EXPECT_LE(worst, 12);
    stbi_image_free(decoded);
  }
}

TEST(PdfWriter, LayerPagesAreEachLayerOverTheBackground) {
  const std::string folder = temporaryFolder("layers");
  DocumentImportFolder output(folder, 300, 40);
  ImportedDocument document;
  document.width = 300;
  document.height = 40;
  document.backgroundColor = std::array<double, 4>{0, 0, 1, 1};
  ImportedNode red;
  red.uuid = output.newUuid();
  red.opacity = 0.5;
  {
    SurfaceTileWriter writer = output.layerWriter(red.uuid);
    const std::vector<uint8_t> pixels(size_t{300} * 40 * 4, 255);
    std::vector<uint8_t> colored = pixels;
    for (size_t pixel = 0; pixel < colored.size(); pixel += 4) {
      colored[pixel + 1] = colored[pixel + 2] = 0;
    }
    ASSERT_TRUE(writer.writePixels(0, 0, 300, 40, colored.data(), size_t{300} * 4));
    ASSERT_TRUE(writer.finish());
  }
  ImportedNode masked;
  masked.uuid = output.newUuid();
  masked.mask = ImportedMask{output.newUuid(), true, false};
  {
    SurfaceTileWriter writer = output.layerWriter(masked.uuid);
    std::vector<uint8_t> green(size_t{300} * 40 * 4, 0);
    for (size_t pixel = 0; pixel < green.size(); pixel += 4) {
      green[pixel + 1] = green[pixel + 3] = 255;
    }
    ASSERT_TRUE(writer.writePixels(0, 0, 300, 40, green.data(), size_t{300} * 4));
    ASSERT_TRUE(writer.finish());
    SurfaceTileWriter mask = output.maskWriter(masked.mask->uuid, 255);
    std::vector<uint8_t> values(size_t{300} * 40, 255);
    for (int32_t y = 0; y < 40; ++y) {
      std::fill_n(values.begin() + static_cast<std::ptrdiff_t>(y) * 300 + 260, 40, 0);
    }
    ASSERT_TRUE(mask.writePixels(0, 0, 300, 40, values.data(), 300));
    ASSERT_TRUE(mask.finish());
  }
  ImportedNode group;
  group.type = ImportedNode::Type::Group;
  group.uuid = "group";
  group.children.push_back(masked);
  document.layers = {red, group};

  auto pages = layerPages(document, folder);
  ASSERT_EQ(pages.size(), 2U);
  std::vector<uint8_t> row(size_t{300} * 4);
  ASSERT_TRUE(pages[0]->readRows(10, 1, row.data()));
  EXPECT_EQ((Pixel{row[0], row[1], row[2], row[3]}), (Pixel{128, 0, 128, 255}));
  ASSERT_TRUE(pages[1]->readRows(39, 1, row.data()));
  EXPECT_EQ((Pixel{row[0], row[1], row[2], row[3]}), (Pixel{0, 255, 0, 255}));
  constexpr size_t kMasked = size_t{270} * 4;
  EXPECT_EQ((Pixel{row[kMasked], row[kMasked + 1], row[kMasked + 2], row[kMasked + 3]}),
            (Pixel{0, 0, 255, 255}));

  std::vector<CompositeSource*> sources{pages[0].get(), pages[1].get()};
  const std::string pdf = io::joinPath(folder, "layers.pdf");
  ASSERT_TRUE(exportPdf(300, 40, 72, sources, {}, pdf).ok);
  const auto objects = readPdf(io::readFile(pdf, 1 << 26).value_or(std::vector<uint8_t>()));
  EXPECT_EQ(imagesOf(objects, false).size(), 2U);
}

TEST(PdfWriter, InvalidInputs) {
  FunctionPage page(4, gradient);
  const std::string folder = temporaryFolder("invalid");
  EXPECT_EQ(exportPdf(4, 4, 72, {}, {}, io::joinPath(folder, "a.pdf")).errorCode,
            "invalidDocument");
  EXPECT_EQ(exportPdf(0, 4, 72, {&page}, {}, io::joinPath(folder, "b.pdf")).errorCode,
            "invalidDocument");
  EXPECT_EQ(exportPdf(4, 4, 72, {&page}, {}, io::joinPath(folder, "missing/c.pdf")).errorCode,
            "writeFailed");
}

}  // namespace
}  // namespace nanquim::formats
