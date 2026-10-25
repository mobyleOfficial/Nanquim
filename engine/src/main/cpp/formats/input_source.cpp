#include "formats/input_source.h"

#include <cstring>
#include <filesystem>
#include <system_error>

namespace nanquim::formats {

namespace {

bool rangeInside(uint64_t offset, size_t count, uint64_t size) {
  return offset <= size && count <= size - offset;
}

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

bool InputSource::readBytes(uint64_t offset, size_t count, std::vector<uint8_t>& bytes) {
  if (!rangeInside(offset, count, size())) {
    bytes.clear();
    return false;
  }
  bytes.resize(count);
  if (count > 0 && !read(offset, bytes.data(), count)) {
    bytes.clear();
    return false;
  }
  return true;
}

bool MemoryInputSource::read(uint64_t offset, void* destination, size_t count) {
  if (!rangeInside(offset, count, bytes_.size())) {
    return false;
  }
  if (count > 0) {
    std::memcpy(destination, bytes_.data() + offset, count);
  }
  return true;
}

std::unique_ptr<FileInputSource> FileInputSource::open(const std::string& path) {
  std::error_code error;
  const auto size = std::filesystem::file_size(std::filesystem::u8path(path), error);
  if (error) {
    return nullptr;
  }
  std::FILE* file = std::fopen(path.c_str(), "rb");
  if (file == nullptr) {
    return nullptr;
  }
  return std::unique_ptr<FileInputSource>(new FileInputSource(file, size));
}

FileInputSource::~FileInputSource() { std::fclose(file_); }

bool FileInputSource::read(uint64_t offset, void* destination, size_t count) {
  if (!rangeInside(offset, count, size_)) {
    return false;
  }
  if (count == 0) {
    return true;
  }
  return seekTo(file_, offset) == 0 && std::fread(destination, 1, count, file_) == count;
}

}  // namespace nanquim::formats
