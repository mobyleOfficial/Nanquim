#include "formats/animation_writer.h"

#include <miniz.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <vector>

#include "formats/color_quantizer.h"
#include "formats/output_file.h"
#include "formats/png_encoding.h"

namespace nanquim::formats {

namespace {

constexpr int32_t kMaximumSide = 65535;
constexpr int kApngLevel = 3;

bool validSize(const AnimationOptions& options) {
  return options.width > 0 && options.height > 0 && options.width <= kMaximumSide &&
         options.height <= kMaximumSide;
}

constexpr size_t kMaximumGifColors = 255;
constexpr int32_t kMaximumLzwCodes = 4096;

constexpr std::array<uint8_t, 64> kBayer{
    0,  32, 8,  40, 2,  34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26, 12, 44, 4,  36, 14, 46,
    6,  38, 60, 28, 52, 20, 62, 30, 54, 22, 3,  35, 11, 43, 1,  33, 9,  41, 51, 19, 59, 27,
    49, 17, 57, 25, 15, 47, 7,  39, 13, 45, 5,  37, 63, 31, 55, 23, 61, 29, 53, 21};
constexpr float kOrderedSpread = 32;

class SubBlockWriter {
 public:
  explicit SubBlockWriter(OutputFile& out) : out_(out) {}
  void byte(uint8_t value) {
    block_[size_++] = value;
    if (size_ == block_.size()) {
      flush();
    }
  }
  void finish() {
    flush();
    out_.u8(0);
  }

 private:
  void flush() {
    if (size_ == 0) {
      return;
    }
    out_.u8(static_cast<uint32_t>(size_));
    out_.bytes(block_.data(), size_);
    size_ = 0;
  }
  OutputFile& out_;
  std::array<uint8_t, 255> block_{};
  size_t size_ = 0;
};

class LzwEncoder {
 public:
  LzwEncoder(SubBlockWriter& output, int32_t minimumCodeSize)
      : output_(output),
        minimumCodeSize_(minimumCodeSize),
        clearCode_(1 << minimumCodeSize),
        endCode_(clearCode_ + 1),
        table_(kTableSize) {
    reset();
    emit(clearCode_);
  }

  void add(uint8_t index) {
    if (prefix_ < 0) {
      prefix_ = index;
      return;
    }
    const int32_t key = (prefix_ << 8) | index;
    size_t slot = hash(key);
    while (table_[slot].key != -1) {
      if (table_[slot].key == key) {
        prefix_ = table_[slot].code;
        return;
      }
      slot = (slot + 1) & (kTableSize - 1);
    }
    emit(prefix_);
    if (nextCode_ < kMaximumLzwCodes) {
      table_[slot] = {key, static_cast<int16_t>(nextCode_)};
      if (nextCode_ == (1 << codeSize_) && codeSize_ < 12) {
        ++codeSize_;
      }
      ++nextCode_;
    } else {
      emit(clearCode_);
      reset();
    }
    prefix_ = index;
  }

  void finish() {
    if (prefix_ >= 0) {
      emit(prefix_);
    }
    emit(endCode_);
    if (bitCount_ > 0) {
      output_.byte(static_cast<uint8_t>(bits_));
    }
    output_.finish();
  }

 private:
  static constexpr size_t kTableSize = 8192;
  struct Entry {
    int32_t key = -1;
    int16_t code = 0;
  };

  static size_t hash(int32_t key) {
    return (static_cast<uint32_t>(key) * 2654435761U >> 19U) & (kTableSize - 1);
  }

  void reset() {
    std::fill(table_.begin(), table_.end(), Entry{});
    codeSize_ = minimumCodeSize_ + 1;
    nextCode_ = endCode_ + 1;
  }

  void emit(int32_t code) {
    bits_ |= static_cast<uint32_t>(code) << static_cast<uint32_t>(bitCount_);
    bitCount_ += codeSize_;
    while (bitCount_ >= 8) {
      output_.byte(static_cast<uint8_t>(bits_));
      bits_ >>= 8U;
      bitCount_ -= 8;
    }
  }

  SubBlockWriter& output_;
  int32_t minimumCodeSize_;
  int32_t clearCode_;
  int32_t endCode_;
  int32_t codeSize_ = 0;
  int32_t nextCode_ = 0;
  int32_t prefix_ = -1;
  uint32_t bits_ = 0;
  int32_t bitCount_ = 0;
  std::vector<Entry> table_;
};

class GifWriter final : public AnimationWriter {
 public:
  GifWriter(std::unique_ptr<OutputFile> out, const AnimationOptions& options)
      : out_(std::move(out)), options_(options) {
    out_->text("GIF89a");
    littleEndian16(static_cast<uint32_t>(options.width));
    littleEndian16(static_cast<uint32_t>(options.height));
    out_->u8(0x70);
    out_->u8(0);
    out_->u8(0);
    if (options.plays != 1) {
      out_->bytes("\x21\xFF\x0B" "NETSCAPE2.0" "\x03\x01", 16);
      littleEndian16(options.plays == 0 ? 0 : static_cast<uint32_t>(std::min(options.plays - 1, 65535)));
      out_->u8(0);
    }
  }

  bool addFrame(const uint8_t* rgba, int32_t durationMilliseconds) override {
    if (!error_.empty()) {
      return false;
    }
    const auto pixelCount = static_cast<size_t>(options_.width) * static_cast<size_t>(options_.height);
    bool transparent = false;
    for (size_t pixel = 0; pixel < pixelCount && !transparent; ++pixel) {
      transparent = rgba[pixel * 4 + 3] < 128;
    }
    bool exact = false;
    std::vector<Rgb8> palette = choosePalette(rgba, pixelCount, kMaximumGifColors, 32768, &exact);
    const Dither dither = exact ? Dither::None : options_.dither;
    if (palette.empty()) {
      palette.push_back({0, 0, 0});
    }
    const auto transparentIndex = static_cast<uint8_t>(palette.size());
    const size_t entries = palette.size() + (transparent ? 1 : 0);
    int32_t tableBits = 1;
    while ((size_t{1} << static_cast<size_t>(tableBits)) < entries) {
      ++tableBits;
    }

    elapsedMilliseconds_ += std::max(durationMilliseconds, 0);
    const int64_t end = (elapsedMilliseconds_ + 5) / 10;
    const auto delay = static_cast<uint32_t>(std::clamp<int64_t>(end - shownCentiseconds_, 0, 65535));
    shownCentiseconds_ = end;
    out_->u8(0x21);
    out_->u8(0xF9);
    out_->u8(4);
    out_->u8((transparent ? 2U : 1U) << 2U | (transparent ? 1U : 0U));
    littleEndian16(delay);
    out_->u8(transparent ? transparentIndex : 0);
    out_->u8(0);

    out_->u8(0x2C);
    littleEndian16(0);
    littleEndian16(0);
    littleEndian16(static_cast<uint32_t>(options_.width));
    littleEndian16(static_cast<uint32_t>(options_.height));
    out_->u8(0x80U | static_cast<uint32_t>(tableBits - 1));
    for (size_t index = 0; index < (size_t{1} << static_cast<size_t>(tableBits)); ++index) {
      const Rgb8 color = index < palette.size() ? palette[index] : Rgb8{0, 0, 0};
      out_->bytes(color.data(), 3);
    }

    const int32_t minimumCodeSize = std::max(2, tableBits);
    out_->u8(static_cast<uint32_t>(minimumCodeSize));
    SubBlockWriter blocks(*out_);
    LzwEncoder lzw(blocks, minimumCodeSize);
    PaletteMapper mapper(palette);
    const auto width = static_cast<size_t>(options_.width);
    std::vector<float> errors;
    std::vector<float> nextErrors;
    if (dither == Dither::FloydSteinberg) {
      errors.assign((width + 2) * 3, 0);
      nextErrors.assign((width + 2) * 3, 0);
    }
    for (int32_t y = 0; y < options_.height; ++y) {
      const uint8_t* row = rgba + static_cast<size_t>(y) * width * 4;
      for (size_t x = 0; x < width; ++x) {
        const uint8_t* pixel = row + x * 4;
        if (pixel[3] < 128) {
          lzw.add(transparentIndex);
          continue;
        }
        std::array<float, 3> wanted{static_cast<float>(pixel[0]), static_cast<float>(pixel[1]),
                                    static_cast<float>(pixel[2])};
        if (dither == Dither::Ordered) {
          const float offset =
              (static_cast<float>(kBayer[static_cast<size_t>((y & 7) * 8) + (x & 7)]) + 0.5F) / 64.0F - 0.5F;
          for (float& channel : wanted) {
            channel += offset * kOrderedSpread;
          }
        } else if (dither == Dither::FloydSteinberg) {
          for (size_t channel = 0; channel < 3; ++channel) {
            wanted[channel] += errors[(x + 1) * 3 + channel];
          }
        }
        const uint8_t index = mapper.nearest(static_cast<int32_t>(std::lround(wanted[0])),
                                             static_cast<int32_t>(std::lround(wanted[1])),
                                             static_cast<int32_t>(std::lround(wanted[2])));
        lzw.add(index);
        if (dither == Dither::FloydSteinberg) {
          const Rgb8& chosen = palette[index];
          for (size_t channel = 0; channel < 3; ++channel) {
            const float error = std::clamp(wanted[channel], 0.0F, 255.0F) - static_cast<float>(chosen[channel]);
            errors[(x + 2) * 3 + channel] += error * 7.0F / 16.0F;
            nextErrors[x * 3 + channel] += error * 3.0F / 16.0F;
            nextErrors[(x + 1) * 3 + channel] += error * 5.0F / 16.0F;
            nextErrors[(x + 2) * 3 + channel] += error * 1.0F / 16.0F;
          }
        }
      }
      if (dither == Dither::FloydSteinberg) {
        errors.swap(nextErrors);
        std::fill(nextErrors.begin(), nextErrors.end(), 0.0F);
      }
    }
    lzw.finish();
    ++frames_;
    if (!out_->ok()) {
      error_ = "writeFailed";
    }
    return error_.empty();
  }

  bool finish() override {
    if (error_.empty() && frames_ == 0) {
      error_ = "noFrames";
    }
    out_->u8(0x3B);
    if (!out_->close() && error_.empty()) {
      error_ = "writeFailed";
    }
    return error_.empty();
  }

  [[nodiscard]] const std::string& error() const override { return error_; }

 private:
  void littleEndian16(uint32_t value) {
    out_->u8(value & 0xFFU);
    out_->u8(value >> 8U);
  }

  std::unique_ptr<OutputFile> out_;
  AnimationOptions options_;
  int64_t elapsedMilliseconds_ = 0;
  int64_t shownCentiseconds_ = 0;
  int32_t frames_ = 0;
  std::string error_;
};

class ApngWriter final : public AnimationWriter {
 public:
  ApngWriter(std::unique_ptr<OutputFile> out, const AnimationOptions& options)
      : out_(std::move(out)), options_(options) {
    out_->bytes(png::kSignature.data(), png::kSignature.size());
    std::vector<uint8_t> header;
    png::bigEndian32(header, static_cast<uint32_t>(options.width));
    png::bigEndian32(header, static_cast<uint32_t>(options.height));
    header.insert(header.end(), {8, 6, 0, 0, 0});
    png::writeChunk(*out_, "IHDR", header);
    animationControlOffset_ = out_->position();
    std::vector<uint8_t> control;
    png::bigEndian32(control, 0);
    png::bigEndian32(control, static_cast<uint32_t>(std::max(options.plays, 0)));
    png::writeChunk(*out_, "acTL", control);
    static constexpr uint8_t kPerceptual = 0;
    png::writeChunk(*out_, "sRGB", &kPerceptual, 1);
  }

  bool addFrame(const uint8_t* rgba, int32_t durationMilliseconds) override {
    if (!error_.empty()) {
      return false;
    }
    std::vector<uint8_t> control;
    png::bigEndian32(control, sequence_++);
    png::bigEndian32(control, static_cast<uint32_t>(options_.width));
    png::bigEndian32(control, static_cast<uint32_t>(options_.height));
    png::bigEndian32(control, 0);
    png::bigEndian32(control, 0);
    png::bigEndian16(control, static_cast<uint32_t>(std::clamp(durationMilliseconds, 0, 65535)));
    png::bigEndian16(control, 1000);
    control.push_back(0);
    control.push_back(0);
    png::writeChunk(*out_, "fcTL", control);

    png::ImageDataWriter data(*out_, frames_ == 0 ? nullptr : &sequence_, kApngLevel);
    const size_t rowBytes = static_cast<size_t>(options_.width) * 4;
    std::vector<uint8_t> previous(rowBytes, 0);
    std::array<std::vector<uint8_t>, 4> candidates;
    std::vector<uint8_t> filtered;
    bool ok = data.ok();
    for (int32_t y = 0; ok && y < options_.height; ++y) {
      const uint8_t* row = rgba + static_cast<size_t>(y) * rowBytes;
      png::filterRow(row, previous.data(), rowBytes, 4, candidates, filtered);
      ok = data.add(filtered);
      std::memcpy(previous.data(), row, rowBytes);
    }
    ok = ok && data.finish();
    ++frames_;
    if (!ok || !out_->ok()) {
      error_ = "writeFailed";
    }
    return error_.empty();
  }

  bool finish() override {
    if (error_.empty() && frames_ == 0) {
      error_ = "noFrames";
    }
    png::writeChunk(*out_, "IEND", nullptr, 0);
    std::array<uint8_t, 12> typeAndData{'a', 'c', 'T', 'L'};
    for (size_t index = 0; index < 4; ++index) {
      typeAndData[4 + index] = static_cast<uint8_t>(static_cast<uint32_t>(frames_) >> (24U - 8U * index));
      typeAndData[8 + index] =
          static_cast<uint8_t>(static_cast<uint32_t>(std::max(options_.plays, 0)) >> (24U - 8U * index));
    }
    out_->patch32(animationControlOffset_ + 8, static_cast<uint32_t>(frames_));
    out_->patch32(animationControlOffset_ + 16,
                  static_cast<uint32_t>(mz_crc32(MZ_CRC32_INIT, typeAndData.data(), typeAndData.size())));
    if (!out_->close() && error_.empty()) {
      error_ = "writeFailed";
    }
    return error_.empty();
  }

  [[nodiscard]] const std::string& error() const override { return error_; }

 private:
  std::unique_ptr<OutputFile> out_;
  AnimationOptions options_;
  uint64_t animationControlOffset_ = 0;
  uint32_t sequence_ = 0;
  int32_t frames_ = 0;
  std::string error_;
};

}  // namespace

std::unique_ptr<AnimationWriter> createGifWriter(const std::string& path,
                                                 const AnimationOptions& options) {
  if (!validSize(options)) {
    return nullptr;
  }
  auto out = OutputFile::create(path);
  return out ? std::make_unique<GifWriter>(std::move(out), options) : nullptr;
}

std::unique_ptr<AnimationWriter> createApngWriter(const std::string& path,
                                                  const AnimationOptions& options) {
  if (!validSize(options)) {
    return nullptr;
  }
  auto out = OutputFile::create(path);
  return out ? std::make_unique<ApngWriter>(std::move(out), options) : nullptr;
}

}  // namespace nanquim::formats
