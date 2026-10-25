#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "formats/input_source.h"

namespace nanquim::formats {

struct ZipLimits {
  uint64_t maximumTotalUncompressedBytes = 4ULL << 30U;
  uint32_t maximumEntries = 100'000;
};

struct ZipEntry {
  std::string name;
  uint64_t compressedSize = 0;
  uint64_t uncompressedSize = 0;
  bool directory = false;
  uint32_t index = 0;
};

class ZipReader {
 public:
  static std::unique_ptr<ZipReader> open(InputSource& source, const ZipLimits& limits,
                                         std::string& error);
  ~ZipReader();
  ZipReader(const ZipReader&) = delete;
  ZipReader& operator=(const ZipReader&) = delete;

  [[nodiscard]] const std::vector<ZipEntry>& entries() const { return entries_; }

  [[nodiscard]] const ZipEntry* find(const std::string& name) const;

  bool extract(const ZipEntry& entry, uint64_t maximumBytes, std::vector<uint8_t>& bytes,
               std::string& error);

 private:
  struct Archive;
  explicit ZipReader(std::unique_ptr<Archive> archive);

  std::unique_ptr<Archive> archive_;
  std::vector<ZipEntry> entries_;
};

[[nodiscard]] bool isSafeZipEntryName(const std::string& name);

}  // namespace nanquim::formats
