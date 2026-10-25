#include "color/color_profile.h"

#include <lcms2.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>

#include "canvas/color_conversion.h"
#include "core/half_float.h"

namespace nanquim::color {

namespace {

constexpr cmsUInt32Number kIntent = INTENT_RELATIVE_COLORIMETRIC;
constexpr size_t kChunkPixels = 4096;
constexpr uint16_t kHalfOne = 0x3C00;

void logError(cmsContext context, cmsUInt32Number /*code*/, const char* text) {
  auto* message = static_cast<std::string*>(cmsGetContextUserData(context));
  if (message != nullptr && message->empty() && text != nullptr) {
    *message = text;
  }
}

cmsContext createContext(std::string* message) {
  cmsContext context = cmsCreateContext(nullptr, message);
  if (context != nullptr) {
    cmsSetLogErrorHandlerTHR(context, logError);
  }
  return context;
}

cmsHPROFILE openProfile(cmsContext context, const std::vector<uint8_t>& bytes) {
  if (bytes.empty()) {
    return cmsCreate_sRGBProfileTHR(context);
  }
  return cmsOpenProfileFromMemTHR(context, bytes.data(),
                                  static_cast<cmsUInt32Number>(bytes.size()));
}

uint32_t colorSpaceSignature(cmsHPROFILE profile) {
  uint32_t signature = 0;
  const cmsColorSpaceSignature value = cmsGetColorSpace(profile);
  std::memcpy(&signature, &value, sizeof(signature));
  return signature;
}

ProfileColorSpace colorSpaceOf(uint32_t signature) {
  if (signature == static_cast<uint32_t>(cmsSigRgbData)) {
    return ProfileColorSpace::Rgb;
  }
  if (signature == static_cast<uint32_t>(cmsSigGrayData)) {
    return ProfileColorSpace::Gray;
  }
  if (signature == static_cast<uint32_t>(cmsSigCmykData)) {
    return ProfileColorSpace::Cmyk;
  }
  return ProfileColorSpace::Other;
}

const std::array<uint16_t, 65536>& encodedOfHalf() {
  static const std::array<uint16_t, 65536> table = [] {
    std::array<uint16_t, 65536> values{};
    for (size_t bits = 0; bits < values.size(); ++bits) {
      const double linear = core::halfToFloat(static_cast<uint16_t>(bits));
      const double clamped = std::isnan(linear) ? 0.0 : std::clamp(linear, 0.0, 1.0);
      values[bits] =
          static_cast<uint16_t>(std::lround(canvas::linearToSrgbValue(clamped) * 65535.0));
    }
    return values;
  }();
  return table;
}

const std::array<float, 65536>& linearOfEncoded() {
  static const std::array<float, 65536> table = [] {
    std::array<float, 65536> values{};
    for (size_t code = 0; code < values.size(); ++code) {
      values[code] = static_cast<float>(canvas::srgbToLinearValue(code / 65535.0));
    }
    return values;
  }();
  return table;
}

}  // namespace

std::optional<ProfileInfo> readProfile(const uint8_t* data, size_t size, std::string& error) {
  if (size < 128 || size > kMaximumProfileBytes) {
    error = "not an ICC profile (size)";
    return std::nullopt;
  }
  std::string message;
  cmsContext context = createContext(&message);
  if (context == nullptr) {
    error = "out of memory";
    return std::nullopt;
  }
  std::optional<ProfileInfo> result;
  cmsHPROFILE profile = cmsOpenProfileFromMemTHR(context, data, static_cast<cmsUInt32Number>(size));
  if (profile == nullptr) {
    error = message.empty() ? "not an ICC profile" : message;
  } else {
    ProfileInfo info;
    info.colorSpace = colorSpaceOf(colorSpaceSignature(profile));
    info.majorVersion = static_cast<int32_t>(cmsGetProfileVersion(profile));
    const auto supports = [profile](cmsUInt32Number direction) {
      return cmsIsIntentSupported(profile, kIntent, direction) != 0 ||
             cmsIsIntentSupported(profile, INTENT_PERCEPTUAL, direction) != 0;
    };
    info.convertsFrom = supports(LCMS_USED_AS_INPUT);
    info.convertsTo = supports(LCMS_USED_AS_OUTPUT);
    const cmsUInt32Number length =
        cmsGetProfileInfoUTF8(profile, cmsInfoDescription, "en", "US", nullptr, 0);
    if (length > 1 && length < 4096) {
      std::vector<char> text(length);
      cmsGetProfileInfoUTF8(profile, cmsInfoDescription, "en", "US", text.data(), length);
      info.description.assign(text.data(), strnlen(text.data(), text.size()));
    }
    result = std::move(info);
    cmsCloseProfile(profile);
  }
  cmsDeleteContext(context);
  return result;
}

struct ProfileTransform::State {
  std::string message;
  cmsContext context = nullptr;
  cmsHTRANSFORM rgb8 = nullptr;
  cmsHTRANSFORM rgb16 = nullptr;

  ~State() {
    if (rgb8 != nullptr) {
      cmsDeleteTransform(rgb8);
    }
    if (rgb16 != nullptr) {
      cmsDeleteTransform(rgb16);
    }
    if (context != nullptr) {
      cmsDeleteContext(context);
    }
  }
};

ProfileTransform::ProfileTransform(std::unique_ptr<State> state) : state_(std::move(state)) {}

ProfileTransform::~ProfileTransform() = default;

std::unique_ptr<ProfileTransform> ProfileTransform::create(const std::vector<uint8_t>& source,
                                                           const std::vector<uint8_t>& destination,
                                                           std::string& error) {
  auto state = std::make_unique<State>();
  state->context = createContext(&state->message);
  if (state->context == nullptr) {
    error = "out of memory";
    return nullptr;
  }
  cmsHPROFILE input = openProfile(state->context, source);
  cmsHPROFILE output = openProfile(state->context, destination);
  if (input != nullptr && output != nullptr) {
    if (colorSpaceOf(colorSpaceSignature(input)) != ProfileColorSpace::Rgb ||
        colorSpaceOf(colorSpaceSignature(output)) != ProfileColorSpace::Rgb) {
      state->message = "not an RGB profile";
    } else {
      const cmsUInt32Number flags = cmsFLAGS_BLACKPOINTCOMPENSATION | cmsFLAGS_NOCACHE;
      state->rgb8 = cmsCreateTransformTHR(state->context, input, TYPE_RGB_8, output, TYPE_RGB_8,
                                          kIntent, flags);
      state->rgb16 = cmsCreateTransformTHR(state->context, input, TYPE_RGB_16, output, TYPE_RGB_16,
                                           kIntent, flags);
    }
  }
  if (input != nullptr) {
    cmsCloseProfile(input);
  }
  if (output != nullptr) {
    cmsCloseProfile(output);
  }
  if (state->rgb8 == nullptr || state->rgb16 == nullptr) {
    error = state->message.empty() ? "cannot convert between these profiles" : state->message;
    return nullptr;
  }
  return std::unique_ptr<ProfileTransform>(new ProfileTransform(std::move(state)));
}

void ProfileTransform::convertRgba8(uint8_t* pixels, size_t pixelCount) const {
  std::vector<uint8_t> colors(kChunkPixels * 3);
  for (size_t start = 0; start < pixelCount; start += kChunkPixels) {
    const size_t count = std::min(kChunkPixels, pixelCount - start);
    uint8_t* chunk = pixels + start * 4;
    for (size_t pixel = 0; pixel < count; ++pixel) {
      const uint32_t alpha = chunk[pixel * 4 + 3];
      for (size_t channel = 0; channel < 3; ++channel) {
        const uint32_t value = chunk[pixel * 4 + channel];
        colors[pixel * 3 + channel] = static_cast<uint8_t>(
            alpha == 0 ? 0 : std::min<uint32_t>(255, (value * 255 + alpha / 2) / alpha));
      }
    }
    cmsDoTransform(state_->rgb8, colors.data(), colors.data(), static_cast<cmsUInt32Number>(count));
    for (size_t pixel = 0; pixel < count; ++pixel) {
      const uint32_t alpha = chunk[pixel * 4 + 3];
      if (alpha == 0) {
        continue;
      }
      for (size_t channel = 0; channel < 3; ++channel) {
        chunk[pixel * 4 + channel] =
            static_cast<uint8_t>((colors[pixel * 3 + channel] * alpha + 127) / 255);
      }
    }
  }
}

struct CmykTransform::State {
  std::string message;
  cmsContext context = nullptr;
  cmsHTRANSFORM transform = nullptr;

  ~State() {
    if (transform != nullptr) {
      cmsDeleteTransform(transform);
    }
    if (context != nullptr) {
      cmsDeleteContext(context);
    }
  }
};

CmykTransform::CmykTransform(std::unique_ptr<State> state) : state_(std::move(state)) {}

CmykTransform::~CmykTransform() = default;

std::unique_ptr<CmykTransform> CmykTransform::create(const std::vector<uint8_t>& rgbProfile,
                                                     const std::vector<uint8_t>& cmykProfile,
                                                     std::string& error) {
  auto state = std::make_unique<State>();
  state->context = createContext(&state->message);
  if (state->context == nullptr) {
    error = "out of memory";
    return nullptr;
  }
  cmsHPROFILE input = openProfile(state->context, rgbProfile);
  cmsHPROFILE output = cmykProfile.empty() ? nullptr
                                           : cmsOpenProfileFromMemTHR(
                                                 state->context, cmykProfile.data(),
                                                 static_cast<cmsUInt32Number>(cmykProfile.size()));
  if (input != nullptr && output != nullptr) {
    if (colorSpaceOf(colorSpaceSignature(input)) != ProfileColorSpace::Rgb) {
      state->message = "not an RGB profile";
    } else if (colorSpaceOf(colorSpaceSignature(output)) != ProfileColorSpace::Cmyk) {
      state->message = "not a CMYK profile";
    } else {
      state->transform =
          cmsCreateTransformTHR(state->context, input, TYPE_RGB_8, output, TYPE_CMYK_8, kIntent,
                                cmsFLAGS_BLACKPOINTCOMPENSATION | cmsFLAGS_NOCACHE);
    }
  }
  if (input != nullptr) {
    cmsCloseProfile(input);
  }
  if (output != nullptr) {
    cmsCloseProfile(output);
  }
  if (state->transform == nullptr) {
    error = state->message.empty() ? "cannot convert to this CMYK profile" : state->message;
    return nullptr;
  }
  return std::unique_ptr<CmykTransform>(new CmykTransform(std::move(state)));
}

void CmykTransform::convert(const uint8_t* rgba, uint8_t* cmyka, size_t pixelCount) const {
  std::vector<uint8_t> colors(kChunkPixels * 3);
  std::vector<uint8_t> inks(kChunkPixels * 4);
  for (size_t start = 0; start < pixelCount; start += kChunkPixels) {
    const size_t count = std::min(kChunkPixels, pixelCount - start);
    for (size_t pixel = 0; pixel < count; ++pixel) {
      std::memcpy(colors.data() + pixel * 3, rgba + (start + pixel) * 4, 3);
    }
    cmsDoTransform(state_->transform, colors.data(), inks.data(),
                   static_cast<cmsUInt32Number>(count));
    for (size_t pixel = 0; pixel < count; ++pixel) {
      uint8_t* out = cmyka + (start + pixel) * 5;
      std::memcpy(out, inks.data() + pixel * 4, 4);
      out[4] = rgba[(start + pixel) * 4 + 3];
    }
  }
}

struct CmykImageTransform::State {
  std::string message;
  cmsContext context = nullptr;
  cmsHTRANSFORM transform = nullptr;

  ~State() {
    if (transform != nullptr) {
      cmsDeleteTransform(transform);
    }
    if (context != nullptr) {
      cmsDeleteContext(context);
    }
  }
};

CmykImageTransform::CmykImageTransform(std::unique_ptr<State> state) : state_(std::move(state)) {}

CmykImageTransform::~CmykImageTransform() = default;

std::unique_ptr<CmykImageTransform> CmykImageTransform::create(
    const std::vector<uint8_t>& cmykProfile, const std::vector<uint8_t>& rgbProfile,
    std::string& error) {
  auto state = std::make_unique<State>();
  state->context = createContext(&state->message);
  if (state->context == nullptr) {
    error = "out of memory";
    return nullptr;
  }
  cmsHPROFILE cmyk = cmykProfile.empty() ? nullptr
                                         : cmsOpenProfileFromMemTHR(
                                               state->context, cmykProfile.data(),
                                               static_cast<cmsUInt32Number>(cmykProfile.size()));
  cmsHPROFILE rgb = openProfile(state->context, rgbProfile);
  if (cmyk != nullptr && rgb != nullptr) {
    if (colorSpaceOf(colorSpaceSignature(cmyk)) != ProfileColorSpace::Cmyk) {
      state->message = "not a CMYK profile";
    } else if (colorSpaceOf(colorSpaceSignature(rgb)) != ProfileColorSpace::Rgb) {
      state->message = "not an RGB profile";
    } else {
      state->transform = cmsCreateTransformTHR(state->context, cmyk, TYPE_CMYK_8, rgb, TYPE_RGB_8,
                                               kIntent, cmsFLAGS_BLACKPOINTCOMPENSATION);
    }
  }
  if (cmyk != nullptr) {
    cmsCloseProfile(cmyk);
  }
  if (rgb != nullptr) {
    cmsCloseProfile(rgb);
  }
  if (state->transform == nullptr) {
    error = state->message.empty() ? "cannot convert from this CMYK profile" : state->message;
    return nullptr;
  }
  return std::unique_ptr<CmykImageTransform>(new CmykImageTransform(std::move(state)));
}

void CmykImageTransform::convert(const uint8_t* inks, uint8_t* rgba, size_t pixelCount) const {
  std::vector<uint8_t> colors(kChunkPixels * 3);
  for (size_t start = 0; start < pixelCount; start += kChunkPixels) {
    const size_t count = std::min(kChunkPixels, pixelCount - start);
    cmsDoTransform(state_->transform, inks + start * 4, colors.data(),
                   static_cast<cmsUInt32Number>(count));
    for (size_t pixel = 0; pixel < count; ++pixel) {
      std::memcpy(rgba + (start + pixel) * 4, colors.data() + pixel * 3, 3);
    }
  }
}

struct CmykColorConverter::State {
  std::string message;
  cmsContext context = nullptr;
  cmsHTRANSFORM toCmyk = nullptr;
  cmsHTRANSFORM toRgb = nullptr;

  ~State() {
    if (toCmyk != nullptr) {
      cmsDeleteTransform(toCmyk);
    }
    if (toRgb != nullptr) {
      cmsDeleteTransform(toRgb);
    }
    if (context != nullptr) {
      cmsDeleteContext(context);
    }
  }
};

CmykColorConverter::CmykColorConverter(std::unique_ptr<State> state) : state_(std::move(state)) {}

CmykColorConverter::~CmykColorConverter() = default;

std::unique_ptr<CmykColorConverter> CmykColorConverter::create(
    const std::vector<uint8_t>& rgbProfile, const std::vector<uint8_t>& cmykProfile,
    std::string& error) {
  auto state = std::make_unique<State>();
  state->context = createContext(&state->message);
  if (state->context == nullptr) {
    error = "out of memory";
    return nullptr;
  }
  cmsHPROFILE rgb = openProfile(state->context, rgbProfile);
  cmsHPROFILE cmyk = cmykProfile.empty() ? nullptr
                                         : cmsOpenProfileFromMemTHR(
                                               state->context, cmykProfile.data(),
                                               static_cast<cmsUInt32Number>(cmykProfile.size()));
  if (rgb != nullptr && cmyk != nullptr) {
    if (colorSpaceOf(colorSpaceSignature(rgb)) != ProfileColorSpace::Rgb) {
      state->message = "not an RGB profile";
    } else if (colorSpaceOf(colorSpaceSignature(cmyk)) != ProfileColorSpace::Cmyk) {
      state->message = "not a CMYK profile";
    } else {
      const cmsUInt32Number flags = cmsFLAGS_BLACKPOINTCOMPENSATION | cmsFLAGS_NOCACHE;
      state->toCmyk = cmsCreateTransformTHR(state->context, rgb, TYPE_RGB_FLT, cmyk, TYPE_CMYK_FLT,
                                            kIntent, flags);
      state->toRgb = cmsCreateTransformTHR(state->context, cmyk, TYPE_CMYK_FLT, rgb, TYPE_RGB_FLT,
                                           kIntent, flags);
    }
  }
  if (rgb != nullptr) {
    cmsCloseProfile(rgb);
  }
  if (cmyk != nullptr) {
    cmsCloseProfile(cmyk);
  }
  if (state->toCmyk == nullptr || state->toRgb == nullptr) {
    error = state->message.empty() ? "cannot convert to this CMYK profile" : state->message;
    return nullptr;
  }
  return std::unique_ptr<CmykColorConverter>(new CmykColorConverter(std::move(state)));
}

std::array<float, 4> CmykColorConverter::toCmyk(const std::array<float, 3>& rgb) const {
  std::array<float, 3> input{};
  for (size_t channel = 0; channel < 3; ++channel) {
    input[channel] = std::isnan(rgb[channel]) ? 0.0F : std::clamp(rgb[channel], 0.0F, 1.0F);
  }
  std::array<float, 4> percent{};
  cmsDoTransform(state_->toCmyk, input.data(), percent.data(), 1);
  std::array<float, 4> cmyk{};
  for (size_t channel = 0; channel < 4; ++channel) {
    const float value = percent[channel] / 100.0F;
    cmyk[channel] = std::isnan(value) ? 0.0F : std::clamp(value, 0.0F, 1.0F);
  }
  return cmyk;
}

std::array<float, 3> CmykColorConverter::toRgb(const std::array<float, 4>& cmyk) const {
  std::array<float, 4> percent{};
  for (size_t channel = 0; channel < 4; ++channel) {
    percent[channel] =
        std::isnan(cmyk[channel]) ? 0.0F : std::clamp(cmyk[channel], 0.0F, 1.0F) * 100.0F;
  }
  std::array<float, 3> rgb{};
  cmsDoTransform(state_->toRgb, percent.data(), rgb.data(), 1);
  for (float& value : rgb) {
    value = std::isnan(value) ? 0.0F : std::clamp(value, 0.0F, 1.0F);
  }
  return rgb;
}

void ProfileTransform::convertStraightRgba8(uint8_t* pixels, size_t pixelCount) const {
  std::vector<uint8_t> colors(kChunkPixels * 3);
  for (size_t start = 0; start < pixelCount; start += kChunkPixels) {
    const size_t count = std::min(kChunkPixels, pixelCount - start);
    uint8_t* chunk = pixels + start * 4;
    for (size_t pixel = 0; pixel < count; ++pixel) {
      std::memcpy(colors.data() + pixel * 3, chunk + pixel * 4, 3);
    }
    cmsDoTransform(state_->rgb8, colors.data(), colors.data(), static_cast<cmsUInt32Number>(count));
    for (size_t pixel = 0; pixel < count; ++pixel) {
      std::memcpy(chunk + pixel * 4, colors.data() + pixel * 3, 3);
    }
  }
}

void ProfileTransform::convertLinearHalf(uint8_t* pixels, size_t pixelCount) const {
  const auto& encodedTable = encodedOfHalf();
  const auto& linearTable = linearOfEncoded();
  std::vector<uint16_t> encoded(kChunkPixels * 3);
  std::vector<float> alphas(kChunkPixels);
  for (size_t start = 0; start < pixelCount; start += kChunkPixels) {
    const size_t count = std::min(kChunkPixels, pixelCount - start);
    uint8_t* chunk = pixels + start * 8;
    for (size_t pixel = 0; pixel < count; ++pixel) {
      std::array<uint16_t, 4> half{};
      std::memcpy(half.data(), chunk + pixel * 8, 8);
      const float alpha = core::halfToFloat(half[3]);
      alphas[pixel] = std::isnan(alpha) ? 0.0F : std::min(alpha, 1.0F);
      for (size_t channel = 0; channel < 3; ++channel) {
        uint16_t code = 0;
        if (half[3] == kHalfOne) {
          code = encodedTable[half[channel]];
        } else if (alphas[pixel] > 0) {
          const double color = std::clamp(
              static_cast<double>(core::halfToFloat(half[channel])) / alphas[pixel], 0.0, 1.0);
          code = static_cast<uint16_t>(
              std::lround(canvas::linearToSrgbValue(std::isnan(color) ? 0.0 : color) * 65535.0));
        }
        encoded[pixel * 3 + channel] = code;
      }
    }
    cmsDoTransform(state_->rgb16, encoded.data(), encoded.data(),
                   static_cast<cmsUInt32Number>(count));
    for (size_t pixel = 0; pixel < count; ++pixel) {
      if (!(alphas[pixel] > 0)) {
        continue;
      }
      for (size_t channel = 0; channel < 3; ++channel) {
        const uint16_t half =
            core::floatToHalf(linearTable[encoded[pixel * 3 + channel]] * alphas[pixel]);
        std::memcpy(chunk + pixel * 8 + channel * 2, &half, 2);
      }
    }
  }
}

}  // namespace nanquim::color
