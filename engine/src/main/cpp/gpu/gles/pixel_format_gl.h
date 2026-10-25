#pragma once

#include <GLES3/gl31.h>

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string_view>

#include "canvas/pixel_format.h"
#include "core/half_float.h"

namespace nanquim::gpu::gles {

inline bool halfFloatRenderable() {
  static const bool renderable = [] {
    GLint count = 0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &count);
    for (GLint index = 0; index < count; ++index) {
      const auto* extension =
          reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, static_cast<GLuint>(index)));
      if (extension != nullptr &&
          (std::string_view(extension) == "GL_EXT_color_buffer_half_float" ||
           std::string_view(extension) == "GL_EXT_color_buffer_float")) {
        return true;
      }
    }
    return false;
  }();
  return renderable;
}

inline GLenum textureFormat(canvas::PixelFormat format) {
  return format == canvas::PixelFormat::Rgba16fPremulLinear ? GL_RGBA16F : GL_RGBA8;
}

inline GLenum pixelType(canvas::PixelFormat format) {
  return format == canvas::PixelFormat::Rgba16fPremulLinear ? GL_HALF_FLOAT : GL_UNSIGNED_BYTE;
}

struct ReadLayout {
  GLenum type = GL_UNSIGNED_BYTE;
  int32_t bytesPerPixel = 4;
};

inline ReadLayout queryReadLayout(canvas::PixelFormat format) {
  if (format == canvas::PixelFormat::Rgba8PremulSrgb) {
    return {GL_UNSIGNED_BYTE, 4};
  }
  GLint readFormat = 0;
  GLint readType = 0;
  glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_FORMAT, &readFormat);
  glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_TYPE, &readType);
  if (readFormat == GL_RGBA && readType == GL_HALF_FLOAT) {
    return {GL_HALF_FLOAT, 8};
  }
  return {GL_FLOAT, 16};
}

inline void readToFormat(const void* read, const ReadLayout& layout, uint8_t* out,
                         size_t pixelCount) {
  if (layout.type != GL_FLOAT) {
    std::memcpy(out, read, pixelCount * static_cast<size_t>(layout.bytesPerPixel));
    return;
  }
  const auto* floats = static_cast<const float*>(read);
  for (size_t index = 0; index < pixelCount * 4; ++index) {
    const uint16_t half = core::floatToHalf(floats[index]);
    std::memcpy(out + index * 2, &half, sizeof(half));
  }
}

}  // namespace nanquim::gpu::gles
