#include "gpu/shader_source.h"

#include <gtest/gtest.h>

#include <map>

namespace nanquim::gpu {
namespace {

ShaderLookup lookupIn(const std::map<std::string, std::string, std::less<>>& files) {
  return [&files](std::string_view name) -> std::optional<std::string_view> {
    const auto found = files.find(name);
    if (found == files.end()) {
      return std::nullopt;
    }
    return std::string_view(found->second);
  };
}

TEST(ShaderSource, IncludeLinesAreReplacedRecursively) {
  const std::map<std::string, std::string, std::less<>> files{
      {"outer", "outer begin\n  // @inner\nouter end\n"}, {"inner", "inner"}};
  std::string error;
  EXPECT_EQ(withIncludes("#version 310 es\n// @outer\nvoid main() {}", lookupIn(files), error),
            "#version 310 es\nouter begin\ninner\nouter end\nvoid main() {}");
  EXPECT_TRUE(error.empty()) << error;
}

TEST(ShaderSource, OnlyWholeLinesInclude) {
  const std::map<std::string, std::string, std::less<>> files{{"name", "included\n"}};
  std::string error;
  const std::string source = "// at the \"// @name\" line\nint a;  // @name\n// @name.\n";
  EXPECT_EQ(withIncludes(source, lookupIn(files), error), source);
  EXPECT_TRUE(error.empty()) << error;
}

TEST(ShaderSource, UnknownIncludesAndCyclesStayAndAreReported) {
  const std::map<std::string, std::string, std::less<>> files{{"loop", "// @loop\n"}};
  std::string error;
  EXPECT_EQ(withIncludes("// @missing\n", lookupIn(files), error), "// @missing\n");
  EXPECT_NE(error.find("unknown include missing"), std::string::npos) << error;
  EXPECT_EQ(withIncludes("// @loop\n", lookupIn(files), error), "// @loop\n");
  EXPECT_NE(error.find("include cycle at loop"), std::string::npos) << error;
}

TEST(ShaderSource, MacrosGoRightAfterTheVersionLine) {
  const std::string gles = shaderMacros(ShaderDialect::Gles);
  EXPECT_EQ(withShaderMacros("#version 310 es\nvoid main() {}\n", ShaderDialect::Gles),
            "#version 310 es\n" + gles + "void main() {}\n");
  EXPECT_EQ(withShaderMacros("void main() {}\n", ShaderDialect::Gles), gles + "void main() {}\n");
}

TEST(ShaderSource, DialectsDefineTheSameMacrosDifferently) {
  const std::string gles = shaderMacros(ShaderDialect::Gles);
  const std::string vulkan = shaderMacros(ShaderDialect::Vulkan);
  EXPECT_NE(gles.find("#define UNIFORM(type, name) uniform type name;"), std::string::npos);
  EXPECT_NE(vulkan.find("layout(std140, set = 0, binding = 0) uniform block {"), std::string::npos);
  EXPECT_NE(gles.find("#define STORAGE_BINDING(n) binding = n"), std::string::npos);
  EXPECT_NE(vulkan.find("#define STORAGE_BINDING(n) set = 1, binding = n"), std::string::npos);
  EXPECT_NE(gles.find("#define VERTEX_INDEX gl_VertexID"), std::string::npos);
  EXPECT_NE(vulkan.find("#define VERTEX_INDEX gl_VertexIndex"), std::string::npos);
  for (const char* macro : {"UNIFORMS_BEGIN(", "UNIFORMS_END", "UNIFORM(", "UNIFORM_ARRAY(",
                            "STORAGE_BINDING(", "VERTEX_INDEX"}) {
    EXPECT_NE(gles.find(std::string("#define ") + macro), std::string::npos) << macro;
    EXPECT_NE(vulkan.find(std::string("#define ") + macro), std::string::npos) << macro;
  }
}

}  // namespace
}  // namespace nanquim::gpu
