#pragma once

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <string>
#include <vector>

namespace nanquim::formats {

class InputSource {
 public:
  virtual ~InputSource() = default;

  [[nodiscard]] virtual uint64_t size() const = 0;

  virtual bool read(uint64_t offset, void* destination, size_t count) = 0;

  bool readBytes(uint64_t offset, size_t count, std::vector<uint8_t>& bytes);
};

class MemoryInputSource final : public InputSource {
 public:
  explicit MemoryInputSource(std::vector<uint8_t> bytes) : bytes_(std::move(bytes)) {}

  [[nodiscard]] uint64_t size() const override { return bytes_.size(); }
  bool read(uint64_t offset, void* destination, size_t count) override;

 private:
  std::vector<uint8_t> bytes_;
};

class FileInputSource final : public InputSource {
 public:
  static std::unique_ptr<FileInputSource> open(const std::string& path);
  ~FileInputSource() override;
  FileInputSource(const FileInputSource&) = delete;
  FileInputSource& operator=(const FileInputSource&) = delete;

  [[nodiscard]] uint64_t size() const override { return size_; }
  bool read(uint64_t offset, void* destination, size_t count) override;

 private:
  FileInputSource(std::FILE* file, uint64_t size) : file_(file), size_(size) {}

  std::FILE* file_;
  uint64_t size_;
};

}  // namespace nanquim::formats
