#pragma once

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <string_view>
#include <vector>

namespace nanquim::core {

class JsonWriter {
 public:
  void beginObject() {
    beforeValue();
    output_.push_back('{');
    containerHasEntries_.push_back(false);
  }

  void endObject() {
    output_.push_back('}');
    containerHasEntries_.pop_back();
  }

  void beginArray() {
    beforeValue();
    output_.push_back('[');
    containerHasEntries_.push_back(false);
  }

  void endArray() {
    output_.push_back(']');
    containerHasEntries_.pop_back();
  }

  void key(std::string_view name) {
    beforeValue();
    appendEscapedString(output_, name);
    output_.push_back(':');
    expectingValueAfterKey_ = true;
  }

  void stringValue(std::string_view text) {
    beforeValue();
    appendEscapedString(output_, text);
  }

  void integerValue(int64_t number) {
    beforeValue();
    output_ += std::to_string(number);
  }

  void numberValue(double number) {
    beforeValue();
    if (!std::isfinite(number)) {
      output_ += "null";
      return;
    }
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "%.9g", number);
    output_ += buffer;
  }

  void booleanValue(bool flag) {
    beforeValue();
    output_ += flag ? "true" : "false";
  }

  void nullValue() {
    beforeValue();
    output_ += "null";
  }

  [[nodiscard]] const std::string& text() const { return output_; }

  static void appendEscapedString(std::string& output, std::string_view text) {
    output.push_back('"');
    for (const char character : text) {
      switch (character) {
        case '"':
          output += "\\\"";
          break;
        case '\\':
          output += "\\\\";
          break;
        case '\n':
          output += "\\n";
          break;
        case '\r':
          output += "\\r";
          break;
        case '\t':
          output += "\\t";
          break;
        default:
          if (static_cast<unsigned char>(character) < 0x20) {
            char escaped[8];
            std::snprintf(escaped, sizeof(escaped), "\\u%04x", static_cast<unsigned>(character));
            output += escaped;
          } else {
            output.push_back(character);
          }
      }
    }
    output.push_back('"');
  }

 private:
  void beforeValue() {
    if (expectingValueAfterKey_) {
      expectingValueAfterKey_ = false;
      return;
    }
    if (!containerHasEntries_.empty()) {
      if (containerHasEntries_.back()) {
        output_.push_back(',');
      }
      containerHasEntries_.back() = true;
    }
  }

  std::string output_;
  std::vector<bool> containerHasEntries_;
  bool expectingValueAfterKey_ = false;
};

}  // namespace nanquim::core
