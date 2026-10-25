#include "formats/png_encoding.h"

#include <cstdlib>

namespace nanquim::formats::png {

namespace {

constexpr size_t kChunkBytes = size_t{256} * 1024;

uint8_t paeth(int32_t left, int32_t up, int32_t upLeft) {
  const int32_t estimate = left + up - upLeft;
  const int32_t toLeft = std::abs(estimate - left);
  const int32_t toUp = std::abs(estimate - up);
  const int32_t toUpLeft = std::abs(estimate - upLeft);
  if (toLeft <= toUp && toLeft <= toUpLeft) {
    return static_cast<uint8_t>(left);
  }
  return static_cast<uint8_t>(toUp <= toUpLeft ? up : upLeft);
}

}  // namespace

void writeChunk(OutputFile& out, const char* type, const uint8_t* data, size_t size) {
  out.u32(static_cast<uint32_t>(size));
  out.bytes(type, 4);
  if (size > 0) {
    out.bytes(data, size);
  }
  mz_ulong crc = mz_crc32(MZ_CRC32_INIT, reinterpret_cast<const uint8_t*>(type), 4);
  if (size > 0) {
    crc = mz_crc32(crc, data, size);
  }
  out.u32(static_cast<uint32_t>(crc));
}

void bigEndian32(std::vector<uint8_t>& bytes, uint32_t value) {
  for (int shift = 24; shift >= 0; shift -= 8) {
    bytes.push_back(static_cast<uint8_t>(value >> static_cast<uint32_t>(shift)));
  }
}

void bigEndian16(std::vector<uint8_t>& bytes, uint32_t value) {
  bytes.push_back(static_cast<uint8_t>(value >> 8U));
  bytes.push_back(static_cast<uint8_t>(value));
}

void filterRow(const uint8_t* row, const uint8_t* previous, size_t size, size_t pixelBytes,
               std::array<std::vector<uint8_t>, 4>& candidates, std::vector<uint8_t>& out) {
  static constexpr std::array<uint8_t, 4> kTypes{0, 1, 2, 4};
  uint64_t bestCost = UINT64_MAX;
  size_t best = 0;
  for (size_t candidate = 0; candidate < kTypes.size(); ++candidate) {
    std::vector<uint8_t>& filtered = candidates[candidate];
    filtered.resize(size);
    uint64_t cost = 0;
    for (size_t index = 0; index < size; ++index) {
      const int32_t left = index >= pixelBytes ? row[index - pixelBytes] : 0;
      const int32_t up = previous[index];
      const int32_t upLeft = index >= pixelBytes ? previous[index - pixelBytes] : 0;
      uint8_t predictor = 0;
      switch (kTypes[candidate]) {
        case 1:
          predictor = static_cast<uint8_t>(left);
          break;
        case 2:
          predictor = static_cast<uint8_t>(up);
          break;
        case 4:
          predictor = paeth(left, up, upLeft);
          break;
        default:
          break;
      }
      const auto value = static_cast<uint8_t>(row[index] - predictor);
      filtered[index] = value;
      cost += static_cast<uint64_t>(std::abs(static_cast<int32_t>(static_cast<int8_t>(value))));
    }
    if (cost < bestCost) {
      bestCost = cost;
      best = candidate;
    }
  }
  out.clear();
  out.push_back(kTypes[best]);
  out.insert(out.end(), candidates[best].begin(), candidates[best].end());
}

ImageDataWriter::ImageDataWriter(OutputFile& out, uint32_t* sequence, int level)
    : out_(out), sequence_(sequence), prefix_(sequence != nullptr ? 4 : 0) {
  buffer_.resize(prefix_ + kChunkBytes);
  ok_ = mz_deflateInit(&stream_, level) == MZ_OK;
  stream_.next_out = buffer_.data() + prefix_;
  stream_.avail_out = static_cast<unsigned int>(kChunkBytes);
}

ImageDataWriter::~ImageDataWriter() { mz_deflateEnd(&stream_); }

void ImageDataWriter::drain(bool all) {
  const size_t filled = kChunkBytes - stream_.avail_out;
  if (filled == 0 || (!all && stream_.avail_out != 0)) {
    return;
  }
  if (sequence_ != nullptr) {
    const uint32_t number = (*sequence_)++;
    for (size_t index = 0; index < 4; ++index) {
      buffer_[index] = static_cast<uint8_t>(number >> (24U - 8U * index));
    }
    writeChunk(out_, "fdAT", buffer_.data(), prefix_ + filled);
  } else {
    writeChunk(out_, "IDAT", buffer_.data(), filled);
  }
  stream_.next_out = buffer_.data() + prefix_;
  stream_.avail_out = static_cast<unsigned int>(kChunkBytes);
}

bool ImageDataWriter::feed(const uint8_t* data, size_t size, int flush) {
  stream_.next_in = data;
  stream_.avail_in = static_cast<unsigned int>(size);
  for (;;) {
    const int status = mz_deflate(&stream_, flush);
    if (status != MZ_OK && status != MZ_STREAM_END && status != MZ_BUF_ERROR) {
      return false;
    }
    drain(false);
    if (flush == MZ_FINISH ? status == MZ_STREAM_END
                           : stream_.avail_in == 0 && stream_.avail_out > 0) {
      return true;
    }
  }
}

bool ImageDataWriter::add(const std::vector<uint8_t>& filteredRow) {
  ok_ = ok_ && feed(filteredRow.data(), filteredRow.size(), MZ_NO_FLUSH);
  return ok_;
}

bool ImageDataWriter::finish() {
  ok_ = ok_ && feed(nullptr, 0, MZ_FINISH);
  drain(true);
  return ok_;
}

}  // namespace nanquim::formats::png
