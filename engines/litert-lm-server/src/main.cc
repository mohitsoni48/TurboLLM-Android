// litertlm_server: a native, OpenAI-compatible HTTP server over the LiteRT-LM C API.
//
// It is a translation of `litert-lm serve` (the Python CLI in the `litert-lm` 0.18 wheel:
// litert_lm_cli/commands/serve/*), which is what TurboLLM runs on desktop and under Termux. The
// standalone Android app cannot run that: it has no Python, and Android only lets an app execute
// files that shipped inside its APK. The Python server is a thin layer anyway — every inference
// call goes through ctypes into liblitert-lm.so — so this program keeps the same native library
// and replaces only the layer above it, keeping the CLI contract the daemon already speaks:
//
//   litertlm_server serve --config <json> --host <h> --port <p>
//
//   - The `model` field of every request is the model reference, "<path>[,<backend>[,<max-tokens>]]".
//     The model loads lazily on the first chat request and stays loaded while requests keep
//     naming the same model and settings (util.get_or_initialize_server_engine).
//   - --config is the CLI's config file: {"default": {...}, "models": {"<model id>": {...}}}, of
//     which the serve path reads backend, cpu_thread_count, max_num_tokens, cache,
//     speculative_decoding, gpu_decode_steps_per_sync, thinking and thinking_budget.
//   - A failed request reports the runtime's own message in the HTTP status line, as
//     BaseHTTPRequestHandler.send_error does, because that is where the daemon's warm-up reads it.
//
// Additions over the Python server, each one something the daemon or a client can use:
//   - GET /health, so readiness does not depend on a fallback.
//   - `timings` (llama.cpp's field names) on the final response, taken from the runtime's own
//     benchmark counters, so speed is measured rather than derived from time-to-first-token.
//   - Thinking-channel text streamed as `reasoning_content` instead of being dropped.
//   - "length" as the finish reason when the reply is cut off: by max_completion_tokens, or by the
//     context window when the model reference or the config sets its size (max_num_tokens).
//   - A client that disconnects cancels its generation instead of letting it run on.
//   - Requests are served concurrently; only inference itself is serialized (the engine is one
//     shared resource, exactly as in the Python server, which serialized everything).
//
// Not translated: /v1/responses, /v1/embeddings and the Gemini API (TurboLLM uses none of them
// with this engine), and image URLs other than base64 data: URLs (remote URLs, and local paths, which the Python
// server opens but a loopback server any app can reach must not).

#include <arpa/inet.h>
#include <dlfcn.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>
#ifdef __linux__
#include <sys/prctl.h>
#endif

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <climits>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <deque>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "litert_lm_api.h"
#include "nlohmann/json.hpp"

using json = nlohmann::json;

LiteRtLmApi L;  // the C API, resolved by LoadLiteRtLm before anything else runs

#ifndef POLLRDHUP
#define POLLRDHUP 0x2000
#endif

namespace {

constexpr const char* kServerName = "turbollm-litertlm-server";
constexpr const char* kServerVersion = "1.0.0";
#ifndef LITERT_LM_VERSION
#define LITERT_LM_VERSION "unknown"
#endif

std::mutex g_log_mu;
bool g_verbose = false;

void Log(const std::string& msg) {
  std::lock_guard<std::mutex> lock(g_log_mu);
  std::fprintf(stderr, "%s\n", msg.c_str());
  std::fflush(stderr);
}

std::string LastRuntimeError() {
  const char* m = L.litert_lm_get_last_error_message();
  return (m && *m) ? std::string(m) : std::string();
}

std::string Trim(const std::string& s) {
  size_t b = s.find_first_not_of(" \t\r\n");
  if (b == std::string::npos) return "";
  size_t e = s.find_last_not_of(" \t\r\n");
  return s.substr(b, e - b + 1);
}

std::string Lower(std::string s) {
  for (auto& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  return s;
}

std::string Basename(const std::string& p) {
  size_t i = p.find_last_of('/');
  return i == std::string::npos ? p : p.substr(i + 1);
}

std::string Dirname(const std::string& p) {
  size_t i = p.find_last_of('/');
  if (i == std::string::npos) return ".";
  return i == 0 ? "/" : p.substr(0, i);
}

bool IsFile(const std::string& p) {
  struct stat st {};
  return ::stat(p.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool IsDir(const std::string& p) {
  struct stat st {};
  return ::stat(p.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

std::string AbsPath(const std::string& p) {
  char buf[PATH_MAX];
  if (::realpath(p.c_str(), buf)) return buf;
  return p;
}

// ── Config (litert_lm_cli/config.py) ─────────────────────────────────────────────────────

struct ModelConfig {
  std::optional<std::string> backend;
  std::optional<int> cpu_thread_count;
  std::optional<int> max_num_tokens;
  std::optional<std::string> cache;
  std::optional<bool> speculative_decoding;
  std::optional<int> gpu_decode_steps_per_sync;
  std::optional<bool> thinking;
  std::optional<int> thinking_budget;

  void MergeFrom(const ModelConfig& o) {
    if (o.backend) backend = o.backend;
    if (o.cpu_thread_count) cpu_thread_count = o.cpu_thread_count;
    if (o.max_num_tokens) max_num_tokens = o.max_num_tokens;
    if (o.cache) cache = o.cache;
    if (o.speculative_decoding) speculative_decoding = o.speculative_decoding;
    if (o.gpu_decode_steps_per_sync) gpu_decode_steps_per_sync = o.gpu_decode_steps_per_sync;
    if (o.thinking) thinking = o.thinking;
    if (o.thinking_budget) thinking_budget = o.thinking_budget;
  }
};

ModelConfig ParseModelConfig(const json& j) {
  ModelConfig c;
  if (!j.is_object()) return c;
  auto str = [&](const char* k) -> std::optional<std::string> {
    if (j.contains(k) && j[k].is_string()) return j[k].get<std::string>();
    return std::nullopt;
  };
  auto num = [&](const char* k) -> std::optional<int> {
    if (j.contains(k) && j[k].is_number_integer()) return j[k].get<int>();
    return std::nullopt;
  };
  auto boolean = [&](const char* k) -> std::optional<bool> {
    if (j.contains(k) && j[k].is_boolean()) return j[k].get<bool>();
    return std::nullopt;
  };
  c.backend = str("backend");
  c.cpu_thread_count = num("cpu_thread_count");
  c.max_num_tokens = num("max_num_tokens");
  c.cache = str("cache");
  c.speculative_decoding = boolean("speculative_decoding");
  c.gpu_decode_steps_per_sync = num("gpu_decode_steps_per_sync");
  c.thinking = boolean("thinking");
  c.thinking_budget = num("thinking_budget");
  return c;
}

struct AppConfig {
  ModelConfig def;
  std::map<std::string, ModelConfig> models;

  // config.get_model_config: the default section with the model's own section layered on top.
  ModelConfig For(const std::string& model_id) const {
    ModelConfig c = def;
    auto it = models.find(model_id);
    if (it != models.end()) c.MergeFrom(it->second);
    return c;
  }
};

AppConfig LoadConfig(const std::string& path) {
  AppConfig cfg;
  if (path.empty()) return cfg;
  std::ifstream in(path);
  if (!in) throw std::runtime_error("cannot read config file " + path);
  json j = json::parse(in, nullptr, /*allow_exceptions=*/true, /*ignore_comments=*/true);
  if (j.contains("default")) cfg.def = ParseModelConfig(j["default"]);
  if (j.contains("models") && j["models"].is_object()) {
    for (auto& [k, v] : j["models"].items()) cfg.models[k] = ParseModelConfig(v);
  }
  return cfg;
}

// ── Model reference (openai_common.parse_model_parameter + model.Model.from_model_reference) ──

struct ModelRef {
  std::string id;    // what config sections are keyed by: the file name, or "<org>/<repo>" for model.litertlm
  std::string path;  // absolute .litertlm path
  std::optional<std::string> backend;
  std::optional<int> max_num_tokens;
};

struct HttpError : std::runtime_error {
  int status;
  HttpError(int s, const std::string& m) : std::runtime_error(m), status(s) {}
};

ModelRef ParseModelParameter(const std::string& raw) {
  std::vector<std::string> parts;
  std::stringstream ss(raw);
  std::string p;
  while (std::getline(ss, p, ',')) parts.push_back(Trim(p));
  if (!raw.empty() && raw.back() == ',') parts.push_back("");
  if (parts.size() > 3) throw HttpError(400, "Invalid model parameter: Too many comma-separated components in model parameter: '" + raw + "'");
  ModelRef ref;
  std::string ref_str = parts.empty() ? "" : parts[0];
  if (ref_str.empty()) throw HttpError(400, "Invalid model parameter: model_id cannot be empty");
  if (parts.size() > 1 && !parts[1].empty()) ref.backend = Lower(parts[1]);
  if (parts.size() > 2 && !parts[2].empty()) {
    char* end = nullptr;
    long v = std::strtol(parts[2].c_str(), &end, 10);
    if (*end != '\0') throw HttpError(400, "Invalid model parameter: Invalid max_num_tokens in model parameter: '" + parts[2] + "'");
    if (v <= 0) throw HttpError(400, "Invalid model parameter: max_num_tokens must be a positive integer, got " + parts[2]);
    ref.max_num_tokens = static_cast<int>(v);
  }

  std::string path;
  if (IsFile(ref_str)) {
    path = AbsPath(ref_str);
  } else if (IsDir(ref_str) && IsFile(ref_str + "/model.litertlm")) {
    path = AbsPath(ref_str + "/model.litertlm");
  } else {
    // The Python CLI also resolves ids imported with `litert-lm import` (~/.litert-lm/models);
    // nothing on Android imports models that way, so only paths are accepted here.
    throw HttpError(404, "FileNotFoundError: Model " + ref_str + " not found");
  }
  ref.path = path;
  if (Basename(path) == "model.litertlm") {
    std::string parent = Basename(Dirname(path));
    size_t pos;
    while ((pos = parent.find("--")) != std::string::npos) parent.replace(pos, 2, "/");
    ref.id = parent;
  } else {
    ref.id = Basename(path);
  }
  return ref;
}

// ── Engine lifetime (util.get_or_initialize_server_engine) ─────────────────────────────────

struct EngineKey {
  std::string path, backend, cache;
  int max_num_tokens = -1, threads = -1, gpu_steps = -1, spec = -1;
  bool operator==(const EngineKey& o) const {
    return path == o.path && backend == o.backend && cache == o.cache && max_num_tokens == o.max_num_tokens &&
           threads == o.threads && gpu_steps == o.gpu_steps && spec == o.spec;
  }
};

struct EngineHolder {
  LiteRtLmEngine* engine = nullptr;
  EngineKey key;
  double init_seconds = 0;
};

std::mutex g_engine_mu;  // held for the whole of every inference: the engine is one shared resource
EngineHolder g_engine;
AppConfig g_config;

// common.cache_dir_value_from_cache_mode
std::string CacheDirValue(const std::optional<std::string>& cache) {
  if (!cache || *cache == "disk") return "";
  if (*cache == "memory") return ":memory";
  if (*cache == "no") return ":nocache";
  throw HttpError(500, "Failed to load engine: ValueError('Invalid cache mode: " + *cache + "')");
}

// "disk" caching puts the compiled-model cache beside the model. Where that folder is read-only (a model the app was
// pointed at in shared storage), the cache would silently be skipped and every load would pay full compilation again;
// TURBOLLM_LITERT_CACHE_DIR, set by the daemon, is used instead. Empty = the runtime's own default.
std::string FallbackCacheDir(const std::string& model_path) {
  const char* fallback = std::getenv("TURBOLLM_LITERT_CACHE_DIR");
  if (!fallback || !*fallback) return "";
  auto slash = model_path.find_last_of('/');
  std::string dir = slash == std::string::npos ? "." : model_path.substr(0, slash == 0 ? 1 : slash);
  if (access(dir.c_str(), W_OK) == 0) return "";
  // One subdirectory per model: the runtime names cache files after the model's file name, and Hugging Face folders
  // often all call it model.litertlm, so a shared directory would mix different models' caches. FNV-1a of the
  // path, stable across runs.
  uint64_t h = 1469598103934665603ULL;
  for (unsigned char c : model_path) h = (h ^ c) * 1099511628211ULL;
  char sub[17];
  std::snprintf(sub, sizeof sub, "%016llx", static_cast<unsigned long long>(h));
  const std::string target = std::string(fallback) + "/" + sub;
  for (auto pos = target.find('/', 1);; pos = target.find('/', pos + 1)) {  // mkdir -p
    mkdir(target.substr(0, pos).c_str(), 0700);
    if (pos == std::string::npos) break;
  }
  return access(target.c_str(), W_OK) == 0 ? target : "";
}

void ReleaseEngine() {
  if (g_engine.engine) {
    L.litert_lm_engine_delete(g_engine.engine);
    g_engine.engine = nullptr;
  }
}

// Caller holds g_engine_mu.
LiteRtLmEngine* GetOrCreateEngine(const ModelRef& ref, const ModelConfig& mc) {
  EngineKey key;
  key.path = ref.path;
  key.backend = ref.backend ? *ref.backend : (mc.backend ? Lower(*mc.backend) : "cpu");
  key.max_num_tokens = ref.max_num_tokens ? *ref.max_num_tokens : (mc.max_num_tokens ? *mc.max_num_tokens : -1);
  key.threads = mc.cpu_thread_count ? *mc.cpu_thread_count : -1;
  key.gpu_steps = mc.gpu_decode_steps_per_sync ? *mc.gpu_decode_steps_per_sync : -1;
  key.spec = mc.speculative_decoding ? (*mc.speculative_decoding ? 1 : 0) : -1;
  key.cache = CacheDirValue(mc.cache);
  if (key.cache.empty()) key.cache = FallbackCacheDir(ref.path);
  if (key.backend != "cpu" && key.backend != "gpu" && key.backend != "npu") {
    throw HttpError(500, "Failed to load engine: ValueError(\"Unsupported backend: '" + key.backend + "'\")");
  }

  if (g_engine.engine && g_engine.key == key) return g_engine.engine;
  // A .litertlm starts with "LITERTLM", three uint32 versions, 4 bytes of padding and a little-endian uint64 header-end
  // offset (the same 32 bytes the daemon's models/litertlm.ts reads). The runtime allocates and zeroes header_end - 32
  // bytes before reading the header, and the std::bad_alloc a bogus offset raises crosses the C API into std::terminate,
  // killing the whole server, so the offset is bounded here first. The bound is the runtime's own: it reads the header
  // from the file's first 16 KiB only (kLitertLmHeaderMaxSize, LiteRT-LM 0.18.0's litert_lm_loader.h), so a larger
  // offset can never load, yet would still cost an allocation up to the file's size (hundreds of MB on a phone).
  // Checked before the loaded engine is released, which a bad file then leaves in place.
  // A file that can't be read or lacks the magic is left to the runtime, which reports it cleanly and more precisely
  // (e.g. "Permission denied").
  {
    struct stat st {};
    unsigned char h[32] = {};
    std::ifstream in(ref.path, std::ios::binary);
    bool ok = ::stat(ref.path.c_str(), &st) == 0 && in.read(reinterpret_cast<char*>(h), sizeof h) &&
              std::memcmp(h, "LITERTLM", 8) == 0;
    uint64_t header_end = 0;
    for (int i = 31; i >= 24; --i) header_end = (header_end << 8) | h[i];
    constexpr uint64_t kRuntimeHeaderMaxSize = 16 * 1024;
    if (ok && (header_end <= 32 || header_end > std::min<uint64_t>(st.st_size, kRuntimeHeaderMaxSize))) {
      throw HttpError(500, "Failed to load engine: RuntimeError('Failed to create LiteRT-LM engine for " + ref.path +
                               ": invalid .litertlm header')");
    }
  }
  if (g_engine.engine) {
    Log("Re-initializing engine (model: " + ref.id + ", backend: " + key.backend + ")");
    ReleaseEngine();
  }

  Log("Initializing engine for model: " + ref.path + " (backend: " + key.backend + ")");
  auto t0 = std::chrono::steady_clock::now();
  L.litert_lm_clear_last_error();
  LiteRtLmEngineSettings* s = L.litert_lm_engine_settings_create(ref.path.c_str(), key.backend.c_str(), nullptr, nullptr);
  if (!s) {
    std::string why = LastRuntimeError();
    throw HttpError(500, "Failed to load engine: RuntimeError('Failed to create engine settings for " + ref.path +
                             (why.empty() ? "" : ": " + why) + "')");
  }
  L.litert_lm_engine_settings_enable_benchmark(s);
  L.litert_lm_engine_settings_set_use_ringbuffers_local_attention(s, true);
  if (key.backend == "cpu" && key.threads > 0) L.litert_lm_engine_settings_set_num_threads(s, key.threads);
  if (key.max_num_tokens > 0) L.litert_lm_engine_settings_set_max_num_tokens(s, key.max_num_tokens);
  L.litert_lm_engine_settings_set_cache_dir(s, key.cache.c_str());
  if (key.spec >= 0) L.litert_lm_engine_settings_set_enable_speculative_decoding(s, key.spec == 1);
  if (key.backend == "gpu" && key.gpu_steps > 0) L.litert_lm_engine_settings_set_gpu_decode_steps_per_sync(s, key.gpu_steps);

  LiteRtLmEngine* e = L.litert_lm_engine_create(s);
  L.litert_lm_engine_settings_delete(s);
  if (!e) {
    std::string why = LastRuntimeError();
    throw HttpError(500, "Failed to load engine: RuntimeError('Failed to create LiteRT-LM engine for " + ref.path +
                             (why.empty() ? "" : ": " + why) + "')");
  }
  g_engine.engine = e;
  g_engine.key = key;
  g_engine.init_seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
  Log("Engine ready in " + std::to_string(g_engine.init_seconds) + " s");
  return e;
}

// ── Message translation (openai_common.translate_openai_message) ───────────────────────────

std::map<std::string, std::string> BuildNameByToolCallId(const json& messages) {
  std::map<std::string, std::string> out;
  for (const auto& m : messages) {
    if (!m.is_object() || m.value("role", "") != "assistant" || !m.contains("tool_calls")) continue;
    const auto& tcs = m["tool_calls"];
    if (!tcs.is_array()) continue;
    for (const auto& tc : tcs) {
      if (!tc.is_object() || !tc.contains("function") || !tc["function"].is_object()) continue;
      std::string id = tc.value("id", "");
      std::string name = tc["function"].value("name", "");
      if (!id.empty() && !name.empty()) out[id] = name;
    }
  }
  return out;
}

// Arrays and objects nested deeper than this are refused in what a request sends. The parser keeps its own stack, but
// copying a value and dump() recurse once per level on the connection thread's stack (8 MB on glibc, about 1 MB on
// Android), so a 20 KB body of ten thousand nested brackets overflowed it and killed the server with SIGSEGV. Nothing
// a chat request carries comes near 128 levels, serde_json's default limit too.
constexpr int kMaxJsonDepth = 128;

struct JsonTooDeep : std::runtime_error {
  JsonTooDeep() : std::runtime_error("nested deeper than " + std::to_string(kMaxJsonDepth) + " levels") {}
};

// The DOM builder json::parse itself uses, counting the arrays and objects open: it throws JsonTooDeep at one that
// kMaxJsonDepth others enclose. Not json::parse with a parser callback, which would be simpler: that parser's
// end_object scans the whole enclosing array or object for a value the callback discarded, each time an object closes,
// so a body holding N objects in one array or object took O(N^2). A normal chat request with an extra field of 50,000
// empty objects (575 KB) kept a core busy for 16 s, on the connection thread and before any lock, and nothing stops
// that once the client has gone. This builder's start and end are what json::parse does, so it stays linear.
struct BoundedDomParser : nlohmann::detail::json_sax_dom_parser<json> {
  using json_sax_dom_parser::json_sax_dom_parser;
  int depth = 0;
  bool start_object(std::size_t n) {
    if (++depth > kMaxJsonDepth) throw JsonTooDeep();
    return json_sax_dom_parser::start_object(n);
  }
  bool end_object() {
    --depth;
    return json_sax_dom_parser::end_object();
  }
  bool start_array(std::size_t n) {
    if (++depth > kMaxJsonDepth) throw JsonTooDeep();
    return json_sax_dom_parser::start_array(n);
  }
  bool end_array() {
    --depth;
    return json_sax_dom_parser::end_array();
  }
};

// json::parse, throwing JsonTooDeep at an array or object that kMaxJsonDepth others enclose. A syntax error throws
// json::parse_error, as with json::parse (the builder is made with allow_exceptions).
json ParseBounded(const std::string& s) {
  json result;
  BoundedDomParser builder(result, true);
  json::sax_parse(s, &builder);
  return result;
}

json ParseToolArguments(const json& args) {
  if (args.is_object()) return args;
  if (args.is_string()) {
    try {
      // Bounded too: a tool call's arguments arrive as a string the body's own depth check cannot see into. Arguments
      // nested too deeply become {}, as unparseable ones do.
      json j = ParseBounded(args.get<std::string>());
      return j;
    } catch (...) {
    }
  }
  return json::object();
}

json TranslateMessage(const json& msg, const std::map<std::string, std::string>& names) {
  if (!msg.is_object()) throw HttpError(400, "Invalid messages: Message must be an object");
  std::string role = msg.value("role", "");
  json content = msg.contains("content") ? msg["content"] : json();

  if (role == "tool") {
    std::string id = msg.value("tool_call_id", "");
    if (id.empty()) throw HttpError(400, "Invalid messages: Tool message must have a tool_call_id");
    auto it = names.find(id);
    if (it == names.end()) throw HttpError(400, "Invalid messages: No matching tool call found for tool_call_id: '" + id + "'");
    return {{"role", "tool"}, {"content", json::array({{{"type", "tool_response"}, {"name", it->second}, {"response", content}}})}};
  }

  if (role == "assistant" && msg.contains("tool_calls")) {
    json calls = json::array();
    if (msg["tool_calls"].is_array()) {
      for (const auto& tc : msg["tool_calls"]) {
        json fn = tc.is_object() && tc.contains("function") ? tc["function"] : json::object();
        calls.push_back({{"type", "function"},
                         {"function", {{"name", fn.value("name", "")}, {"arguments", ParseToolArguments(fn.value("arguments", json("{}")))}}}});
      }
    }
    json out = {{"role", "assistant"}, {"tool_calls", calls}};
    bool has_content = !(content.is_null() || (content.is_string() && content.get<std::string>().empty()) ||
                         (content.is_array() && content.empty()));
    if (has_content) out["content"] = content;
    return out;
  }

  if (!content.is_array()) return msg;

  json parts = json::array();
  for (const auto& part : content) {
    if (!part.is_object()) {
      parts.push_back(part);
      continue;
    }
    std::string type = part.value("type", "");
    if (type == "image_url") {
      std::string url = part.contains("image_url") && part["image_url"].is_object() ? part["image_url"].value("url", "") : "";
      if (url.rfind("data:", 0) == 0) {
        size_t comma = url.find(',');
        if (comma == std::string::npos) throw HttpError(400, "Invalid messages: Invalid data URL format");
        if (url.substr(0, comma).find("base64") == std::string::npos) {
          throw HttpError(400, "Invalid messages: Unsupported data URL format (only base64 is supported)");
        }
        parts.push_back({{"type", "image"}, {"blob", url.substr(comma + 1)}});
      } else {
        // Only inline images. The Python server also opens a local path here; this one does not, because it
        // listens on loopback where any app on the phone can reach it, and a path would let that app have the
        // model read any file this app can (models in shared storage, once All files access is granted).
        throw HttpError(400, "Invalid messages: only base64 data: image URLs are supported by this server");
      }
    } else if (type == "input_audio") {
      std::string data = part.contains("input_audio") && part["input_audio"].is_object() ? part["input_audio"].value("data", "") : "";
      parts.push_back({{"type", "audio"}, {"blob", data}});
    } else {
      parts.push_back(part);
    }
  }
  return {{"role", role}, {"content", parts}};
}

// ── HTTP plumbing ─────────────────────────────────────────────────────────────────────────

struct Request {
  std::string method, path, query;
  std::map<std::string, std::string> headers;  // lower-cased names
  std::string body;
};

std::vector<std::string> g_cors_origins;

// False once the connection is gone, or once the client has stopped reading: every accepted socket has a 30 s
// SO_SNDTIMEO (see main), and a send() that times out with nothing sent fails with EAGAIN.
bool SendAll(int fd, const std::string& data) {
  size_t off = 0;
  while (off < data.size()) {
    ssize_t n = ::send(fd, data.data() + off, data.size() - off, MSG_NOSIGNAL);
    if (n <= 0) {
      if (n < 0 && errno == EINTR) continue;
      return false;
    }
    off += static_cast<size_t>(n);
  }
  return true;
}

// The peer closed its end (or the socket broke) — used to cancel generations nobody is reading.
bool PeerGone(int fd) {
  struct pollfd p {fd, POLLIN | POLLRDHUP, 0};
  int r = ::poll(&p, 1, 0);
  if (r <= 0) return false;
  if (p.revents & (POLLERR | POLLHUP | POLLRDHUP | POLLNVAL)) return true;
  if (p.revents & POLLIN) {
    char c;
    ssize_t n = ::recv(fd, &c, 1, MSG_PEEK | MSG_DONTWAIT);
    return n == 0;
  }
  return false;
}

std::string ReasonFor(int status) {
  switch (status) {
    case 200: return "OK";
    case 204: return "No Content";
    case 400: return "Bad Request";
    case 404: return "Not Found";
    case 405: return "Method Not Allowed";
    case 413: return "Payload Too Large";
    case 500: return "Internal Server Error";
    default: return "Error";
  }
}

// A reason phrase may not carry CR/LF; anything else is passed through, so the runtime's message
// reaches the daemon (which reads it from the status line, as Python's send_error puts it there).
std::string SafeReason(const std::string& s) {
  std::string out;
  for (char c : s) out += (c == '\r' || c == '\n') ? ' ' : c;
  if (out.size() > 900) out.resize(900);
  return out;
}

std::string CorsHeaders(const Request& req) {
  if (g_cors_origins.empty()) return "";
  std::string origin = req.headers.count("origin") ? req.headers.at("origin") : "";
  std::string h;
  bool any = false;
  for (const auto& o : g_cors_origins) {
    if (o == "*") {
      h = "Access-Control-Allow-Origin: *\r\n";
      any = true;
      break;
    }
    if (!origin.empty() && o == origin) {
      h = "Access-Control-Allow-Origin: " + origin + "\r\nVary: Origin\r\n";
      any = true;
    }
  }
  if (any) {
    h += "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
         "Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With\r\n";
  }
  return h;
}

// Why a request that a web page may have sent is refused, or empty. This port takes no key (only the daemon is meant
// to use it), and a browser that lets pages reach loopback (Firefox for Android does) let any site the user opened
// POST here: one cross-site request naming any file released the user's model to load that file instead.
//   - Host: a page on a domain the attacker points at 127.0.0.1 (DNS rebinding) is same-origin with this port, so it
//     can send anything and read the reply. Its requests name that domain in Host; the daemon's name 127.0.0.1. So
//     Host must be localhost or an IP address: a page whose origin is an IP address and this port came from whatever
//     listens there, this server, which serves no pages. Any IP address, not only loopback ones, so that a server
//     started with --host 0.0.0.0 (the user's own serve flags follow the daemon's --host 127.0.0.1) can still be
//     reached by the phone's LAN address. A request without Host is no browser's.
//   - Origin: a browser adds it to every POST a page sends to another origin, and sends one without asking first (no
//     preflight) only with a form's content types: text/plain, application/x-www-form-urlencoded or
//     multipart/form-data. One with application/json it sends only after a preflight that allows it, and this server
//     allows none unless started with --cors-origin. So a POST carrying an Origin must be application/json, unless
//     --cors-origin allows that Origin. Origin itself is not refused: a daemon older than TurboLLM#287 forwarded its
//     client's headers on its /v1/* pass-through, Origin included, so a web client of its API reached this port with
//     one (since TurboLLM#287 the daemon sends no Origin here). Nor is application/json required of every POST:
//     `curl -d` sends a form type, and that older daemon forwarded its client's Content-Type too (the daemon now
//     sends the chat body it re-serializes as application/json). A web client of such a daemon that posted JSON
//     labelled text/plain is refused here: from this port it looks like a page posting here.
std::string CrossSiteRefusal(const Request& req) {
  if (req.headers.count("host")) {
    std::string host = Lower(req.headers.at("host"));
    host = host.substr(0, host[0] == '[' ? host.find(']') + 1 : host.find(':'));  // drop the port
    in6_addr ip;  // room for either family
    bool ip_literal = host.size() > 2 && host[0] == '[' && host.back() == ']'
                          ? ::inet_pton(AF_INET6, host.substr(1, host.size() - 2).c_str(), &ip) == 1
                          : ::inet_pton(AF_INET, host.c_str(), &ip) == 1;
    if (host != "localhost" && !ip_literal) return "Forbidden: Host must be localhost or an IP address";
  }
  if (req.method == "POST" && req.headers.count("origin")) {
    const std::string& origin = req.headers.at("origin");
    for (const auto& o : g_cors_origins) {
      if (o == "*" || o == origin) return "";
    }
    std::string type = req.headers.count("content-type") ? req.headers.at("content-type") : "";
    if (Lower(Trim(type.substr(0, type.find(';')))) != "application/json") {
      return "Forbidden: a cross-origin POST must be application/json";
    }
  }
  return "";
}

bool SendResponse(int fd, const Request& req, int status, const std::string& reason, const std::string& content_type,
                  const std::string& body) {
  std::string head = "HTTP/1.1 " + std::to_string(status) + " " + SafeReason(reason) + "\r\n";
  head += std::string("Server: ") + kServerName + "/" + kServerVersion + "\r\n";
  head += "Content-Type: " + content_type + "\r\n";
  head += "Content-Length: " + std::to_string(body.size()) + "\r\n";
  head += "Connection: close\r\n";
  head += CorsHeaders(req);
  head += "\r\n";
  return SendAll(fd, head + body);
}

bool SendJson(int fd, const Request& req, int status, const json& j) {
  return SendResponse(fd, req, status, ReasonFor(status), "application/json",
                      j.dump(2, ' ', false, json::error_handler_t::replace) + "\n");
}

// Python's send_error: the message is the status line's reason phrase. The body is an
// OpenAI-style error object rather than Python's HTML page. The message is often the runtime's own text, which need
// not be UTF-8 (a bad model file's magic bytes), and this runs inside catch handlers, so dump must not throw.
bool SendError(int fd, const Request& req, int status, const std::string& message) {
  json body = {{"error", {{"message", message}, {"type", status >= 500 ? "server_error" : "invalid_request_error"}, {"code", status}}}};
  return SendResponse(fd, req, status, message, "application/json",
                      body.dump(-1, ' ', false, json::error_handler_t::replace) + "\n");
}

bool ReadRequest(int fd, Request& req) {
  std::string buf;
  char chunk[16384];
  size_t header_end = std::string::npos;
  while (header_end == std::string::npos) {
    ssize_t n = ::recv(fd, chunk, sizeof(chunk), 0);
    if (n <= 0) return false;
    buf.append(chunk, static_cast<size_t>(n));
    header_end = buf.find("\r\n\r\n");
    if (header_end == std::string::npos && buf.size() > 64 * 1024) return false;
  }
  std::string head = buf.substr(0, header_end);
  std::string rest = buf.substr(header_end + 4);

  std::istringstream hs(head);
  std::string line;
  std::getline(hs, line);
  if (!line.empty() && line.back() == '\r') line.pop_back();
  std::istringstream rl(line);
  std::string target, version;
  rl >> req.method >> target >> version;
  size_t q = target.find('?');
  req.path = q == std::string::npos ? target : target.substr(0, q);
  req.query = q == std::string::npos ? "" : target.substr(q + 1);
  while (std::getline(hs, line)) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
    size_t c = line.find(':');
    if (c == std::string::npos) continue;
    req.headers[Lower(Trim(line.substr(0, c)))] = Trim(line.substr(c + 1));
  }
  size_t len = 0;
  if (req.headers.count("content-length")) len = std::strtoull(req.headers["content-length"].c_str(), nullptr, 10);
  if (len > 256u * 1024 * 1024) return false;
  req.body = rest;
  while (req.body.size() < len) {
    ssize_t n = ::recv(fd, chunk, std::min(sizeof(chunk), len - req.body.size()), 0);
    if (n <= 0) return false;
    req.body.append(chunk, static_cast<size_t>(n));
  }
  if (req.body.size() > len) req.body.resize(len);
  return true;
}

// ── Streaming bridge (conversation.send_message_async) ─────────────────────────────────────

struct StreamItem {
  std::string text;
  bool final = false;
  bool is_error = false;
};

struct StreamQueue {
  std::mutex mu;
  std::condition_variable cv;
  std::deque<StreamItem> items;
};

// Keeps a generation's StreamQueue alive until the runtime has delivered its final chunk. The runtime calls
// StreamCallback from its own thread with a pointer to the queue, so a request that leaves early (an exception while
// tokens are still arriving: a malformed tool call, a JSON error) must not free the queue under it. On such an exit
// this cancels the generation and waits for the final chunk; if none comes in 30 s the server exits (see below).
// A chunk marked final is not always the last callback (an unparsable tool call is one), so HandleChatCompletions also
// declares the queue before the conversation, whose deletion waits for the generation to end.
class StreamGuard {
 public:
  StreamGuard(std::unique_ptr<StreamQueue>& q, LiteRtLmConversation* conv) : q_(q), conv_(conv) {}
  void Finished() { finished_ = true; }
  ~StreamGuard() {
    if (finished_ || !q_) return;
    L.litert_lm_conversation_cancel_process(conv_);
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
    std::unique_lock<std::mutex> lock(q_->mu);
    for (;;) {
      for (const auto& item : q_->items) {
        if (item.final) return;
      }
      q_->items.clear();
      if (q_->cv.wait_until(lock, deadline) == std::cv_status::timeout) break;
    }
    lock.unlock();
    // The runtime is still generating into this request's conversation, which the caller is about to delete, while
    // holding no engine lock that would stop the next request from releasing the engine under it. Nothing in this
    // process can be trusted after that, so exit: the daemon sees the engine stop and reloads it on demand.
    Log("generation did not stop 30 s after cancel; exiting so the engine restarts clean");
    std::fflush(nullptr);
    _exit(70);
  }

 private:
  std::unique_ptr<StreamQueue>& q_;
  LiteRtLmConversation* conv_;
  bool finished_ = false;
};

void StreamCallback(void* data, const LiteRtLmStreamChunk* chunk) {
  auto* q = static_cast<StreamQueue*>(data);
  StreamItem item;
  const char* err = L.litert_lm_stream_chunk_get_error(chunk);
  if (err) {
    item.is_error = true;
    item.text = err;
    item.final = true;
  } else {
    const char* t = L.litert_lm_stream_chunk_get_text(chunk);
    item.text = t ? t : "";
    item.final = L.litert_lm_stream_chunk_is_final(chunk);
  }
  std::lock_guard<std::mutex> lock(q->mu);
  q->items.push_back(std::move(item));
  // Notify under the lock: once it is released the request thread may take the final chunk and free q.
  q->cv.notify_one();
}

// One parsed chunk: the assistant text, the thinking-channel text and any tool calls it carries.
struct Piece {
  std::string text, reasoning;
  json tool_calls = json::array();
};

std::string ContentText(const json& content) {
  if (content.is_string()) return content.get<std::string>();
  std::string out;
  if (content.is_array()) {
    for (const auto& c : content) {
      if (c.is_object() && c.value("type", "") == "text" && c.contains("text") && c["text"].is_string()) out += c["text"].get<std::string>();
    }
  }
  return out;
}

Piece ParseChunk(const std::string& s) {
  Piece p;
  if (s.empty()) return p;
  json j;
  try {
    j = json::parse(s);
  } catch (...) {
    p.text = s;  // the Python client treats unparseable chunks as plain text, too
    return p;
  }
  if (!j.is_object()) {
    p.text = s;
    return p;
  }
  if (j.contains("content")) p.text = ContentText(j["content"]);
  if (j.contains("channels") && j["channels"].is_object()) {
    for (auto& [name, v] : j["channels"].items()) {
      if (v.is_string()) p.reasoning += v.get<std::string>();
    }
  }
  if (j.contains("tool_calls") && j["tool_calls"].is_array()) p.tool_calls = j["tool_calls"];
  return p;
}

// `first` is the number of the first call in `calls` within the turn: a stream sends a turn's calls over several
// chunks, and each call keeps its place in the turn's numbering (see the drain loop).
json OpenAiToolCalls(const json& calls, const std::string& now_str, bool with_index, int first = 0) {
  json out = json::array();
  int i = first;
  for (const auto& tc : calls) {
    json fn = tc.is_object() && tc.contains("function") ? tc["function"] : tc;
    json args = fn.is_object() && fn.contains("arguments") ? fn["arguments"] : json::object();
    if (args.is_string()) args = ParseToolArguments(args);
    json o = {{"id", "call_" + now_str + "_" + std::to_string(i)},
              {"type", "function"},
              {"function", {{"name", fn.is_object() ? fn.value("name", "") : ""}, {"arguments", args.dump()}}}};
    if (with_index) o["index"] = i;
    out.push_back(o);
    ++i;
  }
  return out;
}

std::string Sse(const json& j) { return "data: " + j.dump(-1, ' ', false, json::error_handler_t::replace) + "\n\n"; }

// openai_common.compute_token_usage, plus llama.cpp-style `timings`.
void UsageAndTimings(LiteRtLmConversation* conv, int reasoning_tokens, json& usage, json& timings) {
  int prompt = 0, completion = 0;
  double pps = 0, dps = 0;
  LiteRtLmBenchmarkInfo* info = L.litert_lm_conversation_get_benchmark_info(conv);
  if (info) {
    int np = L.litert_lm_benchmark_info_get_num_prefill_turns(info);
    int nd = L.litert_lm_benchmark_info_get_num_decode_turns(info);
    if (np > 0) {
      prompt = L.litert_lm_benchmark_info_get_prefill_token_count_at(info, np - 1);
      pps = L.litert_lm_benchmark_info_get_prefill_tokens_per_sec_at(info, np - 1);
    }
    if (nd > 0) {
      completion = L.litert_lm_benchmark_info_get_decode_token_count_at(info, nd - 1);
      dps = L.litert_lm_benchmark_info_get_decode_tokens_per_sec_at(info, nd - 1);
    }
    L.litert_lm_benchmark_info_delete(info);
  }
  usage = {{"prompt_tokens", prompt},
           {"completion_tokens", completion},
           {"total_tokens", prompt + completion},
           {"completion_tokens_details", {{"reasoning_tokens", reasoning_tokens}}}};
  timings = {{"prompt_n", prompt},
             {"prompt_per_second", pps},
             {"prompt_ms", pps > 0 ? prompt * 1000.0 / pps : 0.0},
             {"predicted_n", completion},
             {"predicted_per_second", dps},
             {"predicted_ms", dps > 0 ? completion * 1000.0 / dps : 0.0}};
}

struct ConvDeleter {
  void operator()(LiteRtLmConversation* c) const {
    if (c) L.litert_lm_conversation_delete(c);
  }
};

LiteRtLmThinkingConfig* MakeThinking(bool enable, int budget) {
  LiteRtLmThinkingConfig* t = L.litert_lm_thinking_config_create();
  if (!t) throw HttpError(500, "Failed to create thinking config");
  L.litert_lm_thinking_config_set_enable_thinking(t, enable);
  L.litert_lm_thinking_config_set_thinking_token_budget(t, budget);
  return t;
}

// openai_common.parse_thinking_config: reasoning_effort wins; otherwise the config's thinking keys.
std::optional<std::pair<bool, int>> ParseThinking(const json& body, const ModelConfig& mc) {
  if (body.contains("reasoning_effort") && !body["reasoning_effort"].is_null()) {
    if (!body["reasoning_effort"].is_string()) throw HttpError(400, "Invalid thinking parameters: ValueError: reasoning_effort must be a string");
    std::string e = Lower(body["reasoning_effort"].get<std::string>());
    if (e == "none") return std::make_pair(false, 0);
    if (e == "minimal" || e == "low" || e == "medium" || e == "high" || e == "xhigh") return std::make_pair(true, -1);
    throw HttpError(400, "Invalid thinking parameters: ValueError: Invalid reasoning_effort value: '" + e +
                             "'. Supported strings: none, minimal, low, medium, high, xhigh.");
  }
  if (!mc.thinking && !mc.thinking_budget) return std::nullopt;
  bool thinking = mc.thinking ? *mc.thinking : (*mc.thinking_budget != 0);
  int budget = mc.thinking_budget ? *mc.thinking_budget : (thinking ? -1 : 0);
  return std::make_pair(thinking, budget);
}

// openai_common.parse_response_format → (constraint type, schema/pattern), or none.
std::optional<std::pair<LiteRtLmConstraintType, std::string>> ParseResponseFormat(const json& body) {
  if (!body.contains("response_format") || body["response_format"].is_null()) return std::nullopt;
  const json& rf = body["response_format"];
  if (!rf.is_object()) throw HttpError(400, "Invalid response_format parameters: ValueError: response_format must be a dict");
  std::string type = rf.value("type", "");
  if (type.empty() || type == "text") return std::nullopt;
  auto schema_string = [](const json& s) -> std::string {
    if (s.is_string()) {
      if (!json::accept(s.get<std::string>())) {
        throw HttpError(400, "Invalid response_format parameters: ValueError: Invalid JSON schema string");
      }
      return s.get<std::string>();
    }
    if (s.is_object()) return s.dump();
    throw HttpError(400, "Invalid response_format parameters: ValueError: schema must be a dict or str");
  };
  if (type == "json_object") {
    json schema = rf.contains("schema") ? rf["schema"] : (rf.contains("json_schema") ? rf["json_schema"] : json::object());
    if (schema.is_object() && schema.contains("schema")) schema = schema["schema"];
    if (schema.is_null()) schema = json::object();
    return std::make_pair(kLiteRtLmConstraintTypeJsonSchema, schema_string(schema));
  }
  if (type == "json_schema") {
    json schema;
    if (rf.contains("json_schema") && rf["json_schema"].is_object()) {
      schema = rf["json_schema"].contains("schema") ? rf["json_schema"]["schema"] : rf["json_schema"];
    } else if (rf.contains("schema")) {
      schema = rf["schema"];
    }
    if (schema.is_null()) throw HttpError(400, "Invalid response_format parameters: ValueError: json_schema response_format requires a dict or str schema");
    return std::make_pair(kLiteRtLmConstraintTypeJsonSchema, schema_string(schema));
  }
  if (type == "regex") {
    std::string pat;
    for (const char* k : {"regex", "pattern", "schema_or_pattern"}) {
      if (rf.contains(k) && rf[k].is_string() && !rf[k].get<std::string>().empty()) {
        pat = rf[k].get<std::string>();
        break;
      }
    }
    if (pat.empty()) throw HttpError(400, "Invalid response_format parameters: ValueError: regex response_format requires a string pattern/regex");
    return std::make_pair(kLiteRtLmConstraintTypeRegex, pat);
  }
  throw HttpError(400, "Invalid response_format parameters: ValueError: Unsupported response_format type: '" + type + "'");
}

std::string NowStr(long long* created) {
  auto now = std::chrono::system_clock::now();
  auto us = std::chrono::duration_cast<std::chrono::microseconds>(now.time_since_epoch()).count();
  std::time_t secs = static_cast<std::time_t>(us / 1000000);
  *created = static_cast<long long>(secs);
  std::tm tm {};
  gmtime_r(&secs, &tm);
  char buf[32];
  std::strftime(buf, sizeof(buf), "%Y%m%d%H%M%S", &tm);
  char micro[8];
  std::snprintf(micro, sizeof(micro), "%06lld", static_cast<long long>(us % 1000000));
  return std::string(buf) + micro;
}

// ── /v1/chat/completions (openai_chat_completions.handle_post_chat_completions) ────────────

void HandleChatCompletions(int fd, const Request& req) {
  json body;
  try {
    body = ParseBounded(req.body);
  } catch (const JsonTooDeep& e) {
    SendError(fd, req, 400, std::string("Invalid JSON: ") + e.what());
    return;
  } catch (...) {
    SendError(fd, req, 400, "Invalid JSON");
    return;
  }
  if (!body.is_object()) {
    SendError(fd, req, 400, "Invalid JSON");
    return;
  }
  if (!body.contains("model") || !body["model"].is_string() || body["model"].get<std::string>().empty()) {
    SendError(fd, req, 400, "Missing model");
    return;
  }
  const std::string raw_model = body["model"].get<std::string>();

  bool headers_sent = false;
  try {
    ModelRef ref = ParseModelParameter(raw_model);
    ModelConfig mc = g_config.For(ref.id);

    json translated = json::array();
    std::map<std::string, std::string> names;
    if (body.contains("messages") && body["messages"].is_array() && !body["messages"].empty()) {
      names = BuildNameByToolCallId(body["messages"]);
      for (const auto& m : body["messages"]) translated.push_back(TranslateMessage(m, names));
    }
    json prompt;
    if (!translated.empty() && translated.back().is_object()) {
      prompt = translated.back();
    } else if (body.contains("input")) {
      prompt = body["input"];
      if (prompt.is_object()) prompt = TranslateMessage(prompt, names);
    }
    if (prompt.is_null() || (prompt.is_string() && prompt.get<std::string>().empty())) {
      SendError(fd, req, 400, "Missing input or messages");
      return;
    }
    // conversation.normalize_message: a bare string is a user turn.
    if (prompt.is_string()) prompt = {{"role", "user"}, {"content", prompt}};

    std::optional<int> max_tokens;
    if (body.contains("max_completion_tokens") && !body["max_completion_tokens"].is_null()) {
      if (!body["max_completion_tokens"].is_number_integer()) {
        SendError(fd, req, 400, "max_completion_tokens must be an integer");
        return;
      }
      max_tokens = body["max_completion_tokens"].get<int>();
    }

    // openai_chat_completions.parse_sampler_config
    bool has_sampler = false;
    for (const char* k : {"temperature", "top_p", "top_k", "seed"}) {
      if (body.contains(k) && !body[k].is_null()) {
        if (!body[k].is_number()) {
          SendError(fd, req, 400, std::string("Invalid sampler parameters: ValueError: ") + k + " must be a number");
          return;
        }
        has_sampler = true;
      }
    }
    auto thinking = ParseThinking(body, mc);
    auto constraint = ParseResponseFormat(body);

    json tools = json::array();
    if (body.contains("tools") && body["tools"].is_array()) {
      for (const auto& t : body["tools"]) {
        if (t.is_object() && t.value("type", "") == "function") tools.push_back(t);
      }
    }

    // Absent or null is false for both, as the Python server's body.get() reads them, and as clients that write every
    // optional field send them. json's value() threw type_error on a null, and the catch below made that a 500. Any
    // other non-boolean is the client's mistake: a 400 that says so, not a 500.
    bool stream = false;
    if (body.contains("stream") && !body["stream"].is_null()) {
      if (!body["stream"].is_boolean()) {
        SendError(fd, req, 400, "stream must be a boolean");
        return;
      }
      stream = body["stream"].get<bool>();
    }
    bool include_usage = false;
    if (body.contains("stream_options") && body["stream_options"].is_object()) {
      const json& so = body["stream_options"];
      if (so.contains("include_usage") && !so.at("include_usage").is_null()) {
        if (!so.at("include_usage").is_boolean()) {
          SendError(fd, req, 400, "stream_options.include_usage must be a boolean");
          return;
        }
        include_usage = so.at("include_usage").get<bool>();
      }
    }

    std::lock_guard<std::mutex> engine_lock(g_engine_mu);
    LiteRtLmEngine* engine = GetOrCreateEngine(ref, mc);

    // Engine.create_conversation, with exactly the options the serve path passes.
    LiteRtLmSessionConfig* sc = L.litert_lm_session_config_create();
    if (has_sampler) {
      LiteRtLmSamplerParams* sp = L.litert_lm_sampler_params_create(kLiteRtLmSamplerTypeTopP);
      L.litert_lm_sampler_params_set_top_k(sp, body.contains("top_k") && !body["top_k"].is_null() ? body["top_k"].get<int>() : 1);
      L.litert_lm_sampler_params_set_top_p(sp, body.contains("top_p") && !body["top_p"].is_null() ? body["top_p"].get<float>() : 0.95f);
      L.litert_lm_sampler_params_set_temperature(
          sp, body.contains("temperature") && !body["temperature"].is_null() ? body["temperature"].get<float>() : 1.0f);
      L.litert_lm_sampler_params_set_seed(sp, body.contains("seed") && !body["seed"].is_null() ? body["seed"].get<int>() : 0);
      L.litert_lm_session_config_set_sampler_params(sc, sp);
      L.litert_lm_sampler_params_delete(sp);
    }
    LiteRtLmConversationConfig* cc = L.litert_lm_conversation_config_create();
    L.litert_lm_conversation_config_set_session_config(cc, sc);
    L.litert_lm_session_config_delete(sc);
    json context = json::array();
    for (size_t i = 0; i + 1 < translated.size(); ++i) context.push_back(translated[i]);
    std::string context_str = context.dump();
    if (!context.empty()) L.litert_lm_conversation_config_set_messages(cc, context_str.c_str());
    std::string tools_str = tools.dump();
    if (!tools.empty()) L.litert_lm_conversation_config_set_tools(cc, tools_str.c_str());
    L.litert_lm_conversation_config_set_enable_constrained_decoding(cc, true);
    LiteRtLmConstraintProviderType provider = kLiteRtLmConstraintProviderTypeLlGuidance;
    if (constraint) L.litert_lm_conversation_config_set_constraint_provider(cc, &provider);
    if (thinking) {
      LiteRtLmThinkingConfig* t = MakeThinking(thinking->first, thinking->second);
      L.litert_lm_conversation_config_set_thinking_config(cc, t);
      L.litert_lm_thinking_config_delete(t);
    }
    // Declared before the conversation so it is freed after it: deleting the conversation waits for its generation to
    // end (~SessionAdvanced's WaitUntilDone), and until then the runtime may call StreamCallback with this queue, even
    // after a chunk marked final (see the error branch of the drain loop below).
    auto q_owner = std::make_unique<StreamQueue>();
    StreamQueue& q = *q_owner;
    L.litert_lm_clear_last_error();
    std::unique_ptr<LiteRtLmConversation, ConvDeleter> conv(L.litert_lm_conversation_create(engine, cc));
    L.litert_lm_conversation_config_delete(cc);
    if (!conv) {
      std::string why = LastRuntimeError();
      throw HttpError(500, "RuntimeError: Failed to create conversation" + (why.empty() ? "" : ": " + why));
    }

    LiteRtLmConversationOptionalArgs* oa = L.litert_lm_conversation_optional_args_create();
    if (max_tokens) L.litert_lm_conversation_optional_args_set_max_output_tokens(oa, *max_tokens);
    if (constraint) L.litert_lm_conversation_optional_args_set_constraint(oa, constraint->first, constraint->second.c_str());

    long long created = 0;
    const std::string now_str = NowStr(&created);
    const std::string chunk_id = "chatcmpl_" + now_str;
    // No "usage" key on these chunks, not even null as the Python server writes with include_usage: the daemon's
    // gateway turns include_usage on for streaming clients that leave it unset, so it can count tokens, and takes the
    // usage chunk back out of their stream, so a null on every other chunk reached clients that never asked for usage.
    // The usage chunk at the end sets it.
    auto make_chunk = [&](json choices) {
      return json{{"id", chunk_id}, {"object", "chat.completion.chunk"}, {"created", created}, {"model", raw_model}, {"choices", choices}};
    };

    std::string msg_str = prompt.dump();
    L.litert_lm_clear_last_error();
    int rc = L.litert_lm_conversation_send_message_stream(conv.get(), msg_str.c_str(), "{}", oa, StreamCallback, &q);
    L.litert_lm_conversation_optional_args_delete(oa);
    // Declared after the queue and the conversation, so it runs before either is destroyed.
    StreamGuard guard(q_owner, conv.get());
    if (rc != 0) {
      guard.Finished(); // nothing was started
      std::string why = LastRuntimeError();
      throw HttpError(500, "RuntimeError: litert_lm_conversation_send_message_stream failed" + (why.empty() ? "" : ": " + why));
    }

    if (stream) {
      std::string head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n" +
                         CorsHeaders(req) + "\r\n";
      headers_sent = true;
      SendAll(fd, head + Sse(make_chunk(json::array({{{"index", 0}, {"delta", {{"role", "assistant"}}}, {"finish_reason", nullptr}}}))));
    }

    std::string text_all, reasoning_all;
    json tool_calls_all = json::array();
    int reasoning_tokens = 0;
    std::string finish = "stop";
    std::optional<std::string> error;
    bool client_gone = false;
    bool cancelled = false;

    // Drain the callback queue until the final chunk. Meanwhile watch the client, at most every 200 ms: a closed
    // connection cancels the generation (the Python server only noticed on its next write). Checked between chunks
    // too, not only while idle, because during decode chunks arrive faster than that and a non-streaming client has
    // no write that would fail.
    auto next_peer_check = std::chrono::steady_clock::now() + std::chrono::milliseconds(200);
    auto watch_peer = [&] {
      auto now = std::chrono::steady_clock::now();
      if (now < next_peer_check) return;
      next_peer_check = now + std::chrono::milliseconds(200);
      if (client_gone) {
        // Cancel again on every tick until the final chunk. A cancel flags only the tasks the runtime has already
        // queued, and the decode task is queued, with a fresh flag, only once the prefill task has finished: a cancel
        // landing in between is lost, and the turn would run to its end for nobody, holding the engine. Cancelling a
        // turn that has ended is a no-op.
        L.litert_lm_conversation_cancel_process(conv.get());
      } else if (!cancelled && PeerGone(fd)) {
        client_gone = cancelled = true;
        L.litert_lm_conversation_cancel_process(conv.get());
      }
    };
    for (bool done = false; !done;) {
      StreamItem item;
      {
        std::unique_lock<std::mutex> lock(q.mu);
        while (q.items.empty()) {
          q.cv.wait_for(lock, std::chrono::milliseconds(200));
          if (q.items.empty()) {
            lock.unlock(); // cancel_process may call back into the queue
            watch_peer();
            lock.lock();
          }
        }
        item = std::move(q.items.front());
        q.items.pop_front();
      }
      if (item.final) guard.Finished();
      watch_peer();
      if (item.is_error) {
        // Not necessarily the end of the generation: the runtime reports a tool call it cannot parse as soon as the
        // call's closing tag streams in, marked final, and keeps decoding. Stop it whatever the error is (a no-op once
        // the generation has ended), so deleting the conversation below does not wait for the rest of the turn while
        // this request holds the engine.
        if (!cancelled) {
          cancelled = true;
          L.litert_lm_conversation_cancel_process(conv.get());
        }
        // The text is absl::Status::ToString(), code first. Match from the start: a parse error quotes the model's
        // output, which may contain these words.
        if (item.text.rfind("CANCELLED:", 0) == 0) {
          // a cancelled turn ends quietly, as in the Python client
        } else if (item.text.rfind("RESOURCE_EXHAUSTED: Max number of tokens reached", 0) == 0) {
          // A full context window, from a runtime that reports it as an error. LiteRT-LM 0.18.0's C API never does
          // (see the token-count check below).
          finish = "length";
        } else {
          error = item.text;
        }
        break;
      }
      Piece p = ParseChunk(item.text);
      if (!p.reasoning.empty()) ++reasoning_tokens;
      // The runtime sends each tool-call block as its own chunk once the block closes, so parallel calls from a model
      // that writes one block per call (Qwen's <tool_call>) arrive in several chunks. An OpenAI stream numbers a turn's
      // calls 0, 1, 2, ... across all its chunks, and clients and the daemon's gateways put the deltas that share an
      // index together into one call; numbered per chunk, every such call was index 0 with the same id, and they came
      // out as one call whose arguments were the calls' objects run together. Numbered as in the non-streaming reply.
      const int first_call = static_cast<int>(tool_calls_all.size());
      if (!p.tool_calls.empty()) {
        for (auto& tc : p.tool_calls) tool_calls_all.push_back(tc);
        finish = "tool_calls";
      }
      if (stream && !client_gone) {
        bool ok = true;
        if (!p.reasoning.empty()) {
          ok = SendAll(fd, Sse(make_chunk(json::array({{{"index", 0}, {"delta", {{"reasoning_content", p.reasoning}}}, {"finish_reason", nullptr}}}))));
        }
        if (ok && !p.text.empty()) {
          ok = SendAll(fd, Sse(make_chunk(json::array({{{"index", 0}, {"delta", {{"content", p.text}}}, {"finish_reason", nullptr}}}))));
        }
        if (ok && !p.tool_calls.empty()) {
          ok = SendAll(fd, Sse(make_chunk(json::array(
                                {{{"index", 0}, {"delta", {{"tool_calls", OpenAiToolCalls(p.tool_calls, now_str, true, first_call)}}}, {"finish_reason", nullptr}}}))));
        }
        if (!ok && !cancelled) {
          client_gone = cancelled = true;
          L.litert_lm_conversation_cancel_process(conv.get());
        }
      }
      text_all += p.text;
      reasoning_all += p.reasoning;
      done = item.final;
    }
    if (client_gone) {
      Log("client disconnected — generation cancelled");
      return;
    }

    json usage, timings;
    // Not after an error, whose reply carries neither: the generation may still be running then (the cancel above only
    // asks it to stop), and the runtime copies the session's benchmark info under a lock its decode thread does not
    // take while writing it.
    if (!error) {
      UsageAndTimings(conv.get(), reasoning_tokens, usage, timings);
      if (finish == "stop" && max_tokens && usage["completion_tokens"].get<int>() >= *max_tokens) finish = "length";
      // A full context window ends the turn with an ordinary final chunk: the conversation API reports it as an error
      // only with return_error_on_max_tokens_reached, which defaults to off and which LiteRT-LM 0.18.0's C API has no
      // way to turn on. So it is told from the counts. The runtime stops once the KV cache holds max_num_tokens, and
      // this request's conversation is new, so its cache holds just this prompt (earlier messages included) and the
      // reply. Only a limit this server set is known: a bundle whose own cache is smaller caps the window lower, and
      // a reply cut off there still reads "stop".
      const int window = g_engine.key.max_num_tokens;
      if (finish == "stop" && window > 0 && usage["total_tokens"].get<int>() >= window) finish = "length";
    }

    if (stream) {
      // Stop at the first send that fails: to a client that has stopped reading, each further one would wait out the
      // send timeout again while this request holds the engine.
      bool ok;
      if (error) {
        // openai_common.OpenAIStreamFormatter.format_error
        ok = SendAll(fd, "event: response.error\ndata: " +
                             json({{"error", "RuntimeError: " + *error}}).dump(-1, ' ', false, json::error_handler_t::replace) + "\n\n");
      } else {
        json last = make_chunk(json::array({{{"index", 0}, {"delta", json::object()}, {"finish_reason", finish}}}));
        last["timings"] = timings;
        ok = SendAll(fd, Sse(last));
        if (ok && include_usage) {
          json u = make_chunk(json::array());
          u["usage"] = usage;
          ok = SendAll(fd, Sse(u));
        }
      }
      if (ok) SendAll(fd, "data: [DONE]\n\n");
      return;
    }

    if (error) throw HttpError(500, "RuntimeError: " + *error);
    json message = {{"role", "assistant"}, {"content", text_all.empty() ? json(nullptr) : json(text_all)}};
    if (!reasoning_all.empty()) message["reasoning_content"] = reasoning_all;
    if (!tool_calls_all.empty()) message["tool_calls"] = OpenAiToolCalls(tool_calls_all, now_str, false);
    json resp = {{"id", chunk_id},
                 {"object", "chat.completion"},
                 {"created", created},
                 {"model", raw_model},
                 {"choices", json::array({{{"index", 0}, {"message", message}, {"finish_reason", finish}}})},
                 {"usage", usage},
                 {"timings", timings}};
    SendJson(fd, req, 200, resp);
  } catch (const HttpError& e) {
    Log("request failed: " + std::string(e.what()));
    if (!headers_sent) SendError(fd, req, e.status, e.what());
  } catch (const std::exception& e) {
    Log("request failed: " + std::string(e.what()));
    if (!headers_sent) SendError(fd, req, 500, std::string("RuntimeError: ") + e.what());
  }
}

// ── /v1/models (openai_models.handle_get_models) ────────────────────────────────────────────

void HandleModels(int fd, const Request& req) {
  json data = json::array();
  std::lock_guard<std::mutex> lock(g_engine_mu);
  if (g_engine.engine) {
    struct stat st {};
    long long mtime = ::stat(g_engine.key.path.c_str(), &st) == 0 ? static_cast<long long>(st.st_mtime) : 0;
    data.push_back({{"id", g_engine.key.path}, {"object", "model"}, {"created", mtime}, {"owned_by", "litert-lm"}});
  }
  SendJson(fd, req, 200, {{"object", "list"}, {"data", data}});
}

void HandleConnection(int fd) {
  Request req;
  if (!ReadRequest(fd, req)) {
    ::close(fd);
    return;
  }
  if (g_verbose) Log(req.method + " " + req.path);
  if (std::string why = CrossSiteRefusal(req); !why.empty()) {
    SendError(fd, req, 403, why);
  } else if (req.method == "OPTIONS") {
    SendResponse(fd, req, 200, "OK", "text/plain", "");
  } else if (req.method == "GET" && req.path == "/health") {
    SendJson(fd, req, 200, {{"status", "ok"}});
  } else if (req.method == "GET" && req.path == "/v1/models") {
    HandleModels(fd, req);
  } else if (req.method == "POST" && (req.path == "/v1/chat/completions" || req.path == "/chat/completions")) {
    HandleChatCompletions(fd, req);
  } else {
    SendError(fd, req, 404, "Not Found");
  }
  ::shutdown(fd, SHUT_WR);
  ::close(fd);
}

std::atomic<bool> g_stop{false};
int g_listen_fd = -1;

void OnSignal(int) {
  g_stop = true;
  if (g_listen_fd >= 0) ::shutdown(g_listen_fd, SHUT_RDWR);
}

int Listen(const std::string& host, int port) {
  struct addrinfo hints {}, *res = nullptr;
  hints.ai_family = host.find(':') != std::string::npos ? AF_INET6 : AF_INET;
  hints.ai_socktype = SOCK_STREAM;
  hints.ai_flags = AI_PASSIVE | AI_NUMERICSERV;
  std::string port_s = std::to_string(port);
  int gai = ::getaddrinfo(host.c_str(), port_s.c_str(), &hints, &res);
  if (gai != 0) throw std::runtime_error(std::string("cannot resolve ") + host + ": " + gai_strerror(gai));
  int fd = ::socket(res->ai_family, res->ai_socktype, res->ai_protocol);
  if (fd < 0) {
    ::freeaddrinfo(res);
    throw std::runtime_error(std::string("socket: ") + std::strerror(errno));
  }
  int one = 1;
  ::setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
  if (::bind(fd, res->ai_addr, res->ai_addrlen) != 0) {
    int e = errno;
    ::freeaddrinfo(res);
    ::close(fd);
    throw std::runtime_error("cannot bind " + host + ":" + port_s + ": " + std::strerror(e));
  }
  ::freeaddrinfo(res);
  if (::listen(fd, 64) != 0) throw std::runtime_error(std::string("listen: ") + std::strerror(errno));
  return fd;
}

void PrintHelp() {
  std::printf(
      "Usage: litertlm_server serve [--config FILE] [--host HOST] [--port PORT] [--cors-origin ORIGIN]... [--verbose]\n"
      "       litertlm_server --version\n\n"
      "Native OpenAI-compatible server for LiteRT-LM (.litertlm) models.\n"
      "Endpoints: GET /health, GET /v1/models, POST /v1/chat/completions\n"
      "The request's `model` field is the model path: <path>[,<backend>[,<max-tokens>]]\n");
}

}  // namespace

int main(int argc, char** argv) {
#ifdef __linux__
  // Die with the process that started this one, by the kernel: SIGKILL the moment the thread that forked it ends. On
  // Android that is the daemon's Node thread in the app process, so the server, and the model it holds, ends however
  // the app process ends (Quit, a crash, Android killing the app), at once. The app's daemon starts it through
  // libtether_exec.so, which arms the same signal before the exec (BUILDING-ENGINES.md, "The engine tether"); this
  // covers every other way it is started: by a daemon without that helper, through the daemon's sh lifeline when the
  // helper fails its self-test, or by hand. Armed before anything else runs, so even a server stuck loading its
  // library goes. A parent process that ended before the prctl never sends it: this process was then already
  // reparented to init, so the server does not start. This sees the process end, not the thread: had only the forking
  // thread ended, this process would have moved to another thread of its parent and the signal would come when that
  // one ends; the daemon's thread ends only with the daemon. A server that init starts on purpose is refused too;
  // none is, as this binary ships only in the APK (host builds are for its tests).
  if (::prctl(PR_SET_PDEATHSIG, SIGKILL) != 0) {
    std::fprintf(stderr, "prctl(PR_SET_PDEATHSIG) failed: %s; the server will not end with its parent\n",
                 std::strerror(errno));
  } else if (::getppid() == 1) {
    std::fprintf(stderr, "the process that started %s has already exited; not starting\n", kServerName);
    return 1;
  }
#endif
  std::string config_path, host = "127.0.0.1";
  int port = 9379;
  std::vector<std::string> args(argv + 1, argv + argc);
  size_t i = 0;
  if (!args.empty() && args[0] == "serve") i = 1;
  for (; i < args.size(); ++i) {
    const std::string& a = args[i];
    auto next = [&](const char* name) -> std::string {
      if (i + 1 >= args.size()) {
        std::fprintf(stderr, "missing value for %s\n", name);
        std::exit(2);
      }
      return args[++i];
    };
    if (a == "--version" || a == "-V") {
      std::printf("%s %s (LiteRT-LM %s)\n", kServerName, kServerVersion, LITERT_LM_VERSION);
      return 0;
    } else if (a == "--help" || a == "-h") {
      PrintHelp();
      return 0;
    } else if (a == "--config") {
      config_path = next("--config");
    } else if (a == "--host") {
      host = next("--host");
    } else if (a == "--port") {
      port = std::atoi(next("--port").c_str());
    } else if (a == "--cors-origin") {
      g_cors_origins.push_back(next("--cors-origin"));
    } else if (a == "--verbose") {
      g_verbose = true;
    } else if (a == "--api") {
      std::string api = Lower(next("--api"));
      if (api != "openai") {
        std::fprintf(stderr, "Unsupported API: %s (only openai)\n", api.c_str());
        return 2;
      }
    } else {
      std::fprintf(stderr, "unknown argument: %s\n", a.c_str());
      PrintHelp();
      return 2;
    }
  }

  std::string lib_path;
  if (std::string err = LoadLiteRtLm(L, &lib_path); !err.empty()) {
    std::fprintf(stderr, "%s\n", err.c_str());
    return 1;
  }
  L.litert_lm_set_min_log_level(g_verbose ? kLiteRtLmLogSeverityVerbose : kLiteRtLmLogSeverityWarning);
  try {
    g_config = LoadConfig(config_path);
  } catch (const std::exception& e) {
    std::fprintf(stderr, "config error: %s\n", e.what());
    return 2;
  }

  ::signal(SIGPIPE, SIG_IGN);
  struct sigaction sa {};
  sa.sa_handler = OnSignal;
  ::sigaction(SIGTERM, &sa, nullptr);
  ::sigaction(SIGINT, &sa, nullptr);

  try {
    g_listen_fd = Listen(host, port);
  } catch (const std::exception& e) {
    std::fprintf(stderr, "%s\n", e.what());
    return 1;
  }
  Log(std::string("Starting OpenAI-compatible API server on ") + host + ":" + std::to_string(port) + "... (" + kServerName + " " +
      kServerVersion + ", LiteRT-LM " + LITERT_LM_VERSION + ", library " + lib_path + ")");

  while (!g_stop) {
    int fd = ::accept(g_listen_fd, nullptr, nullptr);
    if (fd < 0) {
      if (g_stop) break;
      if (errno == EINTR) continue;
      Log(std::string("accept: ") + std::strerror(errno));
      // EMFILE/ENFILE persist until connections close; retrying at once would spin a core.
      std::this_thread::sleep_for(std::chrono::milliseconds(100));
      continue;
    }
    int one = 1;
    ::setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    // A client that connects and never sends a request must not hold a thread forever.
    timeval rcv_timeout{60, 0};
    ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &rcv_timeout, sizeof(rcv_timeout));
    // Nor may one that stops reading. Chat completion and /v1/models responses are written under g_engine_mu, so a
    // send blocked on a full socket held the engine, and with it every later chat and /v1/models request, for as long
    // as the client kept the connection open. Now a send() gives up after 30 s: with a partial count if it got some
    // bytes out, which SendAll takes as progress, else with an error, which ends a stream like a disconnect and cancels
    // its generation. A client that reads nothing is dropped within one to two minutes: each send that still gets a few
    // bytes out waits its own 30 s, and the kernel can free a little room once more after the buffers first fill. One
    // that reads is served however slowly, as long as it never leaves the buffers full for 30 s. The disconnect poll is
    // unaffected.
    timeval snd_timeout{30, 0};
    ::setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &snd_timeout, sizeof(snd_timeout));
    try {
      std::thread(HandleConnection, fd).detach();
    } catch (const std::exception& e) {
      // No thread for this connection: std::system_error (EAGAIN) once the process is out of threads or address
      // space, e.g. with hundreds of idle connections each holding a thread for up to 60 s, or std::bad_alloc. Uncaught,
      // it ended the server (std::terminate) and with it the loaded model. Drop this connection and keep serving:
      // threads come back as connections end. Pause as for accept errors above, so a backlog of pending connections is
      // not dropped, and logged, in a tight loop.
      Log(std::string("cannot start a connection thread: ") + e.what());
      ::close(fd);
      std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
  }
  Log("Shutting down server...");
  // Free the engine if no inference holds it; a generation still running is cut short by exit.
  if (g_engine_mu.try_lock()) {
    ReleaseEngine();
    g_engine_mu.unlock();
  }
  ::close(g_listen_fd);
  // _exit, not return: connection threads are detached and may still be inside the runtime, which static destructors
  // running under them would crash.
  std::fflush(nullptr);
  _exit(0);
}
