#include "core/task_queue.h"

#include <gtest/gtest.h>

#include <chrono>
#include <thread>
#include <vector>

namespace nanquim::core {
namespace {

TEST(TaskQueueTest, PopsInFifoOrder) {
  TaskQueue<int> queue;
  queue.push(1);
  queue.push(2);
  queue.push(3);

  EXPECT_EQ(queue.waitPop(), 1);
  EXPECT_EQ(queue.tryPop(), 2);
  EXPECT_EQ(queue.waitPop(), 3);
  EXPECT_EQ(queue.tryPop(), std::nullopt);
}

TEST(TaskQueueTest, CloseRefusesNewTasksButDrainsQueuedOnes) {
  TaskQueue<int> queue;
  queue.push(7);
  queue.close();

  EXPECT_FALSE(queue.push(8));
  EXPECT_EQ(queue.waitPop(), 7);
  EXPECT_EQ(queue.waitPop(), std::nullopt);
}

TEST(TaskQueueTest, CloseWakesBlockedConsumer) {
  TaskQueue<int> queue;
  std::optional<int> popped = 42;
  std::thread consumer([&] { popped = queue.waitPop(); });

  queue.close();
  consumer.join();

  EXPECT_EQ(popped, std::nullopt);
}

TEST(TaskQueueTest, WaitPopForTimesOutOrReturnsQueuedTask) {
  TaskQueue<int> queue;

  EXPECT_EQ(queue.waitPopFor(std::chrono::milliseconds(5)), std::nullopt);
  queue.push(9);
  EXPECT_EQ(queue.waitPopFor(std::chrono::milliseconds(5)), 9);
}

TEST(TaskQueueTest, DeliversEveryTaskFromManyProducers) {
  constexpr int kProducerCount = 4;
  constexpr int kTasksPerProducer = 1000;
  TaskQueue<int> queue;
  std::vector<std::thread> producers;
  for (int producerIndex = 0; producerIndex < kProducerCount; ++producerIndex) {
    producers.emplace_back([&queue] {
      for (int taskIndex = 0; taskIndex < kTasksPerProducer; ++taskIndex) {
        queue.push(1);
      }
    });
  }

  int sum = 0;
  for (int received = 0; received < kProducerCount * kTasksPerProducer; ++received) {
    if (const std::optional<int> task = queue.waitPop()) {
      sum += *task;
    } else {
      FAIL() << "Queue ended before every task was delivered";
    }
  }
  for (auto& producer : producers) {
    producer.join();
  }

  EXPECT_EQ(sum, kProducerCount * kTasksPerProducer);
  EXPECT_EQ(queue.tryPop(), std::nullopt);
}

}  // namespace
}  // namespace nanquim::core
