#pragma once

#include "llama_session.h"

#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace turbollm {

// Owns every live session behind an opaque handle. Handles are never reused, so a stale or forged
// handle is refused instead of being dereferenced.
//
// Only the engine thread adopts, finds and releases; request_abort may come from any thread. A
// session the engine thread has found therefore stays alive until that same thread releases it.
class session_registry {
public:
    int64_t                        adopt(std::unique_ptr<llama_session> session);
    llama_session &                find(int64_t handle) const;
    std::unique_ptr<llama_session> release(int64_t handle);
    void                           request_abort(int64_t handle);

private:
    llama_session * lookup(int64_t handle) const;

    mutable std::mutex                                          mutex_;
    std::unordered_map<int64_t, std::unique_ptr<llama_session>> sessions_;
    int64_t                                                     last_handle_ = 0;
};

}  // namespace turbollm
