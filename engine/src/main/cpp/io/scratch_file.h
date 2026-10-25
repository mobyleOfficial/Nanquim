#pragma once

#include <cstdint>
#include <cstdio>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

namespace nanquim::io {

struct ScratchLocation {
  int64_t offset = 0;
  int64_t length = 0;
};

class ScratchFile {
 public:
  explicit ScratchFile(std::string path);
  ScratchFile(const ScratchFile&) = delete;
  ScratchFile& operator=(const ScratchFile&) = delete;
  ~ScratchFile();

  std::optional<ScratchLocation> append(const std::vector<uint8_t>& bytes);
  std::optional<std::vector<uint8_t>> read(ScratchLocation location);

  void truncate();

  [[nodiscard]] int64_t sizeBytes() const;

 private:
  bool openIfNeeded();

  std::string path_;
  mutable std::mutex mutex_;
  std::FILE* file_ = nullptr;
  int64_t endOffset_ = 0;
};

}  // namespace nanquim::io
