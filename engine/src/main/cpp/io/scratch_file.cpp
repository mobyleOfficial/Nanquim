#include "io/scratch_file.h"

#include <utility>

namespace nanquim::io {

ScratchFile::ScratchFile(std::string path) : path_(std::move(path)) {}

ScratchFile::~ScratchFile() {
  if (file_ != nullptr) {
    std::fclose(file_);
    std::remove(path_.c_str());
  }
}

bool ScratchFile::openIfNeeded() {
  if (file_ == nullptr) {
    file_ = std::fopen(path_.c_str(), "w+b");
    endOffset_ = 0;
  }
  return file_ != nullptr;
}

std::optional<ScratchLocation> ScratchFile::append(const std::vector<uint8_t>& bytes) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!openIfNeeded() || std::fseek(file_, static_cast<long>(endOffset_), SEEK_SET) != 0) {
    return std::nullopt;
  }
  if (std::fwrite(bytes.data(), 1, bytes.size(), file_) != bytes.size() ||
      std::fflush(file_) != 0) {
    return std::nullopt;
  }
  const ScratchLocation location{endOffset_, static_cast<int64_t>(bytes.size())};
  endOffset_ += location.length;
  return location;
}

std::optional<std::vector<uint8_t>> ScratchFile::read(ScratchLocation location) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (file_ == nullptr || location.offset < 0 || location.offset + location.length > endOffset_ ||
      std::fseek(file_, static_cast<long>(location.offset), SEEK_SET) != 0) {
    return std::nullopt;
  }
  std::vector<uint8_t> bytes(static_cast<size_t>(location.length));
  if (std::fread(bytes.data(), 1, bytes.size(), file_) != bytes.size()) {
    return std::nullopt;
  }
  return bytes;
}

void ScratchFile::truncate() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (file_ != nullptr) {
    std::fclose(file_);
    file_ = nullptr;
    std::remove(path_.c_str());
  }
  endOffset_ = 0;
}

int64_t ScratchFile::sizeBytes() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return endOffset_;
}

}  // namespace nanquim::io
