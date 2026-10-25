#include <jni.h>

#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "brush_pad.h"
#include "core/log.h"
#include "stroke/auto_repeat.h"
#include "stroke/brush_json.h"
#include "stroke/brush_texture.h"
#include "stroke/dab_generator.h"
#include "stroke/loaded_brush.h"

namespace {

using nanquim::reference::BrushPad;
using nanquim::reference::PixelRect;

struct PadHandle {
  BrushPad pad;
  std::shared_ptr<const nanquim::stroke::LoadedBrush> brush;
  std::optional<nanquim::stroke::Curve> pressureCurve;
  uint64_t strokeIndex = 0;
};

PadHandle* fromHandle(jlong handle) { return reinterpret_cast<PadHandle*>(handle); }

std::vector<uint8_t> bytesOf(JNIEnv* environment, jbyteArray array) {
  std::vector<uint8_t> bytes;
  if (array != nullptr) {
    bytes.resize(static_cast<size_t>(environment->GetArrayLength(array)));
    environment->GetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()),
                                    reinterpret_cast<jbyte*>(bytes.data()));
  }
  return bytes;
}

std::string stringOf(JNIEnv* environment, jstring text) {
  const char* characters = environment->GetStringUTFChars(text, nullptr);
  std::string result(characters);
  environment->ReleaseStringUTFChars(text, characters);
  return result;
}

void publish(JNIEnv* environment, const PadHandle& handle, const PixelRect& changed, jobject output,
             jintArray rect) {
  auto* pixels = static_cast<uint8_t*>(environment->GetDirectBufferAddress(output));
  const jlong capacity = environment->GetDirectBufferCapacity(output);
  const jlong needed = static_cast<jlong>(handle.pad.width()) * handle.pad.height() * 4;
  if (pixels == nullptr || capacity < needed) {
    NANQUIM_LOG_ERROR("Brush pad: invalid pixel buffer");
    return;
  }
  handle.pad.writeRgba8(changed, pixels);
  const jint values[4] = {changed.left, changed.top, changed.right, changed.bottom};
  environment->SetIntArrayRegion(rect, 0, 4, values);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_mobyle_nanquim_engine_BrushPad_nativeCreate(JNIEnv*, jclass, jint width,
                                                                      jint height) {
  return reinterpret_cast<jlong>(new PadHandle{BrushPad(width, height), nullptr, std::nullopt, 0});
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_BrushPad_nativeDestroy(JNIEnv*, jclass,
                                                                      jlong handle) {
  delete fromHandle(handle);
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_BrushPad_nativeSetBrush(JNIEnv* environment,
                                                                          jclass, jlong handle,
                                                                          jstring brushJson,
                                                                          jbyteArray shapePng,
                                                                          jbyteArray grainPng) {
  std::string error;
  auto brush =
      nanquim::stroke::loadBrush(stringOf(environment, brushJson), bytesOf(environment, shapePng),
                                 bytesOf(environment, grainPng), error);
  if (!brush) {
    return environment->NewStringUTF(error.c_str());
  }
  fromHandle(handle)->brush = std::move(brush);
  return nullptr;
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_BrushPad_nativeSetPressureCurve(JNIEnv* environment,
                                                                               jclass, jlong handle,
                                                                               jfloatArray points) {
  std::optional<nanquim::stroke::Curve> curve;
  if (points != nullptr) {
    const jsize count = environment->GetArrayLength(points);
    std::vector<jfloat> values(static_cast<size_t>(count));
    environment->GetFloatArrayRegion(points, 0, count, values.data());
    std::vector<std::pair<double, double>> pairs;
    for (size_t index = 0; index + 1 < values.size(); index += 2) {
      pairs.emplace_back(values[index], values[index + 1]);
    }
    curve = nanquim::stroke::Curve(std::move(pairs));
  }
  fromHandle(handle)->pressureCurve = std::move(curve);
}

JNIEXPORT jboolean JNICALL Java_com_mobyle_nanquim_engine_BrushPad_nativeBeginStroke(
    JNIEnv*, jclass, jlong handle, jfloat sizePixels, jfloat opacity, jfloat red, jfloat green,
    jfloat blue, jfloat secondaryRed, jfloat secondaryGreen, jfloat secondaryBlue, jboolean erase) {
  PadHandle& pad = *fromHandle(handle);
  if (!pad.brush) {
    return JNI_FALSE;
  }
  nanquim::stroke::StrokeSettings settings;
  settings.sizePixels = std::max(sizePixels, 1.0F);
  settings.opacity = opacity;
  settings.color = {red, green, blue, 1};
  settings.secondaryColor = {secondaryRed, secondaryGreen, secondaryBlue};
  settings.seed = nanquim::stroke::strokeSeed("brush-pad", pad.strokeIndex++);
  settings.pressureCurve = pad.pressureCurve;
  pad.pad.beginStroke(pad.brush, settings, erase == JNI_TRUE);
  return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_BrushPad_nativeAddSamples(
    JNIEnv* environment, jclass, jlong handle, jobject samples, jint byteCount, jobject output,
    jintArray rect) {
  const auto* data = static_cast<const uint8_t*>(environment->GetDirectBufferAddress(samples));
  std::vector<nanquim::stroke::InputSample> decoded;
  if (data == nullptr || byteCount < 0 ||
      !nanquim::stroke::decodePackedSamples(data, static_cast<size_t>(byteCount), decoded)) {
    NANQUIM_LOG_ERROR("Brush pad: invalid samples");
    return;
  }
  PadHandle& pad = *fromHandle(handle);
  publish(environment, pad, pad.pad.addSamples(decoded), output, rect);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_BrushPad_nativeEndStroke(JNIEnv* environment, jclass,
                                                                        jlong handle,
                                                                        jobject output,
                                                                        jintArray rect) {
  PadHandle& pad = *fromHandle(handle);
  publish(environment, pad, pad.pad.endStroke(), output, rect);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_BrushPad_nativeClear(JNIEnv* environment, jclass,
                                                                    jlong handle, jobject output,
                                                                    jintArray rect) {
  PadHandle& pad = *fromHandle(handle);
  pad.pad.clear();
  publish(environment, pad, PixelRect{0, 0, pad.pad.width(), pad.pad.height()}, output, rect);
}

JNIEXPORT jbyteArray JNICALL Java_com_mobyle_nanquim_engine_BrushPad_nativeRenderThumbnail(
    JNIEnv* environment, jclass, jstring brushJson, jbyteArray shapePng, jbyteArray grainPng,
    jfloat previewSize, jboolean stampPreview, jint width, jint height) {
  std::string error;
  auto brush =
      nanquim::stroke::loadBrush(stringOf(environment, brushJson), bytesOf(environment, shapePng),
                                 bytesOf(environment, grainPng), error);
  if (!brush || width < 1 || height < 1) {
    NANQUIM_LOG_ERROR("Brush thumbnail: %s", error.c_str());
    return nullptr;
  }
  const std::vector<uint8_t> pixels = nanquim::reference::renderBrushThumbnail(
      *brush, previewSize, stampPreview == JNI_TRUE, width, height);
  jbyteArray result = environment->NewByteArray(static_cast<jsize>(pixels.size()));
  environment->SetByteArrayRegion(result, 0, static_cast<jsize>(pixels.size()),
                                  reinterpret_cast<const jbyte*>(pixels.data()));
  return result;
}

JNIEXPORT jbyteArray JNICALL Java_com_mobyle_nanquim_engine_BrushPad_nativeRenderThumbnailFrames(
    JNIEnv* environment, jclass, jstring brushJson, jbyteArray shapePng, jbyteArray grainPng,
    jfloat previewSize, jboolean stampPreview, jint width, jint height, jint frameCount) {
  std::string error;
  auto brush =
      nanquim::stroke::loadBrush(stringOf(environment, brushJson), bytesOf(environment, shapePng),
                                 bytesOf(environment, grainPng), error);
  if (!brush || width < 1 || height < 1 || frameCount < 1) {
    NANQUIM_LOG_ERROR("Brush thumbnail frames: %s", error.c_str());
    return nullptr;
  }
  const std::vector<uint8_t> frames = nanquim::reference::renderBrushThumbnailFrames(
      *brush, previewSize, stampPreview == JNI_TRUE, width, height, frameCount);
  jbyteArray result = environment->NewByteArray(static_cast<jsize>(frames.size()));
  environment->SetByteArrayRegion(result, 0, static_cast<jsize>(frames.size()),
                                  reinterpret_cast<const jbyte*>(frames.data()));
  return result;
}

JNIEXPORT jfloatArray JNICALL Java_com_mobyle_nanquim_engine_BrushPad_nativeTipFootprint(
    JNIEnv* environment, jclass, jstring brushJson, jfloat sizePixels, jfloat pressure,
    jfloat viewRotation) {
  std::string error;
  const std::string text = stringOf(environment, brushJson);
  auto brush = nanquim::stroke::parseBrush(std::string_view(text), error);
  if (!brush) {
    NANQUIM_LOG_ERROR("Tip footprint: %s", error.c_str());
    return nullptr;
  }
  nanquim::stroke::StrokeSettings settings;
  settings.sizePixels = sizePixels;
  settings.viewRotation = viewRotation;
  const auto footprint = nanquim::stroke::tipFootprint(std::move(*brush), settings, pressure);
  const jfloat values[3] = {static_cast<jfloat>(footprint.diameter),
                            static_cast<jfloat>(footprint.angle),
                            static_cast<jfloat>(footprint.roundness)};
  jfloatArray result = environment->NewFloatArray(3);
  environment->SetFloatArrayRegion(result, 0, 3, values);
  return result;
}

JNIEXPORT jbyteArray JNICALL Java_com_mobyle_nanquim_engine_BrushPad_nativeAutoRepeat(
    JNIEnv* environment, jclass, jbyteArray png, jint outputSize, jfloat patchScale,
    jfloat seamOverlap, jboolean mirroring, jboolean ignoreLowBands, jfloat maskHardness,
    jboolean pyramidBlending, jlong seed, jobject listener) {
  const std::vector<uint8_t> bytes = bytesOf(environment, png);
  std::string error;
  const auto texture = nanquim::stroke::decodeGrayTexture(
      bytes.data(), bytes.size(), nanquim::stroke::kMaximumGrainTexturePixels, error);
  if (!texture) {
    NANQUIM_LOG_ERROR("Auto repeat: %s", error.c_str());
    return nullptr;
  }
  nanquim::stroke::AutoRepeatParameters parameters;
  parameters.outputSize = outputSize;
  parameters.patchScale = patchScale;
  parameters.seamOverlap = seamOverlap;
  parameters.mirroring = mirroring == JNI_TRUE;
  parameters.ignoreLowBands = ignoreLowBands == JNI_TRUE;
  parameters.maskHardness = maskHardness;
  parameters.pyramidBlending = pyramidBlending == JNI_TRUE;
  parameters.seed = static_cast<uint64_t>(seed);
  jmethodID onProgress = nullptr;
  if (listener != nullptr) {
    onProgress =
        environment->GetMethodID(environment->GetObjectClass(listener), "onProgress", "(F)Z");
  }
  const auto result = nanquim::stroke::autoRepeat(
      texture->level(0), texture->width(), texture->height(), parameters, [&](double fraction) {
        return onProgress == nullptr ||
               environment->CallBooleanMethod(listener, onProgress,
                                              static_cast<jfloat>(fraction)) == JNI_TRUE;
      });
  if (result.pixels.empty()) {
    return nullptr;
  }
  std::vector<uint8_t> output(4 + result.pixels.size());
  for (int shift = 0; shift < 4; ++shift) {
    output[static_cast<size_t>(shift)] = static_cast<uint8_t>((result.side >> (8 * shift)) & 0xFF);
  }
  std::copy(result.pixels.begin(), result.pixels.end(), output.begin() + 4);
  jbyteArray array = environment->NewByteArray(static_cast<jsize>(output.size()));
  environment->SetByteArrayRegion(array, 0, static_cast<jsize>(output.size()),
                                  reinterpret_cast<const jbyte*>(output.data()));
  return array;
}
}
