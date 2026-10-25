#pragma once

#include <cstdint>
#include <limits>
#include <list>
#include <unordered_map>
#include <utility>

namespace nanquim::core {

template <typename Key, typename Hash = std::hash<Key>>
class LruTracker {
 public:
  struct Entry {
    Key key;
    int64_t bytes;
    int64_t lastUseFrame;
  };

  void touch(const Key& key, int64_t bytes, int64_t frame) {
    auto found = positions_.find(key);
    if (found != positions_.end()) {
      totalBytes_ += bytes - found->second->bytes;
      entries_.erase(found->second);
    } else {
      totalBytes_ += bytes;
    }
    entries_.push_back(Entry{key, bytes, frame});
    positions_[key] = std::prev(entries_.end());
  }

  void demote(const Key& key) {
    auto found = positions_.find(key);
    if (found == positions_.end()) {
      return;
    }
    Entry entry = *found->second;
    entry.lastUseFrame = std::numeric_limits<int64_t>::min();
    entries_.erase(found->second);
    entries_.push_front(entry);
    found->second = entries_.begin();
  }

  void remove(const Key& key) {
    auto found = positions_.find(key);
    if (found == positions_.end()) {
      return;
    }
    totalBytes_ -= found->second->bytes;
    entries_.erase(found->second);
    positions_.erase(found);
  }

  [[nodiscard]] bool contains(const Key& key) const { return positions_.count(key) != 0; }
  [[nodiscard]] int64_t totalBytes() const { return totalBytes_; }
  [[nodiscard]] size_t size() const { return positions_.size(); }

  template <typename Visitor>
  void visitLeastRecentFirst(Visitor&& visitor) const {
    for (const Entry& entry : entries_) {
      if (!visitor(entry)) {
        return;
      }
    }
  }

 private:
  std::list<Entry> entries_;
  std::unordered_map<Key, typename std::list<Entry>::iterator, Hash> positions_;
  int64_t totalBytes_ = 0;
};

}  // namespace nanquim::core
