#include "brush_tools.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <map>
#include <nlohmann/json.hpp>
#include <optional>
#include <string>
#include <vector>

#include "brush_tuning.h"
#include "png_file.h"
#include "stroke/auto_repeat.h"
#include "stroke/loaded_brush.h"
#include "stroke_statistics.h"
#include "test_sheet.h"

namespace nanquim::cli {

namespace {

constexpr int kExitSuccess = 0;
constexpr int kExitFailure = 1;
constexpr int kExitUsage = 2;
constexpr int kNotHandled = -1;

std::optional<std::vector<uint8_t>> readBytes(const std::filesystem::path& path) {
  std::ifstream stream(path, std::ios::binary);
  if (!stream) {
    return std::nullopt;
  }
  return std::vector<uint8_t>(std::istreambuf_iterator<char>(stream), std::istreambuf_iterator<char>());
}

struct BrushFiles {
  nlohmann::json document;
  std::shared_ptr<const stroke::LoadedBrush> brush;
};

std::optional<BrushFiles> loadBrushFiles(const std::filesystem::path& path) {
  const auto file = std::filesystem::is_directory(path) ? path / "brush.json" : path;
  const auto text = readBytes(file);
  if (!text) {
    std::fprintf(stderr, "cannot read %s\n", file.string().c_str());
    return std::nullopt;
  }
  BrushFiles files;
  try {
    files.document = nlohmann::json::parse(text->begin(), text->end());
  } catch (const std::exception& exception) {
    std::fprintf(stderr, "%s: %s\n", file.string().c_str(), exception.what());
    return std::nullopt;
  }
  auto texture = [&](const char* section, const char* fallback) {
    std::string name = fallback;
    if (files.document.contains(section) && files.document[section].contains("texture")) {
      const auto& value = files.document[section]["texture"];
      name = value.is_string() ? value.get<std::string>() : "";
    }
    return name.empty() ? std::vector<uint8_t>{} : readBytes(file.parent_path() / name).value_or(std::vector<uint8_t>{});
  };
  std::string error;
  files.brush = stroke::loadBrush(files.document.dump(), texture("shape", "shape.png"), texture("grain", ""), error);
  if (!files.brush) {
    std::fprintf(stderr, "%s: %s\n", file.string().c_str(), error.c_str());
    return std::nullopt;
  }
  return files;
}

std::map<std::string, std::string> options(const std::vector<std::string>& args, size_t first) {
  std::map<std::string, std::string> result;
  for (size_t index = first; index + 1 < args.size(); index += 2) {
    if (args[index].rfind("--", 0) == 0) {
      result[args[index].substr(2)] = args[index + 1];
    }
  }
  return result;
}

double numberOption(const std::map<std::string, std::string>& values, const char* name, double fallback) {
  const auto found = values.find(name);
  return found == values.end() ? fallback : std::stod(found->second);
}

nlohmann::json statisticsJson(const std::vector<reference::StrokeStatistic>& statistics) {
  nlohmann::json json = nlohmann::json::object();
  for (const auto& statistic : statistics) {
    json[statistic.name] = statistic.value;
  }
  return json;
}

int brushSheet(const std::vector<std::string>& args) {
  const auto files = loadBrushFiles(args[1]);
  if (!files) {
    return kExitFailure;
  }
  const auto values = options(args, 3);
  const double scale = numberOption(values, "scale", 1);
  const auto image = reference::renderTestSheet(files->document, *files->brush, scale,
                                                numberOption(values, "size", 0),
                                                numberOption(values, "opacity", 0));
  if (!reference::writePng(args[2], image)) {
    std::fprintf(stderr, "cannot write %s\n", args[2].c_str());
    return kExitFailure;
  }
  return kExitSuccess;
}

int sheetTemplate(const std::vector<std::string>& args) {
  const double scale = numberOption(options(args, 2), "scale", 1);
  const auto size = static_cast<int32_t>(std::lround(reference::kSheetSize * scale));
  if (!reference::writePng(args[1], reference::Rgba8Image{size, size, reference::renderSheetTemplate(scale)})) {
    std::fprintf(stderr, "cannot write %s\n", args[1].c_str());
    return kExitFailure;
  }
  return kExitSuccess;
}

int brushStatistics(const std::vector<std::string>& args) {
  const auto image = reference::readPng(args[1]);
  if (!image || image->width != image->height) {
    std::fprintf(stderr, "%s: not a square test sheet image\n", args[1].c_str());
    return kExitFailure;
  }
  std::printf("%s\n", statisticsJson(reference::measureSheet(*image)).dump(2).c_str());
  return kExitSuccess;
}

reference::Rgba8Image sideBySide(const reference::Rgba8Image& target, const reference::Rgba8Image& actual) {
  const int32_t width = target.width;
  const int32_t height = target.height;
  reference::Rgba8Image out{width * 3, height, std::vector<uint8_t>(static_cast<size_t>(width) * 3 * height * 4, 255)};
  auto luminance = [](const uint8_t* pixel) {
    const double alpha = pixel[3] / 255.0;
    const double over = [&] {
      double sum = 0;
      const double weights[3] = {0.2126, 0.7152, 0.0722};
      for (size_t channel = 0; channel < 3; ++channel) {
        sum += weights[channel] * (pixel[channel] / 255.0 * alpha + 1 - alpha);
      }
      return sum;
    }();
    return over;
  };
  for (int32_t y = 0; y < height; ++y) {
    for (int32_t x = 0; x < width; ++x) {
      const size_t source = (static_cast<size_t>(y) * width + x) * 4;
      for (int32_t panel = 0; panel < 2; ++panel) {
        const auto& image = panel == 0 ? target : actual;
        std::copy_n(&image.pixels[source], 4, &out.pixels[(static_cast<size_t>(y) * width * 3 + x + panel * width) * 4]);
      }
      const double difference = luminance(&target.pixels[source]) - luminance(&actual.pixels[source]);
      uint8_t* pixel = &out.pixels[(static_cast<size_t>(y) * width * 3 + x + 2 * width) * 4];
      const auto amount = static_cast<uint8_t>(std::lround(std::clamp(std::abs(difference), 0.0, 1.0) * 255));
      pixel[0] = difference > 0 ? 255 : static_cast<uint8_t>(255 - amount);
      pixel[1] = static_cast<uint8_t>(255 - amount);
      pixel[2] = difference > 0 ? static_cast<uint8_t>(255 - amount) : 255;
      pixel[3] = 255;
    }
  }
  return out;
}

void printComparison(const reference::StatisticsComparison& comparison) {
  auto entries = comparison.entries;
  std::sort(entries.begin(), entries.end(), [](const auto& a, const auto& b) {
    return std::abs(a.normalizedDifference) > std::abs(b.normalizedDifference);
  });
  std::printf("distance %.3f (%s, threshold %.1f)\n", comparison.distance,
              comparison.distance < reference::kAcceptanceDistance ? "match" : "different",
              reference::kAcceptanceDistance);
  for (size_t index = 0; index < std::min<size_t>(10, entries.size()); ++index) {
    const auto& entry = entries[index];
    std::printf("  %-22s target %9.4f  nanquim %9.4f  (%+.2f)\n", entry.name.c_str(), entry.target, entry.actual,
                entry.normalizedDifference);
  }
}

int brushCompare(const std::vector<std::string>& args) {
  const auto target = reference::readPng(args[1]);
  const auto actual = reference::readPng(args[2]);
  if (!target || !actual || target->width != actual->width || target->height != actual->height ||
      target->width != target->height) {
    std::fprintf(stderr, "both images must be test sheets of the same square size\n");
    return kExitFailure;
  }
  const auto comparison = reference::compareStatistics(reference::measureSheet(*target), reference::measureSheet(*actual));
  printComparison(comparison);
  if (!reference::writePng(args[3], sideBySide(*target, *actual))) {
    std::fprintf(stderr, "cannot write %s\n", args[3].c_str());
    return kExitFailure;
  }
  return comparison.distance < reference::kAcceptanceDistance ? kExitSuccess : kExitFailure;
}

int brushTune(const std::vector<std::string>& args) {
  const auto files = loadBrushFiles(args[1]);
  const auto target = reference::readPng(args[2]);
  if (!files || !target) {
    if (!target) {
      std::fprintf(stderr, "cannot read %s\n", args[2].c_str());
    }
    return kExitFailure;
  }
  const auto values = options(args, 4);
  std::vector<reference::TunableParameter> parameters;
  const auto standard = reference::standardTunableParameters();
  if (const auto chosen = values.find("parameters"); chosen != values.end()) {
    size_t start = 0;
    while (start < chosen->second.size()) {
      const size_t comma = chosen->second.find(',', start);
      const std::string path = chosen->second.substr(start, comma == std::string::npos ? std::string::npos : comma - start);
      const auto found = std::find_if(standard.begin(), standard.end(), [&](const auto& p) { return p.path == path; });
      if (found == standard.end()) {
        std::fprintf(stderr, "unknown tunable parameter %s\n", path.c_str());
        return kExitUsage;
      }
      parameters.push_back(*found);
      start = comma == std::string::npos ? chosen->second.size() : comma + 1;
    }
  } else {
    parameters = standard;
  }
  const auto evaluations = static_cast<int32_t>(numberOption(values, "evaluations", 200));
  const double scale = numberOption(values, "scale", 0.5);
  const auto result = reference::tuneBrush(files->document, *files->brush, reference::measureSheet(*target), parameters,
                                           evaluations, scale, [](int32_t evaluation, double best) {
                                             if (evaluation % 10 == 0) {
                                               std::fprintf(stderr, "evaluation %d: best distance %.3f\n", evaluation, best);
                                             }
                                           });
  std::ofstream output(args[3]);
  output << result.brush.dump(2) << "\n";
  std::printf("distance %.3f -> %.3f after %d evaluations\n", result.initialDistance, result.distance, result.evaluations);
  return output ? kExitSuccess : kExitFailure;
}

}  // namespace

int autoRepeatTool(const std::vector<std::string>& args) {
  const auto image = reference::readPng(args[1]);
  if (!image) {
    std::fprintf(stderr, "cannot read %s\n", args[1].c_str());
    return 1;
  }
  std::vector<uint8_t> gray(size_t(image->width) * size_t(image->height));
  for (size_t index = 0; index < gray.size(); ++index) {
    const uint8_t* pixel = &image->pixels[index * 4];
    const double luminance = 0.299 * pixel[0] + 0.587 * pixel[1] + 0.114 * pixel[2];
    gray[index] = uint8_t(std::lround(luminance * pixel[3] / 255.0));
  }
  const auto values = options(args, 3);
  stroke::AutoRepeatParameters parameters;
  parameters.outputSize = int32_t(numberOption(values, "size", 0));
  parameters.patchScale = numberOption(values, "patch", parameters.patchScale);
  parameters.seamOverlap = numberOption(values, "overlap", parameters.seamOverlap);
  parameters.maskHardness = numberOption(values, "hardness", parameters.maskHardness);
  parameters.mirroring = numberOption(values, "mirroring", 0) != 0;
  parameters.ignoreLowBands = numberOption(values, "ignore-low-bands", 0) != 0;
  parameters.pyramidBlending = numberOption(values, "pyramid", 0) != 0;
  parameters.seed = uint64_t(numberOption(values, "seed", 1));
  const auto result = stroke::autoRepeat(gray, image->width, image->height, parameters);
  if (result.pixels.empty() ||
      !reference::writeGrayPng(args[2], result.side, result.side, result.pixels)) {
    std::fprintf(stderr, "auto repeat failed\n");
    return 1;
  }
  return 0;
}

void printBrushToolUsage() {
  std::fprintf(stderr,
               "  nanquim-cli brush-sheet <brush folder|brush.json> <out.png> [--scale s] [--size "
               "px] [--opacity o]\n"
               "  nanquim-cli brush-sheet-template <out.png> [--scale s]\n"
               "  nanquim-cli brush-stats <sheet.png>\n"
               "  nanquim-cli brush-compare <target.png> <nanquim.png> <side-by-side.png>\n"
               "  nanquim-cli brush-tune <brush folder|brush.json> <target.png> <out brush.json> "
               "[--evaluations n]\n"
               "              [--scale s] [--parameters path,path,...]\n"
               "  nanquim-cli auto-repeat <in.png> <out.png> [--size n] [--patch s] [--overlap o]\n"
               "              [--hardness h] [--mirroring 0|1] [--ignore-low-bands 0|1] [--pyramid "
               "0|1] [--seed n]\n");
}

int runBrushTool(const std::vector<std::string>& args) {
  if (args.empty()) {
    return kNotHandled;
  }
  const std::string& command = args[0];
  if (command == "brush-sheet" && args.size() >= 3) {
    return brushSheet(args);
  }
  if (command == "brush-sheet-template" && args.size() >= 2) {
    return sheetTemplate(args);
  }
  if (command == "brush-stats" && args.size() == 2) {
    return brushStatistics(args);
  }
  if (command == "brush-compare" && args.size() == 4) {
    return brushCompare(args);
  }
  if (command == "brush-tune" && args.size() >= 4) {
    return brushTune(args);
  }
  if (command == "auto-repeat" && args.size() >= 3) {
    return autoRepeatTool(args);
  }
  return kNotHandled;
}

}  // namespace nanquim::cli
