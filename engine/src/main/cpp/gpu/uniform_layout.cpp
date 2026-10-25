#include "gpu/uniform_layout.h"

#include <algorithm>
#include <cctype>
#include <regex>

namespace nanquim::gpu {
namespace {

struct TypeInfo {
  UniformMember::Kind kind;
  int32_t components;
};

bool typeInfo(const std::string& type, TypeInfo& info) {
  static const std::pair<const char*, UniformMember::Kind> kPrefixes[] = {
      {"", UniformMember::Kind::Float},
      {"i", UniformMember::Kind::Int},
      {"u", UniformMember::Kind::UInt},
      {"b", UniformMember::Kind::Bool}};
  static const std::pair<const char*, TypeInfo> kScalars[] = {
      {"float", {UniformMember::Kind::Float, 1}},
      {"int", {UniformMember::Kind::Int, 1}},
      {"uint", {UniformMember::Kind::UInt, 1}},
      {"bool", {UniformMember::Kind::Bool, 1}}};
  for (const auto& [name, scalar] : kScalars) {
    if (type == name) {
      info = scalar;
      return true;
    }
  }
  for (const auto& [prefix, kind] : kPrefixes) {
    for (int32_t components = 2; components <= 4; ++components) {
      if (type == std::string(prefix) + "vec" + std::to_string(components)) {
        info = TypeInfo{kind, components};
        return true;
      }
    }
  }
  return false;
}

uint32_t roundUp(uint32_t value, uint32_t multiple) {
  return (value + multiple - 1) / multiple * multiple;
}

std::string withoutComments(std::string_view source) {
  std::string result;
  result.reserve(source.size());
  for (size_t index = 0; index < source.size(); ++index) {
    if (source.compare(index, 2, "//") == 0) {
      while (index < source.size() && source[index] != '\n') {
        ++index;
      }
      if (index < source.size()) {
        result += '\n';
      }
      continue;
    }
    if (source.compare(index, 2, "/*") == 0) {
      const size_t end = source.find("*/", index + 2);
      index = end == std::string_view::npos ? source.size() : end + 1;
      result += ' ';
      continue;
    }
    result += source[index];
  }
  return result;
}

}  // namespace

bool parseUniformLayout(std::string_view source, UniformLayout& layout, std::string& errorMessage) {
  layout = UniformLayout{};
  const std::string text = withoutComments(source);
  static const std::regex kBegin(R"(\bUNIFORMS_BEGIN\s*\(\s*(\w+)\s*\))");
  std::smatch begin;
  if (!std::regex_search(text, begin, kBegin)) {
    if (text.find("UNIFORM(") != std::string::npos ||
        text.find("UNIFORM_ARRAY(") != std::string::npos) {
      errorMessage = "UNIFORM outside UNIFORMS_BEGIN … UNIFORMS_END";
      return false;
    }
    return true;
  }
  const size_t bodyStart = static_cast<size_t>(begin.position(0) + begin.length(0));
  const size_t bodyEnd = text.find("UNIFORMS_END", bodyStart);
  if (bodyEnd == std::string::npos) {
    errorMessage = "UNIFORMS_BEGIN without UNIFORMS_END";
    return false;
  }
  if (text.find("UNIFORMS_BEGIN", bodyStart) != std::string::npos) {
    errorMessage = "more than one UNIFORMS_BEGIN";
    return false;
  }
  layout.blockName = begin[1];
  const std::string body = text.substr(bodyStart, bodyEnd - bodyStart);
  if (body.find('#') != std::string::npos) {
    errorMessage = "preprocessor directive inside the uniforms of " + layout.blockName;
    return false;
  }
  static const std::regex kMember(
      R"(\b(UNIFORM|UNIFORM_ARRAY)\s*\(\s*(\w+)\s*,\s*(\w+)\s*(?:,\s*(\w+)\s*)?\))");
  uint32_t offset = 0;
  size_t consumed = 0;
  for (auto match = std::sregex_iterator(body.begin(), body.end(), kMember);
       match != std::sregex_iterator(); ++match) {
    const std::string between =
        body.substr(consumed, static_cast<size_t>(match->position(0)) - consumed);
    if (between.find_first_not_of(" \t\r\n") != std::string::npos) {
      errorMessage = "unexpected text in the uniforms of " + layout.blockName + ": " + between;
      return false;
    }
    consumed = static_cast<size_t>(match->position(0) + match->length(0));
    const bool array = (*match)[1] == "UNIFORM_ARRAY";
    UniformMember member;
    member.name = (*match)[3];
    TypeInfo info{};
    if (!typeInfo((*match)[2], info)) {
      errorMessage = "unsupported uniform type " + (*match)[2].str() + " of " + member.name;
      return false;
    }
    member.kind = info.kind;
    member.components = info.components;
    const uint32_t size = 4U * static_cast<uint32_t>(info.components);
    uint32_t alignment = info.components == 3 ? 16U : size;
    if (array) {
      const std::string count = (*match)[4];
      if (count.empty() || !std::all_of(count.begin(), count.end(), [](char character) {
            return std::isdigit(static_cast<unsigned char>(character)) != 0;
          })) {
        errorMessage = "array size of " + member.name + " must be a decimal literal";
        return false;
      }
      member.count = std::stoi(count);
      if (member.count < 1) {
        errorMessage = "empty array " + member.name;
        return false;
      }
      alignment = 16;
      member.stride = roundUp(size, 16);
    } else if ((*match)[4].matched) {
      errorMessage = "UNIFORM takes a type and a name: " + member.name;
      return false;
    }
    offset = roundUp(offset, alignment);
    member.offset = offset;
    offset += array ? member.stride * static_cast<uint32_t>(member.count) : size;
    layout.members.push_back(std::move(member));
  }
  if (body.substr(consumed).find_first_not_of(" \t\r\n") != std::string::npos) {
    errorMessage = "unexpected text in the uniforms of " + layout.blockName;
    return false;
  }
  layout.size = roundUp(offset, 16);
  return true;
}

}  // namespace nanquim::gpu
