#include "fuzz_targets.h"

#include <filesystem>
#include <string>

#include "color/color_profile.h"
#include "formats/binary_plist.h"
#include "formats/input_source.h"
#include "formats/palette_reader.h"
#include "formats/procreate_reader.h"
#include "formats/psd_reader.h"
#include "formats/tiff_reader.h"
#include "formats/zip_reader.h"
#include "io/file_system.h"

namespace nanquim::fuzz {

namespace {

constexpr uint64_t kMaximumExtractedBytes = 16U << 20U;

}  // namespace

void fuzzZipReader(const uint8_t* data, size_t size) {
  formats::MemoryInputSource source(std::vector<uint8_t>(data, data + size));
  formats::ZipLimits limits;
  limits.maximumEntries = 4096;
  limits.maximumTotalUncompressedBytes = 64U << 20U;
  std::string error;
  auto reader = formats::ZipReader::open(source, limits, error);
  if (!reader) {
    return;
  }
  std::vector<uint8_t> bytes;
  for (const formats::ZipEntry& entry : reader->entries()) {
    reader->extract(entry, kMaximumExtractedBytes, bytes, error);
    static_cast<void>(reader->find(entry.name));
  }
}

void fuzzPsdReader(const uint8_t* data, size_t size) {
  static const std::string folder =
      (std::filesystem::temp_directory_path() / "nanquim_fuzz_psd").u8string();
  io::removePath(folder);
  formats::MemoryInputSource source(std::vector<uint8_t>(data, data + size));
  formats::ImportLimits limits;
  limits.maximumSide = 2048;
  limits.maximumLayers = 64;
  formats::importPsd(source, folder, limits);
}

void fuzzTiffReader(const uint8_t* data, size_t size) {
  static const std::string folder =
      (std::filesystem::temp_directory_path() / "nanquim_fuzz_tiff").u8string();
  io::removePath(folder);
  formats::MemoryInputSource source(std::vector<uint8_t>(data, data + size));
  formats::ImportLimits limits;
  limits.maximumSide = 2048;
  formats::importTiff(source, folder, limits);
}

void fuzzProcreateReader(const uint8_t* data, size_t size) {
  static const std::string folder =
      (std::filesystem::temp_directory_path() / "nanquim_fuzz_procreate").u8string();
  io::removePath(folder);
  formats::MemoryInputSource source(std::vector<uint8_t>(data, data + size));
  formats::ImportLimits limits;
  limits.maximumSide = 2048;
  limits.maximumLayers = 64;
  formats::importProcreate(source, folder, limits);
}

void fuzzBinaryPlist(const uint8_t* data, size_t size) {
  std::string error;
  static_cast<void>(formats::keyedArchiveJson(std::vector<uint8_t>(data, data + size), error));
  const auto archive = formats::KeyedArchive::parse(std::vector<uint8_t>(data, data + size), error);
  if (!archive) {
    return;
  }
  const formats::BinaryPlist& plist = archive->plist();
  for (uint64_t index = 0; index < plist.objectCount() && index < 4096; ++index) {
    const auto object = archive->resolve(index);
    if (object) {
      static_cast<void>(archive->className(*object));
      static_cast<void>(archive->array(*object, "NS.objects"));
      static_cast<void>(archive->string(*object, "name"));
      static_cast<void>(archive->data(*object, "backgroundColor"));
    }
  }
}

void fuzzPaletteReader(const uint8_t* data, size_t size) {
  std::string error;
  static_cast<void>(formats::paletteJson(std::vector<uint8_t>(data, data + size), error));
}

void fuzzIccProfile(const uint8_t* data, size_t size) {
  std::string error;
  const auto info = color::readProfile(data, size, error);
  if (!info || info->colorSpace != color::ProfileColorSpace::Rgb) {
    return;
  }
  const std::vector<uint8_t> profile(data, data + size);
  for (const bool fromProfile : {true, false}) {
    const auto transform =
        color::ProfileTransform::create(fromProfile ? profile : std::vector<uint8_t>{},
                                        fromProfile ? std::vector<uint8_t>{} : profile, error);
    if (!transform) {
      continue;
    }
    std::vector<uint8_t> rgba8{255, 0, 0, 255, 10, 200, 30, 128, 0, 0, 0, 0, 255, 255, 255, 255};
    transform->convertRgba8(rgba8.data(), rgba8.size() / 4);
    std::vector<uint8_t> halves{0, 0x3C, 0, 0,    0, 0x38, 0, 0x3C,
                                0, 0x34, 0, 0x38, 0, 0x30, 0, 0x38};
    transform->convertLinearHalf(halves.data(), halves.size() / 8);
  }
}

const std::vector<FuzzTarget>& fuzzTargets() {
  static const std::vector<FuzzTarget> targets{
      {"zip", fuzzZipReader},     {"psd", fuzzPsdReader},
      {"tiff", fuzzTiffReader},   {"procreate", fuzzProcreateReader},
      {"plist", fuzzBinaryPlist}, {"palette", fuzzPaletteReader},
      {"icc", fuzzIccProfile},
  };
  return targets;
}

}  // namespace nanquim::fuzz
