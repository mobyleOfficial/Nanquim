#pragma once

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <string>
#include <vector>

namespace nanquim::formats {

class OutputFile {
 public:
  static std::unique_ptr<OutputFile> create(const std::string& path);
  ~OutputFile();
  OutputFile(const OutputFile&) = delete;
  OutputFile& operator=(const OutputFile&) = delete;

  [[nodiscard]] bool ok() const { return ok_; }
  [[nodiscard]] uint64_t position() const { return flushed_ + buffer_.size(); }

  void u8(uint32_t value) { buffer_.push_back(static_cast<uint8_t>(value)); }
  void u16(uint32_t value) {
    u8(value >> 8U);
    u8(value);
  }
  void u32(uint32_t value) {
    u16(value >> 16U);
    u16(value);
  }
  void u64(uint64_t value) {
    u32(static_cast<uint32_t>(value >> 32U));
    u32(static_cast<uint32_t>(value));
  }
  void zeros(size_t count) { buffer_.insert(buffer_.end(), count, 0); }
  void text(const std::string& value) { bytes(value.data(), value.size()); }
  void bytes(const void* data, size_t count);
  void bytes(const std::vector<uint8_t>& value) { bytes(value.data(), value.size()); }
  void padFrom(uint64_t start, uint32_t alignment);

  void patch(uint64_t offset, const void* data, size_t count);
  void patch32(uint64_t offset, uint32_t value);
  void patch64(uint64_t offset, uint64_t value);
  void patchLength(uint64_t offset, uint64_t value, bool eightBytes) {
    eightBytes ? patch64(offset, value) : patch32(offset, static_cast<uint32_t>(value));
  }

  bool close();

 private:
  explicit OutputFile(std::FILE* file) : file_(file) {}
  void flush();

  std::FILE* file_;
  std::vector<uint8_t> buffer_;
  uint64_t flushed_ = 0;
  bool ok_ = true;
};

}  // namespace nanquim::formats
