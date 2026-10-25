#include "formats/icc_profile.h"

#include <cmath>
#include <string>
#include <utility>

namespace nanquim::formats {

namespace {

using Matrix = std::array<std::array<double, 3>, 3>;
using Vector = std::array<double, 3>;

constexpr Vector kD50{0.9642, 1.0, 0.8249};

Matrix multiply(const Matrix& a, const Matrix& b) {
  Matrix result{};
  for (size_t row = 0; row < 3; ++row) {
    for (size_t column = 0; column < 3; ++column) {
      for (size_t index = 0; index < 3; ++index) {
        result[row][column] += a[row][index] * b[index][column];
      }
    }
  }
  return result;
}

Vector multiply(const Matrix& a, const Vector& v) {
  Vector result{};
  for (size_t row = 0; row < 3; ++row) {
    result[row] = a[row][0] * v[0] + a[row][1] * v[1] + a[row][2] * v[2];
  }
  return result;
}

Matrix inverse(const Matrix& m) {
  const double determinant = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
                             m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                             m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
  Matrix result{};
  for (size_t row = 0; row < 3; ++row) {
    for (size_t column = 0; column < 3; ++column) {
      const size_t r0 = (column + 1) % 3;
      const size_t r1 = (column + 2) % 3;
      const size_t c0 = (row + 1) % 3;
      const size_t c1 = (row + 2) % 3;
      result[row][column] = (m[r0][c0] * m[r1][c1] - m[r0][c1] * m[r1][c0]) / determinant;
    }
  }
  return result;
}

Vector chromaticityToXyz(double x, double y) { return {x / y, 1.0, (1.0 - x - y) / y}; }

class ProfileWriter {
 public:
  void u8(uint32_t value) { bytes.push_back(static_cast<uint8_t>(value)); }
  void u16(uint32_t value) {
    u8(value >> 8U);
    u8(value);
  }
  void u32(uint32_t value) {
    u16(value >> 16U);
    u16(value);
  }
  void signature(const char (&text)[5]) {
    for (size_t index = 0; index < 4; ++index) {
      u8(static_cast<uint8_t>(text[index]));
    }
  }
  void s15Fixed16(double value) {
    u32(static_cast<uint32_t>(static_cast<int32_t>(std::lround(value * 65536.0))));
  }
  void xyz(const Vector& value) {
    for (const double component : value) {
      s15Fixed16(component);
    }
  }
  void zeros(size_t count) { bytes.insert(bytes.end(), count, 0); }
  void align4() { zeros((4 - bytes.size() % 4) % 4); }

  std::vector<uint8_t> bytes;
};

std::vector<uint8_t> xyzTag(const Vector& value) {
  ProfileWriter tag;
  tag.signature("XYZ ");
  tag.zeros(4);
  tag.xyz(value);
  return tag.bytes;
}

std::vector<uint8_t> textTag(const std::string& text) {
  ProfileWriter tag;
  tag.signature("text");
  tag.zeros(4);
  tag.bytes.insert(tag.bytes.end(), text.begin(), text.end());
  tag.u8(0);
  return tag.bytes;
}

std::vector<uint8_t> descriptionTag(const std::string& text) {
  ProfileWriter tag;
  tag.signature("desc");
  tag.zeros(4);
  tag.u32(static_cast<uint32_t>(text.size() + 1));
  tag.bytes.insert(tag.bytes.end(), text.begin(), text.end());
  tag.u8(0);
  tag.u32(0);
  tag.u32(0);
  tag.u16(0);
  tag.u8(0);
  tag.zeros(67);
  return tag.bytes;
}

std::vector<uint8_t> curveTag() {
  constexpr uint32_t kPoints = 1024;
  ProfileWriter tag;
  tag.signature("curv");
  tag.zeros(4);
  tag.u32(kPoints);
  for (uint32_t index = 0; index < kPoints; ++index) {
    const double encoded = index / static_cast<double>(kPoints - 1);
    const double linear =
        encoded <= 0.04045 ? encoded / 12.92 : std::pow((encoded + 0.055) / 1.055, 2.4);
    tag.u16(static_cast<uint32_t>(std::lround(linear * 65535.0)));
  }
  return tag.bytes;
}

}  // namespace

Matrix srgbD50Colorants() {
  const Vector red = chromaticityToXyz(0.64, 0.33);
  const Vector green = chromaticityToXyz(0.30, 0.60);
  const Vector blue = chromaticityToXyz(0.15, 0.06);
  const Vector white = chromaticityToXyz(0.3127, 0.3290);
  const Matrix primaries{
      {{red[0], green[0], blue[0]}, {red[1], green[1], blue[1]}, {red[2], green[2], blue[2]}}};
  const Vector scale = multiply(inverse(primaries), white);
  Matrix toXyz{};
  for (size_t row = 0; row < 3; ++row) {
    for (size_t column = 0; column < 3; ++column) {
      toXyz[row][column] = primaries[row][column] * scale[column];
    }
  }
  const Matrix bradford{
      {{0.8951, 0.2664, -0.1614}, {-0.7502, 1.7135, 0.0367}, {0.0389, -0.0685, 1.0296}}};
  const Vector sourceCone = multiply(bradford, white);
  const Vector targetCone = multiply(bradford, kD50);
  Matrix gain{};
  for (size_t index = 0; index < 3; ++index) {
    gain[index][index] = targetCone[index] / sourceCone[index];
  }
  const Matrix adaptation = multiply(inverse(bradford), multiply(gain, bradford));
  return multiply(adaptation, toXyz);
}

std::vector<uint8_t> srgbIccProfile() {
  const Matrix colorants = srgbD50Colorants();
  const std::vector<uint8_t> curve = curveTag();
  struct Tag {
    const char* signature;
    std::vector<uint8_t> data;
  };
  std::vector<Tag> tags;
  tags.push_back({"desc", descriptionTag("sRGB IEC61966-2.1")});
  tags.push_back({"cprt", textTag("No copyright, use freely")});
  tags.push_back({"wtpt", xyzTag(kD50)});
  tags.push_back({"rXYZ", xyzTag({colorants[0][0], colorants[1][0], colorants[2][0]})});
  tags.push_back({"gXYZ", xyzTag({colorants[0][1], colorants[1][1], colorants[2][1]})});
  tags.push_back({"bXYZ", xyzTag({colorants[0][2], colorants[1][2], colorants[2][2]})});
  tags.push_back({"rTRC", curve});

  constexpr size_t kHeaderBytes = 128;
  const size_t tableEntries = tags.size() + 2;
  ProfileWriter data;
  std::vector<std::pair<uint32_t, uint32_t>> placement;
  size_t offset = kHeaderBytes + 4 + tableEntries * 12;
  for (const Tag& tag : tags) {
    placement.emplace_back(static_cast<uint32_t>(offset + data.bytes.size()),
                           static_cast<uint32_t>(tag.data.size()));
    data.bytes.insert(data.bytes.end(), tag.data.begin(), tag.data.end());
    data.align4();
  }
  const size_t total = offset + data.bytes.size();

  ProfileWriter profile;
  profile.u32(static_cast<uint32_t>(total));
  profile.u32(0);
  profile.u32(0x02100000);
  profile.signature("mntr");
  profile.signature("RGB ");
  profile.signature("XYZ ");
  for (const uint32_t field : {2000U, 1U, 1U, 0U, 0U, 0U}) {
    profile.u16(field);
  }
  profile.signature("acsp");
  profile.zeros(4 + 4 + 4 + 4 + 8);
  profile.u32(0);
  profile.xyz(kD50);
  profile.zeros(4);
  profile.zeros(kHeaderBytes - profile.bytes.size());
  profile.u32(static_cast<uint32_t>(tableEntries));
  for (size_t index = 0; index < tags.size(); ++index) {
    for (size_t character = 0; character < 4; ++character) {
      profile.u8(static_cast<uint8_t>(tags[index].signature[character]));
    }
    profile.u32(placement[index].first);
    profile.u32(placement[index].second);
  }
  for (const char* shared : {"gTRC", "bTRC"}) {
    for (size_t character = 0; character < 4; ++character) {
      profile.u8(static_cast<uint8_t>(shared[character]));
    }
    profile.u32(placement.back().first);
    profile.u32(placement.back().second);
  }
  profile.bytes.insert(profile.bytes.end(), data.bytes.begin(), data.bytes.end());
  return profile.bytes;
}

}  // namespace nanquim::formats
