#include "session_registry.h"

#include "session_error.h"

#include <string>

namespace turbollm {

int64_t session_registry::adopt(std::unique_ptr<llama_session> session) {
    std::lock_guard<std::mutex> lock(mutex_);
    const int64_t               handle = ++last_handle_;
    sessions_.emplace(handle, std::move(session));
    return handle;
}

// The lease is made while the lock is held, so every copy of a session's pointer is counted under it.
session_lease session_registry::find(int64_t handle) const {
    std::lock_guard<std::mutex> lock(mutex_);
    const auto                  entry = sessions_.find(handle);
    if (entry == sessions_.end()) {
        throw session_error(session_failure::failed, "There is no session " + std::to_string(handle) + ".");
    }
    return session_lease(*this, entry->second);
}

// The handle stops resolving at once. The session is aborted so a decode in flight returns quickly, and it
// is destroyed only after the last lease ends, outside the lock.
void session_registry::release(int64_t handle) {
    std::unique_lock<std::mutex> lock(mutex_);
    const auto                   entry = sessions_.find(handle);
    if (entry == sessions_.end()) {
        return;
    }
    std::shared_ptr<llama_session> session = std::move(entry->second);
    sessions_.erase(entry);
    session->request_abort();
    lease_ended_.wait(lock, [&session] { return session.use_count() == 1; });
    lock.unlock();
    session.reset();
}

void session_registry::request_abort(int64_t handle) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (llama_session * session = lookup(handle)) {
        session->request_abort();
    }
}

llama_session * session_registry::lookup(int64_t handle) const {
    const auto entry = sessions_.find(handle);
    return entry == sessions_.end() ? nullptr : entry->second.get();
}

// A release waiting on this session still holds its own reference, so a lease never destroys it.
void session_registry::end_lease(std::shared_ptr<llama_session> & session) const {
    std::lock_guard<std::mutex> lock(mutex_);
    session.reset();
    lease_ended_.notify_all();
}

session_lease::session_lease(const session_registry & registry, std::shared_ptr<llama_session> session) :
    registry_(registry),
    session_(std::move(session)) {}

session_lease::~session_lease() {
    registry_.end_lease(session_);
}

}  // namespace turbollm
