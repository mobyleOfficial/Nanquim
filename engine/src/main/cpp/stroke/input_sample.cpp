#include "stroke/input_sample.h"

#include <cstring>

namespace nanquim::stroke {

namespace {

void writeUint32(uint32_t value, uint8_t* destination) {
  for (size_t byte = 0; byte < 4; ++byte) {
    destination[byte] = static_cast<uint8_t>(value >> (byte * 8));
  }
}

uint32_t readUint32(const uint8_t* source) {
  uint32_t value = 0;
  for (size_t byte = 0; byte < 4; ++byte) {
    value |= static_cast<uint32_t>(source[byte]) << (byte * 8);
  }
  return value;
}

void writeFloat(float value, uint8_t* destination) {
  uint32_t bits = 0;
  std::memcpy(&bits, &value, sizeof bits);
  writeUint32(bits, destination);
}

float readFloat(const uint8_t* source) {
  const uint32_t bits = readUint32(source);
  float value = 0;
  std::memcpy(&value, &bits, sizeof value);
  return value;
}

void writeInt64(int64_t value, uint8_t* destination) {
  const auto bits = static_cast<uint64_t>(value);
  writeUint32(static_cast<uint32_t>(bits), destination);
  writeUint32(static_cast<uint32_t>(bits >> 32U), destination + 4);
}

int64_t readInt64(const uint8_t* source) {
  const uint64_t bits = static_cast<uint64_t>(readUint32(source)) |
                        (static_cast<uint64_t>(readUint32(source + 4)) << 32U);
  return static_cast<int64_t>(bits);
}

}  // namespace

void encodePackedSample(const InputSample& sample, uint8_t* destination) {
  writeFloat(sample.x, destination);
  writeFloat(sample.y, destination + 4);
  writeFloat(sample.pressure, destination + 8);
  writeFloat(sample.tilt, destination + 12);
  writeFloat(sample.orientation, destination + 16);
  writeFloat(sample.distance, destination + 20);
  writeInt64(sample.eventTimeNanos, destination + 24);
  writeUint32(static_cast<uint32_t>(sample.toolType), destination + 32);
  writeUint32(static_cast<uint32_t>(sample.buttonState), destination + 36);
  writeUint32(static_cast<uint32_t>(sample.flags), destination + 40);
  writeUint32(static_cast<uint32_t>(sample.pointerId), destination + 44);
}

InputSample decodePackedSample(const uint8_t* source) {
  InputSample sample;
  sample.x = readFloat(source);
  sample.y = readFloat(source + 4);
  sample.pressure = readFloat(source + 8);
  sample.tilt = readFloat(source + 12);
  sample.orientation = readFloat(source + 16);
  sample.distance = readFloat(source + 20);
  sample.eventTimeNanos = readInt64(source + 24);
  sample.toolType = static_cast<int32_t>(readUint32(source + 32));
  sample.buttonState = static_cast<int32_t>(readUint32(source + 36));
  sample.flags = static_cast<int32_t>(readUint32(source + 40));
  sample.pointerId = static_cast<int32_t>(readUint32(source + 44));
  return sample;
}

bool decodePackedSamples(const uint8_t* data, size_t byteCount, std::vector<InputSample>& samples) {
  if (byteCount % kPackedSampleBytes != 0) {
    return false;
  }
  samples.reserve(samples.size() + byteCount / kPackedSampleBytes);
  for (size_t offset = 0; offset < byteCount; offset += kPackedSampleBytes) {
    samples.push_back(decodePackedSample(data + offset));
  }
  return true;
}

}  // namespace nanquim::stroke
