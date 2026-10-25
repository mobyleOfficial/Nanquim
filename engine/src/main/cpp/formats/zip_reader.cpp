#include "formats/zip_reader.h"

#include <miniz.h>

#include <algorithm>
#include <cstring>

namespace nanquim::formats {

namespace {

size_t readFromSource(void* opaque, mz_uint64 offset, void* destination, size_t count) {
  auto* source = static_cast<InputSource*>(opaque);
  return source->read(offset, destination, count) ? count : 0;
}

struct ExtractTarget {
  std::vector<uint8_t>* bytes = nullptr;
  uint64_t maximumBytes = 0;
  bool tooLarge = false;
};

size_t appendBounded(void* opaque, mz_uint64 offset, const void* data, size_t count) {
  auto* target = static_cast<ExtractTarget*>(opaque);
  if (offset != target->bytes->size() || count > target->maximumBytes - target->bytes->size()) {
    target->tooLarge = offset == target->bytes->size();
    return 0;
  }
  const auto* bytes = static_cast<const uint8_t*>(data);
  target->bytes->insert(target->bytes->end(), bytes, bytes + count);
  return count;
}

}  // namespace

struct ZipReader::Archive {
  mz_zip_archive zip{};
  bool initialized = false;
  ~Archive() {
    if (initialized) {
      mz_zip_reader_end(&zip);
    }
  }
};

bool isSafeZipEntryName(const std::string& name) {
  if (name.empty() || name.front() == '/' || name.find('\\') != std::string::npos ||
      name.find('\0') != std::string::npos) {
    return false;
  }
  if (name.size() >= 2 && name[1] == ':') {
    return false;
  }
  size_t start = 0;
  while (start <= name.size()) {
    const size_t end = std::min(name.find('/', start), name.size());
    const std::string component = name.substr(start, end - start);
    if (component == "..") {
      return false;
    }
    if ((component.empty() || component == ".") && end != name.size()) {
      return false;
    }
    if (component == "." && end == name.size()) {
      return false;
    }
    start = end + 1;
  }
  return true;
}

ZipReader::ZipReader(std::unique_ptr<Archive> archive) : archive_(std::move(archive)) {}

ZipReader::~ZipReader() = default;

std::unique_ptr<ZipReader> ZipReader::open(InputSource& source, const ZipLimits& limits,
                                           std::string& error) {
  auto archive = std::make_unique<Archive>();
  archive->zip.m_pRead = readFromSource;
  archive->zip.m_pIO_opaque = &source;
  if (!mz_zip_reader_init(&archive->zip, source.size(), 0)) {
    error = "not a ZIP archive";
    return nullptr;
  }
  archive->initialized = true;
  const mz_uint count = mz_zip_reader_get_num_files(&archive->zip);
  if (count > limits.maximumEntries) {
    error = "too many entries (" + std::to_string(count) + ")";
    return nullptr;
  }
  std::unique_ptr<ZipReader> reader(new ZipReader(std::move(archive)));
  reader->entries_.reserve(count);
  uint64_t total = 0;
  for (mz_uint index = 0; index < count; ++index) {
    mz_zip_archive_file_stat stat{};
    if (!mz_zip_reader_file_stat(&reader->archive_->zip, index, &stat)) {
      error = "unreadable central directory entry " + std::to_string(index);
      return nullptr;
    }
    const mz_uint nameLength =
        mz_zip_reader_get_filename(&reader->archive_->zip, index, nullptr, 0);
    std::string name(nameLength > 0 ? nameLength - 1 : 0, '\0');
    if (nameLength > 0) {
      std::vector<char> buffer(nameLength);
      mz_zip_reader_get_filename(&reader->archive_->zip, index, buffer.data(), nameLength);
      name.assign(buffer.data(), std::strlen(buffer.data()));
      if (name.size() != nameLength - 1) {
        error = "entry name with a NUL character";
        return nullptr;
      }
    }
    if (!isSafeZipEntryName(name)) {
      error = "unsafe entry name: " + name;
      return nullptr;
    }
    if (stat.m_uncomp_size > limits.maximumTotalUncompressedBytes - total) {
      error = "archive larger than the limit when uncompressed";
      return nullptr;
    }
    total += stat.m_uncomp_size;
    reader->entries_.push_back(
        ZipEntry{name, stat.m_comp_size, stat.m_uncomp_size, stat.m_is_directory != 0, index});
  }
  return reader;
}

const ZipEntry* ZipReader::find(const std::string& name) const {
  const auto found = std::find_if(entries_.begin(), entries_.end(),
                                  [&name](const ZipEntry& entry) { return entry.name == name; });
  return found == entries_.end() ? nullptr : &*found;
}

bool ZipReader::extract(const ZipEntry& entry, uint64_t maximumBytes, std::vector<uint8_t>& bytes,
                        std::string& error) {
  bytes.clear();
  if (entry.directory) {
    return true;
  }
  if (entry.uncompressedSize > maximumBytes) {
    error = entry.name + " is larger than the limit";
    return false;
  }
  mz_zip_archive_file_stat stat{};
  if (!mz_zip_reader_file_stat(&archive_->zip, entry.index, &stat)) {
    error = "unreadable entry " + entry.name;
    return false;
  }
  if (stat.m_is_encrypted || !stat.m_is_supported) {
    error = entry.name + " is encrypted or uses an unsupported compression method";
    return false;
  }
  bytes.reserve(static_cast<size_t>(entry.uncompressedSize));
  ExtractTarget target{&bytes, maximumBytes, false};
  if (!mz_zip_reader_extract_to_callback(&archive_->zip, entry.index, appendBounded, &target, 0)) {
    error = target.tooLarge ? entry.name + " is larger than the limit"
                            : entry.name + ": " +
                                  mz_zip_get_error_string(mz_zip_get_last_error(&archive_->zip));
    bytes.clear();
    return false;
  }
  return true;
}

}  // namespace nanquim::formats
