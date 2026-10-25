#include "formats/output_file.h"

namespace nanquim::formats {

namespace {

constexpr size_t kBufferBytes = size_t{1} << 20U;

#if defined(_WIN32)
int seekTo(std::FILE* file, uint64_t offset) {
  return _fseeki64(file, static_cast<int64_t>(offset), SEEK_SET);
}
#else
int seekTo(std::FILE* file, uint64_t offset) {
  return fseeko(file, static_cast<off_t>(offset), SEEK_SET);
}
#endif

}  // namespace

std::unique_ptr<OutputFile> OutputFile::create(const std::string& path) {
  std::FILE* file = std::fopen(path.c_str(), "wb");
  if (file == nullptr) {
    return nullptr;
  }
  return std::unique_ptr<OutputFile>(new OutputFile(file));
}

OutputFile::~OutputFile() {
  if (file_ != nullptr) {
    std::fclose(file_);
  }
}

void OutputFile::bytes(const void* data, size_t count) {
  const auto* begin = static_cast<const uint8_t*>(data);
  buffer_.insert(buffer_.end(), begin, begin + count);
  if (buffer_.size() >= kBufferBytes) {
    flush();
  }
}

void OutputFile::padFrom(uint64_t start, uint32_t alignment) {
  const uint64_t length = position() - start;
  zeros(static_cast<size_t>((alignment - length % alignment) % alignment));
}

void OutputFile::flush() {
  if (buffer_.empty()) {
    return;
  }
  if (ok_ && std::fwrite(buffer_.data(), 1, buffer_.size(), file_) != buffer_.size()) {
    ok_ = false;
  }
  flushed_ += buffer_.size();
  buffer_.clear();
}

void OutputFile::patch(uint64_t offset, const void* data, size_t count) {
  if (offset + count > position()) {
    ok_ = false;
    return;
  }
  const auto* source = static_cast<const uint8_t*>(data);
  while (count > 0 && offset + count > flushed_) {
    const uint64_t last = offset + count - 1;
    buffer_[static_cast<size_t>(last - flushed_)] = source[count - 1];
    --count;
  }
  if (count == 0 || !ok_) {
    return;
  }
  if (seekTo(file_, offset) != 0 || std::fwrite(source, 1, count, file_) != count ||
      seekTo(file_, flushed_) != 0) {
    ok_ = false;
  }
}

void OutputFile::patch32(uint64_t offset, uint32_t value) {
  const uint8_t bytes[4] = {static_cast<uint8_t>(value >> 24U), static_cast<uint8_t>(value >> 16U),
                            static_cast<uint8_t>(value >> 8U), static_cast<uint8_t>(value)};
  patch(offset, bytes, sizeof(bytes));
}

void OutputFile::patch64(uint64_t offset, uint64_t value) {
  patch32(offset, static_cast<uint32_t>(value >> 32U));
  patch32(offset + 4, static_cast<uint32_t>(value));
}

bool OutputFile::close() {
  flush();
  if (std::fclose(file_) != 0) {
    ok_ = false;
  }
  file_ = nullptr;
  return ok_;
}

}  // namespace nanquim::formats
