#pragma once

#include <functional>
#include <thread>
#include <utility>
#include <vector>

#include "core/task_queue.h"

namespace nanquim::core {

class WorkerPool {
 public:
  explicit WorkerPool(int threadCount) {
    threads_.reserve(static_cast<size_t>(threadCount));
    for (int index = 0; index < threadCount; ++index) {
      threads_.emplace_back([this] {
        while (auto task = tasks_.waitPop()) {
          (*task)();
        }
      });
    }
  }

  WorkerPool(const WorkerPool&) = delete;
  WorkerPool& operator=(const WorkerPool&) = delete;

  ~WorkerPool() {
    tasks_.close();
    for (auto& thread : threads_) {
      thread.join();
    }
  }

  bool post(std::function<void()> task) { return tasks_.push(std::move(task)); }

 private:
  TaskQueue<std::function<void()>> tasks_;
  std::vector<std::thread> threads_;
};

}  // namespace nanquim::core
