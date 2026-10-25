#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace nanquim::formats {

class BinaryPlist {
 public:
  enum class Type { Null, Boolean, Integer, Real, Date, Data, String, Uid, Array, Set, Dictionary };

  struct Object {
    Type type = Type::Null;
    bool boolean = false;
    int64_t integer = 0;
    double real = 0;
    std::string text;
    std::vector<uint64_t> references;
  };

  static std::optional<BinaryPlist> parse(std::vector<uint8_t> bytes, std::string& error);

  [[nodiscard]] uint64_t objectCount() const { return offsets_.size(); }
  [[nodiscard]] uint64_t topObject() const { return top_; }

  [[nodiscard]] std::optional<Object> object(uint64_t index) const;

  [[nodiscard]] std::optional<uint64_t> dictionaryValue(const Object& dictionary,
                                                        const std::string& key) const;

 private:
  BinaryPlist() = default;
  [[nodiscard]] bool readUnsigned(uint64_t offset, uint32_t size, uint64_t& value) const;
  [[nodiscard]] bool readCount(uint64_t& offset, uint8_t info, uint64_t& count) const;

  std::vector<uint8_t> bytes_;
  std::vector<uint64_t> offsets_;
  uint32_t referenceSize_ = 1;
  uint64_t top_ = 0;
};

class KeyedArchive {
 public:
  using Object = BinaryPlist::Object;

  static std::optional<KeyedArchive> parse(std::vector<uint8_t> bytes, std::string& error);

  [[nodiscard]] std::optional<Object> root() const;

  [[nodiscard]] std::optional<Object> resolve(const Object& value) const;
  [[nodiscard]] std::optional<Object> resolve(uint64_t plistIndex) const;

  [[nodiscard]] std::string className(const Object& object) const;

  [[nodiscard]] std::optional<Object> member(const Object& object, const std::string& key) const;

  [[nodiscard]] std::string string(const Object& object, const std::string& key) const;
  [[nodiscard]] double number(const Object& object, const std::string& key, double fallback) const;
  [[nodiscard]] bool boolean(const Object& object, const std::string& key, bool fallback) const;
  [[nodiscard]] std::string data(const Object& object, const std::string& key) const;

  [[nodiscard]] std::vector<Object> array(const Object& object, const std::string& key) const;

  [[nodiscard]] std::optional<std::string> text(const Object& value) const;

  [[nodiscard]] const BinaryPlist& plist() const { return plist_; }

 private:
  explicit KeyedArchive(BinaryPlist plist) : plist_(std::move(plist)) {}

  BinaryPlist plist_;
  std::vector<uint64_t> objects_;
  uint64_t rootPlistIndex_ = 0;
};

std::optional<std::string> keyedArchiveJson(std::vector<uint8_t> bytes, std::string& error);

}  // namespace nanquim::formats
