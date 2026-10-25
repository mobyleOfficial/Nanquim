#include "core/json_writer.h"

#include <gtest/gtest.h>

#include <limits>

namespace nanquim::core {
namespace {

TEST(JsonWriterTest, WritesNestedObjectsAndArrays) {
  JsonWriter writer;
  writer.beginObject();
  writer.key("name");
  writer.stringValue("Mali-G68");
  writer.key("maxTextureSize");
  writer.integerValue(16384);
  writer.key("halfFloat");
  writer.booleanValue(true);
  writer.key("formats");
  writer.beginArray();
  writer.integerValue(1);
  writer.integerValue(2);
  writer.endArray();
  writer.key("empty");
  writer.beginObject();
  writer.endObject();
  writer.key("missing");
  writer.nullValue();
  writer.endObject();

  EXPECT_EQ(writer.text(),
            R"({"name":"Mali-G68","maxTextureSize":16384,"halfFloat":true,"formats":[1,2],)"
            R"("empty":{},"missing":null})");
}

TEST(JsonWriterTest, EscapesQuotesBackslashesAndControlCharacters) {
  JsonWriter writer;
  writer.stringValue("a\"b\\c\nd\te\x01");

  EXPECT_EQ(writer.text(), R"("a\"b\\c\nd\te\u0001")");
}

TEST(JsonWriterTest, PassesUtf8Through) {
  JsonWriter writer;
  writer.stringValue("nanquim — tinta");

  EXPECT_EQ(writer.text(), "\"nanquim — tinta\"");
}

TEST(JsonWriterTest, WritesNonFiniteNumbersAsNull) {
  JsonWriter writer;
  writer.beginArray();
  writer.numberValue(0.5);
  writer.numberValue(std::numeric_limits<double>::infinity());
  writer.numberValue(std::numeric_limits<double>::quiet_NaN());
  writer.endArray();

  EXPECT_EQ(writer.text(), "[0.5,null,null]");
}

}  // namespace
}  // namespace nanquim::core
