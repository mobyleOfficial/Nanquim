#include <gtest/gtest.h>

#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <map>
#include <regex>
#include <set>
#include <sstream>
#include <string>
#include <vector>

#include "gpu/engine_shader_files.h"
#include "gpu/shader_source.h"
#include "gpu/spirv_reflection.h"
#include "gpu/uniform_layout.h"

namespace nanquim::gpu {
namespace {

#if defined(NANQUIM_GLSLC) && defined(NANQUIM_SPIRV_DIS)

const std::set<std::string> kNotForVulkan{"GLAZE"};

const std::map<std::string, std::string> kRefines{
    {"SHAPE_COLOR", "SHAPE_TEXTURE"}, {"GRAIN_TEXTURIZED", "GRAIN"}, {"GRAIN_BLEND_1", "GRAIN"},
    {"GRAIN_BLEND_2", "GRAIN"},       {"GRAIN_BLEND_3", "GRAIN"},    {"GRAIN_BLEND_4", "GRAIN"},
    {"GRAIN_BLEND_5", "GRAIN"}};

struct Stage {
  std::string file;
  std::string kind;
};

struct Program {
  std::string name;
  std::vector<Stage> stages;
};

std::vector<Program> enginePrograms(const EngineShaderFiles& files) {
  std::vector<Program> programs;
  for (const auto& [name, text] : files.texts) {
    if (text.rfind("#version", 0) != 0) {
      continue;
    }
    const auto suffix = [&name = name](const std::string& end) {
      return name.size() > end.size() &&
             name.compare(name.size() - end.size(), end.size(), end) == 0;
    };
    if (suffix("_fragment")) {
      const std::string prefix = name.substr(0, name.size() - std::string("_fragment").size());
      const std::string vertex =
          files.texts.count(prefix + "_vertex") != 0 ? prefix + "_vertex" : "fullscreen_vertex";
      programs.push_back({prefix, {{vertex, "vert"}, {name, "frag"}}});
    } else if (suffix("_compute")) {
      programs.push_back({name, {{name, "comp"}}});
    }
  }
  return programs;
}

std::set<std::string> variantDefines(const std::string& source) {
  std::set<std::string> defines;
  static const std::regex kTest(R"(#\s*if(n?def)?\s+[^\n]*)");
  static const std::regex kName(R"(\b([A-Z][A-Z0-9_]*)\b)");
  for (auto line = std::sregex_iterator(source.begin(), source.end(), kTest);
       line != std::sregex_iterator(); ++line) {
    const std::string text = line->str();
    for (auto name = std::sregex_iterator(text.begin(), text.end(), kName);
         name != std::sregex_iterator(); ++name) {
      defines.insert((*name)[1]);
    }
  }
  return defines;
}

std::string quoted(const std::string& text) { return "\"" + text + "\""; }

int run(const std::string& command) {
#ifdef _WIN32
  return std::system(quoted(command).c_str());
#else
  return std::system(command.c_str());
#endif
}

std::string readFile(const std::filesystem::path& path) {
  std::ifstream stream(path, std::ios::binary);
  std::stringstream text;
  text << stream.rdbuf();
  return text.str();
}

struct Disassembly {
  std::map<uint32_t, uint32_t> blockOffsets;
  std::map<std::string, std::pair<uint32_t, uint32_t>> bindings;
};

Disassembly readDisassembly(const std::string& text, const std::string& blockName) {
  Disassembly result;
  static const std::regex kOffset(R"(OpMemberDecorate %(\w+) (\d+) Offset (\d+))");
  for (auto match = std::sregex_iterator(text.begin(), text.end(), kOffset);
       match != std::sregex_iterator(); ++match) {
    if ((*match)[1] == blockName) {
      result.blockOffsets[std::stoul((*match)[2])] = std::stoul((*match)[3]);
    }
  }
  static const std::regex kDecoration(R"(OpDecorate %(\w+) (Binding|DescriptorSet) (\d+))");
  for (auto match = std::sregex_iterator(text.begin(), text.end(), kDecoration);
       match != std::sregex_iterator(); ++match) {
    auto& binding = result.bindings[(*match)[1]];
    ((*match)[2] == "Binding" ? binding.second : binding.first) = std::stoul((*match)[3]);
  }
  return result;
}

TEST(ShaderSpirv, EveryProgramCompilesForVulkanWithTheParsedLayout) {
  const EngineShaderFiles files = readEngineShaderFiles();
  const std::filesystem::path directory =
      std::filesystem::temp_directory_path() / "nanquim-shader-spirv-test";
  std::filesystem::create_directories(directory);
  const std::vector<Program> programs = enginePrograms(files);
  ASSERT_GE(programs.size(), 25U);
  int32_t compiled = 0;
  for (const Program& program : programs) {
    std::vector<std::string> sources;
    std::set<std::string> defines;
    UniformLayout layout;
    for (const Stage& stage : program.stages) {
      std::string error;
      sources.push_back(withIncludes(files.texts.at(stage.file), files.lookup(), error));
      ASSERT_TRUE(error.empty()) << stage.file << ": " << error;
      UniformLayout stageLayout;
      ASSERT_TRUE(parseUniformLayout(sources.back(), stageLayout, error))
          << stage.file << ": " << error;
      if (!stageLayout.blockName.empty()) {
        if (!layout.blockName.empty()) {
          EXPECT_EQ(stageLayout.blockName, layout.blockName) << program.name;
          EXPECT_EQ(stageLayout.size, layout.size) << program.name;
        }
        layout = stageLayout;
      }
      const std::set<std::string> stageDefines = variantDefines(sources.back());
      defines.insert(stageDefines.begin(), stageDefines.end());
    }
    std::vector<std::string> variants{""};
    for (const std::string& define : defines) {
      if (kNotForVulkan.count(define) == 0) {
        variants.push_back(define);
      }
    }
    for (const std::string& variant : variants) {
      const std::string label = program.name + (variant.empty() ? "" : "+" + variant);
      std::map<std::pair<uint32_t, uint32_t>, std::string> resources;
      for (size_t index = 0; index < program.stages.size(); ++index) {
        const Stage& stage = program.stages[index];
        const std::filesystem::path base = directory / (label + "." + stage.kind);
        std::ofstream(base.string() + ".glsl", std::ios::binary)
            << withShaderMacros(sources[index], ShaderDialect::Vulkan);
        std::string define = variant.empty() ? "" : " -D" + variant + "=1";
        const auto refined = kRefines.find(variant);
        if (refined != kRefines.end()) {
          define += " -D" + refined->second + "=1";
        }
        const std::string compile = quoted(NANQUIM_GLSLC) + " --target-env=vulkan1.1" +
                                    " -fshader-stage=" + stage.kind + define + " -o " +
                                    quoted(base.string() + ".spv") + " " +
                                    quoted(base.string() + ".glsl");
        ASSERT_EQ(run(compile), 0) << label << " (" << stage.file << ") does not compile";
        ASSERT_EQ(run(quoted(NANQUIM_SPIRV_DIS) + " -o " + quoted(base.string() + ".txt") + " " +
                      quoted(base.string() + ".spv")),
                  0);
        ++compiled;
        const Disassembly disassembly =
            readDisassembly(readFile(base.string() + ".txt"), layout.blockName);
        const std::string module = readFile(base.string() + ".spv");
        std::vector<uint32_t> words(module.size() / sizeof(uint32_t));
        std::memcpy(words.data(), module.data(), words.size() * sizeof(uint32_t));
        std::vector<SpirvResource> descriptors;
        std::string reflectionError;
        ASSERT_TRUE(reflectSpirv(words, descriptors, reflectionError)) << label << reflectionError;
        std::set<std::pair<uint32_t, uint32_t>> reflected;
        for (const SpirvResource& resource : descriptors) {
          reflected.emplace(resource.set, resource.binding);
          switch (resource.kind) {
            case SpirvResource::Kind::UniformBuffer:
              EXPECT_EQ(resource.set * 100 + resource.binding, 0U) << label;
              break;
            case SpirvResource::Kind::SampledImage:
              EXPECT_EQ(resource.set, 0U) << label << ": " << resource.name;
              EXPECT_GE(resource.binding, 1U) << label << ": " << resource.name;
              break;
            case SpirvResource::Kind::StorageImage:
            case SpirvResource::Kind::StorageBuffer:
              EXPECT_EQ(resource.set, 1U) << label << ": " << resource.name;
              break;
          }
        }
        std::set<std::pair<uint32_t, uint32_t>> disassembled;
        for (const auto& [variable, binding] : disassembly.bindings) {
          disassembled.insert(binding);
        }
        EXPECT_EQ(reflected, disassembled) << label;
        if (!layout.blockName.empty() && !disassembly.blockOffsets.empty()) {
          ASSERT_EQ(disassembly.blockOffsets.size(), layout.members.size()) << label;
          for (size_t member = 0; member < layout.members.size(); ++member) {
            EXPECT_EQ(disassembly.blockOffsets.at(static_cast<uint32_t>(member)),
                      layout.members[member].offset)
                << label << ": " << layout.members[member].name;
          }
        }
        for (const auto& [variable, binding] : disassembly.bindings) {
          const auto [existing, inserted] = resources.emplace(binding, variable);
          EXPECT_TRUE(inserted || existing->second == variable)
              << label << ": " << variable << " and " << existing->second << " share set "
              << binding.first << ", binding " << binding.second;
        }
      }
    }
  }
  EXPECT_GE(compiled, 100);
}

#else

TEST(ShaderSpirv, EveryProgramCompilesForVulkanWithTheParsedLayout) {
  GTEST_SKIP() << "glslc and spirv-dis not found (NDK shader-tools)";
}

#endif

}  // namespace
}  // namespace nanquim::gpu
