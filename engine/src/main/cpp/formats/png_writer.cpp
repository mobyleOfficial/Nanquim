#include "formats/png_writer.h"

#include <miniz.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <cstring>

#include "formats/icc_profile.h"
#include "formats/output_file.h"
#include "formats/png_encoding.h"

namespace nanquim::formats {

using png::bigEndian32;
using png::filterRow;
using png::writeChunk;

namespace {

constexpr int32_t kBandRows = 32;
constexpr int32_t kMaximumSide = 65535;

}  // namespace

ExportResult exportPng(int32_t width, int32_t height, double dpi, CompositeSource& composite,
                       const std::string& outputPath, bool deep,
                       const std::vector<uint8_t>& iccProfile) {
  ExportResult result;
  const auto failed = [&result](const char* code, const std::string& detail = {}) {
    result.ok = false;
    result.errorCode = code;
    result.errorDetail = detail;
    return result;
  };
  if (width <= 0 || height <= 0 || width > kMaximumSide || height > kMaximumSide) {
    return failed("invalidDocument", "canvas size");
  }
  const bool sixteen = deep && composite.hasDeepRows();
  auto out = OutputFile::create(outputPath);
  if (!out) {
    return failed("writeFailed", "create");
  }
  out->bytes(png::kSignature.data(), png::kSignature.size());

  std::vector<uint8_t> header;
  bigEndian32(header, static_cast<uint32_t>(width));
  bigEndian32(header, static_cast<uint32_t>(height));
  header.insert(header.end(), {static_cast<uint8_t>(sixteen ? 16 : 8), 6, 0, 0, 0});
  writeChunk(*out, "IHDR", header);

  const std::vector<uint8_t> profile = iccProfile.empty() ? srgbIccProfile() : iccProfile;
  mz_ulong compressedSize = mz_compressBound(static_cast<mz_ulong>(profile.size()));
  std::vector<uint8_t> iccChunk{'I', 'C', 'C', ' ', 'p', 'r', 'o', 'f', 'i', 'l', 'e', 0, 0};
  const size_t prefix = iccChunk.size();
  iccChunk.resize(prefix + compressedSize);
  if (mz_compress2(iccChunk.data() + prefix, &compressedSize, profile.data(),
                   static_cast<mz_ulong>(profile.size()), MZ_DEFAULT_LEVEL) != MZ_OK) {
    return failed("writeFailed", "deflate");
  }
  iccChunk.resize(prefix + compressedSize);
  writeChunk(*out, "iCCP", iccChunk);

  if (dpi > 0) {
    const auto perMeter = static_cast<uint32_t>(std::lround(dpi / 0.0254));
    std::vector<uint8_t> physical;
    bigEndian32(physical, perMeter);
    bigEndian32(physical, perMeter);
    physical.push_back(1);
    writeChunk(*out, "pHYs", physical);
  }

  png::ImageDataWriter data(*out, nullptr);
  const size_t pixelBytes = sixteen ? 8 : 4;
  const size_t rowBytes = static_cast<size_t>(width) * pixelBytes;
  std::vector<uint8_t> band;
  std::vector<uint16_t> deepBand;
  std::vector<uint8_t> previous(rowBytes, 0);
  std::vector<uint8_t> row(rowBytes);
  std::array<std::vector<uint8_t>, 4> candidates;
  std::vector<uint8_t> filtered;
  bool ok = data.ok();
  for (int32_t top = 0; ok && top < height; top += kBandRows) {
    const int32_t rows = std::min(kBandRows, height - top);
    if (sixteen) {
      deepBand.resize(static_cast<size_t>(width) * 4 * static_cast<size_t>(rows));
      if (!composite.readDeepRows(top, rows, deepBand.data())) {
        return failed("compositeFailed");
      }
    } else {
      band.resize(rowBytes * static_cast<size_t>(rows));
      if (!composite.readRows(top, rows, band.data())) {
        return failed("compositeFailed");
      }
    }
    for (int32_t index = 0; ok && index < rows; ++index) {
      if (sixteen) {
        const uint16_t* samples = deepBand.data() + static_cast<size_t>(index) * width * 4;
        for (size_t sample = 0; sample < static_cast<size_t>(width) * 4; ++sample) {
          row[sample * 2] = static_cast<uint8_t>(samples[sample] >> 8U);
          row[sample * 2 + 1] = static_cast<uint8_t>(samples[sample] & 0xFFU);
        }
      } else {
        std::memcpy(row.data(), band.data() + static_cast<size_t>(index) * rowBytes, rowBytes);
      }
      filterRow(row.data(), previous.data(), rowBytes, pixelBytes, candidates, filtered);
      ok = data.add(filtered);
      previous.swap(row);
    }
  }
  ok = ok && data.finish();
  if (!ok) {
    return failed("writeFailed", "deflate");
  }
  writeChunk(*out, "IEND", nullptr, 0);
  if (!out->close()) {
    return failed("writeFailed");
  }
  result.ok = true;
  return result;
}

}  // namespace nanquim::formats
