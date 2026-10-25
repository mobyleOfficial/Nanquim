#include "stroke/input_sample.h"

#include <gtest/gtest.h>

#include <array>

namespace nanquim::stroke {
namespace {

InputSample exampleSample() {
  InputSample sample;
  sample.x = 123.25F;
  sample.y = -4.5F;
  sample.pressure = 0.75F;
  sample.tilt = 0.5F;
  sample.orientation = -1.25F;
  sample.distance = 3.0F;
  sample.eventTimeNanos = 0x0102030405060708LL;
  sample.toolType = kToolTypeStylus;
  sample.buttonState = 32;
  sample.flags = kSampleHistorical | kSampleCanceled;
  sample.pointerId = 7;
  return sample;
}

TEST(InputSampleTest, RoundTripsThroughThePackedRecord) {
  std::array<uint8_t, kPackedSampleBytes> bytes{};
  encodePackedSample(exampleSample(), bytes.data());
  const InputSample decoded = decodePackedSample(bytes.data());

  const InputSample expected = exampleSample();
  EXPECT_EQ(decoded.x, expected.x);
  EXPECT_EQ(decoded.y, expected.y);
  EXPECT_EQ(decoded.pressure, expected.pressure);
  EXPECT_EQ(decoded.tilt, expected.tilt);
  EXPECT_EQ(decoded.orientation, expected.orientation);
  EXPECT_EQ(decoded.distance, expected.distance);
  EXPECT_EQ(decoded.eventTimeNanos, expected.eventTimeNanos);
  EXPECT_EQ(decoded.toolType, expected.toolType);
  EXPECT_EQ(decoded.buttonState, expected.buttonState);
  EXPECT_EQ(decoded.flags, expected.flags);
  EXPECT_EQ(decoded.pointerId, expected.pointerId);
}

TEST(InputSampleTest, UsesTheLittleEndianLayoutOfTheSpec) {
  std::array<uint8_t, kPackedSampleBytes> bytes{};
  encodePackedSample(exampleSample(), bytes.data());

  EXPECT_EQ(bytes[24], 0x08);
  EXPECT_EQ(bytes[31], 0x01);
  EXPECT_EQ(bytes[44], 7);
  EXPECT_EQ(bytes[10], 0x40);
  EXPECT_EQ(bytes[11], 0x3F);
}

TEST(InputSampleTest, RejectsPartialRecords) {
  std::array<uint8_t, kPackedSampleBytes * 2 + 1> bytes{};
  std::vector<InputSample> samples;

  EXPECT_FALSE(decodePackedSamples(bytes.data(), bytes.size(), samples));
  EXPECT_TRUE(samples.empty());
  EXPECT_TRUE(decodePackedSamples(bytes.data(), kPackedSampleBytes * 2, samples));
  EXPECT_EQ(samples.size(), 2U);
}

TEST(InputSampleTest, PredictedAndHoverSamplesAreNotPathSamples) {
  InputSample sample;
  EXPECT_TRUE(isPathSample(sample));
  sample.flags = kSampleHistorical;
  EXPECT_TRUE(isPathSample(sample));
  sample.flags = kSamplePredicted;
  EXPECT_FALSE(isPathSample(sample));
  sample.flags = kSampleHover;
  EXPECT_FALSE(isPathSample(sample));
}

}  // namespace
}  // namespace nanquim::stroke
