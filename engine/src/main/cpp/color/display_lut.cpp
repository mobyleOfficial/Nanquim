#include "color/display_lut.h"

#include <lcms2.h>

#include <algorithm>
#include <cmath>
#include <cstring>

#include "core/half_float.h"

namespace nanquim::color {

namespace {

constexpr uint16_t kHalfOne = 0x3C00;

void logError(cmsContext context, cmsUInt32Number /*code*/, const char* text) {
  auto* message = static_cast<std::string*>(cmsGetContextUserData(context));
  if (message != nullptr && message->empty() && text != nullptr) {
    *message = text;
  }
}

cmsHPROFILE createDisplayP3(cmsContext context) {
  const cmsCIExyY white{0.3127, 0.3290, 1.0};
  const cmsCIExyYTRIPLE primaries{{0.680, 0.320, 1.0}, {0.265, 0.690, 1.0}, {0.150, 0.060, 1.0}};
  const cmsFloat64Number parameters[] = {2.4, 1.0 / 1.055, 0.055 / 1.055, 1.0 / 12.92, 0.04045};
  cmsToneCurve* curve = cmsBuildParametricToneCurve(context, 4, parameters);
  if (curve == nullptr) {
    return nullptr;
  }
  cmsToneCurve* curves[3] = {curve, curve, curve};
  cmsHPROFILE profile = cmsCreateRGBProfileTHR(context, &white, &primaries, curves);
  cmsFreeToneCurve(curve);
  return profile;
}

bool isRgb(cmsHPROFILE profile) {
  const cmsColorSpaceSignature value = cmsGetColorSpace(profile);
  uint32_t signature = 0;
  std::memcpy(&signature, &value, sizeof(signature));
  return signature == static_cast<uint32_t>(cmsSigRgbData);
}

bool isCmyk(cmsHPROFILE profile) {
  const cmsColorSpaceSignature value = cmsGetColorSpace(profile);
  uint32_t signature = 0;
  std::memcpy(&signature, &value, sizeof(signature));
  return signature == static_cast<uint32_t>(cmsSigCmykData);
}

}  // namespace

std::vector<uint16_t> buildDisplayLut(const std::vector<uint8_t>& documentProfile,
                                      const std::vector<uint8_t>& proofProfile,
                                      DisplaySpace display, std::string& error) {
  std::string message;
  cmsContext context = cmsCreateContext(nullptr, &message);
  if (context == nullptr) {
    error = "out of memory";
    return {};
  }
  cmsSetLogErrorHandlerTHR(context, logError);
  cmsHPROFILE input =
      documentProfile.empty()
          ? cmsCreate_sRGBProfileTHR(context)
          : cmsOpenProfileFromMemTHR(context, documentProfile.data(),
                                     static_cast<cmsUInt32Number>(documentProfile.size()));
  cmsHPROFILE output = display == DisplaySpace::DisplayP3 ? createDisplayP3(context)
                                                          : cmsCreate_sRGBProfileTHR(context);
  cmsHPROFILE proof = proofProfile.empty() ? nullptr
                                           : cmsOpenProfileFromMemTHR(
                                                 context, proofProfile.data(),
                                                 static_cast<cmsUInt32Number>(proofProfile.size()));
  cmsHTRANSFORM transform = nullptr;
  if (!proofProfile.empty() && (proof == nullptr || !isCmyk(proof))) {
    if (message.empty()) {
      message = "not a CMYK profile";
    }
  } else if (input != nullptr && output != nullptr && isRgb(input)) {
    const cmsUInt32Number flags = cmsFLAGS_BLACKPOINTCOMPENSATION | cmsFLAGS_NOCACHE;
    transform = proof == nullptr
                    ? cmsCreateTransformTHR(context, input, TYPE_RGB_FLT, output, TYPE_RGB_FLT,
                                            INTENT_RELATIVE_COLORIMETRIC, flags)
                    : cmsCreateProofingTransformTHR(
                          context, input, TYPE_RGB_FLT, output, TYPE_RGB_FLT, proof,
                          INTENT_RELATIVE_COLORIMETRIC, INTENT_RELATIVE_COLORIMETRIC,
                          flags | cmsFLAGS_SOFTPROOFING);
  } else if (input != nullptr && message.empty()) {
    message = "not an RGB profile";
  }
  if (proof != nullptr) {
    cmsCloseProfile(proof);
  }
  if (input != nullptr) {
    cmsCloseProfile(input);
  }
  if (output != nullptr) {
    cmsCloseProfile(output);
  }

  std::vector<uint16_t> lut;
  if (transform == nullptr) {
    error = message.empty() ? "cannot convert to the display" : message;
  } else {
    constexpr int32_t kSize = kDisplayLutSize;
    constexpr int32_t kRow = kSize * kSize * kSize;
    std::vector<float> colors(static_cast<size_t>(kRow) * 3);
    size_t index = 0;
    for (int32_t blue = 0; blue < kSize; ++blue) {
      for (int32_t green = 0; green < kSize; ++green) {
        for (int32_t red = 0; red < kSize; ++red) {
          colors[index++] = static_cast<float>(red) / (kSize - 1);
          colors[index++] = static_cast<float>(green) / (kSize - 1);
          colors[index++] = static_cast<float>(blue) / (kSize - 1);
        }
      }
    }
    cmsDoTransform(transform, colors.data(), colors.data(), static_cast<cmsUInt32Number>(kRow));
    cmsDeleteTransform(transform);
    lut.resize(static_cast<size_t>(kRow) * 4);
    for (size_t entry = 0; entry < static_cast<size_t>(kRow); ++entry) {
      for (size_t channel = 0; channel < 3; ++channel) {
        const float value = colors[entry * 3 + channel];
        lut[entry * 4 + channel] =
            core::floatToHalf(std::isnan(value) ? 0.0F : std::clamp(value, 0.0F, 1.0F));
      }
      lut[entry * 4 + 3] = kHalfOne;
    }
  }
  cmsDeleteContext(context);
  return lut;
}

}  // namespace nanquim::color
