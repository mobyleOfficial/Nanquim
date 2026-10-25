#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "formats/input_source.h"

namespace nanquim::formats {

class SourceReader {
 public:
  SourceReader(InputSource& source, uint64_t begin, uint64_t end);

  [[nodiscard]] bool ok() const { return ok_; }
  [[nodiscard]] uint64_t position() const { return position_; }
  [[nodiscard]] uint64_t end() const { return end_; }
  [[nodiscard]] uint64_t remaining() const { return end_ - position_; }

  void fail() {
    ok_ = false;
    position_ = end_;
  }
  void seek(uint64_t position);
  void skip(uint64_t count);

  uint8_t u8() { return static_cast<uint8_t>(bigEndian(1)); }
  uint16_t u16() { return static_cast<uint16_t>(bigEndian(2)); }
  uint32_t u32() { return static_cast<uint32_t>(bigEndian(4)); }
  uint64_t u64() { return bigEndian(8); }
  int16_t i16() { return static_cast<int16_t>(u16()); }
  int32_t i32() { return static_cast<int32_t>(u32()); }

  bool bytes(uint64_t count, uint64_t maximumBytes, std::vector<uint8_t>& bytes);
  std::string string(uint64_t count);

 private:
  uint64_t bigEndian(size_t count);
  bool fill(size_t count);

  InputSource& source_;
  uint64_t position_;
  uint64_t end_;
  bool ok_ = true;
  std::vector<uint8_t> buffer_;
  uint64_t bufferStart_ = 0;
};

bool decodePackBits(const uint8_t* source, size_t sourceSize, uint8_t* destination,
                    size_t destinationSize);

void encodePackBits(const uint8_t* source, size_t size, std::vector<uint8_t>& encoded);

}  // namespace nanquim::formats
