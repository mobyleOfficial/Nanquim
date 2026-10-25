#include "core/lru_tracker.h"

#include <gtest/gtest.h>

#include <vector>

namespace nanquim::core {
namespace {

std::vector<int> leastRecentFirst(const LruTracker<int>& tracker) {
  std::vector<int> keys;
  tracker.visitLeastRecentFirst([&](const LruTracker<int>::Entry& entry) {
    keys.push_back(entry.key);
    return true;
  });
  return keys;
}

TEST(LruTrackerTest, OrdersByLastTouchAndSumsBytes) {
  LruTracker<int> tracker;
  tracker.touch(1, 100, 0);
  tracker.touch(2, 200, 1);
  tracker.touch(3, 300, 2);
  tracker.touch(1, 150, 3);

  EXPECT_EQ(leastRecentFirst(tracker), (std::vector<int>{2, 3, 1}));
  EXPECT_EQ(tracker.totalBytes(), 650);
}

TEST(LruTrackerTest, DemoteMakesTheKeyNextToEvict) {
  LruTracker<int> tracker;
  tracker.touch(1, 100, 0);
  tracker.touch(2, 100, 1);
  tracker.touch(3, 100, 2);
  tracker.demote(3);
  tracker.demote(42);

  EXPECT_EQ(leastRecentFirst(tracker), (std::vector<int>{3, 1, 2}));
  EXPECT_EQ(tracker.totalBytes(), 300);
}

TEST(LruTrackerTest, RemoveForgetsTheKey) {
  LruTracker<int> tracker;
  tracker.touch(1, 100, 0);
  tracker.touch(2, 200, 0);
  tracker.remove(1);
  tracker.remove(42);

  EXPECT_FALSE(tracker.contains(1));
  EXPECT_EQ(tracker.size(), 1U);
  EXPECT_EQ(tracker.totalBytes(), 200);
}

TEST(LruTrackerTest, VisitStopsWhenVisitorReturnsFalse) {
  LruTracker<int> tracker;
  for (int key = 0; key < 5; ++key) {
    tracker.touch(key, 1, key);
  }
  int visitedCount = 0;
  tracker.visitLeastRecentFirst([&](const LruTracker<int>::Entry&) { return ++visitedCount < 2; });

  EXPECT_EQ(visitedCount, 2);
}

}  // namespace
}  // namespace nanquim::core
