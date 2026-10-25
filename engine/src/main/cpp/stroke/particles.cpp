#include "stroke/particles.h"

#include <algorithm>
#include <cmath>

namespace nanquim::stroke {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr uint64_t kParticleStreamMix = 0x6A09E667F3BCC909ULL;
constexpr double kDragPerSecond = 5;

double lerp(double from, double to, double weight) { return from + (to - from) * weight; }

}  // namespace

ParticleSystem::ParticleSystem(const ParticleParameters& parameters, uint64_t strokeSeed)
    : parameters_(parameters), random_(strokeSeed ^ kParticleStreamMix) {}

void ParticleSystem::emit(const std::vector<Dab>& dabs) {
  if (parameters_.rate <= 0) {
    return;
  }
  const double interval = 100.0 / parameters_.rate;
  for (const Dab& dab : dabs) {
    if (hasPrevious_) {
      const double dx = dab.x - previousPosition_[0];
      const double dy = dab.y - previousPosition_[1];
      const double length = std::hypot(dx, dy);
      if (length > 1e-6) {
        direction_ = {dx / length, dy / length};
      }
    }
    hasPrevious_ = true;
    previousPosition_ = {dab.x, dab.y};
    while (dab.distance >= nextEmissionDistance_) {
      nextEmissionDistance_ += interval;
      const double angleDraw = random_.nextUnit();
      const double speedDraw = random_.nextUnit();
      const double sideDraw = random_.nextUnit();
      std::array<double, 2> heading = direction_;
      switch (parameters_.direction) {
        case ParticleDirection::FollowStroke:
          break;
        case ParticleDirection::Random: {
          const double angle = angleDraw * 2 * kPi;
          heading = {std::cos(angle), std::sin(angle)};
          break;
        }
        case ParticleDirection::Outward: {
          const double side = sideDraw < 0.5 ? -1.0 : 1.0;
          heading = {-direction_[1] * side, direction_[0] * side};
          break;
        }
      }
      const double speed =
          parameters_.initialSpeed * (1.0 - std::clamp(parameters_.speedJitter, 0.0, 1.0) * speedDraw);
      Particle particle;
      particle.birth = dab;
      particle.bornMilliseconds = dab.timeMilliseconds;
      particle.position = {dab.x, dab.y};
      particle.velocity = {heading[0] * speed, heading[1] * speed};
      particles_.push_back(particle);
    }
  }
}

void ParticleSystem::advance(double untilMilliseconds, std::vector<Dab>& dabs) {
  if (particles_.empty()) {
    return;
  }
  if (!started_) {
    nextStep_ = static_cast<int64_t>(std::ceil(particles_.front().bornMilliseconds / kStepMilliseconds));
    started_ = true;
  }
  const double dt = kStepMilliseconds / 1000.0;
  const double damping = std::exp(-kDragPerSecond * std::clamp(parameters_.drag, 0.0, 1.0) * dt);
  const double lifetime = std::max(1.0, parameters_.lifetimeMs);
  while (static_cast<double>(nextStep_) * kStepMilliseconds <= untilMilliseconds) {
    const double time = static_cast<double>(nextStep_) * kStepMilliseconds;
    ++nextStep_;
    for (size_t index = firstLive_; index < particles_.size(); ++index) {
      Particle& particle = particles_[index];
      if (particle.dead || time < particle.bornMilliseconds) {
        continue;
      }
      const double age = time - particle.bornMilliseconds;
      if (age > lifetime) {
        particle.dead = true;
        continue;
      }
      const double life = age / lifetime;
      Dab dab = particle.birth;
      dab.x = particle.position[0];
      dab.y = particle.position[1];
      dab.diameter = particle.birth.diameter * lerp(parameters_.sizeStart, parameters_.sizeEnd, life);
      dab.opacity = std::clamp(
          particle.birth.opacity * lerp(parameters_.opacityStart, parameters_.opacityEnd, life), 0.0,
          1.0);
      dab.angle = particle.birth.angle + parameters_.rotationSpeed * age / 1000.0;
      if (dab.diameter > 0 && dab.opacity > 0) {
        dabs.push_back(dab);
      }
      particle.position[0] += particle.velocity[0] * dt;
      particle.position[1] += particle.velocity[1] * dt;
      particle.velocity[1] += parameters_.gravity * dt;
      particle.velocity[0] *= damping;
      particle.velocity[1] *= damping;
    }
    while (firstLive_ < particles_.size() && particles_[firstLive_].dead) {
      ++firstLive_;
    }
  }
}

bool ParticleSystem::active(double untilMilliseconds) const {
  const double lifetime = std::max(1.0, parameters_.lifetimeMs);
  for (size_t index = firstLive_; index < particles_.size(); ++index) {
    const Particle& particle = particles_[index];
    if (!particle.dead &&
        particle.bornMilliseconds + lifetime >= static_cast<double>(nextStep_) * kStepMilliseconds &&
        static_cast<double>(nextStep_) * kStepMilliseconds <= untilMilliseconds) {
      return true;
    }
  }
  return false;
}

}  // namespace nanquim::stroke
