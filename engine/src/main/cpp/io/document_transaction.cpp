#include "io/document_transaction.h"

#include <algorithm>
#include <functional>
#include <nlohmann/json.hpp>
#include <set>
#include <utility>

#include "io/file_system.h"

namespace nanquim::io {

namespace {

using Json = nlohmann::json;

constexpr const char* kManifestName = "manifest.json";
constexpr int32_t kJournalVersion = 1;
constexpr int64_t kMaximumJournalBytes = 64 * 1024 * 1024;

std::string parentOf(const std::string& path) {
  const size_t separator = path.find_last_of('/');
  return separator == std::string::npos ? std::string() : path.substr(0, separator);
}

bool endsWith(const std::string& text, const std::string& suffix) {
  return text.size() >= suffix.size() &&
         text.compare(text.size() - suffix.size(), suffix.size(), suffix) == 0;
}

struct Journal {
  std::vector<std::string> renames;
  std::vector<std::string> removals;
};

std::vector<uint8_t> journalBytes(const Journal& journal) {
  const std::string text = Json{{"version", kJournalVersion},
                                {"renames", journal.renames},
                                {"removals", journal.removals}}
                               .dump();
  return {text.begin(), text.end()};
}

bool parseJournal(const std::vector<uint8_t>& bytes, Journal& journal) {
  const Json value = Json::parse(bytes.begin(), bytes.end(), nullptr, false);
  if (!value.is_object() || value.value("version", 0) != kJournalVersion ||
      !value.contains("renames") || !value["renames"].is_array() || !value.contains("removals") ||
      !value["removals"].is_array()) {
    return false;
  }
  for (const char* key : {"renames", "removals"}) {
    for (const Json& entry : value[key]) {
      if (!entry.is_string() || !isSafeRelativePath(entry.get<std::string>())) {
        return false;
      }
      (std::string(key) == "renames" ? journal.renames : journal.removals)
          .push_back(entry.get<std::string>());
    }
  }
  return true;
}

bool applyJournal(const std::string& folder, const Journal& journal,
                  const std::function<bool()>& step) {
  std::set<std::string> touchedFolders;
  for (const std::string& target : journal.renames) {
    if (!step()) {
      return false;
    }
    const std::string from = joinPath(folder, target + kStagedSuffix);
    const std::string to = joinPath(folder, target);
    if (pathExists(from) && !renameReplacing(from, to)) {
      return false;
    }
    touchedFolders.insert(joinPath(folder, parentOf(target)));
  }
  for (const std::string& touched : touchedFolders) {
    syncFolder(touched);
  }
  for (const std::string& removal : journal.removals) {
    if (!step()) {
      return false;
    }
    if (!removePath(joinPath(folder, removal))) {
      return false;
    }
  }
  syncFolder(folder);
  return true;
}

void removeStagedFiles(const std::string& folder) {
  for (const std::string& name : listFolder(folder)) {
    const std::string path = joinPath(folder, name);
    if (endsWith(name, kStagedSuffix)) {
      removePath(path);
    } else if (!listFolder(path).empty()) {
      removeStagedFiles(path);
    }
  }
}

}  // namespace

bool isSafeRelativePath(const std::string& relativePath) {
  if (relativePath.empty() || relativePath.front() == '/' ||
      relativePath.find('\\') != std::string::npos || relativePath.find(':') != std::string::npos) {
    return false;
  }
  size_t start = 0;
  while (start <= relativePath.size()) {
    const size_t end = std::min(relativePath.find('/', start), relativePath.size());
    const std::string part = relativePath.substr(start, end - start);
    if (part.empty() || part == "." || part == "..") {
      return false;
    }
    start = end + 1;
  }
  return true;
}

DocumentTransaction::DocumentTransaction(std::string documentFolder)
    : documentFolder_(std::move(documentFolder)) {}

DocumentTransaction::~DocumentTransaction() {
  if (committed_ || stopped_) {
    return;
  }
  for (const std::string& path : staged_) {
    removePath(joinPath(documentFolder_, path + kStagedSuffix));
  }
}

bool DocumentTransaction::step() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (stepLimit_ == 0) {
    stopped_ = true;
    return false;
  }
  if (stepLimit_ > 0) {
    --stepLimit_;
  }
  return true;
}

bool DocumentTransaction::stageFile(const std::string& relativePath,
                                    const std::vector<uint8_t>& bytes) {
  if (committed_ || stopped_ || !isSafeRelativePath(relativePath) ||
      relativePath == kSaveJournalName || endsWith(relativePath, kStagedSuffix) || !step()) {
    return false;
  }
  if (!writeFile(joinPath(documentFolder_, relativePath + kStagedSuffix), bytes)) {
    return false;
  }
  std::lock_guard<std::mutex> lock(mutex_);
  if (std::find(staged_.begin(), staged_.end(), relativePath) == staged_.end()) {
    staged_.push_back(relativePath);
  }
  return true;
}

void DocumentTransaction::removeAfterCommit(const std::string& relativePath) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (isSafeRelativePath(relativePath) &&
      std::find(removals_.begin(), removals_.end(), relativePath) == removals_.end()) {
    removals_.push_back(relativePath);
  }
}

bool DocumentTransaction::commit(std::string& error) {
  if (committed_ || stopped_) {
    error = "transaction already finished";
    return false;
  }
  Journal journal;
  journal.renames = staged_;
  std::stable_partition(journal.renames.begin(), journal.renames.end(),
                        [](const std::string& path) { return path != kManifestName; });
  for (const std::string& removal : removals_) {
    if (std::find(staged_.begin(), staged_.end(), removal) == staged_.end()) {
      journal.removals.push_back(removal);
    }
  }
  std::vector<std::string> stagedPaths;
  for (const std::string& path : staged_) {
    stagedPaths.push_back(joinPath(documentFolder_, path + kStagedSuffix));
  }
  if (!step() || !syncFiles(documentFolder_, stagedPaths)) {
    error = stopped_ ? "stopped" : "cannot flush the staged files";
    return false;
  }
  const std::string journalPath = joinPath(documentFolder_, kSaveJournalName);
  if (!step() || !writeFileDurably(journalPath + kStagedSuffix, journalBytes(journal))) {
    error = stopped_ ? "stopped" : "cannot write the save journal";
    return false;
  }
  if (!step() || !renameReplacing(journalPath + kStagedSuffix, journalPath)) {
    error = stopped_ ? "stopped" : "cannot commit the save journal";
    return false;
  }
  syncFolder(documentFolder_);
  committed_ = true;
  if (!applyJournal(documentFolder_, journal, [this] { return step(); })) {
    error = stopped_ ? "stopped" : "cannot apply the save journal";
    return false;
  }
  if (!step()) {
    error = "stopped";
    return false;
  }
  removePath(journalPath);
  syncFolder(documentFolder_);
  return true;
}

bool recoverDocumentFolder(const std::string& documentFolder, std::string& error) {
  const std::string journalPath = joinPath(documentFolder, kSaveJournalName);
  if (pathExists(journalPath)) {
    Journal journal;
    const auto bytes = readFile(journalPath, kMaximumJournalBytes);
    if (!bytes || !parseJournal(*bytes, journal)) {
      error = "unreadable save journal";
      return false;
    }
    if (!applyJournal(documentFolder, journal, [] { return true; })) {
      error = "cannot apply the save journal";
      return false;
    }
    removePath(journalPath);
    syncFolder(documentFolder);
  }
  removeStagedFiles(documentFolder);
  return true;
}

}  // namespace nanquim::io
