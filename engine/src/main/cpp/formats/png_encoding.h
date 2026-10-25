#pragma once

#include <miniz.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "formats/output_file.h"

namespace nanquim::formats::png {

inline constexpr std::array<uint8_t, 8> kSignature{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

void writeChunk(OutputFile& out, const char* type, const uint8_t* data, size_t size);
inline void writeChunk(OutputFile& out, const char* type, const std::vector<uint8_t>& data) {
  writeChunk(out, type, data.data(), data.size());
}

void bigEndian32(std::vector<uint8_t>& bytes, uint32_t value);
void bigEndian16(std::vector<uint8_t>& bytes, uint32_t value);

void filterRow(const uint8_t* row, const uint8_t* previous, size_t size, size_t pixelBytes,
               std::array<std::vector<uint8_t>, 4>& candidates, std::vector<uint8_t>& out);

class ImageDataWriter {
 public:
  ImageDataWriter(OutputFile& out, uint32_t* sequence, int level = MZ_DEFAULT_LEVEL);
  ~ImageDataWriter();
  ImageDataWriter(const ImageDataWriter&) = delete;
  ImageDataWriter& operator=(const ImageDataWriter&) = delete;

  [[nodiscard]] bool ok() const { return ok_; }
  bool add(const std::vector<uint8_t>& filteredRow);
  bool finish();

 private:
  bool feed(const uint8_t* data, size_t size, int flush);
  void drain(bool all);

  OutputFile& out_;
  uint32_t* sequence_;
  mz_stream stream_{};
  std::vector<uint8_t> buffer_;
  size_t prefix_ = 0;
  bool ok_ = false;
};

}  // namespace nanquim::formats::png
