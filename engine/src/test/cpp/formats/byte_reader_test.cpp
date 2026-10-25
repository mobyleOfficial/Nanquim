#include "formats/byte_reader.h"

#include <gtest/gtest.h>

#include <vector>

namespace nanquim::formats {
namespace {

TEST(ByteReader, ReadsBothEndiannesses) {
  const std::vector<uint8_t> bytes{0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0,
                                   0x01, 0x02, 0x03, 0x04, 0x3F, 0x80, 0x00, 0x00};
  ByteReader reader(bytes.data(), bytes.size());
  EXPECT_EQ(reader.u16be(), 0x1234);
  EXPECT_EQ(reader.u16le(), 0x7856);
  EXPECT_EQ(reader.u32be(), 0x9ABCDEF0U);
  EXPECT_EQ(reader.u32le(), 0x04030201U);
  EXPECT_FLOAT_EQ(reader.f32be(), 1.0F);
  EXPECT_TRUE(reader.ok());
  EXPECT_TRUE(reader.atEnd());
}

TEST(ByteReader, ReadingPastTheEndFailsAndStaysFailed) {
  const std::vector<uint8_t> bytes{1, 2, 3};
  ByteReader reader(bytes.data(), bytes.size());
  EXPECT_EQ(reader.u16be(), 0x0102);
  EXPECT_EQ(reader.u32be(), 0U);
  EXPECT_FALSE(reader.ok());
  EXPECT_EQ(reader.u8(), 0);
  EXPECT_FALSE(reader.ok());
}

TEST(ByteReader, HugeCountsDoNotOverflow) {
  const std::vector<uint8_t> bytes(16, 0);
  ByteReader reader(bytes.data(), bytes.size());
  reader.skip(4);
  reader.skip(UINT64_MAX);
  EXPECT_FALSE(reader.ok());
  ByteReader other(bytes.data(), bytes.size());
  EXPECT_EQ(other.take(UINT64_MAX - 1), nullptr);
  EXPECT_FALSE(other.ok());
  ByteReader third(bytes.data(), bytes.size());
  third.seek(17);
  EXPECT_FALSE(third.ok());
}

TEST(ByteReader, SubReaderIsBoundedAndAdvancesTheParent) {
  const std::vector<uint8_t> bytes{0, 0, 0, 2, 0xAA, 0xBB, 0xCC};
  ByteReader reader(bytes.data(), bytes.size());
  const uint32_t length = reader.u32be();
  ByteReader section = reader.sub(length);
  EXPECT_EQ(section.size(), 2U);
  EXPECT_EQ(section.u16be(), 0xAABB);
  EXPECT_EQ(section.u8(), 0);
  EXPECT_FALSE(section.ok());
  EXPECT_TRUE(reader.ok());
  EXPECT_EQ(reader.u8(), 0xCC);

  ByteReader parent(bytes.data(), bytes.size());
  ByteReader tooLong = parent.sub(100);
  EXPECT_FALSE(tooLong.ok());
  EXPECT_FALSE(parent.ok());
}

TEST(ByteReader, StringsAreRawBytes) {
  const std::vector<uint8_t> bytes{'8', 'B', 'P', 'S', 0, 1};
  ByteReader reader(bytes.data(), bytes.size());
  EXPECT_EQ(reader.string(4), "8BPS");
  EXPECT_EQ(reader.string(2), std::string("\0\1", 2));
  EXPECT_EQ(reader.string(1), "");
  EXPECT_FALSE(reader.ok());
}

}  // namespace
}  // namespace nanquim::formats
