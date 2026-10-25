#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>

namespace nanquim::formats {

class ByteReader {
 public:
  ByteReader(const uint8_t* data, size_t size) : data_(data), size_(size) {}

  [[nodiscard]] bool ok() const { return ok_; }
  [[nodiscard]] size_t position() const { return position_; }
  [[nodiscard]] size_t size() const { return size_; }
  [[nodiscard]] size_t remaining() const { return size_ - position_; }
  [[nodiscard]] bool atEnd() const { return position_ == size_; }

  void fail() {
    ok_ = false;
    position_ = size_;
  }

  void seek(size_t offset) {
    if (offset > size_) {
      fail();
      return;
    }
    position_ = offset;
  }

  void skip(uint64_t count) {
    if (count > remaining()) {
      fail();
      return;
    }
    position_ += static_cast<size_t>(count);
  }

  const uint8_t* take(uint64_t count) {
    if (count > remaining()) {
      fail();
      return nullptr;
    }
    const uint8_t* bytes = data_ + position_;
    position_ += static_cast<size_t>(count);
    return bytes;
  }

  ByteReader sub(uint64_t count) {
    const uint8_t* bytes = take(count);
    if (bytes == nullptr) {
      ByteReader failed(data_, 0);
      failed.fail();
      return failed;
    }
    return ByteReader(bytes, static_cast<size_t>(count));
  }

  uint8_t u8() { return static_cast<uint8_t>(bigEndian(1)); }
  uint16_t u16be() { return static_cast<uint16_t>(bigEndian(2)); }
  uint32_t u32be() { return static_cast<uint32_t>(bigEndian(4)); }
  uint64_t u64be() { return bigEndian(8); }
  int16_t i16be() { return static_cast<int16_t>(u16be()); }
  int32_t i32be() { return static_cast<int32_t>(u32be()); }
  uint16_t u16le() { return static_cast<uint16_t>(littleEndian(2)); }
  uint32_t u32le() { return static_cast<uint32_t>(littleEndian(4)); }
  uint64_t u64le() { return littleEndian(8); }

  float f32be() {
    const uint32_t bits = u32be();
    float value = 0;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
  }
  double f64be() {
    const uint64_t bits = u64be();
    double value = 0;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
  }

  std::string string(uint64_t count) {
    const uint8_t* bytes = take(count);
    return bytes == nullptr
               ? std::string()
               : std::string(reinterpret_cast<const char*>(bytes), static_cast<size_t>(count));
  }

 private:
  uint64_t bigEndian(size_t count) {
    const uint8_t* bytes = take(count);
    uint64_t value = 0;
    for (size_t index = 0; bytes != nullptr && index < count; ++index) {
      value = (value << 8U) | bytes[index];
    }
    return value;
  }
  uint64_t littleEndian(size_t count) {
    const uint8_t* bytes = take(count);
    uint64_t value = 0;
    for (size_t index = count; bytes != nullptr && index > 0; --index) {
      value = (value << 8U) | bytes[index - 1];
    }
    return value;
  }

  const uint8_t* data_;
  size_t size_;
  size_t position_ = 0;
  bool ok_ = true;
};

}  // namespace nanquim::formats
