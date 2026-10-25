#include "formats/source_reader.h"

#include <algorithm>
#include <cstring>

namespace nanquim::formats {

namespace {

constexpr size_t kBufferBytes = size_t{64} * 1024;

}  // namespace

SourceReader::SourceReader(InputSource& source, uint64_t begin, uint64_t end)
    : source_(source), position_(begin), end_(std::min(end, source.size())) {
  if (begin > end_) {
    position_ = end_;
    ok_ = false;
  }
}

void SourceReader::seek(uint64_t position) {
  if (position > end_) {
    fail();
    return;
  }
  position_ = position;
}

void SourceReader::skip(uint64_t count) {
  if (count > remaining()) {
    fail();
    return;
  }
  position_ += count;
}

bool SourceReader::fill(size_t count) {
  if (count > remaining()) {
    fail();
    return false;
  }
  if (position_ >= bufferStart_ && position_ + count <= bufferStart_ + buffer_.size()) {
    return true;
  }
  const auto size =
      static_cast<size_t>(std::min<uint64_t>(std::max(count, kBufferBytes), remaining()));
  if (!source_.readBytes(position_, size, buffer_)) {
    buffer_.clear();
    fail();
    return false;
  }
  bufferStart_ = position_;
  return true;
}

uint64_t SourceReader::bigEndian(size_t count) {
  if (!ok_ || !fill(count)) {
    fail();
    return 0;
  }
  const uint8_t* bytes = buffer_.data() + (position_ - bufferStart_);
  uint64_t value = 0;
  for (size_t index = 0; index < count; ++index) {
    value = (value << 8U) | bytes[index];
  }
  position_ += count;
  return value;
}

bool SourceReader::bytes(uint64_t count, uint64_t maximumBytes, std::vector<uint8_t>& bytes) {
  bytes.clear();
  if (!ok_ || count > maximumBytes || count > remaining()) {
    fail();
    return false;
  }
  if (!source_.readBytes(position_, static_cast<size_t>(count), bytes)) {
    fail();
    return false;
  }
  position_ += count;
  return true;
}

std::string SourceReader::string(uint64_t count) {
  std::vector<uint8_t> data;
  if (!bytes(count, count, data)) {
    return {};
  }
  return {data.begin(), data.end()};
}

bool decodePackBits(const uint8_t* source, size_t sourceSize, uint8_t* destination,
                    size_t destinationSize) {
  size_t in = 0;
  size_t out = 0;
  while (out < destinationSize) {
    if (in >= sourceSize) {
      return false;
    }
    const auto header = static_cast<int8_t>(source[in++]);
    if (header >= 0) {
      const auto count = static_cast<size_t>(header) + 1;
      if (count > sourceSize - in || count > destinationSize - out) {
        return false;
      }
      std::memcpy(destination + out, source + in, count);
      in += count;
      out += count;
    } else if (header != -128) {
      const auto count = static_cast<size_t>(1 - header);
      if (in >= sourceSize || count > destinationSize - out) {
        return false;
      }
      std::memset(destination + out, source[in++], count);
      out += count;
    }
  }
  return true;
}

void encodePackBits(const uint8_t* source, size_t size, std::vector<uint8_t>& encoded) {
  size_t position = 0;
  while (position < size) {
    size_t run = 1;
    while (position + run < size && run < 128 && source[position + run] == source[position]) {
      ++run;
    }
    if (run >= 3) {
      encoded.push_back(static_cast<uint8_t>(static_cast<int8_t>(1 - static_cast<int>(run))));
      encoded.push_back(source[position]);
      position += run;
      continue;
    }
    size_t literal = 0;
    while (position + literal < size && literal < 128) {
      if (position + literal + 2 < size &&
          source[position + literal] == source[position + literal + 1] &&
          source[position + literal] == source[position + literal + 2]) {
        break;
      }
      ++literal;
    }
    encoded.push_back(static_cast<uint8_t>(literal - 1));
    encoded.insert(encoded.end(), source + position, source + position + literal);
    position += literal;
  }
}

}  // namespace nanquim::formats
