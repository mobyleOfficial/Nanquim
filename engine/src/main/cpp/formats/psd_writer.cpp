#include "formats/psd_writer.h"

#include <miniz.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <memory>
#include <set>
#include <utility>

#include "canvas/color_conversion.h"
#include "formats/icc_profile.h"
#include "formats/output_file.h"
#include "formats/psd_reader.h"
#include "formats/source_reader.h"
#include "io/document_tile.h"
#include "io/file_system.h"

namespace nanquim::formats {

namespace {

using canvas::kTileSizePixels;

constexpr int32_t kPsdMaximumSide = 30000;
constexpr int32_t kPsbMaximumSide = 300000;
constexpr int32_t kMaximumRecords = 32767;
constexpr uint32_t kRle = 1;
constexpr uint32_t kZipPredicted = 3;
constexpr int32_t kCompositeBandRows = 256;
constexpr char kGroupEndName[] = "</Layer group>";

struct Bounds {
  int32_t top = 0;
  int32_t left = 0;
  int32_t bottom = 0;
  int32_t right = 0;

  [[nodiscard]] int32_t width() const { return right - left; }
  [[nodiscard]] int32_t height() const { return bottom - top; }
  [[nodiscard]] bool empty() const { return width() <= 0 || height() <= 0; }
};

using TileSet = std::set<std::pair<int32_t, int32_t>>;

TileSet tilesOf(const std::string& surfaceFolder) {
  bool rebuilt = false;
  TileSet tiles;
  for (const canvas::TileCoordinate coordinate :
       io::readSurfaceIndex(surfaceFolder, rebuilt).tiles) {
    tiles.emplace(coordinate.column, coordinate.row);
  }
  return tiles;
}

Bounds boundsOf(const TileSet& tiles, int32_t canvasWidth, int32_t canvasHeight) {
  Bounds bounds{canvasHeight, canvasWidth, 0, 0};
  for (const auto& [column, row] : tiles) {
    bounds.left = std::min(bounds.left, column * kTileSizePixels);
    bounds.top = std::min(bounds.top, row * kTileSizePixels);
    bounds.right = std::max(bounds.right, std::min(canvasWidth, (column + 1) * kTileSizePixels));
    bounds.bottom = std::max(bounds.bottom, std::min(canvasHeight, (row + 1) * kTileSizePixels));
  }
  return bounds.empty() ? Bounds{} : bounds;
}

uint8_t unpremultiply(uint8_t channel, uint8_t alpha) {
  if (alpha == 0) {
    return 0;
  }
  const uint32_t value = (static_cast<uint32_t>(channel) * 255U + alpha / 2U) / alpha;
  return static_cast<uint8_t>(std::min(value, 255U));
}

std::vector<uint16_t> utf16Of(const std::string& text) {
  std::vector<uint16_t> units;
  size_t index = 0;
  while (index < text.size()) {
    const auto lead = static_cast<uint8_t>(text[index]);
    const size_t length = lead < 0x80            ? 1
                          : (lead >> 5U) == 0x6  ? 2
                          : (lead >> 4U) == 0xE  ? 3
                          : (lead >> 3U) == 0x1E ? 4
                                                 : 0;
    uint32_t codePoint = 0xFFFD;
    if (length == 1) {
      codePoint = lead;
    } else if (length > 1 && index + length <= text.size()) {
      uint32_t value = lead & (0x7FU >> length);
      bool valid = true;
      for (size_t next = 1; next < length; ++next) {
        const auto continuation = static_cast<uint8_t>(text[index + next]);
        valid = valid && (continuation >> 6U) == 0x2;
        value = (value << 6U) | (continuation & 0x3FU);
      }
      constexpr uint32_t kMinimum[] = {0, 0, 0x80, 0x800, 0x10000};
      if (valid && value >= kMinimum[length] && value <= 0x10FFFF &&
          (value < 0xD800 || value > 0xDFFF)) {
        codePoint = value;
      }
    }
    index += length == 0 || index + length > text.size() ? 1 : length;
    if (codePoint >= 0x10000) {
      codePoint -= 0x10000;
      units.push_back(static_cast<uint16_t>(0xD800U + (codePoint >> 10U)));
      units.push_back(static_cast<uint16_t>(0xDC00U + (codePoint & 0x3FFU)));
    } else {
      units.push_back(static_cast<uint16_t>(codePoint));
    }
  }
  return units;
}

std::string asciiName(const std::string& text) {
  std::string name;
  for (const uint16_t unit : utf16Of(text)) {
    if (unit >= 0xDC00 && unit < 0xE000) {
      continue;
    }
    name.push_back(unit >= 0x20 && unit < 0x7F ? static_cast<char>(unit) : '?');
    if (name.size() == 255) {
      break;
    }
  }
  return name;
}

uint8_t byteOf(double unit) {
  return static_cast<uint8_t>(std::lround(std::clamp(unit, 0.0, 1.0) * 255.0));
}

uint16_t sampleOf(double unit) {
  return static_cast<uint16_t>(std::lround(std::clamp(unit, 0.0, 1.0) * 65535.0));
}

class ChannelRows {
 public:
  virtual ~ChannelRows() = default;
  virtual bool row(int32_t y, uint8_t* samples) = 0;
  virtual bool deepRow(int32_t y, uint16_t* samples) = 0;
};

class ConstantRows final : public ChannelRows {
 public:
  ConstantRows(int32_t width, double unit) : width_(width), unit_(unit) {}
  bool row(int32_t, uint8_t* samples) override {
    std::fill_n(samples, width_, byteOf(unit_));
    return true;
  }
  bool deepRow(int32_t, uint16_t* samples) override {
    std::fill_n(samples, width_, sampleOf(unit_));
    return true;
  }

 private:
  int32_t width_;
  double unit_;
};

class SurfaceRows final : public ChannelRows {
 public:
  SurfaceRows(std::string folder, io::DocumentTileFormat format, const TileSet& tiles,
              const Bounds& bounds, int32_t channel, bool invert, bool deep,
              const color::CmykTransform* cmyk, bool& damaged)
      : folder_(std::move(folder)),
        format_(format),
        tiles_(tiles),
        bounds_(bounds),
        channel_(channel),
        invert_(invert),
        deep_(deep && cmyk == nullptr),
        cmyk_(format == io::DocumentTileFormat::R8 ? nullptr : cmyk),
        components_(cmyk_ != nullptr ? 5 : 4),
        damaged_(damaged),
        band_(static_cast<size_t>(bounds.width()) * kTileSizePixels),
        tile_(canvas::kTileBytesRgba8) {}

  bool row(int32_t y, uint8_t* samples) override {
    if (y / kTileSizePixels != loadedRow_) {
      load(y / kTileSizePixels);
    }
    const auto width = static_cast<size_t>(bounds_.width());
    const size_t offset = static_cast<size_t>(y % kTileSizePixels) * width;
    for (size_t x = 0; x < width; ++x) {
      samples[x] = static_cast<uint8_t>((band_[offset + x] * 255U + 32767U) / 65535U);
    }
    return true;
  }

  bool deepRow(int32_t y, uint16_t* samples) override {
    if (y / kTileSizePixels != loadedRow_) {
      load(y / kTileSizePixels);
    }
    const auto width = static_cast<size_t>(bounds_.width());
    std::copy_n(band_.data() + static_cast<size_t>(y % kTileSizePixels) * width, width, samples);
    return true;
  }

 private:
  bool readTile(int32_t column, int32_t row, std::vector<uint16_t>& pixels) {
    constexpr size_t kPixels = static_cast<size_t>(kTileSizePixels) * kTileSizePixels;
    pixels.resize(kPixels * components_);
    if (format_ == io::DocumentTileFormat::R8) {
      if (io::readDocumentTile(folder_, {column, row}, format_, tile_.data()) !=
          io::TileReadResult::Ok) {
        return false;
      }
      for (size_t index = 0; index < kPixels * 4; ++index) {
        pixels[index] = static_cast<uint16_t>(tile_[index] * 257U);
      }
      return true;
    }
    if (deep_) {
      halves_.resize(kPixels * 8);
      if (io::readDocumentTile(folder_, {column, row}, io::DocumentTileFormat::Rgba16f,
                               halves_.data(), canvas::PixelFormat::Rgba16fPremulLinear) ==
          io::TileReadResult::Ok) {
        canvas::premultipliedLinearHalfToStraightSrgb16(halves_.data(), pixels.data(), kPixels);
        return true;
      }
    }
    if (io::readDocumentTile(folder_, {column, row}, io::DocumentTileFormat::Rgba8, tile_.data()) !=
        io::TileReadResult::Ok) {
      return false;
    }
    if (cmyk_ != nullptr) {
      straight_.resize(kPixels * 4);
      inks_.resize(kPixels * 5);
      for (size_t pixel = 0; pixel < kPixels; ++pixel) {
        const uint8_t* source = tile_.data() + pixel * 4;
        for (size_t channel = 0; channel < 3; ++channel) {
          straight_[pixel * 4 + channel] = unpremultiply(source[channel], source[3]);
        }
        straight_[pixel * 4 + 3] = source[3];
      }
      cmyk_->convert(straight_.data(), inks_.data(), kPixels);
      for (size_t pixel = 0; pixel < kPixels; ++pixel) {
        for (size_t channel = 0; channel < 4; ++channel) {
          pixels[pixel * 5 + channel] =
              static_cast<uint16_t>((255U - inks_[pixel * 5 + channel]) * 257U);
        }
        pixels[pixel * 5 + 4] = static_cast<uint16_t>(inks_[pixel * 5 + 4] * 257U);
      }
      return true;
    }
    for (size_t pixel = 0; pixel < kPixels; ++pixel) {
      const uint8_t* source = tile_.data() + pixel * 4;
      for (size_t channel = 0; channel < 3; ++channel) {
        pixels[pixel * 4 + channel] =
            static_cast<uint16_t>(unpremultiply(source[channel], source[3]) * 257U);
      }
      pixels[pixel * 4 + 3] = static_cast<uint16_t>(source[3] * 257U);
    }
    return true;
  }

  void load(int32_t tileRow) {
    const bool isMask = format_ == io::DocumentTileFormat::R8;
    const auto width = static_cast<size_t>(bounds_.width());
    std::fill(band_.begin(), band_.end(), isMask && !invert_ ? 65535 : 0);
    for (int32_t column = bounds_.left / kTileSizePixels; column * kTileSizePixels < bounds_.right;
         ++column) {
      if (tiles_.count({column, tileRow}) == 0) {
        continue;
      }
      if (!readTile(column, tileRow, pixels_)) {
        damaged_ = true;
        continue;
      }
      const int32_t firstX = std::max(bounds_.left, column * kTileSizePixels);
      const int32_t endX = std::min(bounds_.right, (column + 1) * kTileSizePixels);
      for (int32_t row = 0; row < kTileSizePixels; ++row) {
        const uint16_t* source =
            pixels_.data() + static_cast<size_t>(row) * kTileSizePixels * components_;
        uint16_t* destination = band_.data() + static_cast<size_t>(row) * width;
        for (int32_t x = firstX; x < endX; ++x) {
          const uint16_t* pixel =
              source + static_cast<size_t>(x - column * kTileSizePixels) * components_;
          uint16_t value = isMask ? pixel[0] : pixel[channel_];
          if (isMask && invert_) {
            value = static_cast<uint16_t>(65535 - value);
          }
          destination[x - bounds_.left] = value;
        }
      }
    }
    loadedRow_ = tileRow;
  }

  std::string folder_;
  io::DocumentTileFormat format_;
  const TileSet& tiles_;
  Bounds bounds_;
  int32_t channel_;
  bool invert_;
  bool deep_;
  const color::CmykTransform* cmyk_;
  size_t components_;
  bool& damaged_;
  int32_t loadedRow_ = -1;
  std::vector<uint16_t> band_;
  std::vector<uint8_t> tile_;
  std::vector<uint8_t> halves_;
  std::vector<uint16_t> pixels_;
  std::vector<uint8_t> straight_;
  std::vector<uint8_t> inks_;
};

class CompositeRows final : public ChannelRows {
 public:
  CompositeRows(CompositeSource& source, int32_t width, int32_t height, int32_t channel,
                const color::CmykTransform* cmyk = nullptr)
      : source_(source),
        width_(width),
        height_(height),
        channel_(channel),
        cmyk_(cmyk),
        band_(static_cast<size_t>(width) * kCompositeBandRows * 4) {}

  bool row(int32_t y, uint8_t* samples) override {
    if (y < bandTop_ || y >= bandTop_ + kCompositeBandRows) {
      bandTop_ = y - y % kCompositeBandRows;
      const int32_t rows = std::min(kCompositeBandRows, height_ - bandTop_);
      if (!source_.readRows(bandTop_, rows, band_.data())) {
        return false;
      }
      if (cmyk_ != nullptr) {
        inks_.resize(static_cast<size_t>(width_) * kCompositeBandRows * 5);
        cmyk_->convert(band_.data(), inks_.data(),
                       static_cast<size_t>(width_) * static_cast<size_t>(rows));
      }
    }
    const size_t stride = cmyk_ != nullptr ? 5 : 4;
    const uint8_t* source =
        (cmyk_ != nullptr ? inks_.data() : band_.data()) +
        static_cast<size_t>(y - bandTop_) * static_cast<size_t>(width_) * stride;
    const bool inverted = cmyk_ != nullptr && channel_ < 4;
    for (int32_t x = 0; x < width_; ++x) {
      const uint8_t value = source[static_cast<size_t>(x) * stride + static_cast<size_t>(channel_)];
      samples[x] = inverted ? static_cast<uint8_t>(255 - value) : value;
    }
    return true;
  }

  bool deepRow(int32_t y, uint16_t* samples) override {
    if (y < deepBandTop_ || y >= deepBandTop_ + kCompositeBandRows) {
      deepBandTop_ = y - y % kCompositeBandRows;
      deepBand_.resize(static_cast<size_t>(width_) * kCompositeBandRows * 4);
      if (!source_.readDeepRows(deepBandTop_, std::min(kCompositeBandRows, height_ - deepBandTop_),
                                deepBand_.data())) {
        return false;
      }
    }
    const uint16_t* source =
        deepBand_.data() + static_cast<size_t>(y - deepBandTop_) * static_cast<size_t>(width_) * 4;
    for (int32_t x = 0; x < width_; ++x) {
      samples[x] = source[static_cast<size_t>(x) * 4 + static_cast<size_t>(channel_)];
    }
    return true;
  }

 private:
  CompositeSource& source_;
  int32_t width_;
  int32_t height_;
  int32_t channel_;
  const color::CmykTransform* cmyk_;
  int32_t bandTop_ = -kCompositeBandRows;
  int32_t deepBandTop_ = -kCompositeBandRows;
  std::vector<uint8_t> band_;
  std::vector<uint8_t> inks_;
  std::vector<uint16_t> deepBand_;
};

class PredictedZipWriter {
 public:
  explicit PredictedZipWriter(OutputFile& out) : out_(out), buffer_(size_t{64} * 1024) {
    ok_ = mz_deflateInit(&stream_, MZ_DEFAULT_LEVEL) == MZ_OK;
  }
  ~PredictedZipWriter() { mz_deflateEnd(&stream_); }
  PredictedZipWriter(const PredictedZipWriter&) = delete;
  PredictedZipWriter& operator=(const PredictedZipWriter&) = delete;

  bool row(const uint16_t* samples, size_t count) {
    bytes_.resize(count * 2);
    uint16_t previous = 0;
    for (size_t index = 0; index < count; ++index) {
      const auto delta = static_cast<uint16_t>(samples[index] - previous);
      previous = samples[index];
      bytes_[index * 2] = static_cast<uint8_t>(delta >> 8U);
      bytes_[index * 2 + 1] = static_cast<uint8_t>(delta & 0xFFU);
    }
    return feed(bytes_.data(), bytes_.size(), MZ_NO_FLUSH);
  }

  bool finish() { return feed(nullptr, 0, MZ_FINISH); }

 private:
  bool feed(const uint8_t* data, size_t size, int flush) {
    if (!ok_) {
      return false;
    }
    stream_.next_in = data;
    stream_.avail_in = static_cast<unsigned int>(size);
    for (;;) {
      stream_.next_out = buffer_.data();
      stream_.avail_out = static_cast<unsigned int>(buffer_.size());
      const int status = mz_deflate(&stream_, flush);
      if (status != MZ_OK && status != MZ_STREAM_END && status != MZ_BUF_ERROR) {
        ok_ = false;
        return false;
      }
      out_.bytes(buffer_.data(), buffer_.size() - stream_.avail_out);
      if (flush == MZ_FINISH ? status == MZ_STREAM_END
                             : stream_.avail_in == 0 && stream_.avail_out > 0) {
        return true;
      }
    }
  }

  OutputFile& out_;
  mz_stream stream_{};
  std::vector<uint8_t> buffer_;
  std::vector<uint8_t> bytes_;
  bool ok_ = false;
};

struct ChannelPlan {
  int16_t id = 0;
  uint64_t lengthOffset = 0;
};

struct RecordPlan {
  enum class Kind { Raster, Background, GroupStart, GroupEnd };
  Kind kind = Kind::Raster;
  const ImportedNode* node = nullptr;
  std::string layerFolder;
  TileSet layerTiles;
  Bounds bounds;
  std::string maskFolder;
  TileSet maskTiles;
  Bounds maskBounds;
  std::vector<ChannelPlan> channels;
};

class PsdExporter {
 public:
  PsdExporter(const ImportedDocument& document, std::string folder, CompositeSource& composite,
              PsdExportOptions options)
      : document_(document),
        folder_(std::move(folder)),
        composite_(composite),
        options_(std::move(options)),
        cmyk_(options_.cmyk != nullptr ? options_.cmyk->transform : nullptr),
        psb_(document.width > kPsdMaximumSide || document.height > kPsdMaximumSide) {
    if (cmyk_ != nullptr) {
      options_.deep = false;
    }
  }

  ExportResult run(const std::string& outputPath);

 private:
  bool fail(const std::string& code, const std::string& detail = {}) {
    result_.ok = false;
    result_.errorCode = code;
    result_.errorDetail = detail;
    return false;
  }
  void warn(const std::string& code, const std::string& detail = {}) {
    for (const ImportWarning& warning : result_.warnings) {
      if (warning.code == code && warning.detail == detail) {
        return;
      }
    }
    result_.warnings.push_back({code, detail});
  }

  void plan(const std::vector<ImportedNode>& nodes);
  void writeHeader();
  void writeImageResources();
  bool writeLayerSection();
  void writeRecord(RecordPlan& record);
  void writeAdditionalInfo(const char (&key)[5], const std::vector<uint8_t>& data);
  bool writeChannelData(RecordPlan& record);
  bool writeRleRows(ChannelRows& rows, int32_t top, int32_t height, int32_t width,
                    uint64_t tableOffset);
  bool writeZipRows(ChannelRows& rows, int32_t top, int32_t height, int32_t width,
                    PredictedZipWriter& zip);
  bool writeMergedImage();
  [[nodiscard]] std::string blendKeyOf(const ImportedNode& node);

  const ImportedDocument& document_;
  std::string folder_;
  CompositeSource& composite_;
  PsdExportOptions options_;
  const color::CmykTransform* cmyk_;
  bool psb_;
  ExportResult result_;
  std::unique_ptr<OutputFile> out_;
  std::vector<RecordPlan> records_;
  std::vector<uint8_t> rowSamples_;
  std::vector<uint16_t> deepSamples_;
  std::vector<uint8_t> packed_;
};

void PsdExporter::plan(const std::vector<ImportedNode>& nodes) {
  for (const ImportedNode& node : nodes) {
    if (node.type == ImportedNode::Type::Group) {
      RecordPlan end;
      end.kind = RecordPlan::Kind::GroupEnd;
      end.node = &node;
      records_.push_back(std::move(end));
      plan(node.children);
      RecordPlan start;
      start.kind = RecordPlan::Kind::GroupStart;
      start.node = &node;
      records_.push_back(std::move(start));
      continue;
    }
    RecordPlan record;
    record.node = &node;
    record.layerFolder = io::joinPath(io::joinPath(folder_, "layers"), node.uuid);
    record.layerTiles = tilesOf(record.layerFolder);
    record.bounds = boundsOf(record.layerTiles, document_.width, document_.height);
    if (node.mask) {
      record.maskFolder = io::joinPath(io::joinPath(folder_, "masks"), node.mask->uuid);
      record.maskTiles = tilesOf(record.maskFolder);
      record.maskBounds = boundsOf(record.maskTiles, document_.width, document_.height);
    }
    records_.push_back(std::move(record));
  }
}

std::string PsdExporter::blendKeyOf(const ImportedNode& node) {
  const bool group = node.type == ImportedNode::Type::Group;
  const std::string key = psdBlendKey(node.blend);
  if (key.empty() || (!group && key == "pass")) {
    warn("blendModeReplaced", node.name);
    return group ? "pass" : "norm";
  }
  return key;
}

void PsdExporter::writeHeader() {
  out_->text("8BPS");
  out_->u16(psb_ ? 2 : 1);
  out_->zeros(6);
  out_->u16(cmyk_ != nullptr ? 5 : 4);
  out_->u32(static_cast<uint32_t>(document_.height));
  out_->u32(static_cast<uint32_t>(document_.width));
  out_->u16(options_.deep ? 16 : 8);
  out_->u16(cmyk_ != nullptr ? 4 : 3);
  out_->u32(0);
}

void PsdExporter::writeImageResources() {
  const uint64_t lengthOffset = out_->position();
  out_->u32(0);
  const uint64_t begin = out_->position();
  const auto resource = [this](uint16_t id, const std::vector<uint8_t>& data) {
    out_->text("8BIM");
    out_->u16(id);
    out_->u16(0);
    out_->u32(static_cast<uint32_t>(data.size()));
    out_->bytes(data);
    out_->zeros(data.size() % 2);
  };
  const auto fixed = static_cast<uint32_t>(std::lround(document_.dpi * 65536.0));
  std::vector<uint8_t> resolution;
  for (int pass = 0; pass < 2; ++pass) {
    for (int shift = 24; shift >= 0; shift -= 8) {
      resolution.push_back(static_cast<uint8_t>(fixed >> static_cast<uint32_t>(shift)));
    }
    resolution.insert(resolution.end(), {0, 1, 0, 1});
  }
  resource(1005, resolution);
  resource(1039, cmyk_ != nullptr              ? options_.cmyk->profile
                 : options_.iccProfile.empty() ? srgbIccProfile()
                                               : options_.iccProfile);
  out_->patch32(lengthOffset, static_cast<uint32_t>(out_->position() - begin));
}

void PsdExporter::writeAdditionalInfo(const char (&key)[5], const std::vector<uint8_t>& data) {
  out_->text("8BIM");
  out_->text(std::string(key, 4));
  const size_t padded = (data.size() + 3) / 4 * 4;
  out_->u32(static_cast<uint32_t>(padded));
  out_->bytes(data);
  out_->zeros(padded - data.size());
}

void PsdExporter::writeRecord(RecordPlan& record) {
  const ImportedNode* node = record.node;
  const bool background = record.kind == RecordPlan::Kind::Background;
  const bool raster = record.kind == RecordPlan::Kind::Raster;
  const bool section = !raster && !background;
  Bounds bounds = record.bounds;
  if (background) {
    bounds = {0, 0, document_.height, document_.width};
    record.bounds = bounds;
  }
  out_->u32(static_cast<uint32_t>(bounds.top));
  out_->u32(static_cast<uint32_t>(bounds.left));
  out_->u32(static_cast<uint32_t>(bounds.bottom));
  out_->u32(static_cast<uint32_t>(bounds.right));
  record.channels = {{-1}, {0}, {1}, {2}};
  if (cmyk_ != nullptr) {
    record.channels.push_back({3});
  }
  if (raster && node->mask) {
    record.channels.push_back({-2});
  }
  out_->u16(static_cast<uint32_t>(record.channels.size()));
  for (ChannelPlan& channel : record.channels) {
    out_->u16(static_cast<uint16_t>(channel.id));
    channel.lengthOffset = out_->position();
    psb_ ? out_->u64(0) : out_->u32(0);
  }

  std::string blendKey = "norm";
  uint8_t opacity = 255;
  uint8_t flags = 0x08;
  std::string name = options_.backgroundLayerName;
  if (record.kind == RecordPlan::Kind::GroupEnd) {
    flags |= 0x10U;
    name = kGroupEndName;
  } else if (!background) {
    blendKey = blendKeyOf(*node);
    opacity = byteOf(node->opacity);
    flags |= (node->alphaLock ? 0x01U : 0U) | (node->hidden ? 0x02U : 0U) | (section ? 0x10U : 0U);
    name = node->name;
  }
  out_->text("8BIM");
  out_->text(blendKey);
  out_->u8(opacity);
  out_->u8(raster && node->clipping ? 1 : 0);
  out_->u8(flags);
  out_->u8(0);

  const uint64_t extraLengthOffset = out_->position();
  out_->u32(0);
  const uint64_t extraBegin = out_->position();
  if (raster && node->mask) {
    const Bounds& mask = record.maskBounds;
    out_->u32(20);
    out_->u32(static_cast<uint32_t>(mask.top));
    out_->u32(static_cast<uint32_t>(mask.left));
    out_->u32(static_cast<uint32_t>(mask.bottom));
    out_->u32(static_cast<uint32_t>(mask.right));
    out_->u8(node->mask->inverted ? 0 : 255);
    out_->u8(node->mask->enabled ? 0 : 0x02U);
    out_->zeros(2);
  } else {
    out_->u32(0);
  }
  out_->u32(0);
  const std::string pascal = asciiName(name);
  const uint64_t nameBegin = out_->position();
  out_->u8(static_cast<uint32_t>(pascal.size()));
  out_->text(pascal);
  out_->padFrom(nameBegin, 4);

  const std::vector<uint16_t> units = utf16Of(name);
  const auto count = static_cast<uint32_t>(units.size());
  std::vector<uint8_t> unicode{static_cast<uint8_t>(count >> 24U),
                               static_cast<uint8_t>(count >> 16U),
                               static_cast<uint8_t>(count >> 8U), static_cast<uint8_t>(count)};
  for (const uint16_t unit : units) {
    unicode.push_back(static_cast<uint8_t>(unit >> 8U));
    unicode.push_back(static_cast<uint8_t>(unit & 0xFFU));
  }
  writeAdditionalInfo("luni", unicode);
  if (section) {
    std::vector<uint8_t> divider{0, 0, 0, 3};
    if (record.kind == RecordPlan::Kind::GroupStart) {
      divider = {0, 0, 0, static_cast<uint8_t>(node->collapsed ? 2 : 1)};
      divider.insert(divider.end(), {'8', 'B', 'I', 'M'});
      divider.insert(divider.end(), blendKey.begin(), blendKey.end());
    }
    writeAdditionalInfo("lsct", divider);
  }
  if (!background && record.kind != RecordPlan::Kind::GroupEnd && node->locked) {
    writeAdditionalInfo("lspf", {0, 0, 0, 0x06});
  }
  out_->patch32(extraLengthOffset, static_cast<uint32_t>(out_->position() - extraBegin));
}

bool PsdExporter::writeRleRows(ChannelRows& rows, int32_t top, int32_t height, int32_t width,
                               uint64_t tableOffset) {
  std::vector<uint8_t> table;
  table.reserve(static_cast<size_t>(height) * (psb_ ? 4 : 2));
  rowSamples_.resize(static_cast<size_t>(width));
  for (int32_t y = top; y < top + height; ++y) {
    if (!rows.row(y, rowSamples_.data())) {
      return false;
    }
    packed_.clear();
    encodePackBits(rowSamples_.data(), rowSamples_.size(), packed_);
    out_->bytes(packed_);
    const auto length = static_cast<uint32_t>(packed_.size());
    if (psb_) {
      table.push_back(static_cast<uint8_t>(length >> 24U));
      table.push_back(static_cast<uint8_t>(length >> 16U));
    }
    table.push_back(static_cast<uint8_t>(length >> 8U));
    table.push_back(static_cast<uint8_t>(length & 0xFFU));
  }
  out_->patch(tableOffset, table.data(), table.size());
  return true;
}

bool PsdExporter::writeZipRows(ChannelRows& rows, int32_t top, int32_t height, int32_t width,
                               PredictedZipWriter& zip) {
  deepSamples_.resize(static_cast<size_t>(width));
  for (int32_t y = top; y < top + height; ++y) {
    if (!rows.deepRow(y, deepSamples_.data()) ||
        !zip.row(deepSamples_.data(), deepSamples_.size())) {
      return false;
    }
  }
  return true;
}

bool PsdExporter::writeChannelData(RecordPlan& record) {
  const ImportedNode* node = record.node;
  bool damaged = false;
  for (const ChannelPlan& channel : record.channels) {
    const uint64_t begin = out_->position();
    const bool mask = channel.id == -2;
    const Bounds& bounds = mask ? record.maskBounds : record.bounds;
    const bool hasPixels =
        record.kind == RecordPlan::Kind::Raster || record.kind == RecordPlan::Kind::Background;
    if (!hasPixels || bounds.empty()) {
      out_->u16(0);
    } else {
      std::unique_ptr<ChannelRows> rows;
      const int32_t alphaComponent = cmyk_ != nullptr ? 4 : 3;
      const int32_t component = channel.id == -1 ? alphaComponent : channel.id;
      if (record.kind == RecordPlan::Kind::Background) {
        const std::array<double, 4> color =
            document_.backgroundColor.value_or(std::array<double, 4>{1, 1, 1, 1});
        double value = component == alphaComponent ? color[3] : 0.0;
        if (component != alphaComponent && cmyk_ == nullptr) {
          value = color[static_cast<size_t>(component)];
        } else if (component != alphaComponent) {
          const std::array<uint8_t, 4> rgba{byteOf(color[0]), byteOf(color[1]), byteOf(color[2]),
                                            255};
          std::array<uint8_t, 5> inks{};
          cmyk_->convert(rgba.data(), inks.data(), 1);
          value = (255 - inks[static_cast<size_t>(component)]) / 255.0;
        }
        rows = std::make_unique<ConstantRows>(bounds.width(), value);
      } else if (mask) {
        rows = std::make_unique<SurfaceRows>(record.maskFolder, io::DocumentTileFormat::R8,
                                             record.maskTiles, bounds, 0, node->mask->inverted,
                                             options_.deep, nullptr, damaged);
      } else {
        rows = std::make_unique<SurfaceRows>(record.layerFolder, io::DocumentTileFormat::Rgba8,
                                             record.layerTiles, bounds, component, false,
                                             options_.deep, cmyk_, damaged);
      }
      if (options_.deep) {
        out_->u16(kZipPredicted);
        PredictedZipWriter zip(*out_);
        if (!writeZipRows(*rows, bounds.top, bounds.height(), bounds.width(), zip) ||
            !zip.finish()) {
          return fail("writeFailed");
        }
      } else {
        out_->u16(kRle);
        const uint64_t tableOffset = out_->position();
        out_->zeros(static_cast<size_t>(bounds.height()) * (psb_ ? 4 : 2));
        if (!writeRleRows(*rows, bounds.top, bounds.height(), bounds.width(), tableOffset)) {
          return fail("writeFailed");
        }
      }
    }
    out_->patchLength(channel.lengthOffset, out_->position() - begin, psb_);
  }
  if (damaged) {
    warn("tileUnreadable", node->name);
  }
  return true;
}

bool PsdExporter::writeLayerSection() {
  const uint64_t sectionLengthOffset = out_->position();
  psb_ ? out_->u64(0) : out_->u32(0);
  const uint64_t sectionBegin = out_->position();
  if (!records_.empty()) {
    const uint64_t infoLengthOffset = out_->position();
    psb_ ? out_->u64(0) : out_->u32(0);
    const uint64_t infoBegin = out_->position();
    out_->u16(static_cast<uint16_t>(-static_cast<int32_t>(records_.size())));
    for (RecordPlan& record : records_) {
      writeRecord(record);
    }
    for (RecordPlan& record : records_) {
      if (!writeChannelData(record)) {
        return false;
      }
    }
    out_->padFrom(infoBegin, 4);
    out_->patchLength(infoLengthOffset, out_->position() - infoBegin, psb_);
  } else {
    psb_ ? out_->u64(0) : out_->u32(0);
  }
  out_->u32(0);
  out_->patchLength(sectionLengthOffset, out_->position() - sectionBegin, psb_);
  return true;
}

bool PsdExporter::writeMergedImage() {
  const int32_t width = document_.width;
  const int32_t height = document_.height;
  if (options_.deep) {
    out_->u16(kZipPredicted);
    PredictedZipWriter zip(*out_);
    for (int32_t channel = 0; channel < 4; ++channel) {
      CompositeRows rows(composite_, width, height, channel);
      if (!writeZipRows(rows, 0, height, width, zip)) {
        return fail("compositeFailed");
      }
    }
    return zip.finish() || fail("writeFailed");
  }
  const int32_t planes = cmyk_ != nullptr ? 5 : 4;
  out_->u16(kRle);
  const uint64_t tableOffset = out_->position();
  const uint64_t tableBytes = static_cast<uint64_t>(height) * (psb_ ? 4 : 2);
  out_->zeros(static_cast<size_t>(tableBytes * static_cast<uint64_t>(planes)));
  for (int32_t channel = 0; channel < planes; ++channel) {
    CompositeRows rows(composite_, width, height, channel, cmyk_);
    if (!writeRleRows(rows, 0, height, width,
                      tableOffset + tableBytes * static_cast<uint64_t>(channel))) {
      return fail("compositeFailed");
    }
  }
  return true;
}

ExportResult PsdExporter::run(const std::string& outputPath) {
  if (document_.width <= 0 || document_.height <= 0 || document_.width > kPsbMaximumSide ||
      document_.height > kPsbMaximumSide) {
    fail("invalidDocument", "canvas size");
    return result_;
  }
  if (document_.backgroundColor && !document_.backgroundHidden) {
    RecordPlan background;
    background.kind = RecordPlan::Kind::Background;
    records_.push_back(std::move(background));
  }
  plan(document_.layers);
  if (records_.size() > static_cast<size_t>(kMaximumRecords)) {
    fail("tooManyLayers", std::to_string(records_.size()));
    return result_;
  }
  out_ = OutputFile::create(outputPath);
  if (!out_) {
    fail("writeFailed", "create");
    return result_;
  }
  writeHeader();
  writeImageResources();
  if (!writeLayerSection() || !writeMergedImage()) {
    return result_;
  }
  if (!out_->close()) {
    fail("writeFailed");
    return result_;
  }
  result_.ok = true;
  return result_;
}

}  // namespace

std::string psdBlendKey(const std::string& blendId) {
  for (const auto& [key, id] : psdBlendKeys()) {
    if (id == blendId) {
      return key;
    }
  }
  return {};
}

ExportResult exportPsd(const ImportedDocument& document, const std::string& documentFolder,
                       CompositeSource& composite, const PsdExportOptions& options,
                       const std::string& outputPath) {
  return PsdExporter(document, documentFolder, composite, options).run(outputPath);
}

}  // namespace nanquim::formats
