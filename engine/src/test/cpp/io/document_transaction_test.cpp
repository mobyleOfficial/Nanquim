#include "io/document_transaction.h"

#include <gtest/gtest.h>

#include <filesystem>
#include <map>

#include "io/file_system.h"

namespace nanquim::io {
namespace {

namespace fs = std::filesystem;

using FolderContents = std::map<std::string, std::string>;

std::vector<uint8_t> bytesOf(const std::string& text) { return {text.begin(), text.end()}; }

FolderContents contentsOf(const fs::path& folder) {
  FolderContents contents;
  for (const auto& entry : fs::recursive_directory_iterator(folder)) {
    if (entry.is_regular_file()) {
      const auto bytes = readFile(entry.path().u8string(), 1 << 20);
      contents[fs::relative(entry.path(), folder).generic_u8string()] =
          std::string(bytes->begin(), bytes->end());
    }
  }
  return contents;
}

fs::path freshFolder(const std::string& name) {
  const fs::path folder = fs::temp_directory_path() / ("nanquim_transaction_" + name);
  fs::remove_all(folder);
  fs::create_directories(folder);
  return folder;
}

void writeOldState(const fs::path& folder) {
  DocumentTransaction transaction(folder.u8string());
  ASSERT_TRUE(transaction.stageFile("layers/a/tiles/0_0.lz4", bytesOf("old 0_0")));
  ASSERT_TRUE(transaction.stageFile("layers/a/tiles/1_0.lz4", bytesOf("old 1_0")));
  ASSERT_TRUE(transaction.stageFile("layers/a/index.json", bytesOf("index a v1")));
  ASSERT_TRUE(transaction.stageFile("layers/gone/tiles/0_0.lz4", bytesOf("gone")));
  ASSERT_TRUE(transaction.stageFile("manifest.json", bytesOf("manifest v1")));
  std::string error;
  ASSERT_TRUE(transaction.commit(error)) << error;
}

bool writeNewState(const fs::path& folder, int64_t stepLimit) {
  DocumentTransaction transaction(folder.u8string());
  transaction.setStepLimitForTesting(stepLimit);
  bool staged = transaction.stageFile("manifest.json", bytesOf("manifest v2")) &&
                transaction.stageFile("layers/a/tiles/0_0.lz4", bytesOf("new 0_0")) &&
                transaction.stageFile("layers/a/tiles/2_3.lz4", bytesOf("new 2_3")) &&
                transaction.stageFile("layers/a/index.json", bytesOf("index a v2")) &&
                transaction.stageFile("layers/b/tiles/0_0.lz4", bytesOf("b 0_0")) &&
                transaction.stageFile("thumbnail.png", bytesOf("thumbnail v2"));
  transaction.removeAfterCommit("layers/a/tiles/1_0.lz4");
  transaction.removeAfterCommit("layers/gone");
  std::string error;
  return staged && transaction.commit(error);
}

TEST(DocumentTransaction, CommitReplacesFilesAndRemovesTheRest) {
  const fs::path folder = freshFolder("commit");
  writeOldState(folder);
  ASSERT_TRUE(writeNewState(folder, -1));
  const FolderContents expected{
      {"manifest.json", "manifest v2"},           {"thumbnail.png", "thumbnail v2"},
      {"layers/a/index.json", "index a v2"},      {"layers/a/tiles/0_0.lz4", "new 0_0"},
      {"layers/a/tiles/2_3.lz4", "new 2_3"},      {"layers/b/tiles/0_0.lz4", "b 0_0"}};
  EXPECT_EQ(contentsOf(folder), expected);
  EXPECT_FALSE(fs::exists(folder / "layers/gone"));
}

TEST(DocumentTransaction, InterruptedSaveRecoversToOldOrNewState) {
  const fs::path reference = freshFolder("reference");
  writeOldState(reference);
  const FolderContents oldState = contentsOf(reference);
  ASSERT_TRUE(writeNewState(reference, -1));
  const FolderContents newState = contentsOf(reference);
  ASSERT_NE(oldState, newState);

  bool sawOld = false;
  bool sawNew = false;
  for (int64_t limit = 0;; ++limit) {
    const fs::path folder = freshFolder("killed");
    writeOldState(folder);
    const bool finished = writeNewState(folder, limit);
    std::string error;
    ASSERT_TRUE(recoverDocumentFolder(folder.u8string(), error)) << error;
    const FolderContents recovered = contentsOf(folder);
    const bool isOld = recovered == oldState;
    const bool isNew = recovered == newState;
    EXPECT_TRUE(isOld || isNew) << "inconsistent folder after " << limit << " steps";
    sawOld = sawOld || isOld;
    sawNew = sawNew || isNew;
    if (sawNew) {
      EXPECT_TRUE(isNew) << "save lost after its commit point (" << limit << " steps)";
    }
    if (finished) {
      break;
    }
    ASSERT_LT(limit, 100);
  }
  EXPECT_TRUE(sawOld);
  EXPECT_TRUE(sawNew);
}

TEST(DocumentTransaction, UncommittedTransactionLeavesNoFiles) {
  const fs::path folder = freshFolder("abandoned");
  writeOldState(folder);
  const FolderContents before = contentsOf(folder);
  {
    DocumentTransaction transaction(folder.u8string());
    ASSERT_TRUE(transaction.stageFile("layers/a/tiles/0_0.lz4", bytesOf("never")));
  }
  EXPECT_EQ(contentsOf(folder), before);
}

TEST(DocumentTransaction, RejectsUnsafePaths) {
  const fs::path folder = freshFolder("unsafe");
  DocumentTransaction transaction(folder.u8string());
  for (const char* path : {"", "/etc/x", "../x", "a/../b", "a//b", "a/./b", "a\\b", "C:/x",
                           "save.journal", "x.tmp"}) {
    EXPECT_FALSE(transaction.stageFile(path, bytesOf("x"))) << path;
  }
  EXPECT_TRUE(isSafeRelativePath("layers/6f1c/tiles/0_0.lz4"));
}

TEST(DocumentTransaction, RecoveryRejectsAnUnreadableJournal) {
  const fs::path folder = freshFolder("bad_journal");
  ASSERT_TRUE(writeFileDurably((folder / kSaveJournalName).u8string(), bytesOf("{not json")));
  std::string error;
  EXPECT_FALSE(recoverDocumentFolder(folder.u8string(), error));
  ASSERT_TRUE(writeFileDurably(
      (folder / kSaveJournalName).u8string(),
      bytesOf(R"({"version":1,"renames":["../outside"],"removals":[]})")));
  EXPECT_FALSE(recoverDocumentFolder(folder.u8string(), error));
}

}  // namespace
}  // namespace nanquim::io
