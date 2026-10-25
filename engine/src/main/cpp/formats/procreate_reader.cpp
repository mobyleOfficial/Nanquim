#include "formats/procreate_reader.h"

#include <lz4.h>

#include <algorithm>
#include <array>
#include <charconv>
#include <climits>
#include <cstring>
#include <memory>
#include <vector>

#include "canvas/layer_tree.h"
#include "formats/binary_plist.h"
#include "formats/zip_reader.h"

namespace nanquim::formats {

namespace {

constexpr uint64_t kMaximumArchiveBytes = uint64_t{64} << 20U;
constexpr uint64_t kMaximumTileFileBytes = uint64_t{16} << 20U;
constexpr int32_t kMaximumTileSize = 4096;
constexpr int32_t kRotatedClockwise = 4;
constexpr uint32_t kLinkedDictionaryBytes = 64 * 1024;

uint32_t littleEndian32(const uint8_t* bytes) {
  return bytes[0] | (bytes[1] << 8U) | (bytes[2] << 16U) | (static_cast<uint32_t>(bytes[3]) << 24U);
}

bool parseSize(const std::string& text, long long& width, long long& height) {
  const char* begin = text.data();
  const char* end = begin + text.size();
  const auto skip = [&begin, end](char expected) {
    while (begin < end && *begin == ' ') {
      ++begin;
    }
    if (begin < end && *begin == expected) {
      ++begin;
      return true;
    }
    return false;
  };
  const auto number = [&begin, end](long long& value) {
    while (begin < end && *begin == ' ') {
      ++begin;
    }
    const auto parsed = std::from_chars(begin, end, value);
    begin = parsed.ptr;
    return parsed.ec == std::errc();
  };
  return skip('{') && number(width) && skip(',') && number(height) && skip('}');
}

uint8_t unpremultiply(uint8_t channel, uint8_t alpha) {
  if (alpha == 0) {
    return 0;
  }
  const uint32_t value = (static_cast<uint32_t>(channel) * 255U + alpha / 2U) / alpha;
  return static_cast<uint8_t>(std::min(value, 255U));
}

class ProcreateImporter {
 public:
  ProcreateImporter(InputSource& source, std::string folder, const ImportLimits& limits,
                    bool compositeOnly)
      : source_(source),
        folder_(std::move(folder)),
        limits_(limits),
        compositeOnly_(compositeOnly) {}

  ImportResult run();

 private:
  bool fail(const std::string& code, const std::string& detail = {}) {
    result_.ok = false;
    result_.errorCode = code;
    result_.errorDetail = detail;
    return false;
  }
  void warn(const std::string& code, const std::string& detail = {}) {
    for (const ImportWarning& warning : result_.document.warnings) {
      if (warning.code == code && warning.detail == detail) {
        return;
      }
    }
    result_.document.warnings.push_back({code, detail});
  }

  bool readDocument(const KeyedArchive::Object& document);
  bool convertNodes(const std::vector<KeyedArchive::Object>& children, int32_t depth,
                    std::vector<ImportedNode>& nodes);
  bool convertLayer(const KeyedArchive::Object& layer, ImportedNode& node);
  bool convertTiles(const std::string& uuid, SurfaceTileWriter& writer, bool mask,
                    const std::string& name);
  bool loadTile(const std::string& uuid, int32_t column, int32_t row, int32_t tileWidth,
                int32_t tileHeight, bool mask, const std::string& name);
  void outputOf(int32_t x, int32_t y, int32_t& outputX, int32_t& outputY) const;

  InputSource& source_;
  std::string folder_;
  ImportLimits limits_;
  bool compositeOnly_;
  ImportResult result_;
  std::unique_ptr<ZipReader> zip_;
  std::unique_ptr<KeyedArchive> archive_;
  std::unique_ptr<DocumentImportFolder> output_;
  int32_t width_ = 0;
  int32_t height_ = 0;
  int32_t tileSize_ = 256;
  int32_t quarterTurns_ = 0;
  bool flipHorizontal_ = false;
  bool flipVertical_ = false;
  int32_t layerCount_ = 0;
  std::vector<uint8_t> compressed_;
  std::vector<uint8_t> tile_;
  std::vector<uint8_t> band_;
};

bool ProcreateImporter::loadTile(const std::string& uuid, int32_t column, int32_t row,
                                 int32_t tileWidth, int32_t tileHeight, bool mask,
                                 const std::string& name) {
  const ZipEntry* entry =
      zip_->find(uuid + "/" + std::to_string(column) + "~" + std::to_string(row) + ".lz4");
  if (entry == nullptr) {
    return false;
  }
  const size_t pixels = static_cast<size_t>(tileWidth) * static_cast<size_t>(tileHeight);
  std::string error;
  if (!zip_->extract(*entry, kMaximumTileFileBytes, compressed_, error)) {
    warn("tileUnreadable", name);
    return false;
  }
  if (mask) {
    tile_.assign(pixels, 0);
    if (decodeProcreateTile(compressed_.data(), compressed_.size(), tile_.data(), pixels)) {
      return true;
    }
    std::vector<uint8_t> rgba(pixels * 4);
    if (!decodeProcreateTile(compressed_.data(), compressed_.size(), rgba.data(), rgba.size())) {
      warn("tileUnreadable", name);
      return false;
    }
    for (size_t pixel = 0; pixel < pixels; ++pixel) {
      tile_[pixel] = unpremultiply(rgba[pixel * 4], rgba[pixel * 4 + 3]);
    }
    return true;
  }
  tile_.assign(pixels * 4, 0);
  if (!decodeProcreateTile(compressed_.data(), compressed_.size(), tile_.data(), tile_.size())) {
    warn("tileUnreadable", name);
    return false;
  }
  for (size_t pixel = 0; pixel < pixels; ++pixel) {
    uint8_t* value = &tile_[pixel * 4];
    for (int channel = 0; channel < 3; ++channel) {
      value[channel] = unpremultiply(value[channel], value[3]);
    }
  }
  return true;
}

void ProcreateImporter::outputOf(int32_t x, int32_t y, int32_t& outputX, int32_t& outputY) const {
  switch (quarterTurns_) {
    case 1:
      outputX = height_ - 1 - y;
      outputY = x;
      break;
    case 2:
      outputX = width_ - 1 - x;
      outputY = height_ - 1 - y;
      break;
    case 3:
      outputX = y;
      outputY = width_ - 1 - x;
      break;
    default:
      outputX = x;
      outputY = y;
      break;
  }
  if (flipHorizontal_) {
    outputX = result_.document.width - 1 - outputX;
  }
  if (flipVertical_) {
    outputY = result_.document.height - 1 - outputY;
  }
}

bool ProcreateImporter::convertTiles(const std::string& uuid, SurfaceTileWriter& writer, bool mask,
                                     const std::string& name) {
  const int32_t columns = (width_ + tileSize_ - 1) / tileSize_;
  const int32_t rows = (height_ + tileSize_ - 1) / tileSize_;
  const int32_t outputWidth = result_.document.width;
  const size_t pixelBytes = mask ? 1 : 4;
  const bool byRows = quarterTurns_ % 2 == 0;
  struct Band {
    int32_t index;
    int32_t top;
    int32_t height;
  };
  std::vector<Band> bands;
  for (int32_t index = 0; index < (byRows ? rows : columns); ++index) {
    const int32_t left = byRows ? 0 : index * tileSize_;
    const int32_t right = byRows ? width_ : std::min(width_, (index + 1) * tileSize_);
    const int32_t bottomUp = index * tileSize_;
    const int32_t top = byRows ? height_ - std::min(height_, bottomUp + tileSize_) : 0;
    const int32_t bottom = byRows ? height_ - bottomUp : height_;
    int32_t x0 = 0;
    int32_t y0 = 0;
    int32_t x1 = 0;
    int32_t y1 = 0;
    outputOf(left, top, x0, y0);
    outputOf(right - 1, bottom - 1, x1, y1);
    bands.push_back({index, std::min(y0, y1), std::abs(y1 - y0) + 1});
  }
  std::sort(bands.begin(), bands.end(), [](const Band& a, const Band& b) { return a.top < b.top; });
  for (const Band& band : bands) {
    band_.assign(static_cast<size_t>(outputWidth) * static_cast<size_t>(band.height) * pixelBytes,
                 mask ? 255 : 0);
    for (int32_t other = 0; other < (byRows ? columns : rows); ++other) {
      const int32_t column = byRows ? other : band.index;
      const int32_t row = byRows ? band.index : other;
      const int32_t tileWidth = std::min(tileSize_, width_ - column * tileSize_);
      const int32_t tileHeight = std::min(tileSize_, height_ - row * tileSize_);
      if (!loadTile(uuid, column, row, tileWidth, tileHeight, mask, name)) {
        continue;
      }
      for (int32_t stored = 0; stored < tileHeight; ++stored) {
        const int32_t y = height_ - 1 - (row * tileSize_ + stored);
        for (int32_t x = 0; x < tileWidth; ++x) {
          int32_t outputX = 0;
          int32_t outputY = 0;
          outputOf(column * tileSize_ + x, y, outputX, outputY);
          std::memcpy(band_.data() + (static_cast<size_t>(outputY - band.top) * outputWidth +
                                      static_cast<size_t>(outputX)) *
                                         pixelBytes,
                      tile_.data() + (static_cast<size_t>(stored) * tileWidth + x) * pixelBytes,
                      pixelBytes);
        }
      }
    }
    if (!writer.writePixels(0, band.top, outputWidth, band.height, band_.data(),
                            static_cast<size_t>(outputWidth) * pixelBytes)) {
      return fail("writeFailed");
    }
  }
  return true;
}

bool ProcreateImporter::convertLayer(const KeyedArchive::Object& layer, ImportedNode& node) {
  const KeyedArchive& archive = *archive_;
  node.type = ImportedNode::Type::Raster;
  node.name = archive.string(layer, "name");
  node.opacity = std::clamp(archive.number(layer, "opacity", 1.0), 0.0, 1.0);
  node.hidden = archive.boolean(layer, "hidden", false);
  node.locked = archive.boolean(layer, "locked", false);
  node.clipping = archive.boolean(layer, "clipped", false);
  const auto blend = static_cast<int64_t>(archive.number(layer, "blend", 0));
  if (blend == 1) {
    node.blend = "multiply";
  } else if (blend != 0) {
    warn("blendModeReplaced", node.name);
  }
  if (archive.boolean(layer, "preserve", false)) {
    node.alphaLock = true;
    warn("provisionalAlphaLock", node.name);
  }
  if (archive.member(layer, "text") || archive.member(layer, "textPDF")) {
    warn("textRasterized", node.name);
  }
  if (archive.number(layer, "type", 0) != 0) {
    warn("layerTypeUnknown", node.name);
  }
  const std::string uuid = archive.string(layer, "UUID");
  if (uuid.empty() || !isSafeZipEntryName(uuid) || uuid.find('/') != std::string::npos) {
    return fail("corrupt", "layer UUID");
  }
  node.uuid = output_->newUuid();
  SurfaceTileWriter writer = output_->layerWriter(node.uuid);
  if (!convertTiles(uuid, writer, false, node.name)) {
    return false;
  }
  if (!writer.finish()) {
    return fail("writeFailed");
  }
  if (const auto mask = archive.member(layer, "mask")) {
    const std::string maskUuid = archive.string(*mask, "UUID");
    if (maskUuid.empty() || !isSafeZipEntryName(maskUuid) ||
        maskUuid.find('/') != std::string::npos) {
      warn("layerMaskSkipped", node.name);
      return true;
    }
    ImportedMask imported;
    imported.uuid = output_->newUuid();
    imported.enabled = !archive.boolean(*mask, "hidden", false);
    SurfaceTileWriter maskWriter = output_->maskWriter(imported.uuid, 255);
    if (!convertTiles(maskUuid, maskWriter, true, node.name)) {
      return false;
    }
    if (!maskWriter.finish()) {
      return fail("writeFailed");
    }
    node.mask = imported;
    warn("provisionalLayerMask", node.name);
  }
  return true;
}

bool ProcreateImporter::convertNodes(const std::vector<KeyedArchive::Object>& children,
                                     int32_t depth, std::vector<ImportedNode>& nodes) {
  if (depth > canvas::kMaximumGroupDepth) {
    return fail("corrupt", "group depth");
  }
  const KeyedArchive& archive = *archive_;
  for (auto child = children.rbegin(); child != children.rend(); ++child) {
    const std::string kind = archive.className(*child);
    ImportedNode node;
    if (kind == "SilicaGroup") {
      node.type = ImportedNode::Type::Group;
      node.uuid = output_->newUuid();
      node.name = archive.string(*child, "name");
      node.opacity = std::clamp(archive.number(*child, "opacity", 1.0), 0.0, 1.0);
      node.hidden = archive.boolean(*child, "isHidden", false);
      node.locked = archive.boolean(*child, "isLocked", false);
      node.collapsed = archive.boolean(*child, "isCollapsed", false);
      node.blend = "passThrough";
      if (archive.boolean(*child, "isClipped", false)) {
        warn("groupClippingIgnored", node.name);
      }
      if (!convertNodes(archive.array(*child, "children"), depth + 1, node.children)) {
        return false;
      }
    } else if (kind == "SilicaLayer") {
      if (++layerCount_ > limits_.maximumLayers) {
        return fail("tooManyLayers", std::to_string(layerCount_));
      }
      if (!convertLayer(*child, node)) {
        return false;
      }
    } else {
      warn("layerTypeUnknown", kind);
      continue;
    }
    nodes.push_back(std::move(node));
  }
  return true;
}

bool ProcreateImporter::readDocument(const KeyedArchive::Object& document) {
  const KeyedArchive& archive = *archive_;
  long long width = 0;
  long long height = 0;
  if (!parseSize(archive.string(document, "size"), width, height)) {
    return fail("corrupt", "canvas size");
  }
  const auto tileSize = static_cast<int64_t>(archive.number(document, "tileSize", 256));
  if (width <= 0 || height <= 0 || width > 1'000'000 || height > 1'000'000 || tileSize < 16 ||
      tileSize > kMaximumTileSize) {
    return fail("corrupt", "canvas size");
  }
  if (width > limits_.maximumSide || height > limits_.maximumSide) {
    return fail("canvasTooLarge", std::to_string(width) + "x" + std::to_string(height));
  }
  width_ = static_cast<int32_t>(width);
  height_ = static_cast<int32_t>(height);
  tileSize_ = static_cast<int32_t>(tileSize);
  const auto orientation = static_cast<int64_t>(archive.number(document, "orientation", 1));
  switch (orientation) {
    case 1:
      break;
    case kRotatedClockwise:
      quarterTurns_ = 1;
      break;
    case 2:
      quarterTurns_ = 2;
      warn("provisionalOrientation");
      break;
    case 3:
      quarterTurns_ = 3;
      warn("provisionalOrientation");
      break;
    default:
      warn("orientationIgnored");
      break;
  }
  flipHorizontal_ = archive.boolean(document, "flippedHorizontally", false);
  flipVertical_ = archive.boolean(document, "flippedVertically", false);
  if (flipHorizontal_ || flipVertical_) {
    warn("provisionalOrientation");
  }
  ImportedDocument& imported = result_.document;
  const bool quarter = quarterTurns_ % 2 == 1;
  imported.width = quarter ? height_ : width_;
  imported.height = quarter ? width_ : height_;
  const double dpi = archive.number(document, "SilicaDocumentArchiveDPIKey", 72);
  imported.dpi = dpi >= 1 && dpi <= 100000 ? dpi : 72;
  std::array<double, 4> background{1, 1, 1, 1};
  const std::string color = archive.data(document, "backgroundColor");
  if (color.size() >= 16) {
    for (size_t channel = 0; channel < 4; ++channel) {
      const uint32_t bits =
          littleEndian32(reinterpret_cast<const uint8_t*>(color.data()) + channel * 4);
      float value = 0;
      std::memcpy(&value, &bits, sizeof(value));
      background[channel] = std::clamp(static_cast<double>(value), 0.0, 1.0);
    }
  }
  imported.backgroundColor = background;
  imported.backgroundHidden = archive.boolean(document, "backgroundHidden", false);
  if (const auto profile = archive.member(document, "colorProfile")) {
    const std::string name = archive.string(*profile, "SiColorProfileArchiveICCNameKey");
    if (!name.empty() && name.find("sRGB") == std::string::npos) {
      imported.colorProfileName = name;
    }
  }
  return true;
}

ImportResult ProcreateImporter::run() {
  std::string error;
  ZipLimits zipLimits;
  zipLimits.maximumTotalUncompressedBytes = uint64_t{1} << 40U;
  zip_ = ZipReader::open(source_, zipLimits, error);
  const ZipEntry* archiveEntry = zip_ ? zip_->find("Document.archive") : nullptr;
  if (archiveEntry == nullptr) {
    fail("notProcreate", error);
    return result_;
  }
  std::vector<uint8_t> archiveBytes;
  if (!zip_->extract(*archiveEntry, kMaximumArchiveBytes, archiveBytes, error)) {
    fail("corrupt", error);
    return result_;
  }
  auto archive = KeyedArchive::parse(std::move(archiveBytes), error);
  if (!archive) {
    fail("corrupt", error);
    return result_;
  }
  archive_ = std::make_unique<KeyedArchive>(std::move(*archive));
  const auto document = archive_->root();
  if (!document || archive_->className(*document) != "SilicaDocument") {
    fail("notProcreate", "root object");
    return result_;
  }
  result_.document.source = "procreate";
  if (!readDocument(*document)) {
    return result_;
  }
  output_ = std::make_unique<DocumentImportFolder>(folder_, result_.document.width,
                                                   result_.document.height);
  if (compositeOnly_) {
    const auto composite = archive_->member(*document, "composite");
    ImportedNode node;
    if (!composite || !convertLayer(*composite, node)) {
      if (result_.errorCode.empty()) {
        fail("corrupt", "no composite");
      }
      return result_;
    }
    result_.document.layers = {std::move(node)};
    result_.document.backgroundHidden = true;
  } else if (!convertNodes(archive_->array(*document, "unwrappedLayers"), 1,
                           result_.document.layers)) {
    return result_;
  }
  if (!output_->writeImportJson(result_.document)) {
    fail("writeFailed");
    return result_;
  }
  result_.ok = true;
  return result_;
}

}  // namespace

bool decodeProcreateTile(const uint8_t* data, size_t size, uint8_t* output, size_t outputSize) {
  size_t position = 0;
  size_t written = 0;
  while (position + 4 <= size) {
    if (std::memcmp(data + position, "bv4$", 4) == 0) {
      return written == outputSize;
    }
    if (std::memcmp(data + position, "bv41", 4) != 0 || size - position < 12) {
      return false;
    }
    const uint32_t decoded = littleEndian32(data + position + 4);
    const uint32_t encoded = littleEndian32(data + position + 8);
    position += 12;
    if (encoded > size - position || decoded > outputSize - written || encoded > INT32_MAX ||
        decoded > INT32_MAX) {
      return false;
    }
    const size_t dictionary = std::min<size_t>(written, kLinkedDictionaryBytes);
    const int result = LZ4_decompress_safe_usingDict(
        reinterpret_cast<const char*>(data + position), reinterpret_cast<char*>(output + written),
        static_cast<int>(encoded), static_cast<int>(decoded),
        reinterpret_cast<const char*>(output + written - dictionary), static_cast<int>(dictionary));
    if (result != static_cast<int>(decoded)) {
      return false;
    }
    position += encoded;
    written += decoded;
  }
  return false;
}

ImportResult importProcreate(InputSource& source, const std::string& folder,
                             const ImportLimits& limits) {
  return ProcreateImporter(source, folder, limits, false).run();
}

ImportResult importProcreateComposite(InputSource& source, const std::string& folder,
                                      const ImportLimits& limits) {
  return ProcreateImporter(source, folder, limits, true).run();
}

}  // namespace nanquim::formats
