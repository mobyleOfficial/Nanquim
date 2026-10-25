#include "formats/tiff_writer.h"

#include <miniz.h>

#include <algorithm>
#include <cmath>
#include <vector>

#include "formats/icc_profile.h"
#include "formats/output_file.h"

namespace nanquim::formats {

namespace {

constexpr int32_t kRowsPerStrip = 32;
constexpr uint64_t kMaximumFileBytes = uint64_t{0xFFFFFFFF};

class LittleEndian {
 public:
  void u16(uint32_t value) {
    bytes.push_back(static_cast<uint8_t>(value & 0xFFU));
    bytes.push_back(static_cast<uint8_t>((value >> 8U) & 0xFFU));
  }
  void u32(uint32_t value) {
    u16(value & 0xFFFFU);
    u16(value >> 16U);
  }
  std::vector<uint8_t> bytes;
};

struct DirectoryEntry {
  uint16_t tag = 0;
  uint16_t type = 0;
  uint32_t count = 0;
  std::vector<uint8_t> data;
};

DirectoryEntry shortEntry(uint16_t tag, std::vector<uint32_t> values) {
  LittleEndian data;
  for (const uint32_t value : values) {
    data.u16(value);
  }
  return {tag, 3, static_cast<uint32_t>(values.size()), data.bytes};
}

DirectoryEntry longEntry(uint16_t tag, const std::vector<uint32_t>& values) {
  LittleEndian data;
  for (const uint32_t value : values) {
    data.u32(value);
  }
  return {tag, 4, static_cast<uint32_t>(values.size()), data.bytes};
}

DirectoryEntry rationalEntry(uint16_t tag, double value) {
  LittleEndian data;
  data.u32(static_cast<uint32_t>(std::lround(value * 10000.0)));
  data.u32(10000);
  return {tag, 5, 1, data.bytes};
}

void applyPredictor(uint8_t* row, int32_t width, size_t samples = 4) {
  for (size_t index = static_cast<size_t>(width) * samples; index-- > samples;) {
    row[index] = static_cast<uint8_t>(row[index] - row[index - samples]);
  }
}

void applyPredictor(uint16_t* row, int32_t width, uint8_t* bytes) {
  for (size_t index = static_cast<size_t>(width) * 4; index-- > 4;) {
    row[index] = static_cast<uint16_t>(row[index] - row[index - 4]);
  }
  for (size_t index = 0; index < static_cast<size_t>(width) * 4; ++index) {
    bytes[index * 2] = static_cast<uint8_t>(row[index] & 0xFFU);
    bytes[index * 2 + 1] = static_cast<uint8_t>(row[index] >> 8U);
  }
}

}  // namespace

ExportResult exportTiff(int32_t width, int32_t height, double dpi, CompositeSource& composite,
                        const std::string& outputPath, bool deep,
                        const std::vector<uint8_t>& iccProfile, const CmykOutput* cmyk) {
  ExportResult result;
  const auto failed = [&result](const char* code, const std::string& detail = {}) {
    result.ok = false;
    result.errorCode = code;
    result.errorDetail = detail;
    return result;
  };
  if (width <= 0 || height <= 0 || width > 65535 || height > 65535) {
    return failed("invalidDocument", "canvas size");
  }
  auto out = OutputFile::create(outputPath);
  if (!out) {
    return failed("writeFailed", "create");
  }
  out->text("II");
  out->bytes(std::vector<uint8_t>{42, 0, 0, 0, 0, 0});

  const bool separated = cmyk != nullptr && cmyk->transform != nullptr;
  const bool sixteen = !separated && deep && composite.hasDeepRows();
  const size_t samplesPerPixel = separated ? 5 : 4;
  const size_t samplesPerRow = static_cast<size_t>(width) * samplesPerPixel;
  const size_t rowBytes = samplesPerRow * (sixteen ? 2 : 1);
  std::vector<uint8_t> rgbaStrip;
  std::vector<uint32_t> offsets;
  std::vector<uint32_t> sizes;
  std::vector<uint8_t> strip;
  std::vector<uint16_t> deepStrip;
  std::vector<uint8_t> compressed;
  for (int32_t top = 0; top < height; top += kRowsPerStrip) {
    const int32_t rows = std::min(kRowsPerStrip, height - top);
    strip.resize(rowBytes * static_cast<size_t>(rows));
    if (sixteen) {
      deepStrip.resize(samplesPerRow * static_cast<size_t>(rows));
      if (!composite.readDeepRows(top, rows, deepStrip.data())) {
        return failed("compositeFailed");
      }
      for (int32_t row = 0; row < rows; ++row) {
        applyPredictor(deepStrip.data() + static_cast<size_t>(row) * samplesPerRow, width,
                       strip.data() + static_cast<size_t>(row) * rowBytes);
      }
    } else if (separated) {
      rgbaStrip.resize(static_cast<size_t>(width) * 4 * static_cast<size_t>(rows));
      if (!composite.readRows(top, rows, rgbaStrip.data())) {
        return failed("compositeFailed");
      }
      cmyk->transform->convert(rgbaStrip.data(), strip.data(),
                               static_cast<size_t>(width) * static_cast<size_t>(rows));
      for (int32_t row = 0; row < rows; ++row) {
        applyPredictor(strip.data() + static_cast<size_t>(row) * rowBytes, width, 5);
      }
    } else {
      if (!composite.readRows(top, rows, strip.data())) {
        return failed("compositeFailed");
      }
      for (int32_t row = 0; row < rows; ++row) {
        applyPredictor(strip.data() + static_cast<size_t>(row) * rowBytes, width);
      }
    }
    mz_ulong size = mz_compressBound(static_cast<mz_ulong>(strip.size()));
    compressed.resize(size);
    if (mz_compress2(compressed.data(), &size, strip.data(), static_cast<mz_ulong>(strip.size()),
                     MZ_DEFAULT_LEVEL) != MZ_OK) {
      return failed("writeFailed", "deflate");
    }
    if (out->position() + size > kMaximumFileBytes) {
      return failed("tooLarge");
    }
    offsets.push_back(static_cast<uint32_t>(out->position()));
    sizes.push_back(static_cast<uint32_t>(size));
    out->bytes(compressed.data(), size);
  }

  std::vector<DirectoryEntry> entries{
      longEntry(256, {static_cast<uint32_t>(width)}),
      longEntry(257, {static_cast<uint32_t>(height)}),
      shortEntry(258, std::vector<uint32_t>(samplesPerPixel, sixteen ? 16 : 8)),
      shortEntry(259, {8}),
      shortEntry(262, {separated ? 5U : 2U}),
      longEntry(273, offsets),
      shortEntry(277, {static_cast<uint32_t>(samplesPerPixel)}),
      longEntry(278, {static_cast<uint32_t>(kRowsPerStrip)}),
      longEntry(279, sizes),
      rationalEntry(282, dpi),
      rationalEntry(283, dpi),
      shortEntry(284, {1}),
      shortEntry(296, {2}),
      shortEntry(317, {2}),
  };
  if (separated) {
    entries.push_back(shortEntry(332, {1}));
  }
  entries.push_back(shortEntry(338, {2}));
  const std::vector<uint8_t> profile = separated            ? cmyk->profile
                                       : iccProfile.empty() ? srgbIccProfile()
                                                            : iccProfile;
  entries.push_back({34675, 7, static_cast<uint32_t>(profile.size()), profile});

  std::vector<uint32_t> valueOffsets(entries.size(), 0);
  for (size_t index = 0; index < entries.size(); ++index) {
    if (entries[index].data.size() > 4) {
      out->zeros(static_cast<size_t>(out->position() % 2));
      valueOffsets[index] = static_cast<uint32_t>(out->position());
      out->bytes(entries[index].data);
    }
  }
  out->zeros(static_cast<size_t>(out->position() % 2));
  const uint64_t directory = out->position();
  if (directory + 2 + entries.size() * 12 + 4 > kMaximumFileBytes) {
    return failed("tooLarge");
  }
  LittleEndian table;
  table.u16(static_cast<uint32_t>(entries.size()));
  for (size_t index = 0; index < entries.size(); ++index) {
    const DirectoryEntry& entry = entries[index];
    table.u16(entry.tag);
    table.u16(entry.type);
    table.u32(entry.count);
    if (entry.data.size() > 4) {
      table.u32(valueOffsets[index]);
    } else {
      std::vector<uint8_t> inline_ = entry.data;
      inline_.resize(4, 0);
      table.bytes.insert(table.bytes.end(), inline_.begin(), inline_.end());
    }
  }
  table.u32(0);
  out->bytes(table.bytes);
  LittleEndian pointer;
  pointer.u32(static_cast<uint32_t>(directory));
  out->patch(4, pointer.bytes.data(), pointer.bytes.size());
  if (!out->close()) {
    return failed("writeFailed");
  }
  result.ok = true;
  return result;
}

}  // namespace nanquim::formats
