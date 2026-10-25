#include "io/file_system.h"

#include <cstdio>
#include <filesystem>
#include <system_error>

#if defined(_WIN32)
#include <io.h>
#else
#include <fcntl.h>
#include <unistd.h>
#endif

namespace nanquim::io {

namespace {

namespace fs = std::filesystem;

fs::path toPath(const std::string& path) { return fs::u8path(path); }

bool syncFile(std::FILE* file) {
#if defined(_WIN32)
  return _commit(_fileno(file)) == 0;
#else
  return fsync(fileno(file)) == 0;
#endif
}

}  // namespace

std::optional<std::vector<uint8_t>> readFile(const std::string& path, int64_t maximumBytes) {
  std::error_code error;
  const auto size = fs::file_size(toPath(path), error);
  if (error || static_cast<int64_t>(size) > maximumBytes) {
    return std::nullopt;
  }
  std::FILE* file = std::fopen(path.c_str(), "rb");
  if (file == nullptr) {
    return std::nullopt;
  }
  std::vector<uint8_t> bytes(static_cast<size_t>(size));
  const size_t read = bytes.empty() ? 0 : std::fread(bytes.data(), 1, bytes.size(), file);
  std::fclose(file);
  if (read != bytes.size()) {
    return std::nullopt;
  }
  return bytes;
}

namespace {

bool writeFileWithSync(const std::string& path, const std::vector<uint8_t>& bytes, bool sync) {
  std::error_code error;
  const fs::path parent = toPath(path).parent_path();
  if (!parent.empty()) {
    fs::create_directories(parent, error);
  }
  std::FILE* file = std::fopen(path.c_str(), "wb");
  if (file == nullptr) {
    return false;
  }
  bool written = bytes.empty() || std::fwrite(bytes.data(), 1, bytes.size(), file) == bytes.size();
  written = written && std::fflush(file) == 0 && (!sync || syncFile(file));
  return std::fclose(file) == 0 && written;
}

}  // namespace

bool writeFileDurably(const std::string& path, const std::vector<uint8_t>& bytes) {
  return writeFileWithSync(path, bytes, true);
}

bool writeFile(const std::string& path, const std::vector<uint8_t>& bytes) {
  return writeFileWithSync(path, bytes, false);
}

bool syncFiles(const std::string& folder, const std::vector<std::string>& paths) {
#if defined(__linux__)
  (void)paths;
  const int descriptor = open(folder.c_str(), O_RDONLY | O_DIRECTORY);
  if (descriptor < 0) {
    return false;
  }
  const bool synced = syncfs(descriptor) == 0;
  close(descriptor);
  return synced;
#else
  (void)folder;
  for (const std::string& path : paths) {
    std::FILE* file = std::fopen(path.c_str(), "r+b");
    if (file == nullptr) {
      return false;
    }
    const bool synced = syncFile(file);
    std::fclose(file);
    if (!synced) {
      return false;
    }
  }
  return true;
#endif
}

bool renameReplacing(const std::string& from, const std::string& to) {
  std::error_code error;
  fs::rename(toPath(from), toPath(to), error);
  return !error;
}

bool removePath(const std::string& path) {
  std::error_code error;
  fs::remove_all(toPath(path), error);
  return !error && !fs::exists(toPath(path), error);
}

bool pathExists(const std::string& path) {
  std::error_code error;
  return fs::exists(toPath(path), error);
}

void syncFolder(const std::string& path) {
#if !defined(_WIN32)
  const int descriptor = open(path.c_str(), O_RDONLY | O_DIRECTORY);
  if (descriptor >= 0) {
    fsync(descriptor);
    close(descriptor);
  }
#else
  (void)path;
#endif
}

std::vector<std::string> listFolder(const std::string& path) {
  std::vector<std::string> names;
  std::error_code error;
  for (fs::directory_iterator entry(toPath(path), error), end; !error && entry != end;
       entry.increment(error)) {
    names.push_back(entry->path().filename().u8string());
  }
  return names;
}

std::string joinPath(const std::string& folder, const std::string& name) {
  if (folder.empty()) {
    return name;
  }
  return folder.back() == '/' ? folder + name : folder + "/" + name;
}

}  // namespace nanquim::io
