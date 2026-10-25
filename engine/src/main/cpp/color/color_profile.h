#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace nanquim::color {

enum class ProfileColorSpace { Rgb, Gray, Cmyk, Other };

struct ProfileInfo {
  std::string description;
  ProfileColorSpace colorSpace = ProfileColorSpace::Other;
  bool convertsFrom = false;
  bool convertsTo = false;
  int32_t majorVersion = 0;

  [[nodiscard]] bool isDocumentProfile() const {
    return colorSpace == ProfileColorSpace::Rgb && convertsFrom && convertsTo;
  }
};

inline constexpr size_t kMaximumProfileBytes = size_t{16} * 1024 * 1024;

std::optional<ProfileInfo> readProfile(const uint8_t* data, size_t size, std::string& error);

class ProfileTransform {
 public:
  static std::unique_ptr<ProfileTransform> create(const std::vector<uint8_t>& source,
                                                  const std::vector<uint8_t>& destination,
                                                  std::string& error);
  ~ProfileTransform();
  ProfileTransform(const ProfileTransform&) = delete;
  ProfileTransform& operator=(const ProfileTransform&) = delete;

  void convertRgba8(uint8_t* pixels, size_t pixelCount) const;
  void convertStraightRgba8(uint8_t* pixels, size_t pixelCount) const;
  void convertLinearHalf(uint8_t* pixels, size_t pixelCount) const;

 private:
  struct State;
  explicit ProfileTransform(std::unique_ptr<State> state);
  std::unique_ptr<State> state_;
};

class CmykTransform {
 public:
  static std::unique_ptr<CmykTransform> create(const std::vector<uint8_t>& rgbProfile,
                                               const std::vector<uint8_t>& cmykProfile,
                                               std::string& error);
  ~CmykTransform();
  CmykTransform(const CmykTransform&) = delete;
  CmykTransform& operator=(const CmykTransform&) = delete;

  void convert(const uint8_t* rgba, uint8_t* cmyka, size_t pixelCount) const;

 private:
  struct State;
  explicit CmykTransform(std::unique_ptr<State> state);
  std::unique_ptr<State> state_;
};

class CmykImageTransform {
 public:
  static std::unique_ptr<CmykImageTransform> create(const std::vector<uint8_t>& cmykProfile,
                                                    const std::vector<uint8_t>& rgbProfile,
                                                    std::string& error);
  ~CmykImageTransform();
  CmykImageTransform(const CmykImageTransform&) = delete;
  CmykImageTransform& operator=(const CmykImageTransform&) = delete;

  void convert(const uint8_t* inks, uint8_t* rgba, size_t pixelCount) const;

 private:
  struct State;
  explicit CmykImageTransform(std::unique_ptr<State> state);
  std::unique_ptr<State> state_;
};

class CmykColorConverter {
 public:
  static std::unique_ptr<CmykColorConverter> create(const std::vector<uint8_t>& rgbProfile,
                                                    const std::vector<uint8_t>& cmykProfile,
                                                    std::string& error);
  ~CmykColorConverter();
  CmykColorConverter(const CmykColorConverter&) = delete;
  CmykColorConverter& operator=(const CmykColorConverter&) = delete;

  [[nodiscard]] std::array<float, 4> toCmyk(const std::array<float, 3>& rgb) const;
  [[nodiscard]] std::array<float, 3> toRgb(const std::array<float, 4>& cmyk) const;

 private:
  struct State;
  explicit CmykColorConverter(std::unique_ptr<State> state);
  std::unique_ptr<State> state_;
};

}  // namespace nanquim::color
