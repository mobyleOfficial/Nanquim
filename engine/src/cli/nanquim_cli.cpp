#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <nlohmann/json.hpp>
#include <optional>
#include <string>
#include <vector>

#include "blend_reference.h"
#include "brush_tools.h"
#include "color_tools.h"
#include "image_compare.h"
#include "interchange_tools.h"
#include "png_file.h"
#include "procedural_texture.h"
#include "reference_document.h"
#include "reference_scene.h"
#include "scene/scene.h"

namespace {

constexpr int kExitSuccess = 0;
constexpr int kExitFailure = 1;
constexpr int kExitUsage = 2;

std::optional<std::vector<uint8_t>> readFile(const std::filesystem::path& path) {
  std::ifstream stream(path, std::ios::binary);
  if (!stream) {
    return std::nullopt;
  }
  return std::vector<uint8_t>(std::istreambuf_iterator<char>(stream),
                              std::istreambuf_iterator<char>());
}

int printUsage() {
  std::fprintf(stderr,
               "usage:\n"
               "  nanquim-cli render <scene.json | document folder> <out.png>\n"
               "  nanquim-cli convert <scene.json> <document folder>\n"
               "  nanquim-cli compare <actual.png> <expected.png>\n"
               "  nanquim-cli texture <disc|bristles|paper|noise|chalk> <size> <seed> <parameter> "
               "<out.png>\n"
               "  nanquim-cli blend-table <out.json>\n");
  nanquim::cli::printBrushToolUsage();
  nanquim::cli::printInterchangeToolUsage();
  nanquim::cli::printColorToolUsage();
  return kExitUsage;
}

int blendTable(const std::string& outputPath) {
  using nanquim::canvas::BlendMode;
  using nanquim::reference::Rgba;
  const std::vector<std::array<double, 3>> colors{
      {0, 0, 0}, {1, 1, 1}, {0.9, 0.2, 0.1}, {0.1, 0.6, 0.7}};
  const std::vector<double> alphas{0.5, 1};
  std::vector<Rgba> samples;
  for (const auto& color : colors) {
    for (const double alpha : alphas) {
      samples.push_back({color[0] * alpha, color[1] * alpha, color[2] * alpha, alpha});
    }
  }
  auto toJson = [](const Rgba& color) {
    return nlohmann::json::array({color.red, color.green, color.blue, color.alpha});
  };
  nlohmann::json entries = nlohmann::json::array();
  for (const auto& entry : nanquim::canvas::kBlendModeNames) {
    if (entry.mode == BlendMode::PassThrough) {
      continue;
    }
    for (const Rgba& backdrop : samples) {
      for (const Rgba& source : samples) {
        nlohmann::json row{
            {"mode", std::string(entry.id)},
            {"backdrop", toJson(backdrop)},
            {"source", toJson(source)},
            {"result", toJson(nanquim::reference::compositePixel(entry.mode, backdrop, source))}};
        if (entry.mode != BlendMode::Behind) {
          row["clipped"] =
              toJson(nanquim::reference::clipCompositePixel(entry.mode, backdrop, source));
        }
        entries.push_back(std::move(row));
      }
    }
  }
  std::ofstream stream(outputPath, std::ios::binary);
  stream << "{\"format\": 1, \"entries\": [\n";
  for (size_t index = 0; index < entries.size(); ++index) {
    stream << entries[index].dump() << (index + 1 < entries.size() ? ",\n" : "\n");
  }
  stream << "]}\n";
  if (!stream) {
    std::fprintf(stderr, "cannot write %s\n", outputPath.c_str());
    return kExitFailure;
  }
  return kExitSuccess;
}

int texture(const std::vector<std::string>& args) {
  std::string error;
  const auto generated = nanquim::reference::generateTexture(
      args[1], std::stoi(args[2]), std::stoull(args[3]), std::stod(args[4]), error);
  if (!generated) {
    std::fprintf(stderr, "%s\n", error.c_str());
    return kExitUsage;
  }
  if (!nanquim::reference::writeGrayPng(args[5], generated->size, generated->size,
                                        generated->pixels)) {
    std::fprintf(stderr, "cannot write %s\n", args[5].c_str());
    return kExitFailure;
  }
  return kExitSuccess;
}

std::optional<nanquim::scene::Scene> loadScene(const std::filesystem::path& scenePath) {
  const auto sceneBytes = readFile(scenePath);
  if (!sceneBytes) {
    std::fprintf(stderr, "cannot read %s\n", scenePath.string().c_str());
    return std::nullopt;
  }
  const std::filesystem::path baseDirectory = scenePath.parent_path();
  std::string error;
  auto scene = nanquim::scene::parseScene(
      std::string_view(reinterpret_cast<const char*>(sceneBytes->data()), sceneBytes->size()),
      [&](const std::string& name) { return readFile(baseDirectory / name); }, error);
  if (!scene) {
    std::fprintf(stderr, "invalid scene %s: %s\n", scenePath.string().c_str(), error.c_str());
  }
  return scene;
}

int writeImage(const nanquim::reference::Image& image, const std::filesystem::path& outputPath) {
  nanquim::reference::Rgba8Image output{image.width, image.height,
                                        nanquim::reference::toUnpremultipliedRgba8(image)};
  if (!nanquim::reference::writePng(outputPath.string(), output)) {
    std::fprintf(stderr, "cannot write %s\n", outputPath.string().c_str());
    return kExitFailure;
  }
  return kExitSuccess;
}

int render(const std::filesystem::path& inputPath, const std::filesystem::path& outputPath) {
  if (std::filesystem::is_directory(inputPath)) {
    std::string error;
    const auto document = nanquim::reference::readDocumentFolder(inputPath.u8string(), error);
    if (!document) {
      std::fprintf(stderr, "invalid document %s: %s\n", inputPath.string().c_str(), error.c_str());
      return kExitFailure;
    }
    for (const std::string& warning : document->warnings) {
      std::fprintf(stderr, "warning: %s\n", warning.c_str());
    }
    return writeImage(nanquim::reference::renderDocument(*document), outputPath);
  }
  const auto scene = loadScene(inputPath);
  if (!scene) {
    return kExitFailure;
  }
  return writeImage(nanquim::reference::renderScene(*scene), outputPath);
}

int convert(const std::filesystem::path& scenePath, const std::filesystem::path& folder) {
  const auto scene = loadScene(scenePath);
  if (!scene) {
    return kExitFailure;
  }
  std::error_code ignored;
  std::filesystem::remove_all(folder, ignored);
  std::string error;
  if (!nanquim::reference::writeDocumentFolder(
          folder.u8string(), scene->layers, scene->width, scene->height,
          nanquim::reference::renderSceneSurfaces(*scene), scenePath.stem().u8string(), error)) {
    std::fprintf(stderr, "cannot write %s: %s\n", folder.string().c_str(), error.c_str());
    return kExitFailure;
  }
  return kExitSuccess;
}

int compare(const std::string& actualPath, const std::string& expectedPath) {
  const auto actual = nanquim::reference::readPng(actualPath);
  const auto expected = nanquim::reference::readPng(expectedPath);
  if (!actual || !expected) {
    std::fprintf(stderr, "cannot read %s\n", (!actual ? actualPath : expectedPath).c_str());
    return kExitFailure;
  }
  const auto result =
      nanquim::reference::compareRgba8(actual->pixels, actual->width, actual->height,
                                       expected->pixels, expected->width, expected->height);
  if (!result.sameSize) {
    std::printf("size mismatch: %dx%d vs %dx%d\n", actual->width, actual->height, expected->width,
                expected->height);
    return kExitFailure;
  }
  std::printf("%zu of %zu pixels differ (max channel difference %d): %s\n", result.differingPixels,
              result.totalPixels, result.maximumChannelDifference,
              result.matches ? "match" : "MISMATCH");
  return result.matches ? kExitSuccess : kExitFailure;
}

}  // namespace

int main(int argumentCount, char** arguments) {
  const std::vector<std::string> args(arguments + 1, arguments + argumentCount);
  if (args.size() == 3 && args[0] == "render") {
    return render(args[1], args[2]);
  }
  if (args.size() == 3 && args[0] == "convert") {
    return convert(args[1], args[2]);
  }
  if (args.size() == 2 && args[0] == "blend-table") {
    return blendTable(args[1]);
  }
  if (args.size() == 3 && args[0] == "compare") {
    return compare(args[1], args[2]);
  }
  if (args.size() == 6 && args[0] == "texture") {
    return texture(args);
  }
  if (const int result = nanquim::cli::runBrushTool(args); result >= 0) {
    return result;
  }
  if (const int result = nanquim::cli::runInterchangeTool(args); result >= 0) {
    return result;
  }
  if (const int result = nanquim::cli::runColorTool(args); result >= 0) {
    return result;
  }
  if (!args.empty() && (args[0] == "run" || args[0] == "batch")) {
    std::fprintf(stderr, "'%s' needs the document model on the host\n",
                 args[0].c_str());
    return kExitUsage;
  }
  return printUsage();
}
