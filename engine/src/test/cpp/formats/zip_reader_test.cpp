#include "formats/zip_reader.h"

#include <gtest/gtest.h>
#include <miniz.h>

#include <algorithm>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

namespace nanquim::formats {
namespace {

using Entries = std::vector<std::pair<std::string, std::string>>;

std::vector<uint8_t> makeZip(const Entries& entries) {
  mz_zip_archive zip{};
  EXPECT_TRUE(mz_zip_writer_init_heap(&zip, 0, 0));
  for (const auto& [name, content] : entries) {
    EXPECT_TRUE(mz_zip_writer_add_mem(&zip, name.c_str(), content.data(), content.size(),
                                      MZ_DEFAULT_COMPRESSION));
  }
  void* buffer = nullptr;
  size_t size = 0;
  EXPECT_TRUE(mz_zip_writer_finalize_heap_archive(&zip, &buffer, &size));
  std::vector<uint8_t> bytes(static_cast<uint8_t*>(buffer), static_cast<uint8_t*>(buffer) + size);
  mz_zip_writer_end(&zip);
  return bytes;
}

void replaceAll(std::vector<uint8_t>& bytes, const std::string& from, const std::string& to) {
  ASSERT_EQ(from.size(), to.size());
  auto position = bytes.begin();
  while ((position = std::search(position, bytes.end(), from.begin(), from.end())) != bytes.end()) {
    std::copy(to.begin(), to.end(), position);
    position += static_cast<std::ptrdiff_t>(to.size());
  }
}

std::unique_ptr<ZipReader> openBytes(MemoryInputSource& source, const ZipLimits& limits,
                                     std::string& error) {
  return ZipReader::open(source, limits, error);
}

TEST(ZipReader, ListsAndExtractsEntries) {
  const std::string big(100'000, 'x');
  MemoryInputSource source(makeZip({{"Document.archive", "hello"},
                                    {"layers/1/0~0.chunk", big},
                                    {"QuickLook/Thumbnail.png", ""}}));
  std::string error;
  auto reader = openBytes(source, {}, error);
  ASSERT_NE(reader, nullptr) << error;
  ASSERT_EQ(reader->entries().size(), 3U);
  const ZipEntry* chunk = reader->find("layers/1/0~0.chunk");
  ASSERT_NE(chunk, nullptr);
  EXPECT_EQ(chunk->uncompressedSize, big.size());
  EXPECT_LT(chunk->compressedSize, big.size());
  std::vector<uint8_t> bytes;
  ASSERT_TRUE(reader->extract(*chunk, big.size(), bytes, error)) << error;
  EXPECT_EQ(std::string(bytes.begin(), bytes.end()), big);
  ASSERT_TRUE(reader->extract(*reader->find("Document.archive"), 5, bytes, error)) << error;
  EXPECT_EQ(std::string(bytes.begin(), bytes.end()), "hello");
  ASSERT_TRUE(reader->extract(*reader->find("QuickLook/Thumbnail.png"), 0, bytes, error));
  EXPECT_TRUE(bytes.empty());
  EXPECT_EQ(reader->find("missing"), nullptr);
}

TEST(ZipReader, ExtractionRespectsTheSizeBound) {
  MemoryInputSource source(makeZip({{"a", std::string(1000, 'a')}}));
  std::string error;
  auto reader = openBytes(source, {}, error);
  ASSERT_NE(reader, nullptr);
  std::vector<uint8_t> bytes;
  EXPECT_FALSE(reader->extract(reader->entries()[0], 999, bytes, error));
  EXPECT_TRUE(bytes.empty());
}

TEST(ZipReader, LyingDeclaredSizeIsCaught) {
  std::vector<uint8_t> bytes = makeZip({{"entry", std::string(4096, 'z')}});
  const std::vector<uint8_t> signature{0x50, 0x4B, 0x01, 0x02};
  auto central = std::search(bytes.begin(), bytes.end(), signature.begin(), signature.end());
  ASSERT_NE(central, bytes.end());
  const auto offset = static_cast<size_t>(central - bytes.begin()) + 24;
  ASSERT_EQ(bytes[offset], 0x00);
  ASSERT_EQ(bytes[offset + 1], 0x10);
  bytes[offset] = 0x10;
  bytes[offset + 1] = 0x00;
  MemoryInputSource source(std::move(bytes));
  std::string error;
  auto reader = openBytes(source, {}, error);
  ASSERT_NE(reader, nullptr) << error;
  EXPECT_EQ(reader->entries()[0].uncompressedSize, 16U);
  std::vector<uint8_t> extracted;
  EXPECT_FALSE(reader->extract(reader->entries()[0], 1U << 20U, extracted, error));
  EXPECT_TRUE(extracted.empty());
}

TEST(ZipReader, CorruptDataFailsTheChecksum) {
  std::string content(5000, '\0');
  for (size_t index = 0; index < content.size(); ++index) {
    content[index] = static_cast<char>((index * 7919U) % 251U);
  }
  std::vector<uint8_t> bytes = makeZip({{"data", content}});
  bytes[60] ^= 0x5A;
  MemoryInputSource source(std::move(bytes));
  std::string error;
  auto reader = openBytes(source, {}, error);
  ASSERT_NE(reader, nullptr);
  std::vector<uint8_t> extracted;
  EXPECT_FALSE(reader->extract(reader->entries()[0], 1U << 20U, extracted, error));
}

TEST(ZipReader, EntryCountLimit) {
  Entries entries;
  for (int index = 0; index < 11; ++index) {
    entries.emplace_back("e" + std::to_string(index), "x");
  }
  MemoryInputSource source(makeZip(entries));
  std::string error;
  ZipLimits limits;
  limits.maximumEntries = 10;
  EXPECT_EQ(openBytes(source, limits, error), nullptr);
  limits.maximumEntries = 11;
  EXPECT_NE(openBytes(source, limits, error), nullptr);
}

TEST(ZipReader, TotalSizeLimit) {
  MemoryInputSource source(makeZip({{"a", std::string(600, 'a')}, {"b", std::string(600, 'b')}}));
  std::string error;
  ZipLimits limits;
  limits.maximumTotalUncompressedBytes = 1000;
  EXPECT_EQ(openBytes(source, limits, error), nullptr);
  limits.maximumTotalUncompressedBytes = 1200;
  EXPECT_NE(openBytes(source, limits, error), nullptr);
}

TEST(ZipReader, UnsafeNamesAreRejected) {
  for (const char* name : {"../evil", "/a/evil", "a/../ev", "C:/evil", "a\\b/evl"}) {
    const std::string unsafe(name);
    std::string placeholder(unsafe.size(), 'q');
    std::vector<uint8_t> bytes = makeZip({{placeholder, "payload"}});
    replaceAll(bytes, placeholder, unsafe);
    MemoryInputSource source(std::move(bytes));
    std::string error;
    EXPECT_EQ(openBytes(source, {}, error), nullptr) << unsafe;
  }
}

TEST(ZipReader, NotAZip) {
  MemoryInputSource empty(std::vector<uint8_t>{});
  MemoryInputSource text(std::vector<uint8_t>{'h', 'e', 'l', 'l', 'o'});
  std::string error;
  EXPECT_EQ(openBytes(empty, {}, error), nullptr);
  EXPECT_EQ(openBytes(text, {}, error), nullptr);
  EXPECT_FALSE(error.empty());
}

TEST(ZipReader, SafeNames) {
  EXPECT_TRUE(isSafeZipEntryName("Document.archive"));
  EXPECT_TRUE(isSafeZipEntryName("a/b/c.png"));
  EXPECT_TRUE(isSafeZipEntryName("folder/"));
  EXPECT_TRUE(isSafeZipEntryName("..name"));
  EXPECT_FALSE(isSafeZipEntryName(""));
  EXPECT_FALSE(isSafeZipEntryName(".."));
  EXPECT_FALSE(isSafeZipEntryName("a/.."));
  EXPECT_FALSE(isSafeZipEntryName("./a"));
  EXPECT_FALSE(isSafeZipEntryName("a//b"));
  EXPECT_FALSE(isSafeZipEntryName("a/."));
  EXPECT_FALSE(isSafeZipEntryName(std::string("a\0b", 3)));
}

}  // namespace
}  // namespace nanquim::formats
