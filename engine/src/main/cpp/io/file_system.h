#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace nanquim::io {

std::optional<std::vector<uint8_t>> readFile(const std::string& path, int64_t maximumBytes);

bool writeFileDurably(const std::string& path, const std::vector<uint8_t>& bytes);

bool writeFile(const std::string& path, const std::vector<uint8_t>& bytes);

bool syncFiles(const std::string& folder, const std::vector<std::string>& paths);

bool renameReplacing(const std::string& from, const std::string& to);

bool removePath(const std::string& path);

[[nodiscard]] bool pathExists(const std::string& path);

void syncFolder(const std::string& path);

std::vector<std::string> listFolder(const std::string& path);

std::string joinPath(const std::string& folder, const std::string& name);

}  // namespace nanquim::io
