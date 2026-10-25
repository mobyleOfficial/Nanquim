#include "gpu/shader_source.h"

#include <vector>

namespace nanquim::gpu {
namespace {

std::string_view includeName(std::string_view line) {
  size_t start = line.find_first_not_of(" \t");
  if (start == std::string_view::npos || line.compare(start, 4, "// @") != 0) {
    return {};
  }
  start += 4;
  size_t end = line.find_last_not_of(" \t\r\n");
  if (end == std::string_view::npos || end < start) {
    return {};
  }
  const std::string_view name = line.substr(start, end + 1 - start);
  for (const char character : name) {
    const bool word = (character >= 'a' && character <= 'z') ||
                      (character >= 'A' && character <= 'Z') ||
                      (character >= '0' && character <= '9') || character == '_';
    if (!word) {
      return {};
    }
  }
  return name;
}

void appendWithIncludes(std::string_view source, const ShaderLookup& lookup,
                        std::vector<std::string>& active, std::string& result,
                        std::string& errorMessage) {
  size_t lineStart = 0;
  while (lineStart < source.size()) {
    size_t lineEnd = source.find('\n', lineStart);
    const bool lastLine = lineEnd == std::string_view::npos;
    lineEnd = lastLine ? source.size() : lineEnd + 1;
    const std::string_view line = source.substr(lineStart, lineEnd - lineStart);
    lineStart = lineEnd;
    const std::string_view name = includeName(line);
    if (name.empty()) {
      result += line;
      continue;
    }
    bool cycle = false;
    for (const std::string& open : active) {
      cycle = cycle || open == name;
    }
    const std::optional<std::string_view> included = cycle ? std::nullopt : lookup(name);
    if (!included) {
      if (!errorMessage.empty()) {
        errorMessage += "; ";
      }
      errorMessage += (cycle ? "include cycle at " : "unknown include ") + std::string(name);
      result += line;
      continue;
    }
    active.emplace_back(name);
    appendWithIncludes(*included, lookup, active, result, errorMessage);
    active.pop_back();
    if (!result.empty() && result.back() != '\n') {
      result += '\n';
    }
  }
}

}  // namespace

std::string withIncludes(std::string_view source, const ShaderLookup& lookup,
                         std::string& errorMessage) {
  errorMessage.clear();
  std::string result;
  result.reserve(source.size());
  std::vector<std::string> active;
  appendWithIncludes(source, lookup, active, result, errorMessage);
  return result;
}

namespace {

std::string insertedAfterVersion(std::string_view source, const std::string& text) {
  std::string result(source);
  const size_t lineEnd = result.find('\n');
  const size_t insertAt =
      result.rfind("#version", 0) == 0 && lineEnd != std::string::npos ? lineEnd + 1 : 0;
  result.insert(insertAt, text);
  return result;
}

}  // namespace

std::string withDefines(std::string_view source, const std::vector<std::string>& defines) {
  std::string block;
  for (const std::string& define : defines) {
    block += "#define " + define + " 1\n";
  }
  return insertedAfterVersion(source, block);
}

std::string shaderMacros(ShaderDialect dialect) {
  if (dialect == ShaderDialect::Gles) {
    return "#define UNIFORMS_BEGIN(block)\n"
           "#define UNIFORMS_END\n"
           "#define UNIFORM(type, name) uniform type name;\n"
           "#define UNIFORM_ARRAY(type, name, count) uniform type name[count];\n"
           "#define STORAGE_BINDING(n) binding = n\n"
           "#define VERTEX_INDEX gl_VertexID\n";
  }
  return "#define UNIFORMS_BEGIN(block) layout(std140, set = 0, binding = 0) uniform block {\n"
         "#define UNIFORMS_END };\n"
         "#define UNIFORM(type, name) type name;\n"
         "#define UNIFORM_ARRAY(type, name, count) type name[count];\n"
         "#define STORAGE_BINDING(n) set = 1, binding = n\n"
         "#define VERTEX_INDEX gl_VertexIndex\n";
}

std::string withShaderMacros(std::string_view source, ShaderDialect dialect) {
  std::string text(source);
  const size_t lineEnd = text.find('\n');
  const size_t insertAt =
      text.rfind("#version", 0) == 0 && lineEnd != std::string::npos ? lineEnd + 1 : 0;
  text.insert(insertAt, shaderMacros(dialect));
  return text;
}

}  // namespace nanquim::gpu
