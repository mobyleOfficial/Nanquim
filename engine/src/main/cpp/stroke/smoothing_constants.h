#pragma once

namespace nanquim::stroke {

inline constexpr double kOneEuroMinimumCutoffAtZeroHz = 8.0;
inline constexpr double kOneEuroMinimumCutoffAtOneHz = 0.3;
inline constexpr double kOneEuroBeta = 0.007;
inline constexpr double kOneEuroDerivativeCutoffHz = 1.0;
inline constexpr double kJitterExpressionScale = 0.5;

inline constexpr double kStreamlineMinimumAlpha = 0.06;
inline constexpr double kStreamlineExponent = 0.6;
inline constexpr double kStreamlineReferenceIntervalMilliseconds = 4.0;

inline constexpr double kStabilizationMaximumExtraSamples = 63.0;

inline constexpr double kMinimumSampleIntervalMilliseconds = 0.5;

}  // namespace nanquim::stroke
