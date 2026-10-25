#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "color/color_profile.h"
#include "color/display_lut.h"
#include "core/json_writer.h"
#include "core/log.h"
#include "engine_context.h"
#include "io/document_tile.h"
#include "io/document_transaction.h"
#include "text/shaper.h"

namespace nanquim::jni {

namespace {

class JniEngineCallbacks final : public EngineCallbacks {
 public:
  JniEngineCallbacks(JavaVM* javaVm, jobject engineObject, jmethodID onGpuInfoMethod,
                     jmethodID onStatsMethod, jmethodID onErrorMethod,
                     jmethodID onSceneRenderedMethod, jmethodID onEventMethod)
      : javaVm_(javaVm),
        engineObject_(engineObject),
        onGpuInfoMethod_(onGpuInfoMethod),
        onStatsMethod_(onStatsMethod),
        onErrorMethod_(onErrorMethod),
        onSceneRenderedMethod_(onSceneRenderedMethod),
        onEventMethod_(onEventMethod) {}

  ~JniEngineCallbacks() override {
    JNIEnv* environment = nullptr;
    if (javaVm_->GetEnv(reinterpret_cast<void**>(&environment), JNI_VERSION_1_6) == JNI_OK) {
      environment->DeleteGlobalRef(engineObject_);
    }
  }

  void onGpuInfo(int32_t requestId, const std::string& json) override {
    callWithRequestIdAndText(onGpuInfoMethod_, requestId, json);
  }

  void onStats(int32_t requestId, const std::string& json) override {
    callWithRequestIdAndText(onStatsMethod_, requestId, json);
  }

  void onError(EngineErrorCode code, const std::string& message, bool recoverable) override {
    NANQUIM_LOG_ERROR("Engine error %d: %s", static_cast<int>(code), message.c_str());
    JNIEnv* environment = renderThreadEnvironment();
    if (environment == nullptr) {
      return;
    }
    jstring messageText = environment->NewStringUTF(message.c_str());
    environment->CallVoidMethod(engineObject_, onErrorMethod_, static_cast<jint>(code), messageText,
                                static_cast<jboolean>(recoverable));
    environment->DeleteLocalRef(messageText);
    clearPendingException(environment);
  }

  void onEvent(const std::string& json) override {
    JNIEnv* environment = renderThreadEnvironment();
    if (environment == nullptr) {
      return;
    }
    jstring javaText = environment->NewStringUTF(json.c_str());
    environment->CallVoidMethod(engineObject_, onEventMethod_, javaText);
    environment->DeleteLocalRef(javaText);
    clearPendingException(environment);
  }

  void onSceneRendered(int32_t requestId, int32_t width, int32_t height,
                       const std::vector<uint8_t>& pixels, const std::string& error) override {
    JNIEnv* environment = renderThreadEnvironment();
    if (environment == nullptr) {
      return;
    }
    jbyteArray javaPixels = environment->NewByteArray(static_cast<jsize>(pixels.size()));
    if (javaPixels == nullptr) {
      clearPendingException(environment);
      return;
    }
    environment->SetByteArrayRegion(javaPixels, 0, static_cast<jsize>(pixels.size()),
                                    reinterpret_cast<const jbyte*>(pixels.data()));
    jstring javaError = environment->NewStringUTF(error.c_str());
    environment->CallVoidMethod(engineObject_, onSceneRenderedMethod_, static_cast<jint>(requestId),
                                static_cast<jint>(width), static_cast<jint>(height), javaPixels,
                                javaError);
    environment->DeleteLocalRef(javaError);
    environment->DeleteLocalRef(javaPixels);
    clearPendingException(environment);
  }

  void onRenderThreadStopping() override {
    if (renderThreadEnvironment_ != nullptr) {
      javaVm_->DetachCurrentThread();
      renderThreadEnvironment_ = nullptr;
    }
  }

 private:
  void callWithRequestIdAndText(jmethodID method, int32_t requestId, const std::string& text) {
    JNIEnv* environment = renderThreadEnvironment();
    if (environment == nullptr) {
      return;
    }
    jstring javaText = environment->NewStringUTF(text.c_str());
    environment->CallVoidMethod(engineObject_, method, static_cast<jint>(requestId), javaText);
    environment->DeleteLocalRef(javaText);
    clearPendingException(environment);
  }

  JNIEnv* renderThreadEnvironment() {
    if (renderThreadEnvironment_ == nullptr &&
        javaVm_->AttachCurrentThread(&renderThreadEnvironment_, nullptr) != JNI_OK) {
      NANQUIM_LOG_ERROR("Could not attach the render thread to the JVM");
      renderThreadEnvironment_ = nullptr;
    }
    return renderThreadEnvironment_;
  }

  static void clearPendingException(JNIEnv* environment) {
    if (environment->ExceptionCheck() == JNI_TRUE) {
      environment->ExceptionDescribe();
      environment->ExceptionClear();
    }
  }

  JavaVM* javaVm_;
  jobject engineObject_;
  jmethodID onGpuInfoMethod_;
  jmethodID onStatsMethod_;
  jmethodID onErrorMethod_;
  jmethodID onSceneRenderedMethod_;
  jmethodID onEventMethod_;
  JNIEnv* renderThreadEnvironment_ = nullptr;
};

EngineContext* fromHandle(jlong handle) { return reinterpret_cast<EngineContext*>(handle); }

std::string toStdString(JNIEnv* environment, jstring text) {
  const char* characters = environment->GetStringUTFChars(text, nullptr);
  std::string result = characters != nullptr ? characters : "";
  environment->ReleaseStringUTFChars(text, characters);
  return result;
}

}  // namespace

std::vector<uint8_t> optionalBytes(JNIEnv* environment, jbyteArray array) {
  std::vector<uint8_t> bytes;
  if (array != nullptr) {
    bytes.resize(static_cast<size_t>(environment->GetArrayLength(array)));
    environment->GetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()),
                                    reinterpret_cast<jbyte*>(bytes.data()));
  }
  return bytes;
}

}  // namespace nanquim::jni

using nanquim::EngineContext;
using nanquim::jni::fromHandle;
using nanquim::jni::JniEngineCallbacks;

extern "C" {

JNIEXPORT jlong JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeCreate(JNIEnv* environment,
                                                                    jobject engineObject,
                                                                    jlong memoryBudgetBytes,
                                                                    jstring scratchDirectory,
                                                                    jint renderer) {
  JavaVM* javaVm = nullptr;
  if (environment->GetJavaVM(&javaVm) != JNI_OK) {
    return 0;
  }
  jclass engineClass = environment->GetObjectClass(engineObject);
  jmethodID onGpuInfoMethod =
      environment->GetMethodID(engineClass, "onNativeGpuInfo", "(ILjava/lang/String;)V");
  jmethodID onStatsMethod =
      environment->GetMethodID(engineClass, "onNativeStats", "(ILjava/lang/String;)V");
  jmethodID onErrorMethod =
      environment->GetMethodID(engineClass, "onNativeError", "(ILjava/lang/String;Z)V");
  jmethodID onSceneRenderedMethod =
      environment->GetMethodID(engineClass, "onNativeSceneRendered", "(III[BLjava/lang/String;)V");
  jmethodID onEventMethod =
      environment->GetMethodID(engineClass, "onNativeEvent", "(Ljava/lang/String;)V");
  environment->DeleteLocalRef(engineClass);
  if (onGpuInfoMethod == nullptr || onStatsMethod == nullptr || onErrorMethod == nullptr ||
      onSceneRenderedMethod == nullptr || onEventMethod == nullptr) {
    return 0;
  }
  nanquim::EngineConfig config;
  config.memoryBudgetBytes = memoryBudgetBytes;
  config.scratchDirectory = nanquim::jni::toStdString(environment, scratchDirectory);
  config.renderer = renderer == static_cast<jint>(nanquim::Renderer::Vulkan)
                        ? nanquim::Renderer::Vulkan
                        : nanquim::Renderer::Gles;
  auto callbacks = std::make_unique<JniEngineCallbacks>(
      javaVm, environment->NewGlobalRef(engineObject), onGpuInfoMethod, onStatsMethod,
      onErrorMethod, onSceneRenderedMethod, onEventMethod);
  return reinterpret_cast<jlong>(new EngineContext(std::move(config), std::move(callbacks)));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeDestroy(JNIEnv*, jobject,
                                                                    jlong handle) {
  delete fromHandle(handle);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeAttachSurface(JNIEnv* environment,
                                                                          jobject, jlong handle,
                                                                          jobject surface,
                                                                          jint widthPixels,
                                                                          jint heightPixels) {
  ANativeWindow* window = ANativeWindow_fromSurface(environment, surface);
  if (window == nullptr) {
    NANQUIM_LOG_ERROR("ANativeWindow_fromSurface returned null");
    return;
  }
  fromHandle(handle)->attachWindow(window, widthPixels, heightPixels);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeDetachSurface(JNIEnv*, jobject,
                                                                          jlong handle) {
  fromHandle(handle)->detachWindowAndWait();
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetClearColor(JNIEnv*, jobject,
                                                                          jlong handle, jfloat red,
                                                                          jfloat green, jfloat blue,
                                                                          jfloat alpha) {
  fromHandle(handle)->setClearColor(red, green, blue, alpha);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetLatencyTestPoint(
    JNIEnv*, jobject, jlong handle, jfloat xPixels, jfloat yPixels, jboolean visible) {
  fromHandle(handle)->setLatencyTestPoint(xPixels, yPixels, visible == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeRequestGpuInfo(JNIEnv*, jobject,
                                                                           jlong handle,
                                                                           jint requestId) {
  fromHandle(handle)->requestGpuInfo(requestId);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeOpenTestDocument(
    JNIEnv*, jobject, jlong handle, jint widthPixels, jint heightPixels, jint layerCount,
    jfloat red, jfloat green, jfloat blue, jfloat alpha) {
  fromHandle(handle)->openTestDocument(widthPixels, heightPixels, layerCount,
                                       {red, green, blue, alpha});
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeOpenDocument(
    JNIEnv* environment, jobject, jlong handle, jint widthPixels, jint heightPixels,
    jstring layerTreeJson) {
  std::string error;
  auto tree = nanquim::canvas::parseLayerTree(
      nanquim::jni::toStdString(environment, layerTreeJson), error);
  if (!tree) {
    return environment->NewStringUTF(error.c_str());
  }
  fromHandle(handle)->openDocument(widthPixels, heightPixels, std::move(*tree));
  return nullptr;
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetLayerTree(
    JNIEnv* environment, jobject, jlong handle, jstring layerTreeJson) {
  std::string error;
  auto tree = nanquim::canvas::parseLayerTree(
      nanquim::jni::toStdString(environment, layerTreeJson), error);
  if (!tree) {
    return environment->NewStringUTF(error.c_str());
  }
  fromHandle(handle)->setLayerTree(std::move(*tree));
  return nullptr;
}

namespace {

std::vector<nanquim::canvas::SaveSurface> toSurfaces(JNIEnv* environment, jintArray ids,
                                                     jobjectArray folders, jintArray formats) {
  const jsize count = environment->GetArrayLength(ids);
  std::vector<jint> idValues(static_cast<size_t>(count));
  environment->GetIntArrayRegion(ids, 0, count, idValues.data());
  std::vector<jint> formatValues(static_cast<size_t>(count));
  environment->GetIntArrayRegion(formats, 0, count, formatValues.data());
  std::vector<nanquim::canvas::SaveSurface> surfaces;
  for (jsize index = 0; index < count; ++index) {
    auto folder = static_cast<jstring>(environment->GetObjectArrayElement(folders, index));
    surfaces.push_back({static_cast<nanquim::canvas::LayerId>(idValues[static_cast<size_t>(index)]),
                        nanquim::jni::toStdString(environment, folder),
                        formatValues[static_cast<size_t>(index)] == 2
                            ? nanquim::io::DocumentTileFormat::SelectionR8
                        : formatValues[static_cast<size_t>(index)] == 1
                            ? nanquim::io::DocumentTileFormat::R8
                            : nanquim::io::DocumentTileFormat::Rgba8});
    environment->DeleteLocalRef(folder);
  }
  return surfaces;
}

std::vector<uint8_t> toBytes(JNIEnv* environment, jbyteArray array) {
  std::vector<uint8_t> bytes(static_cast<size_t>(environment->GetArrayLength(array)));
  environment->GetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()),
                                  reinterpret_cast<jbyte*>(bytes.data()));
  return bytes;
}

}  // namespace

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeOpenDocumentFolder(
    JNIEnv* environment, jobject, jlong handle, jint widthPixels, jint heightPixels,
    jstring layerTreeJson, jstring documentId, jstring folder, jintArray surfaceIds,
    jobjectArray surfaceFolders, jintArray surfaceFormats) {
  std::string error;
  auto tree = nanquim::canvas::parseLayerTree(
      nanquim::jni::toStdString(environment, layerTreeJson), error);
  if (!tree) {
    return environment->NewStringUTF(error.c_str());
  }
  fromHandle(handle)->openDocumentFolder(
      widthPixels, heightPixels, std::move(*tree),
      nanquim::jni::toStdString(environment, documentId),
      nanquim::jni::toStdString(environment, folder),
      toSurfaces(environment, surfaceIds, surfaceFolders, surfaceFormats));
  return nullptr;
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSaveDocument(
    JNIEnv* environment, jobject, jlong handle, jint requestId, jintArray surfaceIds,
    jobjectArray surfaceFolders, jintArray surfaceFormats, jobjectArray fileNames,
    jobjectArray fileContents) {
  nanquim::canvas::SaveRequest request;
  request.requestId = requestId;
  request.surfaces = toSurfaces(environment, surfaceIds, surfaceFolders, surfaceFormats);
  const jsize count = environment->GetArrayLength(fileNames);
  for (jsize index = 0; index < count; ++index) {
    auto name = static_cast<jstring>(environment->GetObjectArrayElement(fileNames, index));
    auto content = static_cast<jbyteArray>(environment->GetObjectArrayElement(fileContents, index));
    request.files.push_back(
        {nanquim::jni::toStdString(environment, name), toBytes(environment, content)});
    environment->DeleteLocalRef(name);
    environment->DeleteLocalRef(content);
  }
  fromHandle(handle)->saveDocument(std::move(request));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeWriteSurfaceTileRow(
    JNIEnv* environment, jobject, jlong handle, jint surfaceId, jint tileRow, jbyteArray pixels) {
  fromHandle(handle)->writeSurfaceTileRow(static_cast<nanquim::canvas::LayerId>(surfaceId),
                                          tileRow, toBytes(environment, pixels));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeRequestCompositeThumbnail(
    JNIEnv*, jobject, jlong handle, jint requestId, jint maxSide) {
  fromHandle(handle)->requestCompositeThumbnail(requestId, maxSide);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeRequestCompositePreview(
    JNIEnv*, jobject, jlong handle, jint requestId, jint maxSide) {
  fromHandle(handle)->requestCompositePreview(requestId, maxSide);
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_DocumentFolder_nativeRecover(JNIEnv* environment,
                                                                              jclass,
                                                                              jstring folder) {
  std::string error;
  if (nanquim::io::recoverDocumentFolder(nanquim::jni::toStdString(environment, folder), error)) {
    return nullptr;
  }
  return environment->NewStringUTF(error.c_str());
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_DocumentFolder_nativeConvertLayer(
    JNIEnv* environment, jclass, jstring source, jstring destination, jboolean fromDeep,
    jboolean toDeep, jbyteArray sourceProfile, jbyteArray destinationProfile) {
  using nanquim::canvas::PixelFormat;
  const auto formatOf = [](jboolean deep) {
    return deep == JNI_TRUE ? PixelFormat::Rgba16fPremulLinear : PixelFormat::Rgba8PremulSrgb;
  };
  const PixelFormat to = formatOf(toDeep);
  std::string error;
  const std::vector<uint8_t> fromProfile = nanquim::jni::optionalBytes(environment, sourceProfile);
  const std::vector<uint8_t> toProfile =
      nanquim::jni::optionalBytes(environment, destinationProfile);
  std::unique_ptr<nanquim::color::ProfileTransform> transform;
  nanquim::io::LayerPixelTransform convert;
  if (fromProfile != toProfile) {
    transform = nanquim::color::ProfileTransform::create(fromProfile, toProfile, error);
    if (!transform) {
      return environment->NewStringUTF(error.c_str());
    }
    const nanquim::color::ProfileTransform* profileTransform = transform.get();
    convert = [profileTransform, to](uint8_t* pixels, size_t count) {
      if (to == PixelFormat::Rgba16fPremulLinear) {
        profileTransform->convertLinearHalf(pixels, count);
      } else {
        profileTransform->convertRgba8(pixels, count);
      }
    };
  }
  if (nanquim::io::convertLayerFolder(nanquim::jni::toStdString(environment, source),
                                      nanquim::jni::toStdString(environment, destination),
                                      formatOf(fromDeep), to, error, convert)) {
    return nullptr;
  }
  return environment->NewStringUTF(error.c_str());
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_ColorProfiles_nativeRead(
    JNIEnv* environment, jclass, jbyteArray profile) {
  const std::vector<uint8_t> bytes = nanquim::jni::optionalBytes(environment, profile);
  std::string error;
  const auto info = nanquim::color::readProfile(bytes.data(), bytes.size(), error);
  nanquim::core::JsonWriter writer;
  writer.beginObject();
  if (!info) {
    writer.key("error");
    writer.stringValue(error);
  } else {
    using nanquim::color::ProfileColorSpace;
    writer.key("description");
    writer.stringValue(info->description);
    writer.key("colorSpace");
    writer.stringValue(info->colorSpace == ProfileColorSpace::Rgb    ? "rgb"
                       : info->colorSpace == ProfileColorSpace::Gray ? "gray"
                       : info->colorSpace == ProfileColorSpace::Cmyk ? "cmyk"
                                                                     : "other");
    writer.key("convertsFrom");
    writer.booleanValue(info->convertsFrom);
    writer.key("convertsTo");
    writer.booleanValue(info->convertsTo);
    writer.key("version");
    writer.integerValue(info->majorVersion);
  }
  writer.endObject();
  return environment->NewStringUTF(writer.text().c_str());
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_ColorProfiles_nativeConvertRgba8(
    JNIEnv* environment, jclass, jbyteArray pixels, jbyteArray source, jbyteArray destination,
    jboolean premultiplied) {
  std::string error;
  const auto transform = nanquim::color::ProfileTransform::create(
      nanquim::jni::optionalBytes(environment, source),
      nanquim::jni::optionalBytes(environment, destination), error);
  if (!transform) {
    return environment->NewStringUTF(error.c_str());
  }
  jbyte* data = environment->GetByteArrayElements(pixels, nullptr);
  if (data == nullptr) {
    return environment->NewStringUTF("out of memory");
  }
  const auto count = static_cast<size_t>(environment->GetArrayLength(pixels)) / 4;
  if (premultiplied == JNI_TRUE) {
    transform->convertRgba8(reinterpret_cast<uint8_t*>(data), count);
  } else {
    transform->convertStraightRgba8(reinterpret_cast<uint8_t*>(data), count);
  }
  environment->ReleaseByteArrayElements(pixels, data, 0);
  return nullptr;
}

JNIEXPORT jlong JNICALL Java_com_mobyle_nanquim_engine_CmykColors_nativeCreate(
    JNIEnv* environment, jclass, jbyteArray rgbProfile, jbyteArray cmykProfile) {
  std::string error;
  auto converter = nanquim::color::CmykColorConverter::create(
      nanquim::jni::optionalBytes(environment, rgbProfile),
      nanquim::jni::optionalBytes(environment, cmykProfile), error);
  return reinterpret_cast<jlong>(converter.release());
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_CmykColors_nativeRelease(JNIEnv*, jclass,
                                                                               jlong handle) {
  delete reinterpret_cast<nanquim::color::CmykColorConverter*>(handle);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_CmykColors_nativeToCmyk(
    JNIEnv* environment, jclass, jlong handle, jfloat red, jfloat green, jfloat blue,
    jfloatArray inks) {
  const auto values =
      reinterpret_cast<nanquim::color::CmykColorConverter*>(handle)->toCmyk({red, green, blue});
  environment->SetFloatArrayRegion(inks, 0, 4, values.data());
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_CmykColors_nativeToRgb(
    JNIEnv* environment, jclass, jlong handle, jfloat cyan, jfloat magenta, jfloat yellow,
    jfloat black, jfloatArray rgb) {
  const auto values = reinterpret_cast<nanquim::color::CmykColorConverter*>(handle)->toRgb(
      {cyan, magenta, yellow, black});
  environment->SetFloatArrayRegion(rgb, 0, 3, values.data());
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeFillSurface(
    JNIEnv*, jobject, jlong handle, jint surface, jfloat red, jfloat green, jfloat blue,
    jfloat alpha, jboolean mask, jlong historyEntry) {
  fromHandle(handle)->fillSurface(static_cast<nanquim::canvas::LayerId>(surface),
                                  {red, green, blue, alpha}, mask == JNI_TRUE, historyEntry);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeClearSurface(JNIEnv*, jobject,
                                                                         jlong handle,
                                                                         jint surface,
                                                                         jlong historyEntry) {
  fromHandle(handle)->clearSurface(static_cast<nanquim::canvas::LayerId>(surface), historyEntry);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeInvertSurface(
    JNIEnv*, jobject, jlong handle, jint surface, jboolean mask, jlong historyEntry) {
  fromHandle(handle)->invertSurface(static_cast<nanquim::canvas::LayerId>(surface),
                                    mask == JNI_TRUE, historyEntry);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSelectPolygon(
    JNIEnv* environment, jobject, jlong handle, jint selection, jfloatArray polygon, jint combine,
    jlong historyEntry) {
  const jsize count = environment->GetArrayLength(polygon);
  std::vector<jfloat> values(static_cast<size_t>(count));
  environment->GetFloatArrayRegion(polygon, 0, count, values.data());
  std::vector<nanquim::canvas::PolygonPoint> points;
  for (size_t index = 0; index + 1 < values.size(); index += 2) {
    points.push_back({values[index], values[index + 1]});
  }
  fromHandle(handle)->selectPolygon(
      static_cast<nanquim::canvas::LayerId>(selection), std::move(points),
      static_cast<nanquim::canvas::SelectionCombine>(std::clamp<jint>(combine, 0, 3)),
      historyEntry);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeFeatherSelection(
    JNIEnv*, jobject, jlong handle, jint selection, jfloat radius, jlong historyEntry) {
  fromHandle(handle)->featherSelection(static_cast<nanquim::canvas::LayerId>(selection), radius,
                                       historyEntry);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeBeginFill(
    JNIEnv*, jobject, jlong handle, jint source, jdouble seedX, jdouble seedY, jdouble threshold,
    jboolean expand, jboolean whole, jint target, jint surface, jfloat red, jfloat green,
    jfloat blue, jfloat alpha, jint selection, jint combine, jint operation) {
  nanquim::FillRequest request;
  request.operation =
      static_cast<nanquim::FillRequest::Operation>(std::clamp<jint>(operation, 0, 2));
  request.source = static_cast<nanquim::canvas::LayerId>(source);
  request.seedX = seedX;
  request.seedY = seedY;
  request.threshold = threshold;
  request.expand = expand == JNI_TRUE;
  request.whole = whole == JNI_TRUE;
  request.target = static_cast<nanquim::FillRequest::Target>(std::clamp<jint>(target, 0, 2));
  request.surface = static_cast<nanquim::canvas::LayerId>(surface);
  request.color = {red, green, blue, alpha};
  if (selection > 0) {
    request.selection = static_cast<nanquim::canvas::LayerId>(selection);
  }
  request.combine = static_cast<nanquim::canvas::SelectionCombine>(std::clamp<jint>(combine, 0, 3));
  fromHandle(handle)->beginFill(request);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetFillThreshold(JNIEnv*, jobject,
                                                                             jlong handle,
                                                                             jdouble threshold) {
  fromHandle(handle)->setFillThreshold(threshold);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeEndFill(JNIEnv*, jobject, jlong handle,
                                                                    jboolean commit,
                                                                    jlong historyEntry) {
  fromHandle(handle)->endFill(commit == JNI_TRUE, historyEntry);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeCopyMasked(JNIEnv*, jobject,
                                                                       jlong handle, jint source,
                                                                       jint destination,
                                                                       jint selection) {
  fromHandle(handle)->copyMasked(static_cast<nanquim::canvas::LayerId>(source),
                                 static_cast<nanquim::canvas::LayerId>(destination),
                                 static_cast<nanquim::canvas::LayerId>(selection));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeBeginTransform(
    JNIEnv*, jobject, jlong handle, jint layer, jint selection, jint requestId) {
  fromHandle(handle)->beginTransform(
      static_cast<nanquim::canvas::LayerId>(layer),
      selection > 0 ? std::optional(static_cast<nanquim::canvas::LayerId>(selection))
                    : std::nullopt,
      requestId);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetTransformMesh(
    JNIEnv* environment, jobject, jlong handle, jfloatArray mesh, jint filter) {
  const jsize count = environment->GetArrayLength(mesh);
  std::vector<jfloat> values(static_cast<size_t>(count));
  environment->GetFloatArrayRegion(mesh, 0, count, values.data());
  std::vector<nanquim::canvas::MeshVertex> triangles;
  const size_t vertexCount = values.size() / 5 / 3 * 3;
  triangles.reserve(vertexCount);
  for (size_t index = 0; index < vertexCount; ++index) {
    const float* vertex = values.data() + index * 5;
    triangles.push_back({vertex[0], vertex[1], vertex[2], vertex[3], vertex[4]});
  }
  fromHandle(handle)->setTransformMesh(
      std::move(triangles),
      static_cast<nanquim::canvas::TransformFilter>(std::clamp<jint>(filter, 0, 2)));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeEndTransform(JNIEnv*, jobject,
                                                                         jlong handle,
                                                                         jboolean commit,
                                                                         jlong historyEntry) {
  fromHandle(handle)->endTransform(commit == JNI_TRUE, historyEntry);
}

namespace {

nanquim::adjust::Parameters adjustmentParameters(JNIEnv* environment, jint filter,
                                                 jfloatArray values, jfloatArray tableData) {
  nanquim::adjust::Parameters parameters;
  parameters.filter = static_cast<nanquim::adjust::Filter>(filter);
  const jsize count = std::min<jsize>(environment->GetArrayLength(values),
                                      static_cast<jsize>(nanquim::adjust::kParameterCount));
  environment->GetFloatArrayRegion(values, 0, count, parameters.values.data());
  if (nanquim::adjust::usesTable(parameters.filter)) {
    std::vector<float> data(static_cast<size_t>(environment->GetArrayLength(tableData)));
    environment->GetFloatArrayRegion(tableData, 0, static_cast<jsize>(data.size()), data.data());
    auto table = nanquim::adjust::bakeTable(parameters.filter, data);
    if (!table) {
      table = nanquim::adjust::bakeTable(parameters.filter,
                                         nanquim::adjust::defaultTableData(parameters.filter));
    }
    parameters.table = std::make_shared<const nanquim::adjust::Table>(std::move(*table));
  }
  return parameters;
}

}  // namespace

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeBeginAdjustment(
    JNIEnv* environment, jobject, jlong handle, jint layer, jint selection, jint mask, jint filter,
    jfloatArray values, jfloatArray tableData, jfloat amount) {
  fromHandle(handle)->beginAdjustment(
      static_cast<nanquim::canvas::LayerId>(layer),
      selection > 0 ? std::optional(static_cast<nanquim::canvas::LayerId>(selection))
                    : std::nullopt,
      mask > 0 ? std::optional(static_cast<nanquim::canvas::LayerId>(mask)) : std::nullopt,
      adjustmentParameters(environment, filter, values, tableData), amount);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetAdjustment(
    JNIEnv* environment, jobject, jlong handle, jint filter, jfloatArray values,
    jfloatArray tableData, jfloat amount) {
  fromHandle(handle)->setAdjustment(adjustmentParameters(environment, filter, values, tableData),
                                    amount);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeEndAdjustment(JNIEnv*, jobject,
                                                                          jlong handle,
                                                                          jboolean commit,
                                                                          jlong historyEntry) {
  fromHandle(handle)->endAdjustment(commit == JNI_TRUE, historyEntry);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeBeginLiquifyStroke(
    JNIEnv*, jobject, jlong handle, jint mode, jfloat size, jfloat pressure, jfloat distortion,
    jfloat momentum, jboolean usePressure, jint seed) {
  nanquim::adjust::LiquifyBrush brush;
  brush.mode = static_cast<nanquim::adjust::LiquifyMode>(
      std::clamp<jint>(mode, 0, static_cast<jint>(nanquim::adjust::LiquifyMode::Reconstruct)));
  brush.size = size;
  brush.pressure = pressure;
  brush.distortion = distortion;
  brush.momentum = momentum;
  brush.usePressure = usePressure == JNI_TRUE;
  brush.seed = static_cast<uint32_t>(seed);
  fromHandle(handle)->beginLiquifyStroke(brush);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeAddLiquifySamples(JNIEnv* environment,
                                                                              jobject, jlong handle,
                                                                              jfloatArray samples) {
  const jsize count = environment->GetArrayLength(samples);
  std::vector<float> values(static_cast<size_t>(count));
  environment->GetFloatArrayRegion(samples, 0, count, values.data());
  std::vector<nanquim::adjust::LiquifySample> decoded;
  for (size_t offset = 0; offset + 4 <= values.size(); offset += 4) {
    decoded.push_back({values[offset], values[offset + 1], values[offset + 2], values[offset + 3]});
  }
  fromHandle(handle)->addLiquifySamples(std::move(decoded));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeEndLiquifyStroke(JNIEnv*, jobject,
                                                                             jlong handle,
                                                                             jboolean cancel) {
  if (cancel == JNI_TRUE) {
    fromHandle(handle)->cancelLiquifyStroke();
  } else {
    fromHandle(handle)->endLiquifyStroke();
  }
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeLiquifyStep(JNIEnv*, jobject,
                                                                        jlong handle,
                                                                        jboolean undo) {
  fromHandle(handle)->liquifyStep(undo == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeScaleLiquify(JNIEnv*, jobject,
                                                                         jlong handle,
                                                                         jfloat factor) {
  fromHandle(handle)->scaleLiquify(factor);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeRemapSurface(
    JNIEnv* environment, jobject, jlong handle, jint surface, jint kind, jfloatArray mesh,
    jint filter, jint newWidth, jint newHeight, jlong historyEntry) {
  const jsize count = environment->GetArrayLength(mesh);
  std::vector<jfloat> values(static_cast<size_t>(count));
  environment->GetFloatArrayRegion(mesh, 0, count, values.data());
  std::vector<nanquim::canvas::MeshVertex> triangles;
  const size_t vertexCount = values.size() / 5 / 3 * 3;
  for (size_t index = 0; index < vertexCount; ++index) {
    const float* vertex = values.data() + index * 5;
    triangles.push_back({vertex[0], vertex[1], vertex[2], vertex[3], vertex[4]});
  }
  fromHandle(handle)->remapSurface(
      static_cast<nanquim::canvas::LayerId>(surface), std::clamp<jint>(kind, 0, 2),
      std::move(triangles),
      static_cast<nanquim::canvas::TransformFilter>(std::clamp<jint>(filter, 0, 2)), newWidth,
      newHeight, historyEntry);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetCanvasSize(JNIEnv*, jobject,
                                                                          jlong handle, jint width,
                                                                          jint height) {
  fromHandle(handle)->setCanvasSize(width, height);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetSelectionDisplay(
    JNIEnv*, jobject, jlong handle, jint selectionId, jboolean dimOutside) {
  fromHandle(handle)->setSelectionDisplay(
      selectionId > 0 ? std::optional(static_cast<nanquim::canvas::LayerId>(selectionId))
                      : std::nullopt,
      dimOutside == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeInvertSelection(JNIEnv*, jobject,
                                                                            jlong handle,
                                                                            jint selection,
                                                                            jlong historyEntry) {
  fromHandle(handle)->invertSelection(static_cast<nanquim::canvas::LayerId>(selection),
                                      historyEntry);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSelectContents(
    JNIEnv*, jobject, jlong handle, jint selection, jint layer, jlong historyEntry) {
  fromHandle(handle)->selectContents(static_cast<nanquim::canvas::LayerId>(selection),
                                     static_cast<nanquim::canvas::LayerId>(layer), historyEntry);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeCopySurface(JNIEnv*, jobject,
                                                                        jlong handle,
                                                                        jint source,
                                                                        jint destination) {
  fromHandle(handle)->copySurface(static_cast<nanquim::canvas::LayerId>(source),
                                  static_cast<nanquim::canvas::LayerId>(destination));
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeComposeIntoSurface(
    JNIEnv* environment, jobject, jlong handle, jint target, jstring layerTreeJson,
    jlong historyEntry) {
  std::string error;
  auto tree = nanquim::canvas::parseLayerTree(
      nanquim::jni::toStdString(environment, layerTreeJson), error);
  if (!tree) {
    return environment->NewStringUTF(error.c_str());
  }
  fromHandle(handle)->composeIntoSurface(static_cast<nanquim::canvas::LayerId>(target),
                                         std::move(*tree), historyEntry);
  return nullptr;
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeDiscardSurfaces(JNIEnv* environment,
                                                                            jobject, jlong handle,
                                                                            jintArray surfaces) {
  std::vector<jint> values(static_cast<size_t>(environment->GetArrayLength(surfaces)));
  environment->GetIntArrayRegion(surfaces, 0, static_cast<jsize>(values.size()), values.data());
  std::vector<nanquim::canvas::LayerId> ids(values.begin(), values.end());
  fromHandle(handle)->discardSurfaces(std::move(ids));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativePrefetchSurfaces(JNIEnv* environment,
                                                                             jobject, jlong handle,
                                                                             jintArray surfaces) {
  std::vector<jint> values(static_cast<size_t>(environment->GetArrayLength(surfaces)));
  environment->GetIntArrayRegion(surfaces, 0, static_cast<jsize>(values.size()), values.data());
  std::vector<nanquim::canvas::LayerId> ids(values.begin(), values.end());
  fromHandle(handle)->prefetchSurfaces(std::move(ids));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeApplyHistory(JNIEnv*, jobject,
                                                                         jlong handle,
                                                                         jlong historyEntry,
                                                                         jboolean undo) {
  fromHandle(handle)->applyHistory(historyEntry, undo == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeReleaseHistory(JNIEnv* environment,
                                                                           jobject, jlong handle,
                                                                           jlongArray entries) {
  std::vector<jlong> values(static_cast<size_t>(environment->GetArrayLength(entries)));
  environment->GetLongArrayRegion(entries, 0, static_cast<jsize>(values.size()), values.data());
  fromHandle(handle)->releaseHistory(std::vector<int64_t>(values.begin(), values.end()));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetHistoryRamBudget(JNIEnv*, jobject,
                                                                                jlong handle,
                                                                                jlong bytes) {
  fromHandle(handle)->setHistoryRamBudget(bytes);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeCloseDocument(JNIEnv*, jobject,
                                                                          jlong handle) {
  fromHandle(handle)->closeDocument();
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetLayerVisible(JNIEnv*, jobject,
                                                                            jlong handle,
                                                                            jint layerId,
                                                                            jboolean visible) {
  fromHandle(handle)->setLayerVisible(static_cast<nanquim::canvas::LayerId>(layerId),
                                      visible == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetLayerOpacity(JNIEnv*, jobject,
                                                                            jlong handle,
                                                                            jint layerId,
                                                                            jfloat opacity) {
  fromHandle(handle)->setLayerOpacity(static_cast<nanquim::canvas::LayerId>(layerId), opacity);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetActiveLayer(JNIEnv*, jobject,
                                                                           jlong handle,
                                                                           jint layerId) {
  fromHandle(handle)->setActiveLayer(static_cast<nanquim::canvas::LayerId>(layerId));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetBackground(JNIEnv*, jobject,
                                                                          jlong handle, jfloat red,
                                                                          jfloat green, jfloat blue,
                                                                          jfloat alpha,
                                                                          jboolean hidden) {
  fromHandle(handle)->setBackground({red, green, blue, alpha}, hidden == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeFillRandomTiles(
    JNIEnv*, jobject, jlong handle, jint layerId, jint tileCount, jint seed) {
  fromHandle(handle)->fillRandomTiles(static_cast<nanquim::canvas::LayerId>(layerId), tileCount,
                                      static_cast<uint32_t>(seed));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetCamera(JNIEnv*, jobject,
                                                                      jlong handle, jdouble a,
                                                                      jdouble b, jdouble c,
                                                                      jdouble d, jdouble tx,
                                                                      jdouble ty) {
  fromHandle(handle)->setCamera(nanquim::core::AffineTransform{a, b, c, d, tx, ty});
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetViewportStyle(
    JNIEnv*, jobject, jlong handle, jfloat surroundRed, jfloat surroundGreen, jfloat surroundBlue,
    jfloat shadowRed, jfloat shadowGreen, jfloat shadowBlue, jfloat shadowStrength,
    jfloat shadowRadiusPixels, jboolean showPixels) {
  nanquim::canvas::ViewportStyle style;
  style.surroundColor = {surroundRed, surroundGreen, surroundBlue};
  style.shadowColor = {shadowRed, shadowGreen, shadowBlue, shadowStrength};
  style.shadowRadiusPixels = shadowRadiusPixels;
  style.showPixels = showPixels == JNI_TRUE;
  fromHandle(handle)->setViewportStyle(style);
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetDisplayColor(
    JNIEnv* environment, jobject, jlong handle, jbyteArray documentProfile, jbyteArray proofProfile,
    jboolean displayP3) {
  const std::vector<uint8_t> profile = nanquim::jni::optionalBytes(environment, documentProfile);
  const std::vector<uint8_t> proof = nanquim::jni::optionalBytes(environment, proofProfile);
  const auto display = displayP3 == JNI_TRUE ? nanquim::color::DisplaySpace::DisplayP3
                                             : nanquim::color::DisplaySpace::Srgb;
  std::string error;
  std::vector<uint16_t> lut;
  if (nanquim::color::needsDisplayLut(profile, proof, display)) {
    lut = nanquim::color::buildDisplayLut(profile, proof, display, error);
  }
  fromHandle(handle)->setDisplayColor(std::move(lut), displayP3 == JNI_TRUE);
  return error.empty() ? nullptr : environment->NewStringUTF(error.c_str());
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetDirtyTileOverlay(JNIEnv*, jobject,
                                                                                jlong handle,
                                                                                jboolean enabled) {
  fromHandle(handle)->setDirtyTileOverlay(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeKeepGpuAwake(JNIEnv*, jobject,
                                                                                jlong handle,
                                                                                jint milliseconds) {
  fromHandle(handle)->keepGpuAwake(std::chrono::milliseconds(milliseconds));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetMemoryBudget(JNIEnv*, jobject,
                                                                            jlong handle,
                                                                            jlong bytes) {
  fromHandle(handle)->setMemoryBudget(bytes);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeRequestStats(JNIEnv*, jobject,
                                                                         jlong handle,
                                                                         jint requestId) {
  fromHandle(handle)->requestStats(requestId);
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetBrush(
    JNIEnv* environment, jobject, jlong handle, jint tool, jstring brushJson, jbyteArray shapePng,
    jbyteArray grainPng, jstring dualJson, jbyteArray dualShapePng, jbyteArray dualGrainPng) {
  auto bytesOf = [environment](jbyteArray array) {
    std::vector<uint8_t> bytes;
    if (array != nullptr) {
      bytes.resize(static_cast<size_t>(environment->GetArrayLength(array)));
      environment->GetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()),
                                      reinterpret_cast<jbyte*>(bytes.data()));
    }
    return bytes;
  };
  if (tool < 0 || tool >= nanquim::kStrokeToolCount) {
    return environment->NewStringUTF("unknown tool");
  }
  std::shared_ptr<const nanquim::stroke::LoadedBrush> brush;
  if (brushJson != nullptr) {
    std::string error;
    brush = nanquim::stroke::loadBrush(nanquim::jni::toStdString(environment, brushJson),
                                       bytesOf(shapePng), bytesOf(grainPng), error);
    if (!brush) {
      return environment->NewStringUTF(error.c_str());
    }
    if (dualJson != nullptr) {
      auto dual = nanquim::stroke::loadBrush(nanquim::jni::toStdString(environment, dualJson),
                                             bytesOf(dualShapePng), bytesOf(dualGrainPng), error);
      if (!dual) {
        return environment->NewStringUTF(("dual brush: " + error).c_str());
      }
      auto combined = std::make_shared<nanquim::stroke::LoadedBrush>(*brush);
      combined->dual = std::move(dual);
      brush = std::move(combined);
    }
  }
  fromHandle(handle)->setBrush(static_cast<nanquim::StrokeTool>(tool), std::move(brush));
  return nullptr;
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetPressureCurve(
    JNIEnv* environment, jobject, jlong handle, jfloatArray points) {
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
  fromHandle(handle)->setPressureCurve(std::move(curve));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetStylusRoll(JNIEnv*, jobject,
                                                                          jlong handle,
                                                                          jboolean reported) {
  fromHandle(handle)->setStylusRoll(reported == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetGlobalSmoothing(JNIEnv*, jobject,
                                                                               jlong handle,
                                                                               jfloat amount) {
  fromHandle(handle)->setGlobalSmoothing(amount);
}

namespace {

std::string toUtf8(JNIEnv* environment, jstring text) {
  const jsize length = environment->GetStringLength(text);
  std::u16string units(static_cast<size_t>(length), u'\0');
  environment->GetStringRegion(text, 0, length, reinterpret_cast<jchar*>(units.data()));
  std::u32string codepoints;
  codepoints.reserve(units.size());
  for (size_t index = 0; index < units.size(); ++index) {
    const char16_t unit = units[index];
    if (unit >= 0xD800 && unit <= 0xDBFF && index + 1 < units.size() &&
        units[index + 1] >= 0xDC00 && units[index + 1] <= 0xDFFF) {
      codepoints.push_back(0x10000 + ((static_cast<char32_t>(unit) - 0xD800) << 10U) +
                           (static_cast<char32_t>(units[index + 1]) - 0xDC00));
      ++index;
    } else {
      codepoints.push_back(unit);
    }
  }
  return nanquim::text::utf8FromUtf32(codepoints);
}

}  // namespace

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeRegisterFont(
    JNIEnv* environment, jobject, jlong handle, jstring fontId, jstring path, jbyteArray data,
    jint faceIndex) {
  fromHandle(handle)->registerFont(
      nanquim::jni::toStdString(environment, fontId),
      path != nullptr ? nanquim::jni::toStdString(environment, path) : std::string(),
      data != nullptr ? toBytes(environment, data) : std::vector<uint8_t>(), faceIndex);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetFontFallbacks(JNIEnv* environment,
                                                                             jobject, jlong handle,
                                                                             jobjectArray fontIds) {
  std::vector<std::string> ids;
  const jsize count = environment->GetArrayLength(fontIds);
  for (jsize index = 0; index < count; ++index) {
    auto id = static_cast<jstring>(environment->GetObjectArrayElement(fontIds, index));
    ids.push_back(nanquim::jni::toStdString(environment, id));
    environment->DeleteLocalRef(id);
  }
  fromHandle(handle)->setFontFallbacks(std::move(ids));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetTextLayer(
    JNIEnv* environment, jobject, jlong handle, jint layerId, jstring modelJson, jdouble dpi) {
  fromHandle(handle)->setTextLayer(
      static_cast<nanquim::canvas::LayerId>(layerId),
      modelJson != nullptr ? toUtf8(environment, modelJson) : std::string(), dpi);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeRequestTextGeometry(
    JNIEnv* environment, jobject, jlong handle, jint requestId, jstring modelJson, jdouble dpi) {
  fromHandle(handle)->requestTextGeometry(requestId, toUtf8(environment, modelJson), dpi);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeBeginStroke(
    JNIEnv* environment, jobject, jlong handle, jint tool, jint layerId, jfloat sizePixels,
    jfloat opacity, jfloat red, jfloat green, jfloat blue, jfloat alpha, jfloat secondaryRed,
    jfloat secondaryGreen, jfloat secondaryBlue, jboolean maskTarget, jlong historyEntry,
    jint selectionId, jboolean selectionTarget, jint cloneSource, jint cloneLayer,
    jint cloneOffsetX, jint cloneOffsetY, jdoubleArray symmetry) {
  nanquim::StrokeStyle style;
  if (symmetry != nullptr) {
    const jsize length = environment->GetArrayLength(symmetry);
    std::vector<jdouble> values(static_cast<size_t>(length));
    environment->GetDoubleArrayRegion(symmetry, 0, length, values.data());
    for (size_t index = 0; index + 6 <= values.size(); index += 6) {
      style.symmetry.push_back({values[index], values[index + 1], values[index + 2],
                                values[index + 3], values[index + 4], values[index + 5]});
    }
  }
  if (cloneSource == 1 || cloneSource == 2) {
    style.clone = nanquim::StrokeStyle::Clone{cloneSource == 1,
                                              static_cast<nanquim::canvas::LayerId>(cloneLayer),
                                              {cloneOffsetX, cloneOffsetY}};
  }
  style.historyEntry = historyEntry;
  if (selectionId > 0) {
    style.selection = static_cast<nanquim::canvas::LayerId>(selectionId);
  }
  style.selectionTarget = selectionTarget == JNI_TRUE;
  style.sizePixels = sizePixels;
  style.opacity = opacity;
  style.color = {red, green, blue, alpha};
  style.secondaryColor = {secondaryRed, secondaryGreen, secondaryBlue};
  style.maskTarget = maskTarget == JNI_TRUE;
  if (tool < 0 || tool >= nanquim::kStrokeToolCount) {
    NANQUIM_LOG_ERROR("Stroke: unknown tool %d", tool);
    return;
  }
  fromHandle(handle)->beginStroke(static_cast<nanquim::StrokeTool>(tool),
                                  static_cast<nanquim::canvas::LayerId>(layerId), style);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeAddStrokeSamples(
    JNIEnv* environment, jobject, jlong handle, jobject samples, jint byteCount) {
  const auto* data = static_cast<const uint8_t*>(environment->GetDirectBufferAddress(samples));
  const jlong capacity = environment->GetDirectBufferCapacity(samples);
  if (data == nullptr || byteCount < 0 || byteCount > capacity) {
    NANQUIM_LOG_ERROR("Stroke samples: invalid buffer");
    return;
  }
  fromHandle(handle)->addStrokeSamples(std::vector<uint8_t>(data, data + byteCount));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeReplaceStroke(JNIEnv* environment,
                                                                          jobject, jlong handle,
                                                                          jobject samples,
                                                                          jint byteCount) {
  const auto* data = static_cast<const uint8_t*>(environment->GetDirectBufferAddress(samples));
  const jlong capacity = environment->GetDirectBufferCapacity(samples);
  if (data == nullptr || byteCount < 0 || byteCount > capacity) {
    NANQUIM_LOG_ERROR("Stroke samples: invalid buffer");
    return;
  }
  fromHandle(handle)->replaceStroke(std::vector<uint8_t>(data, data + byteCount));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeEndStroke(JNIEnv*, jobject,
                                                                      jlong handle) {
  fromHandle(handle)->endStroke();
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeCancelStroke(JNIEnv*, jobject,
                                                                         jlong handle) {
  fromHandle(handle)->cancelStroke();
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSetFrontBufferEnabled(
    JNIEnv*, jobject, jlong handle, jboolean enabled) {
  fromHandle(handle)->setFrontBufferEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeResetFrameStatistics(JNIEnv*, jobject,
                                                                                 jlong handle) {
  fromHandle(handle)->resetFrameStatistics();
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeRenderScene(
    JNIEnv* environment, jobject, jlong handle, jint requestId, jstring sceneJson,
    jobjectArray names, jobjectArray contents) {
  std::map<std::string, std::vector<uint8_t>> files;
  const jsize count = environment->GetArrayLength(names);
  for (jsize index = 0; index < count; ++index) {
    auto name = static_cast<jstring>(environment->GetObjectArrayElement(names, index));
    auto content = static_cast<jbyteArray>(environment->GetObjectArrayElement(contents, index));
    std::vector<uint8_t> bytes(static_cast<size_t>(environment->GetArrayLength(content)));
    environment->GetByteArrayRegion(content, 0, static_cast<jsize>(bytes.size()),
                                    reinterpret_cast<jbyte*>(bytes.data()));
    files.emplace(nanquim::jni::toStdString(environment, name), std::move(bytes));
    environment->DeleteLocalRef(name);
    environment->DeleteLocalRef(content);
  }
  fromHandle(handle)->renderScene(requestId, nanquim::jni::toStdString(environment, sceneJson),
                                  std::move(files));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeRenderViewport(
    JNIEnv*, jobject, jlong handle, jint requestId, jint widthPixels, jint heightPixels, jdouble a,
    jdouble b, jdouble c, jdouble d, jdouble tx, jdouble ty, jboolean showPixels,
    jlong selectionSurface, jboolean dimOutsideSelection, jfloat antsPhasePixels) {
  nanquim::EngineContext::ViewportImageRequest request;
  request.widthPixels = widthPixels;
  request.heightPixels = heightPixels;
  request.canvasToScreen = nanquim::core::AffineTransform{a, b, c, d, tx, ty};
  request.showPixels = showPixels == JNI_TRUE;
  if (selectionSurface >= 0) {
    request.selection = static_cast<nanquim::canvas::LayerId>(selectionSurface);
  }
  request.dimOutsideSelection = dimOutsideSelection == JNI_TRUE;
  request.antsPhasePixels = antsPhasePixels;
  fromHandle(handle)->renderViewportImage(requestId, request);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeReadComposite(JNIEnv*, jobject,
                                                                          jlong handle,
                                                                          jint requestId,
                                                                          jboolean deep) {
  fromHandle(handle)->readComposite(requestId, deep == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeRequestThumbnails(
    JNIEnv* environment, jobject, jlong handle, jint requestId, jintArray surfaces,
    jbooleanArray masks, jint maxSide) {
  std::vector<jint> ids(static_cast<size_t>(environment->GetArrayLength(surfaces)));
  environment->GetIntArrayRegion(surfaces, 0, static_cast<jsize>(ids.size()), ids.data());
  std::vector<jboolean> maskValues(static_cast<size_t>(environment->GetArrayLength(masks)));
  environment->GetBooleanArrayRegion(masks, 0, static_cast<jsize>(maskValues.size()),
                                     maskValues.data());
  std::vector<bool> maskFlags;
  for (const jboolean value : maskValues) {
    maskFlags.push_back(value == JNI_TRUE);
  }
  fromHandle(handle)->requestThumbnails(
      requestId, std::vector<nanquim::canvas::LayerId>(ids.begin(), ids.end()),
      std::move(maskFlags), maxSide);
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeRequestTimelapseFrame(
    JNIEnv* environment, jobject, jlong handle, jint requestId, jint maxSide, jintArray excluded) {
  std::vector<jint> values(static_cast<size_t>(environment->GetArrayLength(excluded)));
  environment->GetIntArrayRegion(excluded, 0, static_cast<jsize>(values.size()), values.data());
  std::vector<nanquim::canvas::LayerId> ids(values.begin(), values.end());
  fromHandle(handle)->requestTimelapseFrame(requestId, maxSide, std::move(ids));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSampleLayerAlphas(
    JNIEnv* environment, jobject, jlong handle, jint requestId, jint canvasX, jint canvasY,
    jintArray surfaces) {
  std::vector<jint> values(static_cast<size_t>(environment->GetArrayLength(surfaces)));
  environment->GetIntArrayRegion(surfaces, 0, static_cast<jsize>(values.size()), values.data());
  std::vector<nanquim::canvas::LayerId> ids(values.begin(), values.end());
  fromHandle(handle)->sampleLayerAlphas(requestId, canvasX, canvasY, std::move(ids));
}

JNIEXPORT void JNICALL Java_com_mobyle_nanquim_engine_Engine_nativeSampleColor(JNIEnv*, jobject,
                                                                        jlong handle,
                                                                        jint requestId,
                                                                        jdouble canvasX,
                                                                        jdouble canvasY,
                                                                        jint size) {
  fromHandle(handle)->sampleColor(requestId, canvasX, canvasY, size);
}
}
