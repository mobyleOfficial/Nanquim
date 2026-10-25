#pragma once

#include <array>
#include <cstdint>
#include <vector>

#include "stroke/brush_parameters.h"
#include "stroke/dab.h"
#include "stroke/random.h"

namespace nanquim::stroke {

class ParticleSystem {
 public:
  static constexpr double kStepMilliseconds = 1000.0 / 120.0;
  static constexpr double kMaximumTailMilliseconds = 3000;

  ParticleSystem(const ParticleParameters& parameters, uint64_t strokeSeed);

  void emit(const std::vector<Dab>& dabs);

  void advance(double untilMilliseconds, std::vector<Dab>& dabs);

  [[nodiscard]] bool active(double untilMilliseconds) const;
  [[nodiscard]] bool empty() const { return particles_.empty(); }

 private:
  struct Particle {
    Dab birth;
    double bornMilliseconds = 0;
    std::array<double, 2> position{};
    std::array<double, 2> velocity{};
    bool dead = false;
  };

  ParticleParameters parameters_;
  Random random_;
  std::vector<Particle> particles_;
  size_t firstLive_ = 0;
  double nextEmissionDistance_ = 0;
  bool hasPrevious_ = false;
  std::array<double, 2> previousPosition_{};
  std::array<double, 2> direction_{1, 0};
  int64_t nextStep_ = 0;
  bool started_ = false;
};

}  // namespace nanquim::stroke
