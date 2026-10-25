#include "formats/psd_reader.h"

#include <miniz.h>

#include <algorithm>
#include <array>
#include <cstring>
#include <limits>
#include <memory>
#include <optional>
#include <set>
#include <utility>
#include <vector>

#include "formats/source_reader.h"
#include "io/file_system.h"

namespace nanquim::formats {

namespace {

constexpr uint32_t fourCharacters(const char (&text)[5]) {
  return (static_cast<uint32_t>(static_cast<uint8_t>(text[0])) << 24U) |
         (static_cast<uint32_t>(static_cast<uint8_t>(text[1])) << 16U) |
         (static_cast<uint32_t>(static_cast<uint8_t>(text[2])) << 8U) |
         static_cast<uint32_t>(static_cast<uint8_t>(text[3]));
}

std::string keyText(uint32_t key) {
  std::string text(4, ' ');
  for (int index = 0; index < 4; ++index) {
    text[static_cast<size_t>(index)] = static_cast<char>((key >> (24U - 8U * index)) & 0xFFU);
  }
  return text;
}

constexpr int32_t kBandRows = 256;
constexpr int32_t kPsdMaximumSide = 30000;
constexpr int32_t kPsbMaximumSide = 300000;
constexpr uint64_t kMaximumBlockBytes = 64ULL << 20U;
constexpr uint32_t kMaximumChannels = 56;
constexpr uint64_t kInflateChunkBytes = uint64_t{64} * 1024;

enum class ColorMode : uint16_t { Grayscale = 1, Rgb = 3, Cmyk = 4 };

enum Compression : uint16_t { Raw = 0, Rle = 1, Zip = 2, ZipPredicted = 3 };

bool hasLongLength(uint32_t key) {
  static const std::set<uint32_t> keys{
      fourCharacters("LMsk"), fourCharacters("Lr16"), fourCharacters("Lr32"),
      fourCharacters("Layr"), fourCharacters("Mt16"), fourCharacters("Mt32"),
      fourCharacters("Mtrn"), fourCharacters("Alph"), fourCharacters("FMsk"),
      fourCharacters("lnk2"), fourCharacters("FEid"), fourCharacters("FXid"),
      fourCharacters("PxSD")};
  return keys.count(key) > 0;
}

bool isAdditionalInfoSignature(uint32_t signature) {
  return signature == fourCharacters("8BIM") || signature == fourCharacters("8B64");
}

void appendUtf8(uint32_t codePoint, std::string& text) {
  if (codePoint < 0x80) {
    text.push_back(static_cast<char>(codePoint));
  } else if (codePoint < 0x800) {
    text.push_back(static_cast<char>(0xC0U | (codePoint >> 6U)));
    text.push_back(static_cast<char>(0x80U | (codePoint & 0x3FU)));
  } else if (codePoint < 0x10000) {
    text.push_back(static_cast<char>(0xE0U | (codePoint >> 12U)));
    text.push_back(static_cast<char>(0x80U | ((codePoint >> 6U) & 0x3FU)));
    text.push_back(static_cast<char>(0x80U | (codePoint & 0x3FU)));
  } else {
    text.push_back(static_cast<char>(0xF0U | (codePoint >> 18U)));
    text.push_back(static_cast<char>(0x80U | ((codePoint >> 12U) & 0x3FU)));
    text.push_back(static_cast<char>(0x80U | ((codePoint >> 6U) & 0x3FU)));
    text.push_back(static_cast<char>(0x80U | (codePoint & 0x3FU)));
  }
}

std::string utf16BigEndianToUtf8(const std::vector<uint16_t>& units) {
  std::string text;
  for (size_t index = 0; index < units.size(); ++index) {
    uint32_t unit = units[index];
    if (unit == 0) {
      break;
    }
    if (unit >= 0xD800 && unit < 0xDC00 && index + 1 < units.size() && units[index + 1] >= 0xDC00 &&
        units[index + 1] < 0xE000) {
      unit = 0x10000 + ((unit - 0xD800) << 10U) + (units[index + 1] - 0xDC00U);
      ++index;
    } else if (unit >= 0xD800 && unit < 0xE000) {
      unit = 0xFFFD;
    }
    appendUtf8(unit, text);
  }
  return text;
}

std::string latin1ToUtf8(const std::string& name) {
  std::string text;
  for (const char character : name) {
    appendUtf8(static_cast<uint8_t>(character), text);
  }
  return text;
}

struct Rectangle {
  int32_t top = 0;
  int32_t left = 0;
  int32_t bottom = 0;
  int32_t right = 0;

  [[nodiscard]] int64_t width() const { return static_cast<int64_t>(right) - left; }
  [[nodiscard]] int64_t height() const { return static_cast<int64_t>(bottom) - top; }
};

struct ChannelInfo {
  int16_t id = 0;
  uint64_t length = 0;
  uint64_t offset = 0;
};

struct LayerRecord {
  Rectangle bounds;
  std::vector<ChannelInfo> channels;
  uint32_t blendKey = fourCharacters("norm");
  uint8_t opacity = 255;
  uint8_t fillOpacity = 255;
  uint8_t clipping = 0;
  uint8_t flags = 0;
  bool hasMask = false;
  Rectangle maskBounds;
  uint8_t maskDefault = 255;
  uint8_t maskFlags = 0;
  std::string name;
  std::optional<uint32_t> sectionType;
  std::optional<uint32_t> sectionBlendKey;
  uint32_t protection = 0;
  std::set<uint32_t> keys;
};

class ChannelReader {
 public:
  ChannelReader(InputSource& source, int32_t width, int32_t height, int32_t bytesPerSample)
      : source_(source),
        width_(width),
        height_(height),
        bytesPerSample_(bytesPerSample),
        rowBytes_(static_cast<size_t>(width) * static_cast<size_t>(bytesPerSample)) {}
  ~ChannelReader() {
    if (inflating_) {
      mz_inflateEnd(&stream_);
    }
  }
  ChannelReader(const ChannelReader&) = delete;
  ChannelReader& operator=(const ChannelReader&) = delete;

  bool openLayerChannel(uint64_t offset, uint64_t length, bool psb) {
    if (length < 2) {
      return false;
    }
    SourceReader reader(source_, offset, offset + length);
    const uint16_t compression = reader.u16();
    if (!reader.ok()) {
      return false;
    }
    std::vector<uint32_t> rowLengths;
    if (compression == Rle) {
      rowLengths.resize(static_cast<size_t>(height_));
      for (uint32_t& rowLength : rowLengths) {
        rowLength = psb ? reader.u32() : reader.u16();
      }
      if (!reader.ok()) {
        return false;
      }
    }
    return open(compression, reader.position(), offset + length, std::move(rowLengths), 0);
  }

  bool open(uint16_t compression, uint64_t dataBegin, uint64_t dataEnd,
            std::vector<uint32_t> rowLengths, uint64_t skipDecodedBytes) {
    compression_ = compression;
    position_ = dataBegin;
    end_ = std::min(dataEnd, source_.size());
    if (position_ > end_) {
      return false;
    }
    switch (compression) {
      case Raw:
        return static_cast<uint64_t>(height_) * rowBytes_ <= end_ - position_;
      case Rle: {
        if (rowLengths.size() != static_cast<size_t>(height_)) {
          return false;
        }
        uint64_t total = 0;
        for (const uint32_t rowLength : rowLengths) {
          total += rowLength;
        }
        if (total > end_ - position_) {
          return false;
        }
        rowLengths_ = std::move(rowLengths);
        return true;
      }
      case Zip:
      case ZipPredicted:
        if (bytesPerSample_ == 4) {
          return false;
        }
        std::memset(&stream_, 0, sizeof(stream_));
        if (mz_inflateInit(&stream_) != MZ_OK) {
          return false;
        }
        inflating_ = true;
        return skipDecoded(skipDecodedBytes);
      default:
        return false;
    }
  }

  bool readRows(int32_t count, uint8_t* destination) {
    for (int32_t row = 0; row < count; ++row) {
      if (rowsRead_ >= height_ || !readRow(destination + static_cast<size_t>(row) * rowBytes_)) {
        return false;
      }
      ++rowsRead_;
    }
    return true;
  }

 private:
  bool readRow(uint8_t* destination) {
    switch (compression_) {
      case Raw:
        if (!source_.read(position_, destination, rowBytes_)) {
          return false;
        }
        position_ += rowBytes_;
        return true;
      case Rle: {
        const uint32_t rowLength = rowLengths_[static_cast<size_t>(rowsRead_)];
        if (rowLength > rowBytes_ + rowBytes_ / 128 + 2 ||
            !source_.readBytes(position_, rowLength, packed_)) {
          return false;
        }
        position_ += rowLength;
        return decodePackBits(packed_.data(), packed_.size(), destination, rowBytes_);
      }
      default:
        if (!inflate(destination, rowBytes_)) {
          return false;
        }
        if (compression_ == ZipPredicted) {
          undoPrediction(destination);
        }
        return true;
    }
  }

  void undoPrediction(uint8_t* row) const {
    if (bytesPerSample_ == 1) {
      for (int32_t x = 1; x < width_; ++x) {
        row[x] = static_cast<uint8_t>(row[x] + row[x - 1]);
      }
      return;
    }
    auto previous = static_cast<uint16_t>((row[0] << 8U) | row[1]);
    for (int32_t x = 1; x < width_; ++x) {
      const size_t index = static_cast<size_t>(x) * 2;
      const auto delta = static_cast<uint16_t>((row[index] << 8U) | row[index + 1]);
      previous = static_cast<uint16_t>(previous + delta);
      row[index] = static_cast<uint8_t>(previous >> 8U);
      row[index + 1] = static_cast<uint8_t>(previous & 0xFFU);
    }
  }

  bool skipDecoded(uint64_t count) {
    std::vector<uint8_t> scratch(std::min<uint64_t>(count, kInflateChunkBytes));
    while (count > 0) {
      const auto chunk = static_cast<size_t>(std::min<uint64_t>(count, scratch.size()));
      if (!inflate(scratch.data(), chunk)) {
        return false;
      }
      count -= chunk;
    }
    return true;
  }

  bool inflate(uint8_t* destination, size_t count) {
    stream_.next_out = destination;
    stream_.avail_out = static_cast<unsigned int>(count);
    while (stream_.avail_out > 0) {
      if (stream_.avail_in == 0 && position_ < end_) {
        const auto chunk =
            static_cast<size_t>(std::min<uint64_t>(end_ - position_, kInflateChunkBytes));
        if (!source_.readBytes(position_, chunk, input_)) {
          return false;
        }
        position_ += chunk;
        stream_.next_in = input_.data();
        stream_.avail_in = static_cast<unsigned int>(input_.size());
      }
      const unsigned int before = stream_.avail_out;
      const int status = mz_inflate(&stream_, MZ_NO_FLUSH);
      if (status == MZ_STREAM_END) {
        return stream_.avail_out == 0;
      }
      if (status != MZ_OK && status != MZ_BUF_ERROR) {
        return false;
      }
      if (stream_.avail_out == before && stream_.avail_in == 0 && position_ >= end_) {
        return false;
      }
    }
    return true;
  }

  InputSource& source_;
  int32_t width_;
  int32_t height_;
  int32_t bytesPerSample_;
  size_t rowBytes_;
  uint16_t compression_ = Raw;
  uint64_t position_ = 0;
  uint64_t end_ = 0;
  int32_t rowsRead_ = 0;
  std::vector<uint32_t> rowLengths_;
  std::vector<uint8_t> packed_;
  std::vector<uint8_t> input_;
  mz_stream stream_{};
  bool inflating_ = false;
};

void skipToSignature(SourceReader& reader, uint64_t end) {
  const uint64_t start = reader.position();
  for (uint64_t padding = 0; padding <= 3 && end - start >= padding + 4; ++padding) {
    reader.seek(start + padding);
    if (isAdditionalInfoSignature(reader.u32())) {
      reader.seek(start + padding);
      return;
    }
  }
  reader.seek(start);
}

uint8_t sampleToByte(const uint8_t* samples, size_t index, int32_t bytesPerSample) {
  if (bytesPerSample == 1) {
    return samples[index];
  }
  const uint32_t value = (static_cast<uint32_t>(samples[index * 2]) << 8U) | samples[index * 2 + 1];
  return static_cast<uint8_t>((value * 255U + 32767U) / 65535U);
}

class PsdImporter {
 public:
  PsdImporter(InputSource& source, std::string folder, const ImportLimits& limits)
      : source_(source), folder_(std::move(folder)), limits_(limits) {}

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

  bool readHeader(SourceReader& reader);
  void readImageResources(SourceReader& reader, uint64_t end);
  bool readLayerInfo(uint64_t begin, uint64_t end);
  bool readLayerRecord(SourceReader& reader, LayerRecord& record);
  void readAdditionalInfo(SourceReader& reader, uint64_t end, LayerRecord& record);
  bool convertLayers();
  bool convertRaster(const LayerRecord& record, ImportedNode& node);
  bool convertMask(const LayerRecord& record, ImportedNode& node);
  bool convertMergedImage(uint64_t begin);
  bool convertPlanes(std::vector<std::unique_ptr<ChannelReader>>& color,
                     std::unique_ptr<ChannelReader>& alpha, const Rectangle& bounds,
                     SurfaceTileWriter& writer);

  [[nodiscard]] int32_t colorChannelCount() const {
    return colorMode_ == ColorMode::Grayscale ? 1 : colorMode_ == ColorMode::Rgb ? 3 : 4;
  }
  [[nodiscard]] int32_t bytesPerSample() const { return depth_ / 8; }
  [[nodiscard]] int32_t maximumRecordSide() const {
    return psb_ ? kPsbMaximumSide : kPsdMaximumSide;
  }

  InputSource& source_;
  std::string folder_;
  ImportLimits limits_;
  ImportResult result_;
  std::unique_ptr<DocumentImportFolder> output_;
  bool psb_ = false;
  uint16_t channels_ = 0;
  int32_t width_ = 0;
  int32_t height_ = 0;
  uint16_t depth_ = 8;
  ColorMode colorMode_ = ColorMode::Rgb;
  std::vector<uint8_t> embeddedCmykProfile_;
  std::unique_ptr<color::CmykImageTransform> cmyk_;
  std::vector<LayerRecord> records_;
  bool mergedHasAlpha_ = false;
};

bool PsdImporter::readHeader(SourceReader& reader) {
  if (reader.u32() != fourCharacters("8BPS")) {
    return fail("notPsd");
  }
  const uint16_t version = reader.u16();
  if (version != 1 && version != 2) {
    return fail("unsupportedVersion", std::to_string(version));
  }
  psb_ = version == 2;
  reader.skip(6);
  channels_ = reader.u16();
  const uint32_t height = reader.u32();
  const uint32_t width = reader.u32();
  depth_ = reader.u16();
  const uint16_t mode = reader.u16();
  if (!reader.ok()) {
    return fail("notPsd");
  }
  if (mode != static_cast<uint16_t>(ColorMode::Grayscale) &&
      mode != static_cast<uint16_t>(ColorMode::Rgb) &&
      mode != static_cast<uint16_t>(ColorMode::Cmyk)) {
    return fail("unsupportedColorMode", std::to_string(mode));
  }
  colorMode_ = static_cast<ColorMode>(mode);
  if (depth_ != 8 && depth_ != 16) {
    return fail("unsupportedDepth", std::to_string(depth_));
  }
  if (width == 0 || height == 0 || width > static_cast<uint32_t>(maximumRecordSide()) ||
      height > static_cast<uint32_t>(maximumRecordSide()) || channels_ == 0 ||
      channels_ > kMaximumChannels || channels_ < colorChannelCount()) {
    return fail("corrupt", "header");
  }
  if (width > static_cast<uint32_t>(limits_.maximumSide) ||
      height > static_cast<uint32_t>(limits_.maximumSide)) {
    return fail("canvasTooLarge", std::to_string(width) + "x" + std::to_string(height));
  }
  width_ = static_cast<int32_t>(width);
  height_ = static_cast<int32_t>(height);
  return true;
}

void PsdImporter::readImageResources(SourceReader& reader, uint64_t end) {
  while (reader.ok() && end - reader.position() >= 12) {
    reader.u32();
    const uint16_t id = reader.u16();
    const uint8_t nameLength = reader.u8();
    reader.skip(nameLength + ((nameLength + 1U) % 2U));
    const uint32_t size = reader.u32();
    const uint64_t dataBegin = reader.position();
    if (!reader.ok() || size > end - dataBegin) {
      return;
    }
    if (id == 1005 && size >= 16) {
      const uint32_t horizontal = reader.u32();
      const uint16_t unit = reader.u16();
      const double pixelsPerUnit = horizontal / 65536.0;
      const double dpi = unit == 2 ? pixelsPerUnit * 2.54 : pixelsPerUnit;
      if (reader.ok() && dpi >= 1 && dpi <= 100000) {
        result_.document.dpi = dpi;
      }
    } else if (id == 1039) {
      std::vector<uint8_t> profile;
      if (reader.bytes(size, kMaximumBlockBytes, profile)) {
        if (colorMode_ == ColorMode::Cmyk) {
          embeddedCmykProfile_ = std::move(profile);
        } else if (!adoptEmbeddedProfile(result_.document, profile)) {
          warn("colorProfileIgnored");
        }
      }
    }
    reader.seek(dataBegin + size + (size % 2U));
  }
}

void PsdImporter::readAdditionalInfo(SourceReader& reader, uint64_t end, LayerRecord& record) {
  while (reader.ok() && end - reader.position() >= 12) {
    const uint32_t signature = reader.u32();
    if (!isAdditionalInfoSignature(signature)) {
      return;
    }
    const uint32_t key = reader.u32();
    const uint64_t length = psb_ && hasLongLength(key) ? reader.u64() : reader.u32();
    const uint64_t dataBegin = reader.position();
    if (!reader.ok() || length > end - dataBegin) {
      return;
    }
    record.keys.insert(key);
    if (key == fourCharacters("luni") && length >= 4) {
      const uint32_t count = reader.u32();
      if (count <= (length - 4) / 2) {
        std::vector<uint16_t> units(count);
        for (uint16_t& unit : units) {
          unit = reader.u16();
        }
        if (reader.ok()) {
          record.name = utf16BigEndianToUtf8(units);
        }
      }
    } else if ((key == fourCharacters("lsct") || key == fourCharacters("lsdk")) && length >= 4) {
      record.sectionType = reader.u32();
      if (length >= 12 && isAdditionalInfoSignature(reader.u32())) {
        record.sectionBlendKey = reader.u32();
      }
    } else if (key == fourCharacters("iOpa") && length >= 1) {
      record.fillOpacity = reader.u8();
    } else if (key == fourCharacters("lspf") && length >= 4) {
      record.protection = reader.u32();
    }
    reader.seek(dataBegin + length);
  }
}

bool PsdImporter::readLayerRecord(SourceReader& reader, LayerRecord& record) {
  record.bounds.top = reader.i32();
  record.bounds.left = reader.i32();
  record.bounds.bottom = reader.i32();
  record.bounds.right = reader.i32();
  const uint16_t channelCount = reader.u16();
  if (!reader.ok() || channelCount > kMaximumChannels) {
    return false;
  }
  record.channels.resize(channelCount);
  for (ChannelInfo& channel : record.channels) {
    channel.id = reader.i16();
    channel.length = psb_ ? reader.u64() : reader.u32();
  }
  if (reader.u32() != fourCharacters("8BIM")) {
    return false;
  }
  record.blendKey = reader.u32();
  record.opacity = reader.u8();
  record.clipping = reader.u8();
  record.flags = reader.u8();
  reader.u8();
  const uint32_t extraLength = reader.u32();
  const uint64_t extraBegin = reader.position();
  if (!reader.ok() || extraLength > reader.remaining()) {
    return false;
  }
  const uint64_t extraEnd = extraBegin + extraLength;
  SourceReader extra(source_, extraBegin, extraEnd);
  const uint32_t maskLength = extra.u32();
  const uint64_t maskBegin = extra.position();
  if (maskLength >= 18) {
    record.maskBounds.top = extra.i32();
    record.maskBounds.left = extra.i32();
    record.maskBounds.bottom = extra.i32();
    record.maskBounds.right = extra.i32();
    record.maskDefault = extra.u8();
    record.maskFlags = extra.u8();
    record.hasMask = extra.ok();
  }
  extra.seek(maskBegin + maskLength);
  extra.skip(extra.u32());
  const uint8_t nameLength = extra.u8();
  record.name = latin1ToUtf8(extra.string(nameLength));
  extra.skip((4U - (1U + nameLength) % 4U) % 4U);
  if (!extra.ok()) {
    return false;
  }
  readAdditionalInfo(extra, extraEnd, record);
  reader.seek(extraEnd);
  return reader.ok();
}

bool PsdImporter::readLayerInfo(uint64_t begin, uint64_t end) {
  SourceReader reader(source_, begin, end);
  const int16_t signedCount = reader.i16();
  const int32_t count = signedCount < 0 ? -static_cast<int32_t>(signedCount) : signedCount;
  mergedHasAlpha_ = signedCount < 0;
  if (!reader.ok()) {
    return fail("corrupt", "layer count");
  }
  if (count > limits_.maximumLayers) {
    return fail("tooManyLayers", std::to_string(count));
  }
  records_.resize(static_cast<size_t>(count));
  for (LayerRecord& record : records_) {
    if (!readLayerRecord(reader, record)) {
      return fail("corrupt", "layer record");
    }
  }
  uint64_t offset = reader.position();
  for (LayerRecord& record : records_) {
    for (ChannelInfo& channel : record.channels) {
      channel.offset = offset;
      if (channel.length > end - std::min(end, offset)) {
        return fail("corrupt", "channel data");
      }
      offset += channel.length;
    }
  }
  return true;
}

bool PsdImporter::convertPlanes(std::vector<std::unique_ptr<ChannelReader>>& color,
                                std::unique_ptr<ChannelReader>& alpha, const Rectangle& bounds,
                                SurfaceTileWriter& writer) {
  const auto width = static_cast<int32_t>(bounds.width());
  const auto height = static_cast<int32_t>(bounds.height());
  const int32_t sampleBytes = bytesPerSample();
  const size_t planeRowBytes = static_cast<size_t>(width) * static_cast<size_t>(sampleBytes);
  std::vector<std::vector<uint8_t>> planes(color.size() + 1);
  std::vector<uint8_t> rgba(static_cast<size_t>(width) * kBandRows * 4);
  std::vector<uint8_t> inks(cmyk_ ? rgba.size() : 0);
  for (int32_t bandTop = 0; bandTop < height; bandTop += kBandRows) {
    const int32_t rows = std::min(kBandRows, height - bandTop);
    const size_t planeBytes = planeRowBytes * static_cast<size_t>(rows);
    for (size_t index = 0; index < color.size(); ++index) {
      planes[index].resize(planeBytes);
      if (color[index] && !color[index]->readRows(rows, planes[index].data())) {
        return fail("corrupt", "channel rows");
      }
      if (!color[index]) {
        std::fill(planes[index].begin(), planes[index].end(), 0);
      }
    }
    std::vector<uint8_t>& alphaPlane = planes.back();
    alphaPlane.resize(planeBytes);
    if (alpha && !alpha->readRows(rows, alphaPlane.data())) {
      return fail("corrupt", "alpha rows");
    }
    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(rows);
    for (size_t pixel = 0; pixel < pixels; ++pixel) {
      uint8_t* out = &rgba[pixel * 4];
      if (colorMode_ == ColorMode::Rgb) {
        out[0] = sampleToByte(planes[0].data(), pixel, sampleBytes);
        out[1] = sampleToByte(planes[1].data(), pixel, sampleBytes);
        out[2] = sampleToByte(planes[2].data(), pixel, sampleBytes);
      } else if (colorMode_ == ColorMode::Grayscale) {
        out[0] = out[1] = out[2] = sampleToByte(planes[0].data(), pixel, sampleBytes);
      } else if (cmyk_) {
        for (size_t channel = 0; channel < 4; ++channel) {
          inks[pixel * 4 + channel] =
              static_cast<uint8_t>(255U - sampleToByte(planes[channel].data(), pixel, sampleBytes));
        }
      } else {
        const uint32_t black = sampleToByte(planes[3].data(), pixel, sampleBytes);
        for (size_t channel = 0; channel < 3; ++channel) {
          const uint32_t ink = sampleToByte(planes[channel].data(), pixel, sampleBytes);
          out[channel] = static_cast<uint8_t>((ink * black + 127U) / 255U);
        }
      }
      out[3] = alpha ? sampleToByte(alphaPlane.data(), pixel, sampleBytes) : 255;
    }
    if (cmyk_ && colorMode_ == ColorMode::Cmyk) {
      cmyk_->convert(inks.data(), rgba.data(), pixels);
    }
    if (!writer.writePixels(bounds.left, bounds.top + bandTop, width, rows, rgba.data(),
                            static_cast<size_t>(width) * 4)) {
      return fail("writeFailed");
    }
  }
  return true;
}

bool PsdImporter::convertRaster(const LayerRecord& record, ImportedNode& node) {
  node.uuid = output_->newUuid();
  SurfaceTileWriter writer = output_->layerWriter(node.uuid);
  const Rectangle& bounds = record.bounds;
  if (bounds.width() > 0 && bounds.height() > 0) {
    if (bounds.width() > maximumRecordSide() || bounds.height() > maximumRecordSide()) {
      return fail("corrupt", "layer bounds");
    }
    const auto width = static_cast<int32_t>(bounds.width());
    const auto height = static_cast<int32_t>(bounds.height());
    std::vector<std::unique_ptr<ChannelReader>> color(static_cast<size_t>(colorChannelCount()));
    std::unique_ptr<ChannelReader> alpha;
    for (const ChannelInfo& channel : record.channels) {
      std::unique_ptr<ChannelReader>* target = nullptr;
      if (channel.id >= 0 && channel.id < colorChannelCount()) {
        target = &color[static_cast<size_t>(channel.id)];
      } else if (channel.id == -1) {
        target = &alpha;
      }
      if (target == nullptr) {
        continue;
      }
      *target = std::make_unique<ChannelReader>(source_, width, height, bytesPerSample());
      if (!(*target)->openLayerChannel(channel.offset, channel.length, psb_)) {
        return fail("corrupt", "channel " + std::to_string(channel.id));
      }
    }
    if (!convertPlanes(color, alpha, bounds, writer)) {
      return false;
    }
  }
  if (!writer.finish()) {
    return fail("writeFailed");
  }
  return convertMask(record, node);
}

bool PsdImporter::convertMask(const LayerRecord& record, ImportedNode& node) {
  const auto channel = std::find_if(record.channels.begin(), record.channels.end(),
                                    [](const ChannelInfo& info) { return info.id == -2; });
  if (!record.hasMask || channel == record.channels.end()) {
    return true;
  }
  const Rectangle& bounds = record.maskBounds;
  if (bounds.width() < 0 || bounds.height() < 0 || bounds.width() > maximumRecordSide() ||
      bounds.height() > maximumRecordSide()) {
    return true;
  }
  ImportedMask mask;
  mask.uuid = output_->newUuid();
  mask.enabled = (record.maskFlags & 0x02U) == 0;
  SurfaceTileWriter writer = output_->maskWriter(mask.uuid, record.maskDefault);
  const auto width = static_cast<int32_t>(bounds.width());
  const auto height = static_cast<int32_t>(bounds.height());
  if (width > 0 && height > 0) {
    ChannelReader reader(source_, width, height, bytesPerSample());
    if (!reader.openLayerChannel(channel->offset, channel->length, psb_)) {
      return fail("corrupt", "mask channel");
    }
    std::vector<uint8_t> samples;
    std::vector<uint8_t> gray;
    for (int32_t bandTop = 0; bandTop < height; bandTop += kBandRows) {
      const int32_t rows = std::min(kBandRows, height - bandTop);
      const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(rows);
      samples.resize(pixels * static_cast<size_t>(bytesPerSample()));
      gray.resize(pixels);
      if (!reader.readRows(rows, samples.data())) {
        return fail("corrupt", "mask rows");
      }
      for (size_t pixel = 0; pixel < pixels; ++pixel) {
        gray[pixel] = sampleToByte(samples.data(), pixel, bytesPerSample());
      }
      if (!writer.writePixels(bounds.left, bounds.top + bandTop, width, rows, gray.data(),
                              static_cast<size_t>(width))) {
        return fail("writeFailed");
      }
    }
  }
  if (!writer.finish()) {
    return fail("writeFailed");
  }
  node.mask = mask;
  return true;
}

bool PsdImporter::convertLayers() {
  static const std::set<uint32_t> adjustmentKeys{
      fourCharacters("levl"), fourCharacters("curv"), fourCharacters("brit"),
      fourCharacters("blnc"), fourCharacters("blwh"), fourCharacters("hue2"),
      fourCharacters("hue "), fourCharacters("selc"), fourCharacters("mixr"),
      fourCharacters("grdm"), fourCharacters("phfl"), fourCharacters("expA"),
      fourCharacters("vibA"), fourCharacters("thrs"), fourCharacters("post"),
      fourCharacters("nvrt"), fourCharacters("clrL")};
  static const std::set<uint32_t> fillKeys{fourCharacters("SoCo"), fourCharacters("GdFl"),
                                           fourCharacters("PtFl")};
  std::vector<std::vector<ImportedNode>> stack(1);
  for (const LayerRecord& record : records_) {
    const std::string& name = record.name;
    const uint32_t section = record.sectionType.value_or(0);
    if (section == 3) {
      stack.emplace_back();
      continue;
    }
    ImportedNode node;
    node.name = name;
    node.opacity = (record.opacity / 255.0) * (section == 0 ? record.fillOpacity / 255.0 : 1.0);
    node.hidden = (record.flags & 0x02U) != 0;
    node.alphaLock = (record.flags & 0x01U) != 0;
    node.locked = (record.protection & 0x02U) != 0;
    const uint32_t blendKey =
        section != 0 && record.sectionBlendKey ? *record.sectionBlendKey : record.blendKey;
    node.blend = psdBlendModeId(keyText(blendKey));
    if (node.blend.empty() || (section == 0 && node.blend == "passThrough")) {
      warn("blendModeReplaced", name);
      node.blend = section == 0 ? "normal" : "passThrough";
    }
    if (section == 1 || section == 2) {
      node.type = ImportedNode::Type::Group;
      node.uuid = output_->newUuid();
      node.collapsed = section == 2;
      if (stack.size() > 1) {
        node.children = std::move(stack.back());
        stack.pop_back();
      } else {
        warn("groupStructureRepaired", name);
      }
      stack.back().push_back(std::move(node));
      continue;
    }
    bool skipped = false;
    for (const uint32_t key : record.keys) {
      if (adjustmentKeys.count(key) > 0) {
        warn("adjustmentLayerSkipped", name);
        skipped = true;
      } else if (fillKeys.count(key) > 0) {
        warn("fillLayerSkipped", name);
        skipped = true;
      } else if (key == fourCharacters("lfx2") || key == fourCharacters("lrFX") ||
                 key == fourCharacters("lmfx")) {
        warn("layerEffectsDropped", name);
      } else if (key == fourCharacters("vmsk") || key == fourCharacters("vsms")) {
        warn("vectorMaskDropped", name);
      } else if (key == fourCharacters("TySh") || key == fourCharacters("tySh")) {
        warn("textRasterized", name);
      } else if (key == fourCharacters("SoLd") || key == fourCharacters("PlLd") ||
                 key == fourCharacters("SoLE")) {
        warn("smartObjectRasterized", name);
      }
    }
    if (skipped) {
      continue;
    }
    node.clipping = record.clipping != 0;
    if (!convertRaster(record, node)) {
      return false;
    }
    stack.back().push_back(std::move(node));
  }
  if (stack.size() > 1) {
    warn("groupStructureRepaired");
    for (size_t level = stack.size() - 1; level > 0; --level) {
      for (ImportedNode& node : stack[level]) {
        stack[level - 1].push_back(std::move(node));
      }
    }
  }
  result_.document.layers = std::move(stack.front());
  return true;
}

bool PsdImporter::convertMergedImage(uint64_t begin) {
  SourceReader reader(source_, begin, source_.size());
  const uint16_t compression = reader.u16();
  if (!reader.ok()) {
    return fail("corrupt", "image data");
  }
  const int32_t colorCount = colorChannelCount();
  const int32_t planeCount = colorCount + (mergedHasAlpha_ && channels_ > colorCount ? 1 : 0);
  const uint64_t rowBytes = static_cast<uint64_t>(width_) * static_cast<uint64_t>(bytesPerSample());
  std::vector<std::vector<uint32_t>> rowLengths(static_cast<size_t>(planeCount));
  uint64_t dataBegin = reader.position();
  if (compression == Rle) {
    const uint64_t lengthBytes = psb_ ? 4 : 2;
    for (int32_t plane = 0; plane < planeCount; ++plane) {
      rowLengths[static_cast<size_t>(plane)].resize(static_cast<size_t>(height_));
      for (uint32_t& length : rowLengths[static_cast<size_t>(plane)]) {
        length = psb_ ? reader.u32() : reader.u16();
      }
    }
    dataBegin =
        begin + 2 + static_cast<uint64_t>(channels_) * static_cast<uint64_t>(height_) * lengthBytes;
    if (!reader.ok()) {
      return fail("corrupt", "image data row lengths");
    }
  }
  std::vector<std::unique_ptr<ChannelReader>> color(static_cast<size_t>(colorCount));
  std::unique_ptr<ChannelReader> alpha;
  uint64_t planeBegin = dataBegin;
  for (int32_t plane = 0; plane < planeCount; ++plane) {
    auto channel = std::make_unique<ChannelReader>(source_, width_, height_, bytesPerSample());
    uint64_t planeBytes = rowBytes * static_cast<uint64_t>(height_);
    bool opened = false;
    if (compression == Rle) {
      planeBytes = 0;
      for (const uint32_t length : rowLengths[static_cast<size_t>(plane)]) {
        planeBytes += length;
      }
      opened = channel->open(Rle, planeBegin, planeBegin + planeBytes,
                             std::move(rowLengths[static_cast<size_t>(plane)]), 0);
    } else if (compression == Raw) {
      opened = channel->open(Raw, planeBegin, planeBegin + planeBytes, {}, 0);
    } else {
      opened =
          channel->open(compression, dataBegin, source_.size(), {},
                        rowBytes * static_cast<uint64_t>(height_) * static_cast<uint64_t>(plane));
      planeBytes = 0;
    }
    if (!opened) {
      return fail("corrupt", "image data plane");
    }
    planeBegin += planeBytes;
    if (plane < colorCount) {
      color[static_cast<size_t>(plane)] = std::move(channel);
    } else {
      alpha = std::move(channel);
    }
  }
  ImportedNode node;
  node.uuid = output_->newUuid();
  SurfaceTileWriter writer = output_->layerWriter(node.uuid);
  const Rectangle bounds{0, 0, height_, width_};
  if (!convertPlanes(color, alpha, bounds, writer)) {
    return false;
  }
  if (!writer.finish()) {
    return fail("writeFailed");
  }
  result_.document.layers.push_back(std::move(node));
  warn("mergedImageUsed");
  return true;
}

ImportResult PsdImporter::run() {
  SourceReader reader(source_, 0, source_.size());
  if (!readHeader(reader)) {
    return result_;
  }
  result_.document.source = psb_ ? "psb" : "psd";
  result_.document.width = width_;
  result_.document.height = height_;
  result_.document.backgroundColor = std::array<double, 4>{1, 1, 1, 1};
  result_.document.backgroundHidden = true;
  output_ = std::make_unique<DocumentImportFolder>(folder_, width_, height_);

  reader.skip(reader.u32());
  const uint32_t resourcesLength = reader.u32();
  const uint64_t resourcesBegin = reader.position();
  if (!reader.ok() || resourcesLength > reader.remaining()) {
    fail("corrupt", "image resources");
    return result_;
  }
  {
    SourceReader resources(source_, resourcesBegin, resourcesBegin + resourcesLength);
    readImageResources(resources, resourcesBegin + resourcesLength);
  }
  if (colorMode_ == ColorMode::Cmyk) {
    cmyk_ = adoptCmykCanvas(result_.document, embeddedCmykProfile_, limits_);
    if (!cmyk_) {
      warn("cmykConverted");
    }
  }
  reader.seek(resourcesBegin + resourcesLength);

  const uint64_t sectionLength = psb_ ? reader.u64() : reader.u32();
  const uint64_t sectionBegin = reader.position();
  if (!reader.ok() || sectionLength > reader.remaining()) {
    fail("corrupt", "layer and mask section");
    return result_;
  }
  const uint64_t sectionEnd = sectionBegin + sectionLength;
  if (sectionLength > 0) {
    const uint64_t layerInfoLength = psb_ ? reader.u64() : reader.u32();
    const uint64_t layerInfoBegin = reader.position();
    if (!reader.ok() || layerInfoLength > sectionEnd - layerInfoBegin) {
      fail("corrupt", "layer info");
      return result_;
    }
    if (layerInfoLength > 0 && !readLayerInfo(layerInfoBegin, layerInfoBegin + layerInfoLength)) {
      return result_;
    }
    reader.seek(layerInfoBegin + layerInfoLength);
    if (sectionEnd - reader.position() >= 4) {
      reader.skip(reader.u32());
    }
    while (reader.ok() && sectionEnd - reader.position() >= 12) {
      const uint32_t signature = reader.u32();
      if (!isAdditionalInfoSignature(signature)) {
        break;
      }
      const uint32_t key = reader.u32();
      const uint64_t length = psb_ && hasLongLength(key) ? reader.u64() : reader.u32();
      const uint64_t dataBegin = reader.position();
      if (!reader.ok() || length > sectionEnd - dataBegin) {
        break;
      }
      if ((key == fourCharacters("Lr16") || key == fourCharacters("Lr32")) && records_.empty() &&
          length > 0) {
        if (key == fourCharacters("Lr32")) {
          fail("unsupportedDepth", "32");
          return result_;
        }
        if (!readLayerInfo(dataBegin, dataBegin + length)) {
          return result_;
        }
      }
      reader.seek(dataBegin + length);
      skipToSignature(reader, sectionEnd);
    }
  }

  if (!records_.empty()) {
    if (!convertLayers()) {
      return result_;
    }
  } else if (!convertMergedImage(sectionEnd)) {
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

const std::vector<std::pair<std::string, std::string>>& psdBlendKeys() {
  static const std::vector<std::pair<std::string, std::string>> kBlendKeys{
      {"norm", "normal"},       {"mul ", "multiply"},   {"scrn", "screen"},
      {"over", "overlay"},      {"dark", "darken"},     {"lite", "lighten"},
      {"div ", "colorDodge"},   {"idiv", "colorBurn"},  {"hLit", "hardLight"},
      {"sLit", "softLight"},    {"diff", "difference"}, {"smud", "exclusion"},
      {"lddg", "add"},          {"fsub", "subtract"},   {"fdiv", "divide"},
      {"lbrn", "linearBurn"},   {"vLit", "vividLight"}, {"lLit", "linearLight"},
      {"pLit", "pinLight"},     {"hMix", "hardMix"},    {"dkCl", "darkerColor"},
      {"lgCl", "lighterColor"}, {"hue ", "hue"},        {"sat ", "saturation"},
      {"colr", "color"},        {"lum ", "luminosity"}, {"pass", "passThrough"},
  };
  return kBlendKeys;
}

std::string psdBlendModeId(const std::string& key) {
  for (const auto& [psdKey, id] : psdBlendKeys()) {
    if (key == psdKey) {
      return id;
    }
  }
  return {};
}

ImportResult importPsd(InputSource& source, const std::string& folder,
                       const ImportLimits& limits) {
  return PsdImporter(source, folder, limits).run();
}

}  // namespace nanquim::formats
