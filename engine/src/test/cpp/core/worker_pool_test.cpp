#include "core/worker_pool.h"

#include <gtest/gtest.h>

#include <atomic>

namespace nanquim::core {
namespace {

TEST(WorkerPoolTest, RunsEveryPostedTaskBeforeDestruction) {
  std::atomic<int> completed{0};
  {
    WorkerPool pool(3);
    for (int index = 0; index < 500; ++index) {
      pool.post([&completed] { completed.fetch_add(1); });
    }
  }

  EXPECT_EQ(completed.load(), 500);
}

}  // namespace
}  // namespace nanquim::core
