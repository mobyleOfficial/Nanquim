#include "gpu/gles/gles_info.h"

#include <GLES3/gl31.h>

#include <sstream>
#include <string_view>
#include <vector>

#include "core/json_writer.h"

namespace nanquim::gpu::gles {

namespace {

std::string_view glString(GLenum name) {
  const auto* text = reinterpret_cast<const char*>(glGetString(name));
  return text != nullptr ? std::string_view(text) : std::string_view();
}

GLint glInteger(GLenum name) {
  GLint value = 0;
  glGetIntegerv(name, &value);
  return value;
}

std::vector<std::string> glExtensions() {
  std::vector<std::string> extensions;
  const GLint extensionCount = glInteger(GL_NUM_EXTENSIONS);
  extensions.reserve(static_cast<size_t>(extensionCount));
  for (GLint index = 0; index < extensionCount; ++index) {
    const auto* name =
        reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, static_cast<GLuint>(index)));
    if (name != nullptr) {
      extensions.emplace_back(name);
    }
  }
  return extensions;
}

std::vector<std::string> splitBySpaces(std::string_view text) {
  std::vector<std::string> words;
  std::istringstream stream{std::string(text)};
  for (std::string word; stream >> word;) {
    words.push_back(word);
  }
  return words;
}

void writeStringArray(core::JsonWriter& writer, const std::vector<std::string>& values) {
  writer.beginArray();
  for (const auto& value : values) {
    writer.stringValue(value);
  }
  writer.endArray();
}

}  // namespace

GlesVersion queryGlesVersion() {
  return GlesVersion{glInteger(GL_MAJOR_VERSION), glInteger(GL_MINOR_VERSION)};
}

std::string queryGlesInfoJson(EGLDisplay display) {
  const std::vector<std::string> extensions = glExtensions();
  bool supportsColorBufferHalfFloat = false;
  for (const auto& extension : extensions) {
    if (extension == "GL_EXT_color_buffer_half_float") {
      supportsColorBufferHalfFloat = true;
    }
  }

  std::vector<GLint> programBinaryFormats(
      static_cast<size_t>(glInteger(GL_NUM_PROGRAM_BINARY_FORMATS)));
  if (!programBinaryFormats.empty()) {
    glGetIntegerv(GL_PROGRAM_BINARY_FORMATS, programBinaryFormats.data());
  }

  const GlesVersion version = queryGlesVersion();
  const auto* eglVersion = eglQueryString(display, EGL_VERSION);
  const auto* eglVendor = eglQueryString(display, EGL_VENDOR);
  const auto* eglExtensions = eglQueryString(display, EGL_EXTENSIONS);

  core::JsonWriter writer;
  writer.beginObject();
  writer.key("api");
  writer.stringValue("gles");
  writer.key("vendor");
  writer.stringValue(glString(GL_VENDOR));
  writer.key("renderer");
  writer.stringValue(glString(GL_RENDERER));
  writer.key("version");
  writer.stringValue(glString(GL_VERSION));
  writer.key("versionMajor");
  writer.integerValue(version.major);
  writer.key("versionMinor");
  writer.integerValue(version.minor);
  writer.key("shadingLanguageVersion");
  writer.stringValue(glString(GL_SHADING_LANGUAGE_VERSION));
  writer.key("maxTextureSize");
  writer.integerValue(glInteger(GL_MAX_TEXTURE_SIZE));
  writer.key("maxRenderbufferSize");
  writer.integerValue(glInteger(GL_MAX_RENDERBUFFER_SIZE));
  writer.key("supportsColorBufferHalfFloat");
  writer.booleanValue(supportsColorBufferHalfFloat);
  writer.key("programBinaryFormats");
  writer.beginArray();
  for (const GLint format : programBinaryFormats) {
    writer.integerValue(format);
  }
  writer.endArray();
  writer.key("extensions");
  writeStringArray(writer, extensions);
  writer.key("eglVersion");
  writer.stringValue(eglVersion != nullptr ? eglVersion : "");
  writer.key("eglVendor");
  writer.stringValue(eglVendor != nullptr ? eglVendor : "");
  writer.key("eglExtensions");
  writeStringArray(writer, splitBySpaces(eglExtensions != nullptr ? eglExtensions : ""));
  writer.endObject();
  return writer.text();
}

}  // namespace nanquim::gpu::gles
