#include "gpu/uniform_layout.h"

#include <gtest/gtest.h>

#include <regex>

#include "gpu/engine_shader_files.h"
#include "gpu/shader_source.h"

namespace nanquim::gpu {
namespace {

UniformLayout parse(const std::string& source) {
  UniformLayout layout;
  std::string error;
  EXPECT_TRUE(parseUniformLayout(source, layout, error)) << error;
  return layout;
}

std::string parseError(const std::string& source) {
  UniformLayout layout;
  std::string error;
  EXPECT_FALSE(parseUniformLayout(source, layout, error));
  return error;
}

TEST(UniformLayout, Std140OffsetsOfScalarsAndVectors) {
  const UniformLayout layout = parse(R"(#version 310 es
// A comment: UNIFORM(vec4, ignored)
UNIFORMS_BEGIN(Parameters)
  UNIFORM(float, a)  // 0
  UNIFORM(vec2, b)   // 8
  UNIFORM(vec3, c)   // 16
  UNIFORM(float, d)  // 28, in the vec3's last slot
  UNIFORM(vec4, e)   // 32
  UNIFORM(ivec2, f)  // 48
  UNIFORM(bool, g)   // 56
  UNIFORM(uint, h)   // 60
  UNIFORM(int, i)    // 64
UNIFORMS_END
void main() {}
)");
  EXPECT_EQ(layout.blockName, "Parameters");
  ASSERT_EQ(layout.members.size(), 9U);
  const uint32_t expected[] = {0, 8, 16, 28, 32, 48, 56, 60, 64};
  for (size_t index = 0; index < 9; ++index) {
    EXPECT_EQ(layout.members[index].offset, expected[index]) << layout.members[index].name;
  }
  EXPECT_EQ(layout.members[2].components, 3);
  EXPECT_EQ(layout.members[5].kind, UniformMember::Kind::Int);
  EXPECT_EQ(layout.members[6].kind, UniformMember::Kind::Bool);
  EXPECT_EQ(layout.members[7].kind, UniformMember::Kind::UInt);
  EXPECT_EQ(layout.size, 80U);
}

TEST(UniformLayout, ArraysHaveSixteenByteElements) {
  const UniformLayout layout = parse(R"(
UNIFORMS_BEGIN(Blur)
  UNIFORM(float, radius)
  UNIFORM_ARRAY(float, weights, 9)
  UNIFORM_ARRAY(ivec2, origins, 3)
  UNIFORM(float, after)
UNIFORMS_END
)");
  ASSERT_EQ(layout.members.size(), 4U);
  EXPECT_EQ(layout.members[1].offset, 16U);
  EXPECT_EQ(layout.members[1].count, 9);
  EXPECT_EQ(layout.members[1].stride, 16U);
  EXPECT_EQ(layout.members[2].offset, 16U + 9 * 16);
  EXPECT_EQ(layout.members[3].offset, 16U + 12 * 16);
  EXPECT_EQ(layout.size, 16U + 13 * 16);
}

TEST(UniformLayout, NoDeclarationIsAnEmptyLayout) {
  const UniformLayout layout = parse("#version 310 es\nvoid main() {}\n");
  EXPECT_TRUE(layout.blockName.empty());
  EXPECT_TRUE(layout.members.empty());
  EXPECT_EQ(layout.size, 0U);
}

TEST(UniformLayout, RejectsWhatTheBackendsCouldNotAgreeOn) {
  EXPECT_NE(parseError("UNIFORMS_BEGIN(P)\n#ifdef X\nUNIFORM(float, a)\n#endif\nUNIFORMS_END\n")
                .find("preprocessor"),
            std::string::npos);
  EXPECT_NE(parseError("UNIFORMS_BEGIN(P)\nUNIFORM_ARRAY(float, a, kCount)\nUNIFORMS_END\n")
                .find("decimal"),
            std::string::npos);
  EXPECT_NE(parseError("UNIFORMS_BEGIN(P)\nUNIFORM(mat4, a)\nUNIFORMS_END\n").find("type"),
            std::string::npos);
  EXPECT_NE(parseError("UNIFORMS_BEGIN(P)\nuniform float a;\nUNIFORMS_END\n").find("unexpected"),
            std::string::npos);
  EXPECT_NE(parseError("UNIFORMS_BEGIN(P)\nUNIFORM(float, a)\n").find("UNIFORMS_END"),
            std::string::npos);
  EXPECT_NE(parseError("UNIFORM(float, a)\n").find("outside"), std::string::npos);
}

TEST(UniformLayout, EngineShadersParse) {
  const EngineShaderFiles files = readEngineShaderFiles();
  int32_t shaders = 0;
  for (const auto& [name, text] : files.texts) {
    if (text.rfind("#version", 0) != 0) {
      continue;
    }
    ++shaders;
    std::string error;
    const std::string source = withIncludes(text, files.lookup(), error);
    EXPECT_TRUE(error.empty()) << name << ": " << error;
    UniformLayout layout;
    EXPECT_TRUE(parseUniformLayout(source, layout, error)) << name << ": " << error;
    static const std::regex kLoose(
        R"((^|\n)\s*uniform\s+((highp|mediump|lowp)\s+)?(float|int|uint|bool|[iub]?vec[234])\s)");
    EXPECT_FALSE(std::regex_search(source, kLoose)) << name << " declares a loose uniform";
  }
  EXPECT_GE(shaders, 30);
}

}  // namespace
}  // namespace nanquim::gpu
