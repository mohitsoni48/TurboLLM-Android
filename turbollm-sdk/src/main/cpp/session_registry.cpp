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

llama_session & session_registry::find(int64_t handle) const {
    std::lock_guard<std::mutex> lock(mutex_);
    llama_session *             session = lookup(handle);
    if (session == nullptr) {
        throw session_error(session_failure::failed, "There is no session " + std::to_string(handle) + ".");
    }
    return *session;
}

// The session is handed back rather than destroyed here, so it is freed after the lock is released.
std::unique_ptr<llama_session> session_registry::release(int64_t handle) {
    std::lock_guard<std::mutex> lock(mutex_);
    const auto                  entry = sessions_.find(handle);
    if (entry == sessions_.end()) {
        return nullptr;
    }
    std::unique_ptr<llama_session> session = std::move(entry->second);
    sessions_.erase(entry);
    return session;
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

}  // namespace turbollm
