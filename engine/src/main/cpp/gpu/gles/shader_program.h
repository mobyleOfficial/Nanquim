#pragma once

#include <GLES3/gl31.h>

#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace nanquim::gpu::gles {

std::string glesShaderSource(std::string_view source);

class ShaderProgram {
 public:
  static std::unique_ptr<ShaderProgram> create(const char* vertexSource, const char* fragmentSource,
                                               std::string& errorMessage,
                                               bool retrievableBinary = false);

  static std::unique_ptr<ShaderProgram> createCompute(const char* computeSource,
                                                      std::string& errorMessage,
                                                      bool retrievableBinary = false);

  static std::unique_ptr<ShaderProgram> createFromBinary(GLenum format,
                                                         const std::vector<uint8_t>& binary);

  [[nodiscard]] std::vector<uint8_t> binary(GLenum& format) const;

  ShaderProgram(const ShaderProgram&) = delete;
  ShaderProgram& operator=(const ShaderProgram&) = delete;
  ShaderProgram(ShaderProgram&& other) noexcept;
  ShaderProgram& operator=(ShaderProgram&& other) noexcept;
  ~ShaderProgram();

  void use() const { glUseProgram(program_); }
  [[nodiscard]] GLuint name() const { return program_; }

  GLint uniform(const char* name) const;

 private:
  explicit ShaderProgram(GLuint program) : program_(program) {}

  GLuint program_ = 0;
  mutable std::unordered_map<std::string, GLint> uniformLocations_;
};

}  // namespace nanquim::gpu::gles
