#pragma once

#include <GLES3/gl31.h>

#include <utility>

namespace nanquim::gpu::gles {

template <void (*Delete)(GLuint)>
class GlObject {
 public:
  GlObject() = default;
  explicit GlObject(GLuint name) : name_(name) {}
  GlObject(const GlObject&) = delete;
  GlObject& operator=(const GlObject&) = delete;
  GlObject(GlObject&& other) noexcept : name_(std::exchange(other.name_, 0)) {}
  GlObject& operator=(GlObject&& other) noexcept {
    if (this != &other) {
      reset();
      name_ = std::exchange(other.name_, 0);
    }
    return *this;
  }
  ~GlObject() { reset(); }

  [[nodiscard]] GLuint name() const { return name_; }
  [[nodiscard]] bool isValid() const { return name_ != 0; }

  void reset() {
    if (name_ != 0) {
      Delete(name_);
      name_ = 0;
    }
  }

 private:
  GLuint name_ = 0;
};

namespace detail {
inline void deleteTexture(GLuint name) { glDeleteTextures(1, &name); }
inline void deleteFramebuffer(GLuint name) { glDeleteFramebuffers(1, &name); }
inline void deleteBuffer(GLuint name) { glDeleteBuffers(1, &name); }
inline void deleteVertexArray(GLuint name) { glDeleteVertexArrays(1, &name); }
inline void deleteSampler(GLuint name) { glDeleteSamplers(1, &name); }
}  // namespace detail

using Texture = GlObject<detail::deleteTexture>;
using Framebuffer = GlObject<detail::deleteFramebuffer>;
using Buffer = GlObject<detail::deleteBuffer>;
using VertexArray = GlObject<detail::deleteVertexArray>;
using Sampler = GlObject<detail::deleteSampler>;

inline Texture createTexture2D(GLenum internalFormat, GLsizei width, GLsizei height) {
  GLuint name = 0;
  glGenTextures(1, &name);
  glBindTexture(GL_TEXTURE_2D, name);
  glTexStorage2D(GL_TEXTURE_2D, 1, internalFormat, width, height);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glBindTexture(GL_TEXTURE_2D, 0);
  return Texture(name);
}

inline Framebuffer createFramebuffer() {
  GLuint name = 0;
  glGenFramebuffers(1, &name);
  return Framebuffer(name);
}

inline Buffer createBuffer() {
  GLuint name = 0;
  glGenBuffers(1, &name);
  return Buffer(name);
}

inline VertexArray createVertexArray() {
  GLuint name = 0;
  glGenVertexArrays(1, &name);
  return VertexArray(name);
}

inline Sampler createSampler(GLint filter) {
  GLuint name = 0;
  glGenSamplers(1, &name);
  glSamplerParameteri(name, GL_TEXTURE_MIN_FILTER, filter);
  glSamplerParameteri(name, GL_TEXTURE_MAG_FILTER, filter);
  glSamplerParameteri(name, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glSamplerParameteri(name, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  return Sampler(name);
}

inline void attachColorTexture(GLenum target, const Framebuffer& framebuffer, GLuint texture) {
  glBindFramebuffer(target, framebuffer.name());
  glFramebufferTexture2D(target, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
}

}  // namespace nanquim::gpu::gles
