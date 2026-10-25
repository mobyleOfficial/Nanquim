#pragma once

#include <condition_variable>
#include <deque>
#include <mutex>
#include <optional>
#include <utility>

namespace nanquim::core {

template <typename Task>
class TaskQueue {
 public:
  bool push(Task task) {
    {
      std::lock_guard<std::mutex> lock(mutex_);
      if (closed_) {
        return false;
      }
      tasks_.push_back(std::move(task));
    }
    condition_.notify_one();
    return true;
  }

  std::optional<Task> waitPop() {
    std::unique_lock<std::mutex> lock(mutex_);
    condition_.wait(lock, [this] { return closed_ || !tasks_.empty(); });
    return popFrontLocked();
  }

  template <typename Duration>
  std::optional<Task> waitPopFor(Duration timeout) {
    std::unique_lock<std::mutex> lock(mutex_);
    condition_.wait_for(lock, timeout, [this] { return closed_ || !tasks_.empty(); });
    return popFrontLocked();
  }

  std::optional<Task> tryPop() {
    std::lock_guard<std::mutex> lock(mutex_);
    return popFrontLocked();
  }

  [[nodiscard]] bool isClosed() {
    std::lock_guard<std::mutex> lock(mutex_);
    return closed_;
  }

  void close() {
    {
      std::lock_guard<std::mutex> lock(mutex_);
      closed_ = true;
    }
    condition_.notify_all();
  }

 private:
  std::optional<Task> popFrontLocked() {
    if (tasks_.empty()) {
      return std::nullopt;
    }
    std::optional<Task> task(std::move(tasks_.front()));
    tasks_.pop_front();
    return task;
  }

  std::mutex mutex_;
  std::condition_variable condition_;
  std::deque<Task> tasks_;
  bool closed_ = false;
};

}  // namespace nanquim::core
