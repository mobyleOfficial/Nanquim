#include <gtest/gtest.h>

#include <filesystem>
#include <random>

#include "fuzz_targets.h"
#include "io/file_system.h"

namespace nanquim::fuzz {
namespace {

std::vector<std::vector<uint8_t>> corpusOf(const std::string& target) {
  std::vector<std::vector<uint8_t>> inputs;
  const auto folder = std::filesystem::u8path(NANQUIM_FUZZ_CORPUS_DIRECTORY) / target;
  if (!std::filesystem::exists(folder)) {
    return inputs;
  }
  for (const auto& entry : std::filesystem::directory_iterator(folder)) {
    if (entry.is_regular_file()) {
      auto bytes = io::readFile(entry.path().u8string(), 64 << 20);
      if (bytes) {
        inputs.push_back(std::move(*bytes));
      }
    }
  }
  return inputs;
}

TEST(FuzzCorpus, EveryTargetHasSeeds) {
  for (const FuzzTarget& target : fuzzTargets()) {
    EXPECT_FALSE(corpusOf(target.name).empty()) << target.name;
  }
}

TEST(FuzzCorpus, ReplayWithMutations) {
  for (const FuzzTarget& target : fuzzTargets()) {
    std::mt19937 random(20261015);
    for (const std::vector<uint8_t>& input : corpusOf(target.name)) {
      target.run(input.data(), input.size());
      for (size_t length = 0; length < input.size(); length += 1 + input.size() / 64) {
        target.run(input.data(), length);
      }
      for (int mutation = 0; mutation < 200 && !input.empty(); ++mutation) {
        std::vector<uint8_t> mutated = input;
        const int flips = 1 + static_cast<int>(random() % 8);
        for (int flip = 0; flip < flips; ++flip) {
          mutated[random() % mutated.size()] = static_cast<uint8_t>(random());
        }
        target.run(mutated.data(), mutated.size());
      }
    }
  }
}

}  // namespace
}  // namespace nanquim::fuzz
