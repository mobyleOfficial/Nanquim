#include "io/scratch_file.h"

#include <gtest/gtest.h>

#include <cstdio>
#include <cstdlib>
#include <string>

namespace nanquim::io {
namespace {

std::string temporaryPath(const char* name) {
  const char* directory = std::getenv("TEST_TMPDIR");
  return std::string(directory != nullptr ? directory : ".") + "/" + name;
}

TEST(ScratchFileTest, ReadsBackEveryAppendedBlob) {
  ScratchFile file(temporaryPath("scratch_file_test_roundtrip.bin"));
  const auto first = file.append({1, 2, 3});
  const auto second = file.append({9, 8, 7, 6});

  ASSERT_TRUE(first && second);
  if (first && second) {
    EXPECT_EQ(file.read(*second), (std::vector<uint8_t>{9, 8, 7, 6}));
    EXPECT_EQ(file.read(*first), (std::vector<uint8_t>{1, 2, 3}));
  }
  EXPECT_EQ(file.sizeBytes(), 7);
}

TEST(ScratchFileTest, RejectsLocationsOutsideTheFile) {
  ScratchFile file(temporaryPath("scratch_file_test_bounds.bin"));
  file.append({1, 2, 3});

  EXPECT_FALSE(file.read(ScratchLocation{2, 5}).has_value());
  EXPECT_FALSE(file.read(ScratchLocation{-1, 1}).has_value());
}

TEST(ScratchFileTest, TruncateDiscardsEverything) {
  ScratchFile file(temporaryPath("scratch_file_test_truncate.bin"));
  const auto location = file.append({1, 2, 3});
  file.truncate();

  EXPECT_EQ(file.sizeBytes(), 0);
  ASSERT_TRUE(location.has_value());
  if (location) {
    EXPECT_FALSE(file.read(*location).has_value());
  }
}

}  // namespace
}  // namespace nanquim::io
