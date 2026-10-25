#pragma once

#include <cstdint>
#include <memory>
#include <string>

namespace nanquim::formats {

enum class Dither {
  None,
  Ordered,
  FloydSteinberg,
};

struct AnimationOptions {
  int32_t width = 0;
  int32_t height = 0;
  int32_t plays = 0;
  Dither dither = Dither::FloydSteinberg;
};

class AnimationWriter {
 public:
  virtual ~AnimationWriter() = default;
  virtual bool addFrame(const uint8_t* rgba, int32_t durationMilliseconds) = 0;
  virtual bool finish() = 0;
  [[nodiscard]] virtual const std::string& error() const = 0;
};

std::unique_ptr<AnimationWriter> createGifWriter(const std::string& path,
                                                 const AnimationOptions& options);

std::unique_ptr<AnimationWriter> createApngWriter(const std::string& path,
                                                  const AnimationOptions& options);

}  // namespace nanquim::formats
