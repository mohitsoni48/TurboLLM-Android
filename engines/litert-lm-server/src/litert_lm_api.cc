#include "litert_lm_api.h"

#include <dlfcn.h>
#include <limits.h>
#include <unistd.h>

#include <cstdlib>
#include <vector>

std::string LoadLiteRtLm(LiteRtLmApi& api, std::string* loaded_from) {
  std::vector<std::string> candidates;
  if (const char* env = std::getenv("LITERT_LM_LIBRARY"); env && *env) candidates.push_back(env);
  // On Android this is the app's nativeLibraryDir, where the APK installed both files side by side.
  char exe[PATH_MAX];
  ssize_t n = ::readlink("/proc/self/exe", exe, sizeof(exe) - 1);
  if (n > 0) {
    exe[n] = '\0';
    std::string dir(exe);
    size_t slash = dir.find_last_of('/');
    if (slash != std::string::npos) candidates.push_back(dir.substr(0, slash) + "/liblitert-lm.so");
  }
  candidates.push_back("liblitert-lm.so");

  std::string errors;
  void* handle = nullptr;
  for (const auto& c : candidates) {
    handle = ::dlopen(c.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (handle) {
      if (loaded_from) *loaded_from = c;
      break;
    }
    const char* e = ::dlerror();
    errors += "\n  " + c + ": " + (e ? e : "unknown error");
  }
  if (!handle) return "cannot load liblitert-lm.so:" + errors;

  std::string missing;
#define LITERT_LM_RESOLVE(name)                                                   \
  api.name = reinterpret_cast<decltype(api.name)>(::dlsym(handle, #name));      \
  if (!api.name) missing += std::string(missing.empty() ? "" : ", ") + #name;
  LITERT_LM_API_FUNCTIONS(LITERT_LM_RESOLVE)
#undef LITERT_LM_RESOLVE
  if (!missing.empty()) return "liblitert-lm.so is missing C API functions (wrong version?): " + missing;
  return "";
}
