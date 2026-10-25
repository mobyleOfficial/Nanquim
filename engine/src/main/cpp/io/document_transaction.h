#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace nanquim::io {

class DocumentTransaction {
 public:
  explicit DocumentTransaction(std::string documentFolder);
  DocumentTransaction(const DocumentTransaction&) = delete;
  DocumentTransaction& operator=(const DocumentTransaction&) = delete;
  ~DocumentTransaction();

  bool stageFile(const std::string& relativePath, const std::vector<uint8_t>& bytes);

  void removeAfterCommit(const std::string& relativePath);

  bool commit(std::string& error);

  void setStepLimitForTesting(int64_t limit) { stepLimit_ = limit; }

  [[nodiscard]] const std::string& documentFolder() const { return documentFolder_; }

 private:
  bool step();

  std::mutex mutex_;
  std::string documentFolder_;
  std::vector<std::string> staged_;
  std::vector<std::string> removals_;
  bool committed_ = false;
  bool stopped_ = false;
  int64_t stepLimit_ = -1;
};

inline constexpr const char* kSaveJournalName = "save.journal";
inline constexpr const char* kStagedSuffix = ".tmp";

bool recoverDocumentFolder(const std::string& documentFolder, std::string& error);

[[nodiscard]] bool isSafeRelativePath(const std::string& relativePath);

}  // namespace nanquim::io
