#include "interchange_tools.h"

#include <cstdio>
#include <filesystem>
#include <nlohmann/json.hpp>
#include <string_view>

#include "formats/binary_plist.h"
#include "formats/document_import.h"
#include "formats/input_source.h"
#include "formats/palette_reader.h"
#include "formats/pdf_writer.h"
#include "formats/procreate_reader.h"
#include "formats/psd_reader.h"
#include "formats/psd_writer.h"
#include "formats/tiff_reader.h"
#include "formats/tiff_writer.h"
#include "io/document_manifest.h"
#include "io/file_system.h"
#include "reference_document.h"

namespace nanquim::cli {

namespace {

using Json = nlohmann::json;

constexpr int kExitSuccess = 0;
constexpr int kExitFailure = 1;

Json manifestNode(const formats::ImportedNode& node) {
  const bool group = node.type == formats::ImportedNode::Type::Group;
  Json value{{"type", group ? "group" : "raster"},
             {"id", node.uuid},
             {"name", node.name},
             {"opacity", node.opacity},
             {"blend", node.blend},
             {"hidden", node.hidden},
             {"locked", node.locked}};
  if (group) {
    value["collapsed"] = node.collapsed;
    Json children = Json::array();
    for (const formats::ImportedNode& child : node.children) {
      children.push_back(manifestNode(child));
    }
    value["children"] = std::move(children);
    return value;
  }
  value["alphaLock"] = node.alphaLock;
  value["clipping"] = node.clipping;
  if (node.mask) {
    value["mask"] = Json{{"id", node.mask->uuid},
                         {"enabled", node.mask->enabled},
                         {"inverted", node.mask->inverted}};
  }
  return value;
}

formats::ImportedNode nodeOf(const Json& value) {
  formats::ImportedNode node;
  node.type = value.value("type", std::string()) == "group" ? formats::ImportedNode::Type::Group
                                                            : formats::ImportedNode::Type::Raster;
  node.uuid = value.value("id", std::string());
  node.name = value.value("name", std::string());
  node.opacity = value.value("opacity", 1.0);
  node.blend = value.value(
      "blend",
      std::string(node.type == formats::ImportedNode::Type::Group ? "passThrough" : "normal"));
  node.hidden = value.value("hidden", false);
  node.locked = value.value("locked", false);
  node.alphaLock = value.value("alphaLock", false);
  node.clipping = value.value("clipping", false);
  node.collapsed = value.value("collapsed", false);
  if (value.contains("mask") && value["mask"].is_object()) {
    const Json& mask = value["mask"];
    node.mask = formats::ImportedMask{mask.value("id", std::string()), mask.value("enabled", true),
                                      mask.value("inverted", false)};
  }
  if (value.contains("children") && value["children"].is_array()) {
    for (const Json& child : value["children"]) {
      node.children.push_back(nodeOf(child));
    }
  }
  return node;
}

enum class ImportKind { Psd, Tiff, Procreate, ProcreateComposite };

int importFile(const std::string& sourcePath, const std::filesystem::path& folder,
               ImportKind kind) {
  const auto source = formats::FileInputSource::open(sourcePath);
  if (!source) {
    std::fprintf(stderr, "cannot read %s\n", sourcePath.c_str());
    return kExitFailure;
  }
  std::error_code ignored;
  std::filesystem::remove_all(folder, ignored);
  std::filesystem::create_directories(folder, ignored);
  const std::string target = folder.u8string();
  formats::ImportResult result;
  switch (kind) {
    case ImportKind::Psd:
      result = formats::importPsd(*source, target, {});
      break;
    case ImportKind::Tiff:
      result = formats::importTiff(*source, target, {});
      break;
    case ImportKind::Procreate:
      result = formats::importProcreate(*source, target, {});
      break;
    case ImportKind::ProcreateComposite:
      result = formats::importProcreateComposite(*source, target, {});
      break;
  }
  if (!result.ok) {
    std::fprintf(stderr, "import failed: %s %s\n", result.errorCode.c_str(),
                 result.errorDetail.c_str());
    return kExitFailure;
  }
  for (const formats::ImportWarning& warning : result.document.warnings) {
    std::fprintf(stderr, "warning: %s %s\n", warning.code.c_str(), warning.detail.c_str());
  }
  const formats::ImportedDocument& document = result.document;
  Json layers = Json::array();
  for (const formats::ImportedNode& node : document.layers) {
    layers.push_back(manifestNode(node));
  }
  const auto background = document.backgroundColor.value_or(std::array<double, 4>{1, 1, 1, 1});
  const Json manifest{
      {"format", "nanquim"},
      {"version", io::kSupportedManifestVersion},
      {"minReaderVersion", 1},
      {"app", {{"name", "nanquim-cli"}, {"version", "0"}}},
      {"id", "00000000-0000-4000-8000-000000000000"},
      {"name", std::filesystem::path(sourcePath).stem().u8string()},
      {"canvas",
       {{"width", document.width},
        {"height", document.height},
        {"dpi", document.dpi},
        {"unit", "px"},
        {"tileSize", canvas::kTileSizePixels},
        {"pixelFormat", "rgba8_premul_srgb"},
        {"colorProfile", {{"name", "sRGB IEC61966-2.1"}, {"icc", nullptr}}}}},
      {"background",
       {{"color", Json::array({background[0], background[1], background[2], background[3]})},
        {"hidden", document.backgroundHidden}}},
      {"activeLayerId", nullptr},
      {"referenceLayerId", nullptr},
      {"layers", std::move(layers)},
      {"extensions", Json::object()}};
  const std::string text = manifest.dump(2, ' ', false, Json::error_handler_t::replace);
  if (!io::writeFileDurably(io::joinPath(folder.u8string(), "manifest.json"),
                            std::vector<uint8_t>(text.begin(), text.end()))) {
    std::fprintf(stderr, "cannot write the manifest\n");
    return kExitFailure;
  }
  io::removePath(io::joinPath(folder.u8string(), "import.json"));
  return kExitSuccess;
}

class VectorComposite final : public formats::CompositeSource {
 public:
  VectorComposite(std::vector<uint8_t> pixels, int32_t width)
      : pixels_(std::move(pixels)), width_(width) {}
  bool readRows(int32_t top, int32_t count, uint8_t* rgba) override {
    const size_t rowBytes = static_cast<size_t>(width_) * 4;
    std::copy_n(pixels_.data() + static_cast<size_t>(top) * rowBytes,
                static_cast<size_t>(count) * rowBytes, rgba);
    return true;
  }

 private:
  std::vector<uint8_t> pixels_;
  int32_t width_;
};

enum class ExportKind { Psd, Tiff, Pdf };

struct PdfChoices {
  bool layers = false;
  bool jpeg = false;
};

int exportFile(const std::filesystem::path& folder, const std::string& outputPath, ExportKind kind,
               PdfChoices pdf = {}) {
  std::string error;
  const auto loaded = reference::readDocumentFolder(folder.u8string(), error);
  const auto manifestBytes =
      io::readFile(io::joinPath(folder.u8string(), "manifest.json"), io::kMaximumManifestBytes);
  if (!loaded || !manifestBytes) {
    std::fprintf(stderr, "invalid document %s: %s\n", folder.string().c_str(), error.c_str());
    return kExitFailure;
  }
  const Json manifest = Json::parse(manifestBytes->begin(), manifestBytes->end(), nullptr, false);
  formats::ImportedDocument document;
  document.width = loaded->width;
  document.height = loaded->height;
  document.dpi = manifest["canvas"].value("dpi", 72.0);
  const canvas::LayerTree& tree = loaded->tree;
  document.backgroundColor = std::array<double, 4>{tree.background[0], tree.background[1],
                                                   tree.background[2], tree.background[3]};
  document.backgroundHidden = tree.backgroundHidden;
  for (const Json& node : manifest["layers"]) {
    document.layers.push_back(nodeOf(node));
  }
  VectorComposite composite(reference::toUnpremultipliedRgba8(reference::renderDocument(*loaded)),
                            loaded->width);
  formats::ExportResult result;
  if (kind == ExportKind::Pdf) {
    formats::PdfExportOptions options;
    options.jpeg = pdf.jpeg;
    options.title = manifest.value("name", std::string());
    std::vector<std::unique_ptr<formats::CompositeSource>> layers;
    std::vector<formats::CompositeSource*> pages{&composite};
    if (pdf.layers) {
      layers = formats::layerPages(document, folder.u8string());
      pages.clear();
      for (const auto& page : layers) {
        pages.push_back(page.get());
      }
    }
    result = formats::exportPdf(document.width, document.height, document.dpi, pages, options,
                                outputPath);
  } else if (kind == ExportKind::Tiff) {
    result =
        formats::exportTiff(document.width, document.height, document.dpi, composite, outputPath);
  } else {
    result = formats::exportPsd(document, folder.u8string(), composite, {}, outputPath);
  }
  for (const formats::ImportWarning& warning : result.warnings) {
    std::fprintf(stderr, "warning: %s %s\n", warning.code.c_str(), warning.detail.c_str());
  }
  if (!result.ok) {
    std::fprintf(stderr, "export failed: %s %s\n", result.errorCode.c_str(),
                 result.errorDetail.c_str());
    return kExitFailure;
  }
  return kExitSuccess;
}

}  // namespace

int runInterchangeTool(const std::vector<std::string>& args) {
  if (args.size() == 3 && (args[0] == "import-psd" || args[0] == "import-tiff")) {
    return importFile(args[1], std::filesystem::u8path(args[2]),
                      args[0] == "import-tiff" ? ImportKind::Tiff : ImportKind::Psd);
  }
  if ((args.size() == 3 || (args.size() == 4 && args[3] == "--composite")) &&
      args[0] == "import-procreate") {
    return importFile(args[1], std::filesystem::u8path(args[2]),
                      args.size() == 4 ? ImportKind::ProcreateComposite : ImportKind::Procreate);
  }
  if (args.size() == 2 && args[0] == "plist-json") {
    const auto bytes = io::readFile(args[1], int64_t{64} << 20U);
    std::string error;
    const auto json = bytes ? formats::keyedArchiveJson(*bytes, error) : std::nullopt;
    if (!json) {
      std::fprintf(stderr, "not a keyed archive: %s\n", error.c_str());
      return kExitFailure;
    }
    std::printf("%s\n", json->c_str());
    return kExitSuccess;
  }
  if (args.size() == 2 && args[0] == "palette-json") {
    const auto bytes = io::readFile(args[1], int64_t{16} << 20U);
    std::string error = "unreadable";
    const auto json = bytes ? formats::paletteJson(*bytes, error) : std::nullopt;
    if (!json) {
      std::fprintf(stderr, "not a palette: %s\n", error.c_str());
      return kExitFailure;
    }
    std::printf("%s\n", json->c_str());
    return kExitSuccess;
  }
  if (args.size() == 3 && (args[0] == "export-psd" || args[0] == "export-tiff")) {
    return exportFile(std::filesystem::u8path(args[1]), args[2],
                      args[0] == "export-tiff" ? ExportKind::Tiff : ExportKind::Psd);
  }
  if (args.size() >= 3 && args[0] == "export-pdf") {
    PdfChoices choices;
    for (size_t index = 3; index < args.size(); ++index) {
      if (args[index] == "layers") {
        choices.layers = true;
      } else if (args[index] == "jpeg") {
        choices.jpeg = true;
      } else {
        return -1;
      }
    }
    return exportFile(std::filesystem::u8path(args[1]), args[2], ExportKind::Pdf, choices);
  }
  return -1;
}

void printInterchangeToolUsage() {
  std::fprintf(stderr,
               "  nanquim-cli import-psd <file.psd> <document folder>\n"
               "  nanquim-cli export-psd <document folder> <out.psd>\n"
               "  nanquim-cli import-tiff <file.tif> <document folder>\n"
               "  nanquim-cli export-tiff <document folder> <out.tif>\n"
               "  nanquim-cli import-procreate <file.procreate> <document folder> [--composite]\n"
               "  nanquim-cli plist-json <file>\n"
               "  nanquim-cli palette-json <file.aco|.ase|.gpl>\n"
               "  nanquim-cli export-pdf <document folder> <out.pdf> [layers] [jpeg]\n");
}

}  // namespace nanquim::cli
