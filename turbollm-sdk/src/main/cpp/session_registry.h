#pragma once

#include "llama_session.h"

#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace turbollm {

class session_registry;

// The right to use one session for the length of one native call. While any lease on a session is
// alive, the registry does not destroy that session.
class session_lease {
public:
    session_lease(const session_registry & registry, std::shared_ptr<llama_session> session);
    ~session_lease();

    session_lease(const session_lease &)             = delete;
    session_lease & operator=(const session_lease &) = delete;

    llama_session * operator->() const { return session_.get(); }

private:
    const session_registry &       registry_;
    std::shared_ptr<llama_session> session_;
};

// Owns every live session behind an opaque handle. Handles are never reused, so a stale or forged
// handle is refused instead of being dereferenced.
//
// Any thread may find, abort or release. A release aborts the session, then waits for every lease on it
// to end before destroying it, so a call still running on another thread never sees a freed session.
class session_registry {
public:
    int64_t       adopt(std::unique_ptr<llama_session> session);
    session_lease find(int64_t handle) const;
    void          release(int64_t handle);
    void          request_abort(int64_t handle);

private:
    friend class session_lease;

    llama_session * lookup(int64_t handle) const;
    void            end_lease(std::shared_ptr<llama_session> & session) const;

    mutable std::mutex                                          mutex_;
    mutable std::condition_variable                             lease_ended_;
    std::unordered_map<int64_t, std::shared_ptr<llama_session>> sessions_;
    int64_t                                                     last_handle_ = 0;
};

}  // namespace turbollm
