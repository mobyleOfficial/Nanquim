#include "formats/binary_plist.h"

#include <cmath>
#include <cstring>
#include <nlohmann/json.hpp>

namespace nanquim::formats {

namespace {

constexpr char kMagic[] = "bplist00";
constexpr size_t kMagicBytes = 8;
constexpr size_t kTrailerBytes = 32;
constexpr int kMaximumUidHops = 8;

void appendUtf8(uint32_t codePoint, std::string& text) {
  if (codePoint < 0x80) {
    text.push_back(static_cast<char>(codePoint));
  } else if (codePoint < 0x800) {
    text.push_back(static_cast<char>(0xC0U | (codePoint >> 6U)));
    text.push_back(static_cast<char>(0x80U | (codePoint & 0x3FU)));
  } else if (codePoint < 0x10000) {
    text.push_back(static_cast<char>(0xE0U | (codePoint >> 12U)));
    text.push_back(static_cast<char>(0x80U | ((codePoint >> 6U) & 0x3FU)));
    text.push_back(static_cast<char>(0x80U | (codePoint & 0x3FU)));
  } else {
    text.push_back(static_cast<char>(0xF0U | (codePoint >> 18U)));
    text.push_back(static_cast<char>(0x80U | ((codePoint >> 12U) & 0x3FU)));
    text.push_back(static_cast<char>(0x80U | ((codePoint >> 6U) & 0x3FU)));
    text.push_back(static_cast<char>(0x80U | (codePoint & 0x3FU)));
  }
}

}  // namespace

bool BinaryPlist::readUnsigned(uint64_t offset, uint32_t size, uint64_t& value) const {
  if (size == 0 || size > 8 || offset > bytes_.size() || size > bytes_.size() - offset) {
    return false;
  }
  value = 0;
  for (uint32_t index = 0; index < size; ++index) {
    value = (value << 8U) | bytes_[offset + index];
  }
  return true;
}

bool BinaryPlist::readCount(uint64_t& offset, uint8_t info, uint64_t& count) const {
  if (info != 0xF) {
    count = info;
    return true;
  }
  if (offset >= bytes_.size()) {
    return false;
  }
  const uint8_t marker = bytes_[offset];
  if ((marker >> 4U) != 0x1 || (marker & 0xFU) > 3) {
    return false;
  }
  const uint32_t size = 1U << (marker & 0xFU);
  if (!readUnsigned(offset + 1, size, count)) {
    return false;
  }
  offset += 1 + size;
  return true;
}

std::optional<BinaryPlist> BinaryPlist::parse(std::vector<uint8_t> bytes, std::string& error) {
  BinaryPlist plist;
  plist.bytes_ = std::move(bytes);
  const std::vector<uint8_t>& data = plist.bytes_;
  if (data.size() < kMagicBytes + kTrailerBytes ||
      std::string(data.begin(), data.begin() + kMagicBytes) != kMagic) {
    error = "not a binary plist";
    return std::nullopt;
  }
  const size_t trailer = data.size() - kTrailerBytes;
  const uint32_t offsetSize = data[trailer + 6];
  plist.referenceSize_ = data[trailer + 7];
  uint64_t count = 0;
  uint64_t top = 0;
  uint64_t tableOffset = 0;
  if (!plist.readUnsigned(trailer + 8, 8, count) || !plist.readUnsigned(trailer + 16, 8, top) ||
      !plist.readUnsigned(trailer + 24, 8, tableOffset) || offsetSize == 0 || offsetSize > 8 ||
      plist.referenceSize_ == 0 || plist.referenceSize_ > 8 || count == 0 || count > data.size() ||
      top >= count || tableOffset > trailer || count > (trailer - tableOffset) / offsetSize) {
    error = "invalid binary plist trailer";
    return std::nullopt;
  }
  plist.offsets_.resize(count);
  for (uint64_t index = 0; index < count; ++index) {
    uint64_t offset = 0;
    if (!plist.readUnsigned(tableOffset + index * offsetSize, offsetSize, offset) ||
        offset < kMagicBytes || offset >= tableOffset) {
      error = "invalid object offset";
      return std::nullopt;
    }
    plist.offsets_[index] = offset;
  }
  plist.top_ = top;
  return plist;
}

std::optional<BinaryPlist::Object> BinaryPlist::object(uint64_t index) const {
  if (index >= offsets_.size()) {
    return std::nullopt;
  }
  uint64_t offset = offsets_[index];
  const uint8_t marker = bytes_[offset++];
  const auto info = static_cast<uint8_t>(marker & 0xFU);
  Object object;
  switch (marker >> 4U) {
    case 0x0:
      if (marker == 0x08 || marker == 0x09) {
        object.type = Type::Boolean;
        object.boolean = marker == 0x09;
      }
      return object;
    case 0x1: {
      if (info > 3) {
        return std::nullopt;
      }
      const uint32_t size = 1U << info;
      uint64_t value = 0;
      if (!readUnsigned(offset, size, value)) {
        return std::nullopt;
      }
      object.type = Type::Integer;
      object.integer = static_cast<int64_t>(value);
      return object;
    }
    case 0x2:
    case 0x3: {
      const uint32_t size = 1U << info;
      uint64_t bits = 0;
      if ((size != 4 && size != 8) || !readUnsigned(offset, size, bits)) {
        return std::nullopt;
      }
      if (size == 4) {
        const auto narrow = static_cast<uint32_t>(bits);
        float value = 0;
        std::memcpy(&value, &narrow, sizeof(value));
        object.real = value;
      } else {
        std::memcpy(&object.real, &bits, sizeof(object.real));
      }
      object.type = (marker >> 4U) == 0x2 ? Type::Real : Type::Date;
      return object;
    }
    case 0x4:
    case 0x5:
    case 0x6: {
      uint64_t count = 0;
      if (!readCount(offset, info, count)) {
        return std::nullopt;
      }
      const uint64_t unitBytes = (marker >> 4U) == 0x6 ? 2 : 1;
      if (count > (bytes_.size() - offset) / unitBytes) {
        return std::nullopt;
      }
      object.type = (marker >> 4U) == 0x4 ? Type::Data : Type::String;
      if (unitBytes == 1) {
        object.text.assign(bytes_.begin() + static_cast<std::ptrdiff_t>(offset),
                           bytes_.begin() + static_cast<std::ptrdiff_t>(offset + count));
        return object;
      }
      for (uint64_t unit = 0; unit < count; ++unit) {
        uint32_t value = (bytes_[offset + unit * 2] << 8U) | bytes_[offset + unit * 2 + 1];
        if (value >= 0xD800 && value < 0xDC00 && unit + 1 < count) {
          const uint32_t low =
              (bytes_[offset + unit * 2 + 2] << 8U) | bytes_[offset + unit * 2 + 3];
          if (low >= 0xDC00 && low < 0xE000) {
            value = 0x10000 + ((value - 0xD800) << 10U) + (low - 0xDC00);
            ++unit;
          }
        }
        appendUtf8(value >= 0xD800 && value < 0xE000 ? 0xFFFD : value, object.text);
      }
      return object;
    }
    case 0x8: {
      uint64_t value = 0;
      if (!readUnsigned(offset, info + 1U, value)) {
        return std::nullopt;
      }
      object.type = Type::Uid;
      object.integer = static_cast<int64_t>(value);
      return object;
    }
    case 0xA:
    case 0xC:
    case 0xD: {
      uint64_t count = 0;
      if (!readCount(offset, info, count)) {
        return std::nullopt;
      }
      const uint64_t references = (marker >> 4U) == 0xD ? count * 2 : count;
      if (count > bytes_.size() || references > (bytes_.size() - offset) / referenceSize_) {
        return std::nullopt;
      }
      object.type = (marker >> 4U) == 0xA   ? Type::Array
                    : (marker >> 4U) == 0xC ? Type::Set
                                            : Type::Dictionary;
      object.references.resize(references);
      for (uint64_t index = 0; index < references; ++index) {
        if (!readUnsigned(offset + index * referenceSize_, referenceSize_,
                          object.references[index])) {
          return std::nullopt;
        }
      }
      return object;
    }
    default:
      return std::nullopt;
  }
}

std::optional<uint64_t> BinaryPlist::dictionaryValue(const Object& dictionary,
                                                     const std::string& key) const {
  if (dictionary.type != Type::Dictionary) {
    return std::nullopt;
  }
  const size_t count = dictionary.references.size() / 2;
  for (size_t index = 0; index < count; ++index) {
    const auto candidate = object(dictionary.references[index]);
    if (candidate && candidate->type == Type::String && candidate->text == key) {
      return dictionary.references[count + index];
    }
  }
  return std::nullopt;
}

std::optional<KeyedArchive> KeyedArchive::parse(std::vector<uint8_t> bytes, std::string& error) {
  auto plist = BinaryPlist::parse(std::move(bytes), error);
  if (!plist) {
    return std::nullopt;
  }
  KeyedArchive archive(std::move(*plist));
  const BinaryPlist& source = archive.plist_;
  const auto top = source.object(source.topObject());
  const auto objectsIndex = top ? source.dictionaryValue(*top, "$objects") : std::nullopt;
  const auto topIndex = top ? source.dictionaryValue(*top, "$top") : std::nullopt;
  const auto objects = objectsIndex ? source.object(*objectsIndex) : std::nullopt;
  const auto topDictionary = topIndex ? source.object(*topIndex) : std::nullopt;
  const auto rootIndex =
      topDictionary ? source.dictionaryValue(*topDictionary, "root") : std::nullopt;
  if (!objects || objects->type != BinaryPlist::Type::Array || !rootIndex) {
    error = "not a keyed archive";
    return std::nullopt;
  }
  archive.objects_ = objects->references;
  archive.rootPlistIndex_ = *rootIndex;
  return archive;
}

std::optional<KeyedArchive::Object> KeyedArchive::root() const { return resolve(rootPlistIndex_); }

std::optional<KeyedArchive::Object> KeyedArchive::resolve(uint64_t plistIndex) const {
  const auto value = plist_.object(plistIndex);
  return value ? resolve(*value) : std::nullopt;
}

std::optional<KeyedArchive::Object> KeyedArchive::resolve(const Object& value) const {
  Object current = value;
  for (int hop = 0; current.type == BinaryPlist::Type::Uid; ++hop) {
    if (hop == kMaximumUidHops || current.integer < 0 ||
        static_cast<uint64_t>(current.integer) >= objects_.size()) {
      return std::nullopt;
    }
    auto next = plist_.object(objects_[static_cast<size_t>(current.integer)]);
    if (!next) {
      return std::nullopt;
    }
    current = std::move(*next);
  }
  if (current.type == BinaryPlist::Type::Null ||
      (current.type == BinaryPlist::Type::String && current.text == "$null")) {
    return std::nullopt;
  }
  return current;
}

std::optional<KeyedArchive::Object> KeyedArchive::member(const Object& object,
                                                         const std::string& key) const {
  const auto index = plist_.dictionaryValue(object, key);
  return index ? resolve(*index) : std::nullopt;
}

std::string KeyedArchive::className(const Object& object) const {
  const auto archivedClass = member(object, "$class");
  if (!archivedClass) {
    return {};
  }
  return string(*archivedClass, "$classname");
}

std::optional<std::string> KeyedArchive::text(const Object& value) const {
  if (value.type == BinaryPlist::Type::String) {
    return value.text;
  }
  if (value.type == BinaryPlist::Type::Dictionary) {
    const auto inner = member(value, "NS.string");
    if (inner && inner->type == BinaryPlist::Type::String) {
      return inner->text;
    }
  }
  return std::nullopt;
}

std::string KeyedArchive::string(const Object& object, const std::string& key) const {
  const auto value = member(object, key);
  return value ? text(*value).value_or(std::string()) : std::string();
}

double KeyedArchive::number(const Object& object, const std::string& key, double fallback) const {
  const auto value = member(object, key);
  if (!value) {
    return fallback;
  }
  switch (value->type) {
    case BinaryPlist::Type::Integer:
      return static_cast<double>(value->integer);
    case BinaryPlist::Type::Real:
      return value->real;
    case BinaryPlist::Type::Boolean:
      return value->boolean ? 1 : 0;
    default:
      return fallback;
  }
}

bool KeyedArchive::boolean(const Object& object, const std::string& key, bool fallback) const {
  const auto value = member(object, key);
  if (!value) {
    return fallback;
  }
  if (value->type == BinaryPlist::Type::Boolean) {
    return value->boolean;
  }
  if (value->type == BinaryPlist::Type::Integer) {
    return value->integer != 0;
  }
  return fallback;
}

std::string KeyedArchive::data(const Object& object, const std::string& key) const {
  const auto value = member(object, key);
  if (!value) {
    return {};
  }
  if (value->type == BinaryPlist::Type::Data) {
    return value->text;
  }
  if (value->type == BinaryPlist::Type::Dictionary) {
    const auto bytes = member(*value, "NS.bytes");
    if (bytes && bytes->type == BinaryPlist::Type::Data) {
      return bytes->text;
    }
  }
  return {};
}

std::vector<KeyedArchive::Object> KeyedArchive::array(const Object& object,
                                                      const std::string& key) const {
  std::vector<Object> elements;
  const auto container = member(object, key);
  if (!container) {
    return elements;
  }
  const auto list = member(*container, "NS.objects");
  if (!list || list->type != BinaryPlist::Type::Array) {
    return elements;
  }
  for (const uint64_t reference : list->references) {
    if (auto element = resolve(reference)) {
      elements.push_back(std::move(*element));
    }
  }
  return elements;
}

namespace {

constexpr int kMaximumJsonDepth = 16;
constexpr size_t kMaximumJsonElements = 1'000'000;

std::string base64(const std::string& bytes) {
  static constexpr char kAlphabet[] =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  std::string text;
  text.reserve((bytes.size() + 2) / 3 * 4);
  for (size_t index = 0; index < bytes.size(); index += 3) {
    uint32_t chunk = static_cast<uint8_t>(bytes[index]) << 16U;
    if (index + 1 < bytes.size()) {
      chunk |= static_cast<uint8_t>(bytes[index + 1]) << 8U;
    }
    if (index + 2 < bytes.size()) {
      chunk |= static_cast<uint8_t>(bytes[index + 2]);
    }
    text.push_back(kAlphabet[(chunk >> 18U) & 63U]);
    text.push_back(kAlphabet[(chunk >> 12U) & 63U]);
    text.push_back(index + 1 < bytes.size() ? kAlphabet[(chunk >> 6U) & 63U] : '=');
    text.push_back(index + 2 < bytes.size() ? kAlphabet[chunk & 63U] : '=');
  }
  return text;
}

class JsonConverter {
 public:
  explicit JsonConverter(const KeyedArchive& archive) : archive_(archive) {}

  nlohmann::json convert(const KeyedArchive::Object& object, int depth) {
    if (depth > kMaximumJsonDepth || ++elements_ > kMaximumJsonElements) {
      return nullptr;
    }
    using Type = BinaryPlist::Type;
    switch (object.type) {
      case Type::Boolean:
        return object.boolean;
      case Type::Integer:
        return object.integer;
      case Type::Real:
      case Type::Date:
        return std::isfinite(object.real) ? nlohmann::json(object.real) : nlohmann::json(nullptr);
      case Type::String:
        return object.text;
      case Type::Data:
        return base64(object.text);
      case Type::Array:
      case Type::Set: {
        nlohmann::json array = nlohmann::json::array();
        for (const uint64_t reference : object.references) {
          array.push_back(element(reference, depth));
        }
        return array;
      }
      case Type::Dictionary:
        return dictionary(object, depth);
      default:
        return nullptr;
    }
  }

 private:
  nlohmann::json element(uint64_t reference, int depth) {
    const auto value = archive_.resolve(reference);
    return value ? convert(*value, depth + 1) : nlohmann::json(nullptr);
  }

  nlohmann::json dictionary(const KeyedArchive::Object& object, int depth) {
    const std::string className = archive_.className(object);
    const BinaryPlist& plist = archive_.plist();
    if (className == "NSString" || className == "NSMutableString") {
      return archive_.text(object).value_or(std::string());
    }
    if (className == "NSArray" || className == "NSMutableArray" || className == "NSSet" ||
        className == "NSMutableSet") {
      const auto list = archive_.member(object, "NS.objects");
      return list ? convert(*list, depth) : nlohmann::json::array();
    }
    if (className == "NSData" || className == "NSMutableData") {
      const auto bytes = archive_.member(object, "NS.bytes");
      return bytes ? convert(*bytes, depth) : nlohmann::json(nullptr);
    }
    if (className == "NSDictionary" || className == "NSMutableDictionary") {
      nlohmann::json result = nlohmann::json::object();
      const auto keys = archive_.member(object, "NS.keys");
      const auto values = archive_.member(object, "NS.objects");
      if (keys && values && keys->references.size() == values->references.size()) {
        for (size_t index = 0; index < keys->references.size(); ++index) {
          const auto key = archive_.resolve(keys->references[index]);
          const auto text = key ? archive_.text(*key) : std::nullopt;
          if (text) {
            result[*text] = element(values->references[index], depth);
          }
        }
      }
      return result;
    }
    nlohmann::json result = nlohmann::json::object();
    if (!className.empty()) {
      result["$class"] = className;
    }
    const size_t count = object.references.size() / 2;
    for (size_t index = 0; index < count; ++index) {
      const auto key = plist.object(object.references[index]);
      if (!key || key->type != BinaryPlist::Type::String || key->text == "$class") {
        continue;
      }
      result[key->text] = element(object.references[count + index], depth);
    }
    return result;
  }

  const KeyedArchive& archive_;
  size_t elements_ = 0;
};

}  // namespace

std::optional<std::string> keyedArchiveJson(std::vector<uint8_t> bytes, std::string& error) {
  const auto archive = KeyedArchive::parse(std::move(bytes), error);
  if (!archive) {
    return std::nullopt;
  }
  const auto root = archive->root();
  if (!root) {
    error = "no root object";
    return std::nullopt;
  }
  JsonConverter converter(*archive);
  return converter.convert(*root, 0).dump(-1, ' ', false, nlohmann::json::error_handler_t::replace);
}

}  // namespace nanquim::formats
