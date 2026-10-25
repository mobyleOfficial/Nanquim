#include <jni.h>

#include <algorithm>
#include <climits>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "core/json_writer.h"
#include "formats/animation_writer.h"
#include "formats/binary_plist.h"
#include "formats/document_import.h"
#include "formats/input_source.h"
#include "formats/palette_reader.h"
#include "formats/pdf_writer.h"
#include "formats/png_writer.h"
#include "formats/procreate_reader.h"
#include "formats/psd_reader.h"
#include "formats/psd_writer.h"
#include "formats/tiff_reader.h"
#include "formats/tiff_writer.h"
#include "io/document_tile.h"
#include "io/file_system.h"

namespace {

std::string toStdString(JNIEnv* environment, jstring text) {
  const char* characters = environment->GetStringUTFChars(text, nullptr);
  std::string result = characters != nullptr ? characters : "";
  environment->ReleaseStringUTFChars(text, characters);
  return result;
}

jstring resultJson(JNIEnv* environment, bool ok, const std::string& errorCode,
                   const std::string& errorDetail) {
  nanquim::core::JsonWriter writer;
  writer.beginObject();
  writer.key("ok");
  writer.booleanValue(ok);
  writer.key("errorCode");
  writer.stringValue(errorCode);
  writer.key("errorDetail");
  writer.stringValue(errorDetail);
  writer.endObject();
  return environment->NewStringUTF(writer.text().c_str());
}

class ByteArrayComposite final : public nanquim::formats::CompositeSource {
 public:
  ByteArrayComposite(JNIEnv* environment, jbyteArray pixels, int32_t width, bool deep = false)
      : environment_(environment), pixels_(pixels), width_(width), deep_(deep) {}

  static bool sizeMatches(JNIEnv* environment, jbyteArray pixels, int32_t width, int32_t height,
                          bool deep) {
    return static_cast<int64_t>(environment->GetArrayLength(pixels)) ==
           static_cast<int64_t>(width) * height * (deep ? 8 : 4);
  }

  bool readRows(int32_t top, int32_t count, uint8_t* rgba) override {
    if (!deep_) {
      return read(top, count, rgba);
    }
    const size_t samples = static_cast<size_t>(width_) * 4 * static_cast<size_t>(count);
    deepRows_.resize(samples);
    if (!readDeepRows(top, count, deepRows_.data())) {
      return false;
    }
    for (size_t index = 0; index < samples; ++index) {
      rgba[index] = static_cast<uint8_t>((deepRows_[index] * 255U + 32767U) / 65535U);
    }
    return true;
  }

  [[nodiscard]] bool hasDeepRows() const override { return deep_; }

  bool readDeepRows(int32_t top, int32_t count, uint16_t* rgba) override {
    if (!deep_) {
      return false;
    }
    const size_t samples = static_cast<size_t>(width_) * 4 * static_cast<size_t>(count);
    bytes_.resize(samples * 2);
    if (!read(top, count, bytes_.data())) {
      return false;
    }
    for (size_t index = 0; index < samples; ++index) {
      rgba[index] = static_cast<uint16_t>(bytes_[index * 2] | (bytes_[index * 2 + 1] << 8U));
    }
    return true;
  }

 private:
  bool read(int32_t top, int32_t count, uint8_t* out) {
    const jsize rowBytes = width_ * (deep_ ? 8 : 4);
    environment_->GetByteArrayRegion(pixels_, top * rowBytes, count * rowBytes,
                                     reinterpret_cast<jbyte*>(out));
    if (environment_->ExceptionCheck() == JNI_TRUE) {
      environment_->ExceptionClear();
      return false;
    }
    return true;
  }

  JNIEnv* environment_;
  jbyteArray pixels_;
  int32_t width_;
  bool deep_;
  std::vector<uint8_t> bytes_;
  std::vector<uint16_t> deepRows_;
};

std::vector<uint8_t> optionalBytes(JNIEnv* environment, jbyteArray array) {
  if (array == nullptr) {
    return {};
  }
  std::vector<uint8_t> bytes(static_cast<size_t>(environment->GetArrayLength(array)));
  environment->GetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()),
                                  reinterpret_cast<jbyte*>(bytes.data()));
  return bytes;
}

jstring exportResultJson(JNIEnv* environment, const nanquim::formats::ExportResult& result) {
  nanquim::core::JsonWriter writer;
  writer.beginObject();
  writer.key("ok");
  writer.booleanValue(result.ok);
  writer.key("errorCode");
  writer.stringValue(result.errorCode);
  writer.key("errorDetail");
  writer.stringValue(result.errorDetail);
  writer.key("warnings");
  writer.beginArray();
  for (const nanquim::formats::ImportWarning& warning : result.warnings) {
    writer.beginObject();
    writer.key("code");
    writer.stringValue(warning.code);
    writer.key("detail");
    writer.stringValue(warning.detail);
    writer.endObject();
  }
  writer.endArray();
  writer.endObject();
  return environment->NewStringUTF(writer.text().c_str());
}

nanquim::formats::ExportResult exportFailure(const std::string& code, const std::string& detail) {
  nanquim::formats::ExportResult result;
  result.errorCode = code;
  result.errorDetail = detail;
  return result;
}

struct CmykExport {
  std::unique_ptr<nanquim::color::CmykTransform> transform;
  nanquim::formats::CmykOutput output;
  std::string error;

  [[nodiscard]] const nanquim::formats::CmykOutput* get() const {
    return transform ? &output : nullptr;
  }
};

CmykExport cmykExport(const std::vector<uint8_t>& documentProfile,
                      std::vector<uint8_t> cmykProfile) {
  CmykExport result;
  if (cmykProfile.empty()) {
    return result;
  }
  result.transform =
      nanquim::color::CmykTransform::create(documentProfile, cmykProfile, result.error);
  result.output.transform = result.transform.get();
  result.output.profile = std::move(cmykProfile);
  return result;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_InterchangeFiles_nativeImportPsd(
    JNIEnv* environment, jclass, jstring sourcePath, jstring folder, jint maximumSide,
    jint maximumLayers, jbyteArray cmykFallbackProfile, jbyteArray cmykWorkingProfile) {
  const auto source = nanquim::formats::FileInputSource::open(toStdString(environment, sourcePath));
  if (!source) {
    return resultJson(environment, false, "unreadable", "");
  }
  nanquim::formats::ImportLimits limits;
  limits.maximumSide = maximumSide;
  limits.maximumLayers = maximumLayers;
  limits.cmykFallbackProfile = optionalBytes(environment, cmykFallbackProfile);
  limits.cmykWorkingProfile = optionalBytes(environment, cmykWorkingProfile);
  const nanquim::formats::ImportResult result =
      nanquim::formats::importPsd(*source, toStdString(environment, folder), limits);
  return resultJson(environment, result.ok, result.errorCode, result.errorDetail);
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_InterchangeFiles_nativeExportPsd(
    JNIEnv* environment, jclass, jstring documentFolder, jstring layerTree,
    jstring backgroundLayerName, jbyteArray composite, jint width, jint height,
    jboolean deepComposite, jbyteArray iccProfile, jbyteArray cmykProfile, jstring outputPath) {
  const bool deep = deepComposite == JNI_TRUE;
  std::string error;
  const auto document =
      nanquim::formats::parseImportJson(toStdString(environment, layerTree), error);
  if (!document) {
    return exportResultJson(environment, exportFailure("invalidDocument", error));
  }
  if (document->width != width || document->height != height ||
      !ByteArrayComposite::sizeMatches(environment, composite, width, height, deep)) {
    return exportResultJson(environment, exportFailure("invalidDocument", "composite size"));
  }
  ByteArrayComposite source(environment, composite, width, deep);
  nanquim::formats::PsdExportOptions options;
  options.backgroundLayerName = toStdString(environment, backgroundLayerName);
  options.iccProfile = optionalBytes(environment, iccProfile);
  options.deep = deep;
  const CmykExport cmyk = cmykExport(options.iccProfile, optionalBytes(environment, cmykProfile));
  if (!cmyk.error.empty()) {
    return exportResultJson(environment, exportFailure("cmykProfileUnusable", cmyk.error));
  }
  options.cmyk = cmyk.get();
  const std::string output = toStdString(environment, outputPath);
  const nanquim::formats::ExportResult result = nanquim::formats::exportPsd(
      *document, toStdString(environment, documentFolder), source, options, output);
  if (!result.ok) {
    nanquim::io::removePath(output);
  }
  return exportResultJson(environment, result);
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_InterchangeFiles_nativeImportTiff(
    JNIEnv* environment, jclass, jstring sourcePath, jstring folder, jint maximumSide,
    jbyteArray cmykFallbackProfile, jbyteArray cmykWorkingProfile) {
  const auto source = nanquim::formats::FileInputSource::open(toStdString(environment, sourcePath));
  if (!source) {
    return resultJson(environment, false, "unreadable", "");
  }
  nanquim::formats::ImportLimits limits;
  limits.maximumSide = maximumSide;
  limits.cmykFallbackProfile = optionalBytes(environment, cmykFallbackProfile);
  limits.cmykWorkingProfile = optionalBytes(environment, cmykWorkingProfile);
  const nanquim::formats::ImportResult result =
      nanquim::formats::importTiff(*source, toStdString(environment, folder), limits);
  return resultJson(environment, result.ok, result.errorCode, result.errorDetail);
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_InterchangeFiles_nativeExportTiff(
    JNIEnv* environment, jclass, jbyteArray composite, jint width, jint height, jdouble dpi,
    jboolean deepComposite, jbyteArray iccProfile, jbyteArray cmykProfile, jstring outputPath) {
  const bool deep = deepComposite == JNI_TRUE;
  if (width <= 0 || height <= 0 ||
      !ByteArrayComposite::sizeMatches(environment, composite, width, height, deep)) {
    return exportResultJson(environment, exportFailure("invalidDocument", "composite size"));
  }
  const std::vector<uint8_t> profile = optionalBytes(environment, iccProfile);
  const CmykExport cmyk = cmykExport(profile, optionalBytes(environment, cmykProfile));
  if (!cmyk.error.empty()) {
    return exportResultJson(environment, exportFailure("cmykProfileUnusable", cmyk.error));
  }
  ByteArrayComposite source(environment, composite, width, deep);
  const std::string output = toStdString(environment, outputPath);
  const nanquim::formats::ExportResult result =
      nanquim::formats::exportTiff(width, height, dpi, source, output, deep, profile, cmyk.get());
  if (!result.ok) {
    nanquim::io::removePath(output);
  }
  return exportResultJson(environment, result);
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_InterchangeFiles_nativeExportPdf(
    JNIEnv* environment, jclass, jstring documentFolder, jstring layerTree, jbyteArray composite,
    jint width, jint height, jboolean deepComposite, jboolean layersAsPages, jboolean jpeg,
    jint jpegQuality, jstring title, jbyteArray iccProfile, jbyteArray cmykProfile,
    jstring outputPath) {
  const bool deep = deepComposite == JNI_TRUE;
  std::string error;
  const auto document =
      nanquim::formats::parseImportJson(toStdString(environment, layerTree), error);
  if (!document) {
    return exportResultJson(environment, exportFailure("invalidDocument", error));
  }
  if (document->width != width || document->height != height ||
      !ByteArrayComposite::sizeMatches(environment, composite, width, height, deep)) {
    return exportResultJson(environment, exportFailure("invalidDocument", "composite size"));
  }
  ByteArrayComposite flattened(environment, composite, width, deep);
  std::vector<std::unique_ptr<nanquim::formats::CompositeSource>> layers;
  std::vector<nanquim::formats::CompositeSource*> pages{&flattened};
  if (layersAsPages == JNI_TRUE) {
    layers = nanquim::formats::layerPages(*document, toStdString(environment, documentFolder));
    if (!layers.empty()) {
      pages.clear();
      for (const auto& page : layers) {
        pages.push_back(page.get());
      }
    }
  }
  nanquim::formats::PdfExportOptions options;
  options.jpeg = jpeg == JNI_TRUE;
  options.jpegQuality = jpegQuality;
  options.title = toStdString(environment, title);
  options.iccProfile = optionalBytes(environment, iccProfile);
  const CmykExport cmyk = cmykExport(options.iccProfile, optionalBytes(environment, cmykProfile));
  if (!cmyk.error.empty()) {
    return exportResultJson(environment, exportFailure("cmykProfileUnusable", cmyk.error));
  }
  options.cmyk = cmyk.get();
  const std::string output = toStdString(environment, outputPath);
  const nanquim::formats::ExportResult result =
      nanquim::formats::exportPdf(width, height, document->dpi, pages, options, output);
  if (!result.ok) {
    nanquim::io::removePath(output);
  }
  return exportResultJson(environment, result);
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_InterchangeFiles_nativeExportPng(
    JNIEnv* environment, jclass, jbyteArray composite, jint width, jint height, jdouble dpi,
    jboolean deepComposite, jbyteArray iccProfile, jstring outputPath) {
  const bool deep = deepComposite == JNI_TRUE;
  if (width <= 0 || height <= 0 ||
      !ByteArrayComposite::sizeMatches(environment, composite, width, height, deep)) {
    return exportResultJson(environment, exportFailure("invalidDocument", "composite size"));
  }
  ByteArrayComposite source(environment, composite, width, deep);
  const std::string output = toStdString(environment, outputPath);
  const nanquim::formats::ExportResult result = nanquim::formats::exportPng(
      width, height, dpi, source, output, deep, optionalBytes(environment, iccProfile));
  if (!result.ok) {
    nanquim::io::removePath(output);
  }
  return exportResultJson(environment, result);
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_InterchangeFiles_nativeImportProcreate(
    JNIEnv* environment, jclass, jstring sourcePath, jstring folder, jint maximumSide,
    jint maximumLayers) {
  const auto source = nanquim::formats::FileInputSource::open(toStdString(environment, sourcePath));
  if (!source) {
    return resultJson(environment, false, "unreadable", "");
  }
  nanquim::formats::ImportLimits limits;
  limits.maximumSide = maximumSide;
  limits.maximumLayers = maximumLayers;
  const nanquim::formats::ImportResult result =
      nanquim::formats::importProcreate(*source, toStdString(environment, folder), limits);
  return resultJson(environment, result.ok, result.errorCode, result.errorDetail);
}

JNIEXPORT jbyteArray JNICALL Java_com_mobyle_nanquim_engine_InterchangeFiles_nativeReadLayer(
    JNIEnv* environment, jclass, jstring surfaceFolder, jint width, jint height) {
  using nanquim::canvas::kTileSizePixels;
  if (width <= 0 || height <= 0 || static_cast<int64_t>(width) * height * 4 > INT32_MAX) {
    return nullptr;
  }
  const std::string folder = toStdString(environment, surfaceFolder);
  bool rebuilt = false;
  const nanquim::io::SurfaceIndex index = nanquim::io::readSurfaceIndex(folder, rebuilt);
  std::vector<uint8_t> pixels(static_cast<size_t>(width) * static_cast<size_t>(height) * 4, 0);
  std::vector<uint8_t> tile(nanquim::canvas::kTileBytesRgba8);
  for (const nanquim::canvas::TileCoordinate coordinate : index.tiles) {
    if (nanquim::io::readDocumentTile(folder, coordinate, nanquim::io::DocumentTileFormat::Rgba8,
                                      tile.data()) != nanquim::io::TileReadResult::Ok) {
      return nullptr;
    }
    const int32_t left = coordinate.column * kTileSizePixels;
    const int32_t top = coordinate.row * kTileSizePixels;
    for (int32_t row = 0; row < kTileSizePixels && top + row < height; ++row) {
      const int32_t columns = std::min(kTileSizePixels, width - left);
      if (columns <= 0 || left < 0 || top < 0) {
        break;
      }
      std::memcpy(pixels.data() + (static_cast<size_t>(top + row) * width + left) * 4,
                  tile.data() + static_cast<size_t>(row) * kTileSizePixels * 4,
                  static_cast<size_t>(columns) * 4);
    }
  }
  jbyteArray result = environment->NewByteArray(static_cast<jsize>(pixels.size()));
  if (result != nullptr) {
    environment->SetByteArrayRegion(result, 0, static_cast<jsize>(pixels.size()),
                                    reinterpret_cast<const jbyte*>(pixels.data()));
  }
  return result;
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_InterchangeFiles_nativeKeyedArchiveJson(
    JNIEnv* environment, jclass, jbyteArray archive) {
  const jsize length = environment->GetArrayLength(archive);
  std::vector<uint8_t> bytes(static_cast<size_t>(length));
  environment->GetByteArrayRegion(archive, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
  std::string error;
  const auto json = nanquim::formats::keyedArchiveJson(std::move(bytes), error);
  return json ? environment->NewStringUTF(json->c_str()) : nullptr;
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_InterchangeFiles_nativePaletteJson(
    JNIEnv* environment, jclass, jbyteArray palette, jbyteArray cmykProfile) {
  const jsize length = environment->GetArrayLength(palette);
  std::vector<uint8_t> bytes(static_cast<size_t>(length));
  environment->GetByteArrayRegion(palette, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
  std::string error;
  const std::vector<uint8_t> profile = optionalBytes(environment, cmykProfile);
  std::string profileError;
  const auto cmyk = profile.empty()
                        ? nullptr
                        : nanquim::color::CmykColorConverter::create({}, profile, profileError);
  const auto json = nanquim::formats::paletteJson(bytes, error, cmyk.get());
  return json ? environment->NewStringUTF(json->c_str()) : nullptr;
}

JNIEXPORT jlong JNICALL Java_com_mobyle_nanquim_engine_AnimationFileWriter_nativeOpen(
    JNIEnv* environment, jclass, jstring outputPath, jboolean gif, jint width, jint height,
    jint plays, jint dither) {
  nanquim::formats::AnimationOptions options;
  options.width = width;
  options.height = height;
  options.plays = std::max(plays, 0);
  options.dither = dither == 0   ? nanquim::formats::Dither::None
                   : dither == 1 ? nanquim::formats::Dither::Ordered
                                 : nanquim::formats::Dither::FloydSteinberg;
  const std::string path = toStdString(environment, outputPath);
  auto writer = gif == JNI_TRUE ? nanquim::formats::createGifWriter(path, options)
                                : nanquim::formats::createApngWriter(path, options);
  return reinterpret_cast<jlong>(writer.release());
}

JNIEXPORT jboolean JNICALL Java_com_mobyle_nanquim_engine_AnimationFileWriter_nativeAddFrame(
    JNIEnv* environment, jclass, jlong handle, jbyteArray rgba, jint durationMilliseconds) {
  auto* writer = reinterpret_cast<nanquim::formats::AnimationWriter*>(handle);
  jbyte* pixels = environment->GetByteArrayElements(rgba, nullptr);
  const bool ok = writer->addFrame(reinterpret_cast<const uint8_t*>(pixels), durationMilliseconds);
  environment->ReleaseByteArrayElements(rgba, pixels, JNI_ABORT);
  return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_AnimationFileWriter_nativeFinish(JNIEnv* environment,
                                                                                  jclass,
                                                                                  jlong handle) {
  std::unique_ptr<nanquim::formats::AnimationWriter> writer(
      reinterpret_cast<nanquim::formats::AnimationWriter*>(handle));
  const bool ok = writer->finish();
  return ok ? nullptr : environment->NewStringUTF(writer->error().c_str());
}

}
