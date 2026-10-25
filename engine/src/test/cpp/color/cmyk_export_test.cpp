#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <filesystem>
#include <string>
#include <vector>

#include "color/color_profile.h"
#include "color/synthetic_cmyk_profile.h"
#include "formats/pdf_writer.h"
#include "formats/psd_reader.h"
#include "formats/psd_writer.h"
#include "formats/source_reader.h"
#include "formats/tiff_reader.h"
#include "formats/tiff_writer.h"
#include "io/file_system.h"

namespace nanquim::formats {
namespace {

std::string temporaryFolder(const std::string& name) {
  const auto folder = std::filesystem::temp_directory_path() / ("nanquim_cmyk_" + name);
  std::filesystem::remove_all(folder);
  std::filesystem::create_directories(folder);
  return folder.u8string();
}

class Halves final : public CompositeSource {
 public:
  explicit Halves(int32_t width) : width_(width) {}
  bool readRows(int32_t top, int32_t count, uint8_t* rgba) override {
    for (int32_t y = 0; y < count; ++y) {
      for (int32_t x = 0; x < width_; ++x) {
        const std::array<uint8_t, 4> pixel = x < width_ / 2
                                                 ? std::array<uint8_t, 4>{255, 0, 0, 255}
                                                 : std::array<uint8_t, 4>{255, 255, 255, 128};
        std::copy(pixel.begin(), pixel.end(), rgba + (static_cast<size_t>(y) * width_ + x) * 4);
      }
    }
    (void)top;
    return true;
  }

 private:
  int32_t width_;
};

bool hasWarning(const ImportedDocument& document, const std::string& code) {
  return std::any_of(document.warnings.begin(), document.warnings.end(),
                     [&code](const ImportWarning& warning) { return warning.code == code; });
}

struct Cmyk {
  std::vector<uint8_t> profile = color::testing::syntheticCmykProfile();
  std::unique_ptr<color::CmykTransform> transform;
  CmykOutput output;

  Cmyk() {
    std::string error;
    transform = color::CmykTransform::create({}, profile, error);
    EXPECT_NE(transform, nullptr) << error;
    output.transform = transform.get();
    output.profile = profile;
    io::writeFileDurably(
        (std::filesystem::temp_directory_path() / "nanquim_synthetic_cmyk.icc").u8string(),
        profile);
  }
};

TEST(CmykExport, TheSyntheticProfileIsAnOutputProfile) {
  const std::vector<uint8_t> profile = color::testing::syntheticCmykProfile();
  std::string error;
  const auto info = color::readProfile(profile.data(), profile.size(), error);
  ASSERT_TRUE(info) << error;
  EXPECT_EQ(info->colorSpace, color::ProfileColorSpace::Cmyk);
  EXPECT_TRUE(info->convertsTo);
  EXPECT_TRUE(info->convertsFrom);
  EXPECT_EQ(color::CmykTransform::create({}, {}, error), nullptr);
  EXPECT_EQ(color::CmykTransform::create(profile, profile, error), nullptr);
}

TEST(CmykExport, InksOfPlainColors) {
  Cmyk cmyk;
  const std::vector<uint8_t> rgba{255, 255, 255, 255, 0, 0, 0, 255, 255, 0, 0, 77};
  std::vector<uint8_t> inks(15);
  cmyk.transform->convert(rgba.data(), inks.data(), 3);
  for (size_t channel = 0; channel < 4; ++channel) {
    EXPECT_LE(inks[channel], 3) << "white " << channel;
  }
  EXPECT_GE(inks[8], 250);
  EXPECT_LE(inks[10], 10);
  EXPECT_GE(inks[11], 225);
  EXPECT_GE(inks[12], 225);
  EXPECT_EQ(inks[14], 77);
}

TEST(CmykExport, TiffIsSeparatedWithTheCmykProfile) {
  Cmyk cmyk;
  Halves composite(64);
  const std::string output = io::joinPath(temporaryFolder("tiff"), "out.tif");
  ASSERT_TRUE(exportTiff(64, 16, 72, composite, output, false, {}, &cmyk.output).ok);
  const auto file = io::readFile(output, 1 << 24).value_or(std::vector<uint8_t>{});
  EXPECT_NE(std::search(file.begin(), file.end(), cmyk.profile.begin(), cmyk.profile.end()),
            file.end());
  MemoryInputSource source(file);
  const ImportResult imported = importTiff(source, temporaryFolder("tiff_again"), {});
  ASSERT_TRUE(imported.ok) << imported.errorCode << " " << imported.errorDetail;
  EXPECT_TRUE(hasWarning(imported.document, "cmykCanvas"));
  EXPECT_FALSE(hasWarning(imported.document, "cmykConverted"));
  EXPECT_EQ(imported.document.cmykProfile, cmyk.profile);
  EXPECT_TRUE(imported.document.iccProfile.empty());
}

TEST(CmykExport, CmykWithoutAnyProfileIsConvertedApproximately) {
  Cmyk cmyk;
  Halves composite(16);
  const std::string output = io::joinPath(temporaryFolder("tiff_plain"), "out.tif");
  ASSERT_TRUE(exportTiff(16, 4, 72, composite, output, false, {}, &cmyk.output).ok);
  auto file = io::readFile(output, 1 << 24).value_or(std::vector<uint8_t>{});
  const auto embedded =
      std::search(file.begin(), file.end(), cmyk.profile.begin(), cmyk.profile.end());
  ASSERT_NE(embedded, file.end());
  std::fill(embedded, embedded + 40, uint8_t{0});
  MemoryInputSource plain(file);
  const ImportResult approximate = importTiff(plain, temporaryFolder("tiff_plain_again"), {});
  ASSERT_TRUE(approximate.ok) << approximate.errorCode;
  EXPECT_TRUE(hasWarning(approximate.document, "cmykConverted"));
  EXPECT_TRUE(approximate.document.cmykProfile.empty());

  ImportLimits limits;
  limits.cmykFallbackProfile = cmyk.profile;
  limits.cmykWorkingProfile =
      io::readFile(io::joinPath(NANQUIM_PROFILES_DIRECTORY, "AdobeCompat-v4.icc"), 1 << 20)
          .value_or(std::vector<uint8_t>{});
  MemoryInputSource again(file);
  const ImportResult canvas = importTiff(again, temporaryFolder("tiff_plain_canvas"), limits);
  ASSERT_TRUE(canvas.ok) << canvas.errorCode;
  EXPECT_TRUE(hasWarning(canvas.document, "cmykCanvas"));
  EXPECT_EQ(canvas.document.cmykProfile, cmyk.profile);
  EXPECT_EQ(canvas.document.iccProfile, limits.cmykWorkingProfile);
  EXPECT_FALSE(canvas.document.colorProfileName.empty());
}

TEST(CmykExport, PsdIsInCmykMode) {
  Cmyk cmyk;
  Halves composite(32);
  ImportedDocument document;
  document.width = 32;
  document.height = 8;
  document.backgroundColor = std::array<double, 4>{1, 1, 1, 1};
  PsdExportOptions options;
  options.cmyk = &cmyk.output;
  const std::string output = io::joinPath(temporaryFolder("psd"), "out.psd");
  const ExportResult result =
      exportPsd(document, temporaryFolder("psd_folder"), composite, options, output);
  ASSERT_TRUE(result.ok) << result.errorCode << " " << result.errorDetail;
  const auto file = io::readFile(output, 1 << 24).value_or(std::vector<uint8_t>{});
  ASSERT_GT(file.size(), 26U);
  EXPECT_EQ((file[12] << 8U) | file[13], 5);
  EXPECT_EQ((file[24] << 8U) | file[25], 4);
  EXPECT_NE(std::search(file.begin(), file.end(), cmyk.profile.begin(), cmyk.profile.end()),
            file.end());
  MemoryInputSource source(file);
  const ImportResult imported = importPsd(source, temporaryFolder("psd_again"), {32768, 100});
  ASSERT_TRUE(imported.ok) << imported.errorCode << " " << imported.errorDetail;
  ASSERT_EQ(imported.document.layers.size(), 1U);
  EXPECT_TRUE(hasWarning(imported.document, "cmykCanvas"));
  EXPECT_EQ(imported.document.cmykProfile, cmyk.profile);
}

TEST(CmykExport, PdfImagesAreCmyk) {
  Cmyk cmyk;
  Halves page(16);
  PdfExportOptions options;
  options.cmyk = &cmyk.output;
  options.jpeg = true;
  const std::string output = io::joinPath(temporaryFolder("pdf"), "out.pdf");
  ASSERT_TRUE(exportPdf(16, 16, 72, {&page}, options, output).ok);
  const auto file = io::readFile(output, 1 << 24).value_or(std::vector<uint8_t>{});
  const std::string text(file.begin(), file.end());
  EXPECT_NE(text.find("/N 4 /Alternate /DeviceCMYK"), std::string::npos);
  EXPECT_NE(text.find("/Colors 4"), std::string::npos);
  EXPECT_EQ(text.find("/DCTDecode"), std::string::npos);
}

}  // namespace
}  // namespace nanquim::formats
