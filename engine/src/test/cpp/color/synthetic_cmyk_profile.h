#pragma once

#include <lcms2.h>

#include <algorithm>
#include <cstdint>
#include <vector>

namespace nanquim::color::testing {

inline std::vector<uint8_t> syntheticCmykProfile() {
  cmsContext context = cmsCreateContext(nullptr, nullptr);
  cmsHPROFILE srgb = cmsCreate_sRGBProfileTHR(context);
  cmsHPROFILE lab = cmsCreateLab4ProfileTHR(context, nullptr);
  struct Cargo {
    cmsHTRANSFORM toLab;
    cmsHTRANSFORM toRgb;
  } cargo{cmsCreateTransformTHR(context, srgb, TYPE_RGB_DBL, lab, TYPE_Lab_DBL,
                                INTENT_RELATIVE_COLORIMETRIC, cmsFLAGS_NOCACHE),
          cmsCreateTransformTHR(context, lab, TYPE_Lab_DBL, srgb, TYPE_RGB_DBL,
                                INTENT_RELATIVE_COLORIMETRIC, cmsFLAGS_NOCACHE)};

  const auto cmykToLab = [](const cmsUInt16Number in[], cmsUInt16Number out[], void* data) {
    const auto* cargo = static_cast<const Cargo*>(data);
    const double k = in[3] / 65535.0;
    const double rgb[3] = {(1 - in[0] / 65535.0) * (1 - k), (1 - in[1] / 65535.0) * (1 - k),
                           (1 - in[2] / 65535.0) * (1 - k)};
    cmsCIELab value{};
    cmsDoTransform(cargo->toLab, rgb, &value, 1);
    cmsFloat2LabEncodedV2(out, &value);
    return 1;
  };
  const auto labToCmyk = [](const cmsUInt16Number in[], cmsUInt16Number out[], void* data) {
    const auto* cargo = static_cast<const Cargo*>(data);
    cmsCIELab value{};
    cmsLabEncoded2FloatV2(&value, in);
    double rgb[3] = {0, 0, 0};
    cmsDoTransform(cargo->toRgb, &value, rgb, 1);
    for (double& channel : rgb) {
      channel = std::clamp(channel, 0.0, 1.0);
    }
    const double k = 1 - std::max({rgb[0], rgb[1], rgb[2]});
    for (int channel = 0; channel < 3; ++channel) {
      const double ink = k >= 1 ? 0 : (1 - rgb[channel] - k) / (1 - k);
      out[channel] = static_cast<cmsUInt16Number>(std::clamp(ink, 0.0, 1.0) * 65535.0 + 0.5);
    }
    out[3] = static_cast<cmsUInt16Number>(k * 65535.0 + 0.5);
    return 1;
  };

  cmsHPROFILE profile = cmsCreateProfilePlaceholder(context);
  cmsSetProfileVersion(profile, 2.1);
  cmsSetDeviceClass(profile, cmsSigOutputClass);
  cmsSetColorSpace(profile, cmsSigCmykData);
  cmsSetPCS(profile, cmsSigLabData);
  cmsPipeline* aToB = cmsPipelineAlloc(context, 4, 3);
  cmsStage* aToBTable = cmsStageAllocCLut16bit(context, 9, 4, 3, nullptr);
  cmsStageSampleCLut16bit(aToBTable, cmykToLab, &cargo, 0);
  cmsPipelineInsertStage(aToB, cmsAT_BEGIN, aToBTable);
  cmsWriteTag(profile, cmsSigAToB0Tag, aToB);
  cmsPipeline* bToA = cmsPipelineAlloc(context, 3, 4);
  cmsStage* bToATable = cmsStageAllocCLut16bit(context, 17, 3, 4, nullptr);
  cmsStageSampleCLut16bit(bToATable, labToCmyk, &cargo, 0);
  cmsPipelineInsertStage(bToA, cmsAT_BEGIN, bToATable);
  cmsWriteTag(profile, cmsSigBToA0Tag, bToA);
  cmsMLU* description = cmsMLUalloc(context, 1);
  cmsMLUsetASCII(description, "en", "US", "Synthetic CMYK (tests)");
  cmsWriteTag(profile, cmsSigProfileDescriptionTag, description);
  cmsWriteTag(profile, cmsSigMediaWhitePointTag, cmsD50_XYZ());

  cmsUInt32Number size = 0;
  cmsSaveProfileToMem(profile, nullptr, &size);
  std::vector<uint8_t> bytes(size);
  cmsSaveProfileToMem(profile, bytes.data(), &size);

  cmsMLUfree(description);
  cmsPipelineFree(aToB);
  cmsPipelineFree(bToA);
  cmsCloseProfile(profile);
  cmsDeleteTransform(cargo.toLab);
  cmsDeleteTransform(cargo.toRgb);
  cmsCloseProfile(srgb);
  cmsCloseProfile(lab);
  cmsDeleteContext(context);
  return bytes;
}

}  // namespace nanquim::color::testing
