#include "formats/tiff_reader.h"

#include <miniz.h>

#include <algorithm>
#include <array>
#include <cstring>
#include <map>
#include <memory>
#include <optional>
#include <vector>

namespace nanquim::formats {

namespace {

constexpr uint32_t kMaximumEntries = 4096;
constexpr uint64_t kMaximumArrayValues = uint64_t{1} << 22U;
constexpr uint64_t kMaximumIccBytes = uint64_t{16} << 20U;
constexpr uint64_t kMaximumBandBytes = uint64_t{256} << 20U;
constexpr size_t kInputChunkBytes = size_t{64} * 1024;
constexpr int32_t kBandRows = 64;

enum Tag : uint16_t {
  kImageWidth = 256,
  kImageLength = 257,
  kBitsPerSample = 258,
  kCompression = 259,
  kPhotometric = 262,
  kStripOffsets = 273,
  kOrientation = 274,
  kSamplesPerPixel = 277,
  kRowsPerStrip = 278,
  kStripByteCounts = 279,
  kXResolution = 282,
  kPlanarConfiguration = 284,
  kResolutionUnit = 296,
  kPredictor = 317,
  kColorMap = 320,
  kTileWidth = 322,
  kTileLength = 323,
  kTileOffsets = 324,
  kTileByteCounts = 325,
  kInkSet = 332,
  kExtraSamples = 338,
  kSampleFormat = 339,
  kIccProfile = 34675,
};

enum Compression : uint32_t {
  kNone = 1,
  kLzw = 5,
  kDeflate = 8,
  kDeflateOld = 32946,
  kPackBits = 32773,
};

enum class ColorMode { Gray, GrayInverted, Palette, Rgb, Cmyk };

class ByteInput {
 public:
  ByteInput(InputSource& source, uint64_t begin, uint64_t end)
      : source_(source), position_(begin), end_(std::min(end, source.size())) {}

  bool next(uint8_t& byte) {
    if (index_ == buffer_.size() && !refill()) {
      return false;
    }
    byte = buffer_[index_++];
    return true;
  }

  bool chunk(const uint8_t*& data, size_t& size) {
    if (index_ == buffer_.size() && !refill()) {
      return false;
    }
    data = buffer_.data() + index_;
    size = buffer_.size() - index_;
    index_ = buffer_.size();
    return true;
  }

 private:
  bool refill() {
    if (position_ >= end_) {
      return false;
    }
    const auto count = static_cast<size_t>(std::min<uint64_t>(end_ - position_, kInputChunkBytes));
    if (!source_.readBytes(position_, count, buffer_)) {
      return false;
    }
    position_ += count;
    index_ = 0;
    return true;
  }

  InputSource& source_;
  uint64_t position_;
  uint64_t end_;
  std::vector<uint8_t> buffer_;
  size_t index_ = 0;
};

class Decoder {
 public:
  virtual ~Decoder() = default;
  virtual bool read(uint8_t* destination, size_t count) = 0;
};

class RawDecoder final : public Decoder {
 public:
  RawDecoder(InputSource& source, uint64_t begin, uint64_t end)
      : source_(source), position_(begin), end_(end) {}
  bool read(uint8_t* destination, size_t count) override {
    if (count > end_ - std::min(end_, position_) || !source_.read(position_, destination, count)) {
      return false;
    }
    position_ += count;
    return true;
  }

 private:
  InputSource& source_;
  uint64_t position_;
  uint64_t end_;
};

class PackBitsDecoder final : public Decoder {
 public:
  PackBitsDecoder(InputSource& source, uint64_t begin, uint64_t end) : input_(source, begin, end) {}
  bool read(uint8_t* destination, size_t count) override {
    while (count > 0) {
      if (literal_ > 0) {
        if (!input_.next(*destination)) {
          return false;
        }
        --literal_;
      } else if (repeat_ > 0) {
        *destination = value_;
        --repeat_;
      } else {
        uint8_t header = 0;
        if (!input_.next(header)) {
          return false;
        }
        if (header < 128) {
          literal_ = header + 1U;
        } else if (header > 128) {
          if (!input_.next(value_)) {
            return false;
          }
          repeat_ = 257U - header;
        }
        continue;
      }
      ++destination;
      --count;
    }
    return true;
  }

 private:
  ByteInput input_;
  uint32_t literal_ = 0;
  uint32_t repeat_ = 0;
  uint8_t value_ = 0;
};

class DeflateDecoder final : public Decoder {
 public:
  DeflateDecoder(InputSource& source, uint64_t begin, uint64_t end) : input_(source, begin, end) {
    std::memset(&stream_, 0, sizeof(stream_));
    ready_ = mz_inflateInit(&stream_) == MZ_OK;
  }
  ~DeflateDecoder() override {
    if (ready_) {
      mz_inflateEnd(&stream_);
    }
  }
  DeflateDecoder(const DeflateDecoder&) = delete;
  DeflateDecoder& operator=(const DeflateDecoder&) = delete;

  bool read(uint8_t* destination, size_t count) override {
    if (!ready_) {
      return false;
    }
    stream_.next_out = destination;
    stream_.avail_out = static_cast<unsigned int>(count);
    while (stream_.avail_out > 0) {
      if (stream_.avail_in == 0 && !ended_) {
        const uint8_t* data = nullptr;
        size_t size = 0;
        if (input_.chunk(data, size)) {
          stream_.next_in = data;
          stream_.avail_in = static_cast<unsigned int>(size);
        } else {
          ended_ = true;
        }
      }
      const unsigned int before = stream_.avail_out;
      const int status = mz_inflate(&stream_, MZ_NO_FLUSH);
      if (status == MZ_STREAM_END) {
        return stream_.avail_out == 0;
      }
      if (status != MZ_OK && status != MZ_BUF_ERROR) {
        return false;
      }
      if (stream_.avail_out == before && stream_.avail_in == 0 && ended_) {
        return false;
      }
    }
    return true;
  }

 private:
  ByteInput input_;
  mz_stream stream_{};
  bool ready_ = false;
  bool ended_ = false;
};

class LzwDecoder final : public Decoder {
 public:
  LzwDecoder(InputSource& source, uint64_t begin, uint64_t end) : input_(source, begin, end) {
    for (uint32_t code = 0; code < 256; ++code) {
      prefix_[code] = 0xFFFF;
      suffix_[code] = static_cast<uint8_t>(code);
      first_[code] = static_cast<uint8_t>(code);
      length_[code] = 1;
    }
  }

  bool read(uint8_t* destination, size_t count) override {
    while (count > 0) {
      if (pendingIndex_ < pending_.size()) {
        const size_t take = std::min(count, pending_.size() - pendingIndex_);
        std::memcpy(destination, pending_.data() + pendingIndex_, take);
        pendingIndex_ += take;
        destination += take;
        count -= take;
        continue;
      }
      if (ended_ || !decodeNext()) {
        return false;
      }
    }
    return true;
  }

 private:
  static constexpr uint32_t kClear = 256;
  static constexpr uint32_t kEnd = 257;
  static constexpr uint32_t kFirst = 258;
  static constexpr uint32_t kTableSize = 4096;

  bool readCode(uint32_t& code) {
    while (bitCount_ < width_) {
      uint8_t byte = 0;
      if (!input_.next(byte)) {
        return false;
      }
      bits_ = (bits_ << 8U) | byte;
      bitCount_ += 8;
    }
    bitCount_ -= width_;
    code = (bits_ >> bitCount_) & ((1U << width_) - 1U);
    return true;
  }

  void emit(uint32_t code) {
    pending_.resize(length_[code]);
    pendingIndex_ = 0;
    for (size_t index = pending_.size(); index > 0; --index) {
      pending_[index - 1] = suffix_[code];
      code = prefix_[code];
    }
  }

  void add(uint32_t prefix, uint8_t suffix) {
    if (next_ >= kTableSize) {
      return;
    }
    prefix_[next_] = static_cast<uint16_t>(prefix);
    suffix_[next_] = suffix;
    first_[next_] = first_[prefix];
    length_[next_] = static_cast<uint16_t>(length_[prefix] + 1U);
    ++next_;
    if (next_ + 1 >= (1U << width_) && width_ < 12) {
      ++width_;
    }
  }

  bool decodeNext() {
    uint32_t code = 0;
    if (!readCode(code)) {
      return false;
    }
    if (code == kEnd) {
      ended_ = true;
      return false;
    }
    if (code == kClear) {
      next_ = kFirst;
      width_ = 9;
      previous_.reset();
      pending_.clear();
      pendingIndex_ = 0;
      return true;
    }
    if (!previous_) {
      if (code >= 256) {
        return false;
      }
      emit(code);
    } else if (code < next_) {
      emit(code);
      add(*previous_, first_[code]);
    } else if (code == next_) {
      add(*previous_, first_[*previous_]);
      emit(code);
    } else {
      return false;
    }
    previous_ = code;
    return true;
  }

  ByteInput input_;
  std::array<uint16_t, kTableSize> prefix_{};
  std::array<uint8_t, kTableSize> suffix_{};
  std::array<uint8_t, kTableSize> first_{};
  std::array<uint16_t, kTableSize> length_{};
  uint32_t next_ = kFirst;
  uint32_t width_ = 9;
  uint32_t bits_ = 0;
  uint32_t bitCount_ = 0;
  std::optional<uint32_t> previous_;
  std::vector<uint8_t> pending_;
  size_t pendingIndex_ = 0;
  bool ended_ = false;
};

struct Chunk {
  uint64_t offset = 0;
  uint64_t size = 0;
};

struct PlaneFormat {
  int32_t width = 0;
  int32_t samples = 1;
  int32_t bits = 8;
  bool littleEndian = false;
  uint32_t compression = kNone;
  bool predictor = false;

  [[nodiscard]] size_t rowBytes(int32_t pixels) const {
    return (static_cast<size_t>(pixels) * static_cast<size_t>(samples) * static_cast<size_t>(bits) +
            7) /
           8;
  }
};

std::unique_ptr<Decoder> openDecoder(InputSource& source, uint32_t compression,
                                     const Chunk& chunk) {
  const uint64_t end = chunk.offset + chunk.size;
  switch (compression) {
    case kNone:
      return std::make_unique<RawDecoder>(source, chunk.offset, end);
    case kLzw:
      return std::make_unique<LzwDecoder>(source, chunk.offset, end);
    case kDeflate:
    case kDeflateOld:
      return std::make_unique<DeflateDecoder>(source, chunk.offset, end);
    case kPackBits:
      return std::make_unique<PackBitsDecoder>(source, chunk.offset, end);
    default:
      return nullptr;
  }
}

void undoPredictor(uint8_t* row, int32_t pixels, const PlaneFormat& format) {
  const auto stride = static_cast<size_t>(format.samples);
  const auto count = static_cast<size_t>(pixels) * stride;
  if (format.bits == 8) {
    for (size_t index = stride; index < count; ++index) {
      row[index] = static_cast<uint8_t>(row[index] + row[index - stride]);
    }
    return;
  }
  const auto load = [&format, row](size_t index) {
    return format.littleEndian ? static_cast<uint16_t>(row[index * 2] | (row[index * 2 + 1] << 8U))
                               : static_cast<uint16_t>((row[index * 2] << 8U) | row[index * 2 + 1]);
  };
  for (size_t index = stride; index < count; ++index) {
    const auto value = static_cast<uint16_t>(load(index) + load(index - stride));
    if (format.littleEndian) {
      row[index * 2] = static_cast<uint8_t>(value & 0xFFU);
      row[index * 2 + 1] = static_cast<uint8_t>(value >> 8U);
    } else {
      row[index * 2] = static_cast<uint8_t>(value >> 8U);
      row[index * 2 + 1] = static_cast<uint8_t>(value & 0xFFU);
    }
  }
}

class PlaneRows {
 public:
  virtual ~PlaneRows() = default;
  virtual bool next(uint8_t* row) = 0;
};

class StripRows final : public PlaneRows {
 public:
  StripRows(InputSource& source, PlaneFormat format, std::vector<Chunk> strips,
            int32_t rowsPerStrip, int32_t height)
      : source_(source),
        format_(format),
        strips_(std::move(strips)),
        rowsPerStrip_(rowsPerStrip),
        height_(height) {}

  bool next(uint8_t* row) override {
    if (rowsLeft_ == 0) {
      if (strip_ >= strips_.size()) {
        return false;
      }
      decoder_ = openDecoder(source_, format_.compression, strips_[strip_++]);
      rowsLeft_ = std::min(rowsPerStrip_, height_ - rowsDone_);
    }
    if (!decoder_ || !decoder_->read(row, format_.rowBytes(format_.width))) {
      return false;
    }
    if (format_.predictor) {
      undoPredictor(row, format_.width, format_);
    }
    --rowsLeft_;
    ++rowsDone_;
    return true;
  }

 private:
  InputSource& source_;
  PlaneFormat format_;
  std::vector<Chunk> strips_;
  int32_t rowsPerStrip_;
  int32_t height_;
  size_t strip_ = 0;
  int32_t rowsLeft_ = 0;
  int32_t rowsDone_ = 0;
  std::unique_ptr<Decoder> decoder_;
};

class TileRows final : public PlaneRows {
 public:
  TileRows(InputSource& source, PlaneFormat format, std::vector<Chunk> tiles, int32_t tileWidth,
           int32_t tileLength, int32_t height)
      : source_(source),
        format_(format),
        tiles_(std::move(tiles)),
        tileWidth_(tileWidth),
        tileLength_(tileLength),
        height_(height),
        across_((format.width + tileWidth - 1) / tileWidth),
        rowBytes_(format.rowBytes(format.width)),
        band_(rowBytes_ * static_cast<size_t>(tileLength)) {}

  bool next(uint8_t* row) override {
    if (rowInBand_ == tileLength_ || bandRow_ < 0) {
      if (!loadBand(bandRow_ + 1)) {
        return false;
      }
    }
    std::memcpy(row, band_.data() + static_cast<size_t>(rowInBand_) * rowBytes_, rowBytes_);
    ++rowInBand_;
    return true;
  }

 private:
  bool loadBand(int32_t tileRow) {
    if (tileRow * tileLength_ >= height_) {
      return false;
    }
    PlaneFormat tileFormat = format_;
    tileFormat.width = tileWidth_;
    const size_t tileRowBytes = tileFormat.rowBytes(tileWidth_);
    std::vector<uint8_t> line(tileRowBytes);
    for (int32_t column = 0; column < across_; ++column) {
      const size_t index =
          static_cast<size_t>(tileRow) * static_cast<size_t>(across_) + static_cast<size_t>(column);
      auto decoder = openDecoder(source_, format_.compression, tiles_.at(index));
      const size_t offset = tileFormat.rowBytes(column * tileWidth_);
      const size_t copy = std::min(tileRowBytes, rowBytes_ - offset);
      for (int32_t row = 0; row < tileLength_; ++row) {
        if (!decoder || !decoder->read(line.data(), tileRowBytes)) {
          return false;
        }
        if (format_.predictor) {
          undoPredictor(line.data(), tileWidth_, tileFormat);
        }
        std::memcpy(band_.data() + static_cast<size_t>(row) * rowBytes_ + offset, line.data(),
                    copy);
      }
    }
    bandRow_ = tileRow;
    rowInBand_ = 0;
    return true;
  }

  InputSource& source_;
  PlaneFormat format_;
  std::vector<Chunk> tiles_;
  int32_t tileWidth_;
  int32_t tileLength_;
  int32_t height_;
  int32_t across_;
  size_t rowBytes_;
  std::vector<uint8_t> band_;
  int32_t bandRow_ = -1;
  int32_t rowInBand_ = 0;
};

uint32_t rawSample(const uint8_t* row, size_t index, const PlaneFormat& format) {
  switch (format.bits) {
    case 1:
      return (row[index / 8] >> (7U - index % 8U)) & 1U;
    case 4:
      return (row[index / 2] >> (index % 2 == 0 ? 4U : 0U)) & 0xFU;
    case 8:
      return row[index];
    default:
      return format.littleEndian ? (row[index * 2] | (row[index * 2 + 1] << 8U))
                                 : ((row[index * 2] << 8U) | row[index * 2 + 1]);
  }
}

uint8_t toByte(uint32_t value, int32_t bits) {
  switch (bits) {
    case 1:
      return value != 0 ? 255 : 0;
    case 4:
      return static_cast<uint8_t>(value * 17U);
    case 8:
      return static_cast<uint8_t>(value);
    default:
      return static_cast<uint8_t>((value * 255U + 32767U) / 65535U);
  }
}

struct Entry {
  uint16_t type = 0;
  uint32_t count = 0;
  uint64_t valueOffset = 0;
};

uint64_t typeSize(uint16_t type) {
  switch (type) {
    case 1:
    case 2:
    case 6:
    case 7:
      return 1;
    case 3:
    case 8:
      return 2;
    case 4:
    case 9:
      return 4;
    case 5:
    case 10:
      return 8;
    default:
      return 0;
  }
}

class TiffImporter {
 public:
  TiffImporter(InputSource& source, std::string folder, const ImportLimits& limits)
      : source_(source), folder_(std::move(folder)), limits_(limits) {}

  ImportResult run();

 private:
  bool fail(const std::string& code, const std::string& detail = {}) {
    result_.ok = false;
    result_.errorCode = code;
    result_.errorDetail = detail;
    return false;
  }
  void warn(const std::string& code) {
    for (const ImportWarning& warning : result_.document.warnings) {
      if (warning.code == code) {
        return;
      }
    }
    result_.document.warnings.push_back({code, ""});
  }

  bool u16At(uint64_t offset, uint32_t& value) {
    uint8_t bytes[2];
    if (!source_.read(offset, bytes, 2)) {
      return false;
    }
    value = little_ ? (bytes[0] | (bytes[1] << 8U)) : ((bytes[0] << 8U) | bytes[1]);
    return true;
  }
  bool u32At(uint64_t offset, uint32_t& value) {
    uint8_t bytes[4];
    if (!source_.read(offset, bytes, 4)) {
      return false;
    }
    value = little_ ? (bytes[0] | (bytes[1] << 8U) | (bytes[2] << 16U) |
                       (static_cast<uint32_t>(bytes[3]) << 24U))
                    : ((static_cast<uint32_t>(bytes[0]) << 24U) | (bytes[1] << 16U) |
                       (bytes[2] << 8U) | bytes[3]);
    return true;
  }

  bool readDirectory(uint32_t offset);
  std::optional<std::vector<uint64_t>> values(uint16_t tag);
  std::optional<uint64_t> value(uint16_t tag) {
    const auto all = values(tag);
    return all && !all->empty() ? std::optional<uint64_t>(all->front()) : std::nullopt;
  }
  std::optional<double> rational(uint16_t tag);
  bool readProfile(bool cmyk);
  bool convert();

  InputSource& source_;
  std::string folder_;
  ImportLimits limits_;
  ImportResult result_;
  std::vector<uint8_t> embeddedCmykProfile_;
  std::unique_ptr<color::CmykImageTransform> cmyk_;
  bool little_ = false;
  std::map<uint16_t, Entry> entries_;
};

bool TiffImporter::readDirectory(uint32_t offset) {
  uint32_t count = 0;
  if (!u16At(offset, count) || count == 0 || count > kMaximumEntries) {
    return fail("corrupt", "directory");
  }
  for (uint32_t index = 0; index < count; ++index) {
    const uint64_t at = offset + 2 + static_cast<uint64_t>(index) * 12;
    uint32_t tag = 0;
    uint32_t type = 0;
    uint32_t valueCount = 0;
    if (!u16At(at, tag) || !u16At(at + 2, type) || !u32At(at + 4, valueCount)) {
      return fail("corrupt", "directory entry");
    }
    Entry entry;
    entry.type = static_cast<uint16_t>(type);
    entry.count = valueCount;
    const uint64_t size = typeSize(entry.type) * valueCount;
    if (size <= 4) {
      entry.valueOffset = at + 8;
    } else {
      uint32_t pointer = 0;
      if (!u32At(at + 8, pointer)) {
        return fail("corrupt", "directory entry");
      }
      entry.valueOffset = pointer;
    }
    entries_[static_cast<uint16_t>(tag)] = entry;
  }
  uint32_t nextDirectory = 0;
  if (u32At(offset + 2 + static_cast<uint64_t>(count) * 12, nextDirectory) && nextDirectory != 0) {
    warn("extraImagesIgnored");
  }
  return true;
}

std::optional<std::vector<uint64_t>> TiffImporter::values(uint16_t tag) {
  const auto found = entries_.find(tag);
  if (found == entries_.end()) {
    return std::nullopt;
  }
  const Entry& entry = found->second;
  const uint64_t size = typeSize(entry.type);
  if ((entry.type != 1 && entry.type != 3 && entry.type != 4) ||
      entry.count > kMaximumArrayValues || entry.valueOffset > source_.size() ||
      size * entry.count > source_.size() - entry.valueOffset) {
    return std::nullopt;
  }
  std::vector<uint8_t> bytes;
  if (!source_.readBytes(entry.valueOffset, static_cast<size_t>(size * entry.count), bytes)) {
    return std::nullopt;
  }
  std::vector<uint64_t> result(entry.count);
  for (size_t index = 0; index < result.size(); ++index) {
    const uint8_t* at = bytes.data() + index * size;
    uint64_t number = 0;
    for (uint64_t byte = 0; byte < size; ++byte) {
      const uint64_t shift = little_ ? 8 * byte : 8 * (size - 1 - byte);
      number |= static_cast<uint64_t>(at[byte]) << shift;
    }
    result[index] = number;
  }
  return result;
}

std::optional<double> TiffImporter::rational(uint16_t tag) {
  const auto found = entries_.find(tag);
  if (found == entries_.end() || found->second.type != 5 || found->second.count < 1) {
    return std::nullopt;
  }
  uint32_t numerator = 0;
  uint32_t denominator = 0;
  if (!u32At(found->second.valueOffset, numerator) ||
      !u32At(found->second.valueOffset + 4, denominator) || denominator == 0) {
    return std::nullopt;
  }
  return static_cast<double>(numerator) / denominator;
}

bool TiffImporter::readProfile(bool cmyk) {
  const auto found = entries_.find(kIccProfile);
  if (found == entries_.end()) {
    return true;
  }
  const Entry& entry = found->second;
  std::vector<uint8_t> profile;
  if (entry.count <= kMaximumIccBytes &&
      source_.readBytes(entry.valueOffset, entry.count, profile)) {
    if (cmyk) {
      embeddedCmykProfile_ = std::move(profile);
    } else if (!adoptEmbeddedProfile(result_.document, profile)) {
      warn("colorProfileIgnored");
    }
  }
  return true;
}

bool TiffImporter::convert() {
  const auto width = value(kImageWidth);
  const auto height = value(kImageLength);
  if (!width || !height || *width == 0 || *height == 0) {
    return fail("corrupt", "image size");
  }
  if (*width > static_cast<uint64_t>(limits_.maximumSide) ||
      *height > static_cast<uint64_t>(limits_.maximumSide)) {
    return fail("canvasTooLarge", std::to_string(*width) + "x" + std::to_string(*height));
  }
  const auto imageWidth = static_cast<int32_t>(*width);
  const auto imageHeight = static_cast<int32_t>(*height);
  const auto samplesPerPixel = static_cast<int32_t>(value(kSamplesPerPixel).value_or(1));
  if (samplesPerPixel < 1 || samplesPerPixel > 16) {
    return fail("corrupt", "samples per pixel");
  }
  std::vector<uint64_t> bitsPerSample = values(kBitsPerSample).value_or(std::vector<uint64_t>());
  if (bitsPerSample.empty()) {
    bitsPerSample = {1};
  }
  const uint64_t bits = bitsPerSample.front();
  if (std::any_of(bitsPerSample.begin(), bitsPerSample.end(),
                  [bits](uint64_t other) { return other != bits; }) ||
      (bits != 1 && bits != 4 && bits != 8 && bits != 16)) {
    return fail("unsupportedDepth", std::to_string(bits));
  }
  if (const auto formats = values(kSampleFormat)) {
    if (std::any_of(formats->begin(), formats->end(),
                    [](uint64_t format) { return format != 1; })) {
      return fail("unsupportedDepth", "sample format");
    }
  }
  const uint64_t compression = value(kCompression).value_or(kNone);
  if (compression != kNone && compression != kLzw && compression != kDeflate &&
      compression != kDeflateOld && compression != kPackBits) {
    return fail("unsupportedCompression", std::to_string(compression));
  }
  const uint64_t predictor = value(kPredictor).value_or(1);
  if (predictor != 1 && (predictor != 2 || bits < 8)) {
    return fail("unsupportedCompression", "predictor " + std::to_string(predictor));
  }

  const uint64_t photometric = value(kPhotometric).value_or(samplesPerPixel >= 3 ? 2 : 1);
  ColorMode mode = ColorMode::Gray;
  int32_t colorSamples = 1;
  switch (photometric) {
    case 0:
      mode = ColorMode::GrayInverted;
      break;
    case 1:
      mode = ColorMode::Gray;
      break;
    case 2:
      mode = ColorMode::Rgb;
      colorSamples = 3;
      break;
    case 3:
      mode = ColorMode::Palette;
      break;
    case 5:
      if (value(kInkSet).value_or(1) != 1) {
        return fail("unsupportedColorMode", "ink set");
      }
      mode = ColorMode::Cmyk;
      colorSamples = 4;
      break;
    default:
      return fail("unsupportedColorMode", std::to_string(photometric));
  }
  if (samplesPerPixel < colorSamples) {
    return fail("corrupt", "samples per pixel");
  }
  if (bits < 8 &&
      (samplesPerPixel != 1 || (mode != ColorMode::Gray && mode != ColorMode::GrayInverted &&
                                mode != ColorMode::Palette))) {
    return fail("unsupportedDepth", std::to_string(bits));
  }
  if (mode == ColorMode::Palette && bits == 16) {
    return fail("unsupportedDepth", "16-bit palette");
  }
  std::vector<uint64_t> colorMap;
  if (mode == ColorMode::Palette) {
    colorMap = values(kColorMap).value_or(std::vector<uint64_t>());
    if (colorMap.size() < 3 * (size_t{1} << bits)) {
      return fail("corrupt", "color map");
    }
  }
  std::optional<int32_t> alphaSample;
  bool associatedAlpha = false;
  if (const auto extra = values(kExtraSamples); extra && !extra->empty() &&
                                                samplesPerPixel > colorSamples &&
                                                (extra->front() == 1 || extra->front() == 2)) {
    alphaSample = colorSamples;
    associatedAlpha = extra->front() == 1;
  }
  if (value(kOrientation).value_or(1) != 1) {
    warn("orientationIgnored");
  }
  readProfile(mode == ColorMode::Cmyk);
  if (mode == ColorMode::Cmyk) {
    cmyk_ = adoptCmykCanvas(result_.document, embeddedCmykProfile_, limits_);
    if (!cmyk_) {
      warn("cmykConverted");
    }
  }
  if (const auto resolution = rational(kXResolution)) {
    const uint64_t unit = value(kResolutionUnit).value_or(2);
    const double dpi = unit == 3 ? *resolution * 2.54 : *resolution;
    if (unit != 1 && dpi >= 1 && dpi <= 100000) {
      result_.document.dpi = dpi;
    }
  }

  const bool planar = value(kPlanarConfiguration).value_or(1) == 2 && samplesPerPixel > 1;
  const int32_t planeCount = planar ? samplesPerPixel : 1;
  PlaneFormat format;
  format.width = imageWidth;
  format.samples = planar ? 1 : samplesPerPixel;
  format.bits = static_cast<int32_t>(bits);
  format.littleEndian = little_;
  format.compression = static_cast<uint32_t>(compression);
  format.predictor = predictor == 2;

  const bool tiled = entries_.count(kTileOffsets) > 0;
  const auto offsets = values(tiled ? kTileOffsets : kStripOffsets);
  auto sizes = values(tiled ? kTileByteCounts : kStripByteCounts);
  if (!offsets) {
    return fail("corrupt", "data offsets");
  }
  int32_t rowsPerStrip = imageHeight;
  int32_t tileWidth = 0;
  int32_t tileLength = 0;
  size_t chunksPerPlane = 0;
  if (tiled) {
    const uint64_t tileWidthValue = value(kTileWidth).value_or(0);
    const uint64_t tileLengthValue = value(kTileLength).value_or(0);
    if (tileWidthValue == 0 || tileLengthValue == 0 || tileWidthValue > 65536 ||
        tileLengthValue > 65536 || (bits < 8 && tileWidthValue % 8 != 0)) {
      return fail("corrupt", "tile size");
    }
    tileWidth = static_cast<int32_t>(tileWidthValue);
    tileLength = static_cast<int32_t>(tileLengthValue);
    if (format.rowBytes(imageWidth + tileWidth) * static_cast<uint64_t>(tileLength) >
        kMaximumBandBytes) {
      return fail("corrupt", "tile size");
    }
    chunksPerPlane = static_cast<size_t>((imageWidth + tileWidth - 1) / tileWidth) *
                     static_cast<size_t>((imageHeight + tileLength - 1) / tileLength);
  } else {
    const uint64_t rows = value(kRowsPerStrip).value_or(imageHeight);
    rowsPerStrip = static_cast<int32_t>(std::clamp<uint64_t>(rows, 1, imageHeight));
    chunksPerPlane = static_cast<size_t>((imageHeight + rowsPerStrip - 1) / rowsPerStrip);
  }
  const size_t chunkCount = chunksPerPlane * static_cast<size_t>(planeCount);
  if (offsets->size() < chunkCount) {
    return fail("corrupt", "data offsets");
  }
  if (!sizes && compression == kNone && !tiled) {
    sizes = std::vector<uint64_t>(chunkCount, format.rowBytes(imageWidth) * rowsPerStrip);
  }
  if (!sizes || sizes->size() < chunkCount) {
    return fail("corrupt", "data byte counts");
  }

  std::vector<std::unique_ptr<PlaneRows>> planes(static_cast<size_t>(planeCount));
  for (int32_t plane = 0; plane < planeCount; ++plane) {
    if (planar && plane >= colorSamples && (!alphaSample || plane != *alphaSample)) {
      continue;
    }
    std::vector<Chunk> chunks(chunksPerPlane);
    for (size_t index = 0; index < chunksPerPlane; ++index) {
      const size_t at = static_cast<size_t>(plane) * chunksPerPlane + index;
      chunks[index] = {(*offsets)[at], (*sizes)[at]};
    }
    if (tiled) {
      planes[static_cast<size_t>(plane)] = std::make_unique<TileRows>(
          source_, format, std::move(chunks), tileWidth, tileLength, imageHeight);
    } else {
      planes[static_cast<size_t>(plane)] = std::make_unique<StripRows>(
          source_, format, std::move(chunks), rowsPerStrip, imageHeight);
    }
  }

  DocumentImportFolder output(folder_, imageWidth, imageHeight);
  ImportedNode node;
  node.uuid = output.newUuid();
  SurfaceTileWriter writer = output.layerWriter(node.uuid);
  const size_t rowBytes = format.rowBytes(imageWidth);
  std::vector<std::vector<uint8_t>> rows(static_cast<size_t>(planeCount),
                                         std::vector<uint8_t>(rowBytes));
  std::vector<uint8_t> band(static_cast<size_t>(imageWidth) * kBandRows * 4);
  std::vector<uint8_t> rowInks(
      mode == ColorMode::Cmyk && cmyk_ ? static_cast<size_t>(imageWidth) * 4 : 0);
  const auto sampleAt = [&](int32_t x, int32_t sample) -> uint32_t {
    if (planar) {
      return rawSample(rows[static_cast<size_t>(sample)].data(), static_cast<size_t>(x), format);
    }
    return rawSample(
        rows[0].data(),
        static_cast<size_t>(x) * static_cast<size_t>(samplesPerPixel) + static_cast<size_t>(sample),
        format);
  };
  const auto byteAt = [&](int32_t x, int32_t sample) {
    return toByte(sampleAt(x, sample), format.bits);
  };
  for (int32_t bandTop = 0; bandTop < imageHeight; bandTop += kBandRows) {
    const int32_t bandRows = std::min(kBandRows, imageHeight - bandTop);
    for (int32_t row = 0; row < bandRows; ++row) {
      for (size_t plane = 0; plane < planes.size(); ++plane) {
        if (planes[plane] && !planes[plane]->next(rows[plane].data())) {
          return fail("corrupt", "image data");
        }
      }
      uint8_t* rowStart =
          band.data() + static_cast<size_t>(row) * static_cast<size_t>(imageWidth) * 4;
      uint8_t* out = rowStart;
      for (int32_t x = 0; x < imageWidth; ++x, out += 4) {
        switch (mode) {
          case ColorMode::Gray:
            out[0] = out[1] = out[2] = byteAt(x, 0);
            break;
          case ColorMode::GrayInverted:
            out[0] = out[1] = out[2] = static_cast<uint8_t>(255 - byteAt(x, 0));
            break;
          case ColorMode::Palette: {
            const size_t index = sampleAt(x, 0);
            const size_t entries = size_t{1} << bits;
            for (size_t channel = 0; channel < 3; ++channel) {
              out[channel] = toByte(static_cast<uint32_t>(colorMap[channel * entries + index]), 16);
            }
            break;
          }
          case ColorMode::Rgb:
            out[0] = byteAt(x, 0);
            out[1] = byteAt(x, 1);
            out[2] = byteAt(x, 2);
            break;
          case ColorMode::Cmyk: {
            if (cmyk_) {
              for (int32_t channel = 0; channel < 4; ++channel) {
                rowInks[static_cast<size_t>(x) * 4 + static_cast<size_t>(channel)] =
                    byteAt(x, channel);
              }
              break;
            }
            const uint32_t black = 255U - byteAt(x, 3);
            for (int32_t channel = 0; channel < 3; ++channel) {
              const uint32_t paper = 255U - byteAt(x, channel);
              out[channel] = static_cast<uint8_t>((paper * black + 127U) / 255U);
            }
            break;
          }
        }
        out[3] = alphaSample ? byteAt(x, *alphaSample) : 255;
        if (associatedAlpha && out[3] != 0 && out[3] != 255) {
          for (int32_t channel = 0; channel < 3; ++channel) {
            const uint32_t straight = (out[channel] * 255U + out[3] / 2U) / out[3];
            out[channel] = static_cast<uint8_t>(std::min(straight, 255U));
          }
        }
      }
      if (!rowInks.empty()) {
        cmyk_->convert(rowInks.data(), rowStart, static_cast<size_t>(imageWidth));
      }
    }
    if (!writer.writePixels(0, bandTop, imageWidth, bandRows, band.data(),
                            static_cast<size_t>(imageWidth) * 4)) {
      return fail("writeFailed");
    }
  }
  if (!writer.finish()) {
    return fail("writeFailed");
  }
  result_.document.layers.push_back(std::move(node));
  result_.document.width = imageWidth;
  result_.document.height = imageHeight;
  if (!output.writeImportJson(result_.document)) {
    return fail("writeFailed");
  }
  return true;
}

ImportResult TiffImporter::run() {
  uint8_t header[4];
  if (!source_.read(0, header, 4) ||
      !((header[0] == 'I' && header[1] == 'I') || (header[0] == 'M' && header[1] == 'M'))) {
    fail("notTiff");
    return result_;
  }
  little_ = header[0] == 'I';
  uint32_t version = 0;
  uint32_t directory = 0;
  if (!u16At(2, version) || (version != 42 && version != 43)) {
    fail("notTiff");
    return result_;
  }
  if (version == 43) {
    fail("unsupportedVersion", "BigTIFF");
    return result_;
  }
  if (!u32At(4, directory) || !readDirectory(directory)) {
    if (result_.errorCode.empty()) {
      fail("corrupt", "header");
    }
    return result_;
  }
  result_.document.source = "tiff";
  result_.document.backgroundColor = std::array<double, 4>{1, 1, 1, 1};
  result_.document.backgroundHidden = true;
  if (convert()) {
    result_.ok = true;
  }
  return result_;
}

}  // namespace

ImportResult importTiff(InputSource& source, const std::string& folder,
                        const ImportLimits& limits) {
  return TiffImporter(source, folder, limits).run();
}

}  // namespace nanquim::formats
