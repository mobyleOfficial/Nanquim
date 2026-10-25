#include "gpu/gles/shader_program.h"

#include <utility>
#include <vector>

#include "core/log.h"
#include "gpu/embedded_shader_source.h"
#include "gpu/shader_source.h"

namespace nanquim::gpu::gles {

std::string glesShaderSource(std::string_view source) {
  return withShaderMacros(withEmbeddedIncludes(source), ShaderDialect::Gles);
}

namespace {

std::string shaderLog(GLuint shader) {
  GLint length = 0;
  glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
  std::vector<char> log(static_cast<size_t>(length > 0 ? length : 1), '\0');
  glGetShaderInfoLog(shader, static_cast<GLsizei>(log.size()), nullptr, log.data());
  return log.data();
}

std::string programLog(GLuint program) {
  GLint length = 0;
  glGetProgramiv(program, GL_INFO_LOG_LENGTH, &length);
  std::vector<char> log(static_cast<size_t>(length > 0 ? length : 1), '\0');
  glGetProgramInfoLog(program, static_cast<GLsizei>(log.size()), nullptr, log.data());
  return log.data();
}

GLuint compile(GLenum stage, const char* writtenSource, std::string& errorMessage) {
  const std::string text = glesShaderSource(writtenSource);
  const char* source = text.c_str();
  const GLuint shader = glCreateShader(stage);
  glShaderSource(shader, 1, &source, nullptr);
  glCompileShader(shader);
  GLint compiled = GL_FALSE;
  glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
  if (compiled != GL_TRUE) {
    const char* name = stage == GL_VERTEX_SHADER     ? "Vertex"
                       : stage == GL_FRAGMENT_SHADER ? "Fragment"
                                                     : "Compute";
    errorMessage = std::string(name) + " shader compilation failed: " + shaderLog(shader);
    glDeleteShader(shader);
    return 0;
  }
  return shader;
}

}  // namespace

std::unique_ptr<ShaderProgram> ShaderProgram::create(const char* vertexSource,
                                                     const char* fragmentSource,
                                                     std::string& errorMessage,
                                                     bool retrievableBinary) {
  const GLuint vertexShader = compile(GL_VERTEX_SHADER, vertexSource, errorMessage);
  if (vertexShader == 0) {
    return nullptr;
  }
  const GLuint fragmentShader = compile(GL_FRAGMENT_SHADER, fragmentSource, errorMessage);
  if (fragmentShader == 0) {
    glDeleteShader(vertexShader);
    return nullptr;
  }
  const GLuint program = glCreateProgram();
  glAttachShader(program, vertexShader);
  glAttachShader(program, fragmentShader);
  if (retrievableBinary) {
    glProgramParameteri(program, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL_TRUE);
  }
  glLinkProgram(program);
  glDeleteShader(vertexShader);
  glDeleteShader(fragmentShader);
  GLint linked = GL_FALSE;
  glGetProgramiv(program, GL_LINK_STATUS, &linked);
  if (linked != GL_TRUE) {
    errorMessage = "Program link failed: " + programLog(program);
    glDeleteProgram(program);
    return nullptr;
  }
  return std::unique_ptr<ShaderProgram>(new ShaderProgram(program));
}

std::unique_ptr<ShaderProgram> ShaderProgram::createCompute(const char* computeSource,
                                                            std::string& errorMessage,
                                                            bool retrievableBinary) {
  const GLuint computeShader = compile(GL_COMPUTE_SHADER, computeSource, errorMessage);
  if (computeShader == 0) {
    return nullptr;
  }
  const GLuint program = glCreateProgram();
  glAttachShader(program, computeShader);
  if (retrievableBinary) {
    glProgramParameteri(program, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL_TRUE);
  }
  glLinkProgram(program);
  glDeleteShader(computeShader);
  GLint linked = GL_FALSE;
  glGetProgramiv(program, GL_LINK_STATUS, &linked);
  if (linked != GL_TRUE) {
    errorMessage = "Program link failed: " + programLog(program);
    glDeleteProgram(program);
    return nullptr;
  }
  return std::unique_ptr<ShaderProgram>(new ShaderProgram(program));
}

std::unique_ptr<ShaderProgram> ShaderProgram::createFromBinary(GLenum format,
                                                               const std::vector<uint8_t>& binary) {
  if (binary.empty()) {
    return nullptr;
  }
  const GLuint program = glCreateProgram();
  glProgramBinary(program, format, binary.data(), static_cast<GLsizei>(binary.size()));
  GLint linked = GL_FALSE;
  glGetProgramiv(program, GL_LINK_STATUS, &linked);
  if (linked != GL_TRUE) {
    glDeleteProgram(program);
    return nullptr;
  }
  return std::unique_ptr<ShaderProgram>(new ShaderProgram(program));
}

std::vector<uint8_t> ShaderProgram::binary(GLenum& format) const {
  GLint length = 0;
  glGetProgramiv(program_, GL_PROGRAM_BINARY_LENGTH, &length);
  if (length <= 0) {
    return {};
  }
  std::vector<uint8_t> bytes(static_cast<size_t>(length));
  GLsizei written = 0;
  glGetProgramBinary(program_, length, &written, &format, bytes.data());
  bytes.resize(static_cast<size_t>(written > 0 ? written : 0));
  return bytes;
}

ShaderProgram::ShaderProgram(ShaderProgram&& other) noexcept
    : program_(std::exchange(other.program_, 0)),
      uniformLocations_(std::move(other.uniformLocations_)) {}

ShaderProgram& ShaderProgram::operator=(ShaderProgram&& other) noexcept {
  if (this != &other) {
    if (program_ != 0) {
      glDeleteProgram(program_);
    }
    program_ = std::exchange(other.program_, 0);
    uniformLocations_ = std::move(other.uniformLocations_);
  }
  return *this;
}

ShaderProgram::~ShaderProgram() {
  if (program_ != 0) {
    glDeleteProgram(program_);
  }
}

GLint ShaderProgram::uniform(const char* name) const {
  auto found = uniformLocations_.find(name);
  if (found != uniformLocations_.end()) {
    return found->second;
  }
  const GLint location = glGetUniformLocation(program_, name);
  uniformLocations_.emplace(name, location);
  return location;
}

}  // namespace nanquim::gpu::gles
