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
//     which the serve path reads backend, vision_backend, audio_backend, cpu_thread_count,
//     max_num_tokens, cache, speculative_decoding, gpu_decode_steps_per_sync, thinking and thinking_budget.
//   - A failed request reports the runtime's own message in the HTTP status line, as
//     BaseHTTPRequestHandler.send_error does, because that is where the daemon's warm-up reads it.
//
// Additions over the Python server, each one something the daemon or a client can use:
//   - GET /health, so readiness does not depend on a fallback.
//   - `timings` (llama.cpp's field names) on the final response, taken from the runtime's own
//     benchmark counters, so speed is measured rather than derived from time-to-first-token.
//   - Thinking-channel text streamed as `reasoning_content` instead of being dropped.
//   - "length" as the finish reason when the reply is cut off: by max_completion_tokens, or by the
//     context window LiteRT-LM gives the engine, which each reply's X-Context-Window header names.
//   - A client that disconnects cancels its generation instead of letting it run on.
//   - Requests are served concurrently; only inference itself is serialized (the engine is one
//     shared resource, exactly as in the Python server, which serialized everything).
//   - Constrained decoding only for requests with tools, and none for a model that refuses it, so
//     LFM2 models answer at all (the Python server fails every request to one). A response_format
//     the model cannot follow, or whose regex or schema cannot be compiled, is a 400, not a 500.
//   - A new engine for each conversation of a model whose decoder keeps a recurrent state (LFM2), which the runtime
//     would otherwise carry from one conversation into the next (see GetOrCreateEngine).
//   - An image or audio part sent to a model that takes no such input is a 400 that says so, not the runtime's 500.
//   - The developer role, OpenAI's newer name for system, is read as system.
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
#include <cmath>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <deque>
#include <fstream>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <random>
#include <set>
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
  std::optional<std::string> backend, vision_backend, audio_backend;
  std::optional<int> cpu_thread_count;
  std::optional<int> max_num_tokens;
  std::optional<std::string> cache;
  std::optional<bool> speculative_decoding;
  std::optional<int> gpu_decode_steps_per_sync;
  std::optional<bool> thinking;
  std::optional<int> thinking_budget;

  void MergeFrom(const ModelConfig& o) {
    if (o.backend) backend = o.backend;
    if (o.vision_backend) vision_backend = o.vision_backend;
    if (o.audio_backend) audio_backend = o.audio_backend;
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
  c.vision_backend = str("vision_backend");
  c.audio_backend = str("audio_backend");
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

// ── What the model file says about itself ─────────────────────────────────────────────────
// After the 32-byte preamble a .litertlm has a FlatBuffer header (schema/core/litertlm_header_schema.fbs in LiteRT-LM
// 0.18.0) that lists its sections with their byte ranges in the file. Two are small protobufs the runtime reads at load:
// LlmMetadata (runtime/proto/llm_metadata.proto) and ExecutorMetadata (executor_metadata.proto).

struct ModelFileInfo {
  // The decoder keeps a recurrent state besides its KV cache: LFM2's short-convolution cache (the kv_cache_c_* inputs),
  // or any linear-attention state. See GetOrCreateEngine for why that matters.
  bool recurrent_state = false;
  // What the header's TFLite sections say, when the header could be read: for text, image and audio input, the
  // backends the file's decoder or encoder supports, the one it prefers first; none when it takes no such input.
  bool capabilities_known = false;
  std::vector<std::string> text_backends, vision_backends, audio_backends;
  // The model's own sampler (LlmMetadata's sampler_params): type UNSPECIFIED and zeros for a file that has none, as
  // LFM2.5-230M and gemma-3-270m-it are, as the C API's litert_lm_loaded_file_sampler_* also answer.
  LiteRtLmSamplerType sampler_type = kLiteRtLmSamplerTypeUnspecified;
  int top_k = 0;
  float top_p = 0, temperature = 0;
  // Its text decoder is an Artisan one (a tf_lite_artisan_text_decoder section), which LiteRT-LM runs on GPU as
  // GPU_ARTISAN (engine_settings.cc), a backend whose sampling is the model's own.
  bool artisan = false;
  // The number of logits its decode signature outputs: its vocabulary. 0 when unknown.
  int vocabulary = 0;
  // What LlmMetadata says about thinking: its supports_thinking (field 11, absent from most files: LFM2.5-230M,
  // Qwen3-0.6B, Gemma 4), whether its Jinja template has the enable_thinking switch (Qwen3's and Gemma 4's do), and
  // whether it has a channel, the thinking one being the first (Conversation::CreateDecodeConfig).
  std::optional<bool> supports_thinking;
  bool thinking_switch = false, channel = false;
  // The context the file takes, as the C API's litert_lm_loaded_file_max_context_tokens and _is_dynamic_context read
  // it: a static model's fixed size, or a dynamic one's magic number (see RuntimeWindow); 0 when unknown. And
  // LlmMetadata's own max_num_tokens, which the runtime starts from when it is not given one: 0 when it has none.
  int max_context = 0, metadata_context = 0;
  bool dynamic_context = false;
};

// Bounds-checked reads of a FlatBuffer of `size` bytes, through `read`: just what the server needs of the header's
// section list. Little-endian, as are the FlatBuffer format and every ABI the server is built for.
struct FbReader {
  std::function<bool(uint64_t pos, void* out, size_t n)> read;  // called only within the bounds
  uint64_t size;
  template <class T>
  std::optional<T> Get(uint64_t pos) const {
    T v;
    if (pos > size || sizeof(T) > size - pos || !read(pos, &v, sizeof v)) return std::nullopt;
    return v;
  }
  // Where field i of the table at `table` is, or 0 when the table does not have it.
  uint64_t Field(uint64_t table, int i) const {
    auto back = Get<int32_t>(table);
    if (!back || static_cast<int64_t>(table) - *back < 0) return 0;
    uint64_t vtable = static_cast<uint64_t>(static_cast<int64_t>(table) - *back);
    auto vsize = Get<uint16_t>(vtable);
    if (!vsize || 4u + 2u * i + 2u > *vsize) return 0;
    auto at = Get<uint16_t>(vtable + 4 + 2 * i);
    return at && *at ? table + *at : 0;
  }
  // The table or vector a uoffset at `pos` points to, or 0.
  uint64_t Follow(uint64_t pos) const {
    auto off = pos ? Get<uint32_t>(pos) : std::nullopt;
    return off && *off ? pos + *off : 0;
  }
  // The number of elements of the vector at `vec`, 0 when there is none.
  uint32_t Length(uint64_t vec) const { return vec ? Get<uint32_t>(vec).value_or(0) : 0; }
  // The scalar at `pos` (a field's place, from Field), or `missing` when the table does not have the field.
  template <class T>
  T Scalar(uint64_t pos, T missing) const {
    return pos ? Get<T>(pos).value_or(missing) : missing;
  }
  // The string a uoffset at `pos` points to (its first 256 bytes), empty when there is none.
  std::string String(uint64_t pos) const {
    const uint64_t at = Follow(pos);
    std::string out(std::min<uint32_t>(Length(at), 256), '\0');
    if (!at || at + 4 > size || out.size() > size - at - 4 || !read(at + 4, &out[0], out.size())) return "";
    return out;
  }
};

FbReader MemoryReader(const std::string& b) {
  return {[&b](uint64_t pos, void* out, size_t n) {
            std::memcpy(out, b.data() + pos, n);
            return true;
          },
          b.size()};
}

// Calls f(field number, value, bytes) for each top-level field of a protobuf message: the varint for wire type 0, the
// 32 bits for wire type 5 (a float's), the bytes for wire type 2; 64-bit fields are skipped. False when the message is
// malformed.
template <class F>
bool ForEachProtoField(const std::string& m, F f) {
  size_t i = 0;
  auto varint = [&](uint64_t& v) {
    v = 0;
    for (int shift = 0; shift < 64; shift += 7) {
      if (i >= m.size()) return false;
      unsigned char c = static_cast<unsigned char>(m[i++]);
      v |= static_cast<uint64_t>(c & 0x7f) << shift;
      if (c < 0x80) return true;
    }
    return false;
  };
  while (i < m.size()) {
    uint64_t key, v;
    if (!varint(key)) return false;
    const int field = static_cast<int>(key >> 3);
    switch (key & 7) {
      case 0:
        if (!varint(v)) return false;
        f(field, v, std::string());
        break;
      case 2:
        if (!varint(v) || v > m.size() - i) return false;
        f(field, 0, m.substr(i, v));
        i += v;
        break;
      case 1:
        i += 8;
        break;
      case 5:
        if (m.size() - i < 4) return false;
        v = 0;
        for (int b = 3; b >= 0; --b) v = (v << 8) | static_cast<unsigned char>(m[i + b]);
        f(field, v, std::string());
        i += 4;
        break;
      default:
        return false;
    }
    if (i > m.size()) return false;
  }
  return true;
}

// LlmMetadata names the model type LFM2 (llm_model_type, field 6, with its lfm2 member, field 11), or ExecutorMetadata
// declares a state buffer that is a linear-attention state (StateBuffer.type 5) or LFM2's conv cache (kv_cache_c_*,
// which the runtime keeps among its KV buffers: GetKVCacheRootNames in litert_compiled_model_executor_utils.cc). The
// type alone misses files packed with generic_model, as litert-community's LFM2.5-230M was before 2026-10-14 and a
// third-party LFM2.5-VL-3B is; their ExecutorMetadata still declares the conv caches.
bool HasRecurrentState(int section_type, const std::string& proto) {
  bool found = false;
  if (section_type == 5) {  // LlmMetadataProto
    ForEachProtoField(proto, [&](int field, uint64_t, const std::string& type) {
      if (field == 6) ForEachProtoField(type, [&](int member, uint64_t, const std::string&) { found |= member == 11; });
    });
  } else if (section_type == 9) {  // ExecutorMetadataProto
    ForEachProtoField(proto, [&](int field, uint64_t, const std::string& llm) {
      if (field != 1) return;
      ForEachProtoField(llm, [&](int field, uint64_t, const std::string& buffer) {
        if (field != 2) return;  // state_buffers
        ForEachProtoField(buffer, [&](int field, uint64_t v, const std::string& name) {
          found |= (field == 6 && v == 5) || ((field == 1 || field == 3) && name.rfind("kv_cache_c_", 0) == 0);
        });
      });
    });
  }
  return found;
}

// LlmMetadata's sampler_params (field 4, a SamplerParameters: type 1, k 2, p 3, temperature 4), when it has them.
void ReadSampler(const std::string& llm_metadata, ModelFileInfo& info) {
  auto as_float = [](uint64_t bits) {
    const uint32_t b = static_cast<uint32_t>(bits);
    float f;
    std::memcpy(&f, &b, sizeof f);
    return f;
  };
  ForEachProtoField(llm_metadata, [&](int field, uint64_t, const std::string& params) {
    if (field != 4) return;
    ForEachProtoField(params, [&](int field, uint64_t v, const std::string&) {
      if (field == 1) info.sampler_type = static_cast<LiteRtLmSamplerType>(v);
      if (field == 2) info.top_k = static_cast<int32_t>(v);
      if (field == 3) info.top_p = as_float(v);
      if (field == 4) info.temperature = as_float(v);
    });
  });
}

// LlmMetadata's max_num_tokens (field 5): the model's context, which a prime number above 10 marks as dynamic, as
// LiteRT-LM reads it (IsMagicNumber in schema/model_info/model_info.cc).
void ReadContext(const std::string& llm_metadata, ModelFileInfo& info) {
  ForEachProtoField(llm_metadata, [&](int field, uint64_t v, const std::string&) {
    if (field == 5) info.max_context = info.metadata_context = static_cast<int>(std::min<uint64_t>(v, INT32_MAX));
  });
}

bool IsMagicNumber(int64_t n) {
  if (n < 11 || n % 2 == 0) return false;
  for (int64_t i = 3; i * i <= n; i += 2) {
    if (n % i == 0) return false;
  }
  return true;
}

void ReadThinking(const std::string& llm_metadata, ModelFileInfo& info) {
  ForEachProtoField(llm_metadata, [&](int field, uint64_t v, const std::string& bytes) {
    if (field == 11) info.supports_thinking = v != 0;
    if (field == 7) info.thinking_switch = bytes.find("enable_thinking") != std::string::npos;
    if (field == 8) info.channel = true;
  });
}

// What the server needs of the model's main TFLite graph (a tflite::Model FlatBuffer: subgraphs 2, signature_defs 7;
// SignatureDef inputs 0, outputs 1, signature_key 2, subgraph_index 4; TensorMap name 0, tensor_index 1; SubGraph
// tensors 0; Tensor shape 0), read through `m` where it lies in the file, a few tables of it and never the whole: the
// number of logits the decode signature outputs (the last dimension of its "logits", which is what the runtime sizes
// its sampler by: GetVocabSize in llm_litert_compiled_model_executor.cc); and, for a file whose LlmMetadata gives no
// max_num_tokens, the context: the last dimension of the first mask input of the first prefill signature that has one
// (ExtractContextTokensFromTfliteBuffer in schema/model_info/model_info.cc, which the C API reads it with).
void ReadTflite(const FbReader& m, ModelFileInfo& info) {
  const uint64_t model = m.Get<uint32_t>(0).value_or(0);  // the root table's uoffset, from 0
  const uint64_t subgraphs = m.Follow(m.Field(model, 2)), signatures = m.Follow(m.Field(model, 7));
  // The last dimension of the tensor that `entry`, one of the signature's inputs or outputs, names; 0 if none.
  auto last_dimension = [&](uint64_t signature, uint64_t entry) {
    const uint32_t subgraph = m.Scalar<uint32_t>(m.Field(signature, 4), 0);
    if (subgraph >= m.Length(subgraphs)) return 0;
    const uint64_t tensors = m.Follow(m.Field(m.Follow(subgraphs + 4 + 4ull * subgraph), 0));
    const uint32_t tensor = m.Scalar<uint32_t>(m.Field(entry, 1), 0);
    if (tensor >= m.Length(tensors)) return 0;
    const uint64_t shape = m.Follow(m.Field(m.Follow(tensors + 4 + 4ull * tensor), 0));
    const uint32_t rank = m.Length(shape);
    return rank ? m.Get<int32_t>(shape + 4 + 4ull * (rank - 1)).value_or(0) : 0;
  };
  const bool want_context = info.max_context == 0;
  for (uint32_t k = 0; k < m.Length(signatures) && k < 256; ++k) {
    const uint64_t signature = m.Follow(signatures + 4 + 4ull * k);
    const std::string key = signature ? m.String(m.Field(signature, 2)) : "";
    if (want_context && info.max_context == 0 && key.rfind("prefill", 0) == 0) {
      const uint64_t inputs = m.Follow(m.Field(signature, 0));
      for (uint32_t j = 0; j < m.Length(inputs) && j < 4096 && info.max_context == 0; ++j) {
        const uint64_t input = m.Follow(inputs + 4 + 4ull * j);
        if (input && m.String(m.Field(input, 0)).find("mask") != std::string::npos) {
          info.max_context = std::max(0, last_dimension(signature, input));
        }
      }
    }
    if (key != "decode") continue;
    const uint64_t outputs = m.Follow(m.Field(signature, 1));
    for (uint32_t j = 0; j < m.Length(outputs) && j < 4096; ++j) {
      const uint64_t output = m.Follow(outputs + 4 + 4ull * j);
      if (output && m.String(m.Field(output, 0)) == "logits") {
        info.vocabulary = std::max(0, last_dimension(signature, output));
      }
    }
  }
}

// The backends a section's backend_constraint lists, in its order, as LiteRT-LM 0.18.0 reads it
// (ParseBackendConstraint in schema/model_info/model_info.cc, what its C API's litert_lm_loaded_file_* answer): cpu
// or cpu_artisan, gpu or gpu_artisan, npu or google_tensor_artisan; a section without one takes cpu, then gpu.
std::vector<std::string> ConstraintBackends(const std::string& constraint) {
  if (constraint.empty()) return {"cpu", "gpu"};
  std::vector<std::string> out;
  std::stringstream ss(constraint);
  for (std::string b; std::getline(ss, b, ',');) {
    b = Trim(b);
    std::string name = b == "cpu" || b == "cpu_artisan" ? "cpu"
                       : b == "gpu" || b == "gpu_artisan" ? "gpu"
                       : b == "npu" || b == "google_tensor_artisan" ? "npu"
                                                                   : "";
    if (!name.empty() && std::find(out.begin(), out.end(), name) == out.end()) out.push_back(name);
  }
  return out;
}

// `header` is the file's bytes from 32 to the header's end, `in` the file. What the file takes and on which backends is
// what its header lists, as LiteRT-LM 0.18.0 reads it (DiscoverSections in schema/model_info/model_info.cc): each
// TFLite section's model_type and backend_constraint items, a vision or audio encoder or adapter being that input,
// the last one listed deciding its backends. The C API's litert_lm_loaded_file_create reads the same, but also reads
// whole sections into memory: every vision section, and the main model when LlmMetadata has no max_num_tokens
// (GetModelInfo), so asking it which inputs gemma-3-270m-it takes raised the server's peak memory by 285 MB, the size
// of its model, before any engine loaded, and by up to 2 GB for a larger bundle. Here nothing but the header, the small
// protobuf sections and a few tables of the main model (ReadTflite) is read. A header or section this cannot read says
// nothing.
ModelFileInfo ReadModelFileInfo(std::ifstream& in, const std::string& header) {
  ModelFileInfo info;
  const FbReader fb = MemoryReader(header);
  const uint64_t root = fb.Get<uint32_t>(0).value_or(0);  // the root table's uoffset, from 0
  const uint64_t sections = fb.Follow(fb.Field(fb.Follow(fb.Field(root, 1)), 0));  // section_metadata.objects
  const uint32_t n = fb.Length(sections);
  info.capabilities_known = n > 0;
  std::optional<std::string> vision, audio;  // the encoder's backend_constraint
  std::string text;                          // the decoder's, or the text encoder's
  bool npu_aux = false;                      // an NPU auxiliary model, which makes NPU a text backend too
  uint64_t main_begin = 0, main_end = 0;  // the main TFLite model, as DiscoverSections picks it
  bool main_found = false, other_found = false;
  for (uint32_t k = 0; k < n && k < 4096; ++k) {
    const uint64_t s = fb.Follow(sections + 4 + 4ull * k);
    if (!s) continue;
    const int type = fb.Scalar<uint8_t>(fb.Field(s, 3), 0);
    if (type == 3) {  // TFLiteModel
      std::string model_type, constraint;
      const uint64_t items = fb.Follow(fb.Field(s, 0));
      for (uint32_t j = 0; j < fb.Length(items) && j < 256; ++j) {
        const uint64_t item = fb.Follow(items + 4 + 4ull * j);  // a KeyValuePair, whose value is a StringValue (9)
        if (!item || fb.Scalar<uint8_t>(fb.Field(item, 1), 0) != 9) continue;
        const std::string key = fb.String(fb.Field(item, 0));
        const std::string value = Lower(Trim(fb.String(fb.Field(fb.Follow(fb.Field(item, 2)), 0))));
        if (key == "model_type") model_type = value;
        if (key == "backend_constraint") constraint = value;
      }
      // ClassifyModelType's kinds: the main model is the last main section, or else the first that is no other kind.
      const uint64_t begin = fb.Scalar<uint64_t>(fb.Field(s, 1), 0), end = fb.Scalar<uint64_t>(fb.Field(s, 2), 0);
      const bool main = model_type == "tf_lite_prefill_decode" || model_type == "tf_lite_prefill_decode_hw" ||
                        model_type == "tf_lite_prefill" || model_type == "tf_lite_decode" ||
                        model_type == "tf_lite_artisan_text_decoder";
      const bool other = !main && model_type != "tf_lite_text_encoder" && model_type != "tf_lite_embedder" &&
                         model_type != "tf_lite_vision_adapter" && model_type != "tf_lite_vision_encoder" &&
                         model_type != "tf_lite_audio_adapter" && model_type != "tf_lite_audio_encoder_hw" &&
                         model_type != "tf_lite_audio_frontend" && model_type != "tf_lite_video_adapter" &&
                         model_type != "tf_lite_video_encoder" && model_type != "tf_lite_mtp_drafter" &&
                         model_type != "tf_lite_aux";
      if (main || (other && !main_found && !other_found)) {
        main_begin = begin;
        main_end = end;
      }
      main_found |= main;
      other_found |= other;
      info.artisan |= model_type == "tf_lite_artisan_text_decoder";
      if (main || model_type == "tf_lite_text_encoder" || model_type == "tf_lite_embedder") text = constraint;
      npu_aux |= model_type == "tf_lite_aux";
      if (model_type == "tf_lite_vision_adapter" || model_type == "tf_lite_vision_encoder") vision = constraint;
      if (model_type == "tf_lite_audio_adapter" || model_type == "tf_lite_audio_encoder_hw" ||
          model_type == "tf_lite_audio_frontend") {
        audio = constraint;
      }
      continue;
    }
    const uint64_t begin = fb.Scalar<uint64_t>(fb.Field(s, 1), 0), end = fb.Scalar<uint64_t>(fb.Field(s, 2), 0);
    if ((type != 5 && type != 9) || end <= begin || end - begin > (1u << 20)) continue;
    std::string proto(end - begin, '\0');
    in.clear();
    if (!in.seekg(static_cast<std::streamoff>(begin)) || !in.read(&proto[0], static_cast<std::streamsize>(proto.size()))) {
      continue;
    }
    info.recurrent_state |= HasRecurrentState(type, proto);
    if (type == 5) {
      ReadSampler(proto, info);
      ReadThinking(proto, info);
      ReadContext(proto, info);
    }
  }
  if (main_end > main_begin) {
    const FbReader model{[&in, main_begin](uint64_t pos, void* out, size_t n) {
                           in.clear();
                           return static_cast<bool>(in.seekg(static_cast<std::streamoff>(main_begin + pos)) &&
                                                    in.read(static_cast<char*>(out), static_cast<std::streamsize>(n)));
                         },
                         main_end - main_begin};
    ReadTflite(model, info);
  }
  info.dynamic_context = IsMagicNumber(info.max_context);
  // The text's backends as the C API lists them (ConfigureSupportedBackendsAndNpu), except that an Artisan text decoder
  // puts GPU first, as `litert-lm serve` does (model.model_default_backend): LiteRT-LM runs it on GPU only, switching
  // GPU to GPU_ARTISAN for it (engine_settings.cc).
  info.text_backends = ConstraintBackends(text);
  if (npu_aux && std::find(info.text_backends.begin(), info.text_backends.end(), "npu") == info.text_backends.end()) {
    info.text_backends.push_back("npu");
  }
  if (info.artisan) {
    std::vector<std::string>& text_backends = info.text_backends;
    text_backends.erase(std::remove(text_backends.begin(), text_backends.end(), "gpu"), text_backends.end());
    text_backends.insert(text_backends.begin(), "gpu");
  }
  if (vision) info.vision_backends = ConstraintBackends(*vision);
  if (audio) info.audio_backends = ConstraintBackends(*audio);
  return info;
}

// ModelFileInfo for the model file, read once per version of it (its size and modification time). A .litertlm starts
// with "LITERTLM", three uint32 versions, 4 bytes of padding and a little-endian uint64 header-end offset (the same 32
// bytes the daemon's models/litertlm.ts reads). The runtime allocates and zeroes header_end - 32 bytes before reading
// the header, and the std::bad_alloc a bogus offset raises crosses the C API into std::terminate, killing the whole
// server, so the offset is bounded here first. The bound is the runtime's own: it reads the header from the file's
// first 16 KiB only (kLitertLmHeaderMaxSize, LiteRT-LM 0.18.0's litert_lm_loader.h), so a larger offset can never load,
// yet would still cost an allocation up to the file's size (hundreds of MB on a phone). Checked before the loaded
// engine is released, which a bad file then leaves in place. A file that can't be read or lacks the magic is left to
// the runtime, which reports it cleanly and more precisely (e.g. "Permission denied"), and says nothing here. Caller
// holds g_engine_mu.
const ModelFileInfo& FileInfo(const ModelRef& ref) {
  struct Cached {
    long long size = -1, mtime = -1;
    ModelFileInfo info;
  };
  static std::map<std::string, Cached> cache;
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
  Cached& c = cache[ref.path];
  if (c.size == static_cast<long long>(st.st_size) && c.mtime == static_cast<long long>(st.st_mtime)) return c.info;
  c = Cached{static_cast<long long>(st.st_size), static_cast<long long>(st.st_mtime), ModelFileInfo()};
  if (!ok) return c.info;
  std::string header(header_end - 32, '\0');
  if (in.read(&header[0], static_cast<std::streamsize>(header.size()))) c.info = ReadModelFileInfo(in, header);
  return c.info;
}

// ── Engine lifetime (util.get_or_initialize_server_engine) ─────────────────────────────────

struct EngineKey {
  std::string path, backend, cache;
  std::string vision_backend, audio_backend;  // empty: no vision or audio executor
  int max_num_tokens = -1, threads = -1, gpu_steps = -1, spec = -1;
  bool operator==(const EngineKey& o) const {
    return path == o.path && backend == o.backend && cache == o.cache && vision_backend == o.vision_backend &&
           audio_backend == o.audio_backend && max_num_tokens == o.max_num_tokens && threads == o.threads &&
           gpu_steps == o.gpu_steps && spec == o.spec;
  }
};

struct EngineHolder {
  LiteRtLmEngine* engine = nullptr;
  EngineKey key;
  double init_seconds = 0;
  // Set once a conversation with constrained decoding failed to be created for this engine's model and one without
  // it was created: its data processor cannot constrain tool calls (see HandleChatCompletions), so its later requests
  // with tools are created without it at once. Cleared when another engine is loaded.
  bool tools_unconstrained = false;
  // What the model file says about itself (ModelFileInfo), as it was when this engine was created.
  ModelFileInfo info;
  // The backend the engine runs on: key.backend, or CPU when the model's own backend did not start.
  std::string backend;
  // The context window this engine has, in tokens (-1: unknown), and the larger one that was asked for, if any.
  int window = -1, window_asked = -1;
  // The model's decoder keeps a recurrent state (info.recurrent_state), and a message has been sent on this engine
  // since it was created, so that state is no longer the initial one.
  bool state_used = false;
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

// The backend for a model's vision or audio encoder: the config's vision_backend or audio_backend when it sets one,
// else the one the file prefers, as `litert-lm serve` chooses them (util.get_or_initialize_server_engine and
// model.parse_backend: the config key, else the encoder section's first backend_constraint, else cpu, which is also
// what ReadModelFileInfo lists first). Empty for a model without that input: LiteRT-LM then creates no executor for it.
std::string EncoderBackend(const ModelRef& ref, const std::vector<std::string>& supported,
                           const std::optional<std::string>& configured, const char* input) {
  if (supported.empty()) {
    if (configured) {
      static std::set<std::string> told;
      if (told.insert(ref.path + input).second) {
        Log(std::string(input) + "_backend in the config is not used for " + ref.id + ": it takes no " + input +
            " input");
      }
    }
    return "";
  }
  if (!configured) return supported.front();
  std::string b = Lower(*configured);
  if (b != "cpu" && b != "gpu" && b != "npu") {
    throw HttpError(500, "Failed to load engine: ValueError(\"Unsupported " + std::string(input) + " backend: '" + b +
                             "'\")");
  }
  return b;
}

// What a request's messages hold besides text, which only a model with that input can take.
struct InputNeeds {
  bool image = false, audio = false;
};

// The context window LiteRT-LM 0.18.0 gives an engine of this model on `backend` when `asked` tokens are asked for
// (0 or less: none), worked out as it does, so that the window the server reports and judges "length" by is the
// engine's; 0 when the file does not say. With none asked, the runtime takes LlmMetadata's max_num_tokens, or 4096
// for a file without one, and on GPU no more than 4096 (EngineSettings::MaybeUpdateAndValidate, engine_settings.cc;
// an Artisan decoder runs as GPU_ARTISAN, which that cap leaves out). A static model's window is then cut to the
// model's own (ClampMaxNumTokens, llm_litert_compiled_model_executor.cc). A dynamic one, whose context is a magic
// number M, is made for that many tokens when they are fewer than M, and otherwise for LiteRT-LM's default for M: the
// largest multiple of 256 below it, or below 256 the largest power of two (GetTargetNumber and GetDefaultTargetNumber,
// magic_number_configs_helper.cc). So M itself, which the server reported, is never what such a model gets.
int RuntimeWindow(const ModelFileInfo& info, int asked, const std::string& backend) {
  if (info.max_context <= 0) return std::max(asked, 0);
  int n = asked;
  if (n <= 0) {
    n = info.metadata_context > 0 ? info.metadata_context : 4096;
    if (backend == "gpu" && !info.artisan && n > 4096) n = 4096;
  }
  if (!info.dynamic_context) return std::min(n, info.max_context);
  const int m = info.max_context;
  if (n < m) return n;
  if (m > 256) return m / 256 * 256;
  int t = 256;
  while (t > m) t /= 2;
  return t;
}

// Caller holds g_engine_mu.
LiteRtLmEngine* GetOrCreateEngine(const ModelRef& ref, const ModelConfig& mc, const InputNeeds& needs) {
  EngineKey key;
  key.path = ref.path;
  key.backend = ref.backend ? *ref.backend : (mc.backend ? Lower(*mc.backend) : "");
  key.max_num_tokens = ref.max_num_tokens ? *ref.max_num_tokens : (mc.max_num_tokens ? *mc.max_num_tokens : -1);
  key.threads = mc.cpu_thread_count ? *mc.cpu_thread_count : -1;
  key.gpu_steps = mc.gpu_decode_steps_per_sync ? *mc.gpu_decode_steps_per_sync : -1;
  key.spec = mc.speculative_decoding ? (*mc.speculative_decoding ? 1 : 0) : -1;
  key.cache = CacheDirValue(mc.cache);
  if (key.cache.empty()) key.cache = FallbackCacheDir(ref.path);
  if (!key.backend.empty() && key.backend != "cpu" && key.backend != "gpu" && key.backend != "npu") {
    throw HttpError(500, "Failed to load engine: ValueError(\"Unsupported backend: '" + key.backend + "'\")");
  }
  const ModelFileInfo& info = FileInfo(ref);
  // With no backend in the model reference or the config, the model's own: the first its header lists for its text
  // (ReadModelFileInfo: a GPU-only bundle lists gpu alone, one with no constraint [cpu, gpu]), as `litert-lm serve`
  // picks it (model.model_default_backend: an Artisan text decoder means gpu, else the decoder section's first
  // backend_constraint, else cpu). The server picked CPU for every model, so a GPU-only bundle sent without a backend
  // failed to load ("Main backend constraint mismatch", engine_settings.cc). A backend the model picked that does not
  // start falls back to the next one it lists (below); one the user set does not.
  const bool backend_set = !key.backend.empty();
  if (!backend_set) key.backend = info.text_backends.empty() ? "cpu" : info.text_backends.front();
  // A vision or audio executor is created only when the engine settings name a backend for it (c/engine.cc,
  // CreateEngineSettingsHelper), and none was named, so an image sent to LFM2.5-VL-450M got a 500 "Vision executor
  // should not be null, please TryLoadingVisionExecutor() first." although the model takes images.
  key.vision_backend = EncoderBackend(ref, info.vision_backends, mc.vision_backend, "vision");
  key.audio_backend = EncoderBackend(ref, info.audio_backends, mc.audio_backend, "audio");
  // An image or audio part for a model that has no such input is the request's mistake, said before anything loads.
  // When the server could not read the file's header, the runtime is left to say it.
  if (info.capabilities_known && needs.image && key.vision_backend.empty()) {
    throw HttpError(400, "Invalid messages: " + ref.id + " takes no image input");
  }
  if (info.capabilities_known && needs.audio && key.audio_backend.empty()) {
    throw HttpError(400, "Invalid messages: " + ref.id + " takes no audio input");
  }

  // A model whose decoder keeps a recurrent state gets a new engine for every conversation after the first. LiteRT-LM
  // 0.18.0 starts each new conversation on an engine at step 0 and keeps the executor's buffers as they are there
  // (RestoreContext in llm_litert_compiled_model_executor.cc), which is right for a KV cache, whose entries past the
  // step are masked, but not for LFM2's short-convolution cache: it is read as it is, so every conversation after the
  // first began from the end of the one before. On one engine LFM2.5-230M answered [system "Answer in one word.",
  // user "What is the capital of France?", assistant "Paris", user "And of Italy?"] with "Paris is the capital of
  // France." after that same question's first turn and a title request, and with "The capital of Italy is **Rome**."
  // on a fresh one (LiteRT-LM #3165; the model card says to create a new Engine for each conversation). The C API has
  // no way to reset that state, so the engine is created again, with the same settings.
  const bool fresh_state = g_engine.engine && g_engine.key == key && g_engine.info.recurrent_state && g_engine.state_used;
  if (g_engine.engine && g_engine.key == key && !fresh_state) return g_engine.engine;
  if (fresh_state) {
    static std::set<std::string> told;  // once per model file, not per conversation
    if (told.insert(ref.path).second) {
      Log(ref.id + " keeps a recurrent state that LiteRT-LM carries from one conversation to the next on an engine; "
                   "its engine is created again for each conversation");
    }
    ReleaseEngine();
  } else if (g_engine.engine) {
    Log("Re-initializing engine (model: " + ref.id + ", backend: " + key.backend + ")");
    ReleaseEngine();
  }

  // A context larger than the model takes was cut to the model's by the runtime with only a WARNING in its log
  // ("Passed in max_num_tokens (8192) is larger than what the static model supports (4096). Using model limit.",
  // ClampMaxNumTokens in llm_litert_compiled_model_executor.cc), so nobody was told, and "length" was judged against
  // the size asked for: a reply cut off at the model's 4096 read "stop". Now the server gives the model its own
  // limit, says so in the log, and reports both on /v1/models and in each reply's headers. With no size asked for,
  // the window is the one the runtime picks (RuntimeWindow, once the backend is known).
  int window = key.max_num_tokens > 0 ? RuntimeWindow(info, key.max_num_tokens, key.backend) : -1, window_asked = -1;
  if (window > 0 && window < key.max_num_tokens) {
    window_asked = key.max_num_tokens;
    // Once per model and size: a model that gets a new engine for each conversation (LFM2) would log it every time.
    static std::set<std::string> told;
    if (told.insert(ref.path + "," + std::to_string(window_asked)).second) {
      Log("max_num_tokens " + std::to_string(window_asked) + " is more than " + ref.id + " takes (" +
          (info.dynamic_context ? "its dynamic context takes fewer than " + std::to_string(info.max_context)
                                : "its fixed context is " + std::to_string(info.max_context)) +
          " tokens); its context window is " + std::to_string(window));
    }
  }
  auto t0 = std::chrono::steady_clock::now();
  // The engine on `backend`, or null with the runtime's reason in `why`.
  std::string why;
  auto create = [&](const std::string& backend) -> LiteRtLmEngine* {
    Log("Initializing engine for model: " + ref.path + " (backend: " + backend +
        (key.vision_backend.empty() ? "" : ", vision: " + key.vision_backend) +
        (key.audio_backend.empty() ? "" : ", audio: " + key.audio_backend) + ")");
    L.litert_lm_clear_last_error();
    LiteRtLmEngineSettings* s = L.litert_lm_engine_settings_create(
        ref.path.c_str(), backend.c_str(), key.vision_backend.empty() ? nullptr : key.vision_backend.c_str(),
        key.audio_backend.empty() ? nullptr : key.audio_backend.c_str());
    if (!s) {
      why = "Failed to create engine settings for " + ref.path;
      if (std::string runtime = LastRuntimeError(); !runtime.empty()) why += ": " + runtime;
      return nullptr;
    }
    L.litert_lm_engine_settings_enable_benchmark(s);
    L.litert_lm_engine_settings_set_use_ringbuffers_local_attention(s, true);
    if (backend == "cpu" && key.threads > 0) L.litert_lm_engine_settings_set_num_threads(s, key.threads);
    if (window > 0) L.litert_lm_engine_settings_set_max_num_tokens(s, window);
    L.litert_lm_engine_settings_set_cache_dir(s, key.cache.c_str());
    if (key.spec >= 0) L.litert_lm_engine_settings_set_enable_speculative_decoding(s, key.spec == 1);
    if (backend == "gpu" && key.gpu_steps > 0) L.litert_lm_engine_settings_set_gpu_decode_steps_per_sync(s, key.gpu_steps);
    LiteRtLmEngine* e = L.litert_lm_engine_create(s);
    L.litert_lm_engine_settings_delete(s);
    if (!e) {
      why = "Failed to create LiteRT-LM engine for " + ref.path;
      if (std::string runtime = LastRuntimeError(); !runtime.empty()) why += ": " + runtime;
    }
    return e;
  };
  // A new engine for a fresh state (above) runs where the one before it did, so a model whose own backend fell back is
  // not tried on that backend again for every conversation. A backend other than CPU that the model picked and that
  // does not start falls back to the next one the model lists, and only to one it lists: a GPU-only bundle was tried
  // on CPU, which cannot run it, and the error then gave the CPU's reason instead of the GPU's. When none starts, the
  // error gives each one's reason.
  std::vector<std::string> backends = {fresh_state ? g_engine.backend : key.backend};
  if (!backend_set && backends[0] != "cpu") {
    auto at = std::find(info.text_backends.begin(), info.text_backends.end(), backends[0]);
    if (at != info.text_backends.end()) backends.insert(backends.end(), at + 1, info.text_backends.end());
  }
  if (!backend_set && !fresh_state && backends[0] != "cpu") Log("Using " + ref.id + "'s own backend: " + backends[0]);
  LiteRtLmEngine* e = nullptr;
  std::string backend, reasons;
  for (size_t i = 0; i < backends.size() && !e; ++i) {
    backend = backends[i];
    if ((e = create(backend))) break;
    reasons += (reasons.empty() ? "" : "; ") + (backends.size() > 1 ? "on " + backend + ": " : std::string()) + why;
    if (i + 1 < backends.size()) {
      Log(ref.id + "'s own backend, " + backend + ", did not start (" + why + "); falling back to " + backends[i + 1]);
    }
  }
  if (!e) throw HttpError(500, "Failed to load engine: RuntimeError('" + reasons + "')");
  if (window <= 0) window = RuntimeWindow(info, -1, backend);
  g_engine.engine = e;
  g_engine.key = key;
  g_engine.backend = backend;
  // The same model and settings again: what was found out about the model still holds.
  if (!fresh_state) g_engine.tools_unconstrained = false;
  g_engine.info = info;
  g_engine.window = window > 0 ? window : -1;
  g_engine.window_asked = window_asked;
  g_engine.state_used = false;
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
  // OpenAI's newer name for the system role. Model templates know only "system", so a developer message was rendered
  // as an unknown role (LFM2.5-230M showed the word in its prompt) and its instructions were not followed: with
  // developer "Answer in one word." it answered "The capital of France is Paris." where the system role gets "Paris".
  if (role == "developer") {
    static std::once_flag told;
    std::call_once(told, [] { Log("the developer role is read as system, its older name"); });
    json out = msg;
    out["role"] = "system";
    return TranslateMessage(out, names);
  }

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
                  const std::string& body, const std::string& extra_headers = "") {
  std::string head = "HTTP/1.1 " + std::to_string(status) + " " + SafeReason(reason) + "\r\n";
  head += std::string("Server: ") + kServerName + "/" + kServerVersion + "\r\n";
  head += "Content-Type: " + content_type + "\r\n";
  head += "Content-Length: " + std::to_string(body.size()) + "\r\n";
  head += "Connection: close\r\n";
  head += extra_headers;
  head += CorsHeaders(req);
  head += "\r\n";
  return SendAll(fd, head + body);
}

bool SendJson(int fd, const Request& req, int status, const json& j, const std::string& extra_headers = "") {
  return SendResponse(fd, req, status, ReasonFor(status), "application/json",
                      j.dump(2, ' ', false, json::error_handler_t::replace) + "\n", extra_headers);
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

// Thinking as a request asks for it. The server read only the top-level reasoning_effort and the config's thinking
// keys (openai_common.parse_thinking_config), and passed an empty extra context with every message, so what TurboLLM
// and other clients send was dropped: chat_template_kwargs (enable_thinking, reasoning_effort, preserve_thinking),
// which LiteRT-LM renders into the model's Jinja template as extra context (c/conversation.cc, CreateOptionalArgs), and
// thinking_budget_tokens, whose budget the runtime enforces (ThinkingBudgetConstraint, runtime/core/tasks.cc). On
// Qwen3-0.6B {"chat_template_kwargs": {"enable_thinking": false}} and {"thinking_budget_tokens": 0} both thought for
// 190 tokens; only reasoning_effort "none" turned it off. In order, the first that says: the top-level
// reasoning_effort (as the Python server, and what the daemon sends for a LiteRT-LM model), then
// chat_template_kwargs.enable_thinking, then chat_template_kwargs.reasoning_effort, then thinking_budget_tokens (0 is
// off, n > 0 a budget of n, below 0 no budget), then the config's thinking keys. A budget of n > 0 applies whichever
// turned thinking on. reasoning_effort's levels other than none turn it on with no budget, and none turns it off, as in
// the Python server.
struct Thinking {
  bool enable;
  int budget;  // -1: none
  std::string from;
};

// Whether a model thinks, as its file says: its metadata's supports_thinking, else its template's switch or a thinking
// channel (gemma-3-270m-it has neither).
bool Thinks(const ModelFileInfo& m) { return m.supports_thinking ? *m.supports_thinking : m.thinking_switch || m.channel; }

// The 400 for thinking turned on, by `t`, for a model that does not think.
HttpError ThinkingRefused(const ModelFileInfo& m, const Thinking& t, const std::string& id) {
  return HttpError(400, "Invalid thinking parameters: " + t.from + " turns thinking on, but " + id +
                            (m.supports_thinking ? " does not think (its metadata says so)" : " does not think"));
}

std::optional<Thinking> ParseThinking(const json& body, const ModelConfig& mc) {
  auto effort = [](const json& v, const std::string& field) -> Thinking {
    if (!v.is_string()) throw HttpError(400, "Invalid thinking parameters: ValueError: " + field + " must be a string");
    std::string e = Lower(v.get<std::string>());
    if (e == "none") return {false, 0, field};
    if (e == "minimal" || e == "low" || e == "medium" || e == "high" || e == "xhigh") return {true, -1, field};
    throw HttpError(400, "Invalid thinking parameters: ValueError: Invalid " + field + " value: '" + e +
                             "'. Supported strings: none, minimal, low, medium, high, xhigh.");
  };
  auto has = [](const json& o, const char* k) { return o.contains(k) && !o[k].is_null(); };
  std::optional<Thinking> t;
  if (has(body, "reasoning_effort")) t = effort(body["reasoning_effort"], "reasoning_effort");
  if (has(body, "chat_template_kwargs")) {
    const json& ctk = body["chat_template_kwargs"];
    if (!ctk.is_object()) throw HttpError(400, "Invalid thinking parameters: ValueError: chat_template_kwargs must be an object");
    if (has(ctk, "enable_thinking")) {
      if (!ctk["enable_thinking"].is_boolean()) {
        throw HttpError(400, "Invalid thinking parameters: ValueError: chat_template_kwargs.enable_thinking must be a boolean");
      }
      if (!t) t = Thinking{ctk["enable_thinking"].get<bool>(), ctk["enable_thinking"].get<bool>() ? -1 : 0,
                           "chat_template_kwargs.enable_thinking"};
    }
    if (has(ctk, "reasoning_effort")) {
      Thinking e = effort(ctk["reasoning_effort"], "chat_template_kwargs.reasoning_effort");
      if (!t) t = e;
    }
  }
  if (has(body, "thinking_budget_tokens")) {
    const json& b = body["thinking_budget_tokens"];
    if (!b.is_number_integer()) throw HttpError(400, "Invalid thinking parameters: ValueError: thinking_budget_tokens must be an integer");
    const long long n = b.get<long long>();
    if (n > INT32_MAX) throw HttpError(400, "Invalid thinking parameters: ValueError: thinking_budget_tokens is too large");
    if (!t) {
      t = Thinking{n != 0, n > 0 ? static_cast<int>(n) : n == 0 ? 0 : -1, "thinking_budget_tokens"};
    } else if (t->enable && n > 0) {
      t->budget = static_cast<int>(n);
    } else if (t->enable != (n != 0)) {
      Log("thinking_budget_tokens " + std::to_string(n) + " is not used: " + t->from + " turned thinking " +
          (t->enable ? "on" : "off"));
    }
  }
  if (t || (!mc.thinking && !mc.thinking_budget)) return t;
  bool thinking = mc.thinking ? *mc.thinking : (*mc.thinking_budget != 0);
  int budget = mc.thinking_budget ? *mc.thinking_budget : (thinking ? -1 : 0);
  return Thinking{thinking, budget, "the config's thinking"};
}

// Repetition penalties, which LiteRT-LM 0.18.0 applies per message while it decodes (RepetitionPenaltyConfig, through
// litert_lm_conversation_optional_args_set_repetition_penalty_config; tasks.cc), and which the server dropped:
// presence_penalty and frequency_penalty (OpenAI's: subtracted from a token's logit once, or once per occurrence) and
// repetition_penalty, or repeat_penalty, llama.cpp's name, which the daemon sends (HuggingFace's multiplicative
// penalty, 1 = none). Its window: repeat_last_n when the request sets it, as llama.cpp reads it (0 turns the penalties
// off, -1 is the whole reply); else llama.cpp's own default of the last 64 tokens for a request that names the penalty
// repeat_penalty, llama.cpp's way; else the whole reply, as OpenAI's penalties and LiteRT-LM's default are. LiteRT-LM
// counts the tokens of the reply only, not the prompt's (repetition_penalty_config.h). A repetition penalty below 1,
// which in llama.cpp favours repeats, LiteRT-LM clamps to 1 without a word, so it is a 400. none: no penalty.
struct Penalties {
  float repetition = 1, presence = 0, frequency = 0;
  int window = 0;
};

std::optional<Penalties> ParsePenalties(const json& body) {
  auto has = [&](const char* k) { return body.contains(k) && !body[k].is_null(); };
  auto number = [&](const char* k) {
    if (!body[k].is_number() || !std::isfinite(body[k].get<double>())) {
      throw HttpError(400, std::string("Invalid sampler parameters: ValueError: ") + k + " must be a number");
    }
    return body[k].get<float>();
  };
  Penalties p;
  const char* rep = has("repetition_penalty") ? "repetition_penalty" : has("repeat_penalty") ? "repeat_penalty" : nullptr;
  if (rep) {
    p.repetition = number(rep);
    if (p.repetition < 1) {
      throw HttpError(400, std::string("Invalid sampler parameters: ") + rep + " " + body[rep].dump() +
                               " is below 1, which LiteRT-LM does not take (1 is no penalty)");
    }
  }
  if (has("presence_penalty")) p.presence = number("presence_penalty");
  if (has("frequency_penalty")) p.frequency = number("frequency_penalty");
  p.window = rep && std::string(rep) == "repeat_penalty" ? 64 : 0;
  if (has("repeat_last_n")) {
    if (!body["repeat_last_n"].is_number_integer() || body["repeat_last_n"].get<long long>() < -1 ||
        body["repeat_last_n"].get<long long>() > INT32_MAX) {
      throw HttpError(400, "Invalid sampler parameters: ValueError: repeat_last_n must be an integer of -1 or more");
    }
    const int n = body["repeat_last_n"].get<int>();
    if (n == 0) return std::nullopt;
    p.window = n > 0 ? n : 0;
  }
  if (p.repetition == 1 && p.presence == 0 && p.frequency == 0) return std::nullopt;
  return p;
}

// The sampler a conversation is created with. A session's sampler parameters replace the model's own entirely: the
// runtime uses LlmMetadata's only when the session's type is UNSPECIFIED (SessionConfig::MaybeUpdateAndValidate in
// engine_settings.cc, LiteRT-LM 0.18.0). The server used to build one only when the request set any of temperature,
// top_p, top_k and seed, and then filled every field the request left out with a fixed value: top_k 1, which is
// greedy decoding (sampling_cpu_util.cc), top_p 0.95, temperature 1 and seed 0. So a temperature or top_p sent alone
// never had any effect (with temperature 1.5 and seeds 1, 2 and 3, LFM2.5-230M wrote the same sentence it writes
// with none), and the model's own parameters were thrown away whenever a request set one field. And the seed was 0
// whenever the request gave none, the runtime's default as well (engine_settings.cc), so every conversation drew the
// same "random" sample and a Regenerate repeated the answer word for word.
struct Sampling {
  int top_k;
  float top_p, temperature;
  int seed;
};

// The top_k that is no top-k limit. On CPU the largest k, which the CPU sampler clamps to the vocabulary
// (TopKTopPSampling in sampling_cpu_util.cc). On GPU the vocabulary itself, all the tokens there are: the GPU sampler
// is created for the k it is given and the model's number of logits (CreateGpuSampler in sampler_factory.cc; the
// sampler itself is not open source), so it gets no k larger than that. LiteRT-LM 0.18.0 sets no top_k limit of its
// own there: GpuConfig::max_top_k, 64, is read nowhere. Where the vocabulary is unknown, the largest k, as on CPU.
int NoTopKLimit(const std::string& backend, const ModelFileInfo& model) {
  return backend == "gpu" && model.vocabulary > 0 ? model.vocabulary : INT32_MAX;
}

// Each field the request sets, as it set it; the others from the model's own parameters, or, for a model that has
// none, LiteRT-LM's own defaults for CPU and GPU, the ones it would use (engine_settings.cc: TOP_P, k 1, p 0.95,
// temperature 1); and a random seed unless the request gives one. The CPU sampler implements TOP_P only
// (CreateCpuSampler in sampler_factory.cc), and TOP_P with p 1 is top-k sampling, so a model whose own type is TOP_K
// or GREEDY gets that. On NPU, and for an Artisan text decoder on GPU, which LiteRT-LM runs as GPU_ARTISAN, its
// default type is UNSPECIFIED (engine_settings.cc), which leaves sampling to the model, so there a sampler is set only
// for a request that sets one of the fields, as before. none: the runtime's own choice.
std::optional<Sampling> SamplingFor(const json& body, const ModelFileInfo& model, const std::string& backend,
                                    const std::string& model_id, bool request_sets_any) {
  const bool model_samples = (backend != "cpu" && backend != "gpu") || (backend == "gpu" && model.artisan);
  if (model_samples && !request_sets_any) return std::nullopt;
  Sampling s{1, 0.95f, 1.0f, 0};
  switch (model.sampler_type) {
    case kLiteRtLmSamplerTypeTopP:
      s = {model.top_k, model.top_p, model.temperature, 0};
      break;
    case kLiteRtLmSamplerTypeTopK:
      s = {model.top_k, 1.0f, model.temperature, 0};
      break;
    case kLiteRtLmSamplerTypeGreedy:
      s = {1, 1.0f, 1.0f, 0};
      break;
    default:
      break;
  }
  // A model that names TOP_P or TOP_K with no k (0) asks for no top-k limit; the runtime refuses k 0.
  if (s.top_k <= 0) s.top_k = NoTopKLimit(backend, model);
  auto has = [&](const char* k) { return body.contains(k) && !body[k].is_null(); };
  if (has("temperature")) s.temperature = body["temperature"].get<float>();
  if (has("top_p")) s.top_p = body["top_p"].get<float>();
  if (has("top_k")) {
    const int k = static_cast<int>(body["top_k"].get<double>());
    if (k <= 0) {
      s.top_k = NoTopKLimit(backend, model);
      static std::set<std::string> told;
      if (told.insert(model_id + "," + backend).second) {
        const char* what = s.top_k != INT32_MAX ? " (its vocabulary)"
                           : backend == "cpu"   ? " (the CPU sampler takes the whole vocabulary)"
                                                : " (its vocabulary is unknown)";
        Log("top_k " + std::to_string(k) + " is no top-k limit: top_k " + std::to_string(s.top_k) + " for " + model_id +
            what);
      }
    } else {
      s.top_k = k;  // as set, on GPU too (see NoTopKLimit)
    }
  } else if (s.top_k == 1 && ((has("temperature") && s.temperature > 0) || (has("top_p") && s.top_p < 1))) {
    // k 1 picks the likeliest token whatever the temperature or top_p, so the request's would have no effect: the
    // model's default here is greedy decoding, and the request asks to sample. It gets no top-k limit.
    s.top_k = NoTopKLimit(backend, model);
    static std::set<std::string> told;  // once per model and backend, not per request
    if (told.insert(model_id + "," + backend).second) {
      Log("top_k is not set and " + model_id + "'s default is 1 (greedy), under which the request's " +
          (has("temperature") ? "temperature" : "top_p") + " would have no effect; sampling on " + backend +
          " with top_k " + std::to_string(s.top_k) +
          (s.top_k == INT32_MAX ? " (no limit)" : " (its vocabulary: no limit)"));
    }
  }
  if (has("seed")) {
    s.seed = static_cast<int>(body["seed"].get<double>());
  } else {
    // The CPU sampler seeds std::default_random_engine (minstd_rand0) with it, which takes 0 and 1 alike, so the
    // range starts at 1; each conversation seeds its own generator (CreateNewContext).
    static std::mt19937 gen{std::random_device{}()};
    s.seed = std::uniform_int_distribution<int>(1, INT32_MAX - 1)(gen);
  }
  return s;
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

    // max_completion_tokens, or max_tokens, OpenAI's older name for it, which the server did not read: a client that
    // sent max_tokens 5 got the whole reply. Below 1 is a 400, as OpenAI answers it: the runtime generated one token
    // for 0 or -5 and the server called that "length".
    std::optional<int> max_tokens;
    for (const char* k : {"max_completion_tokens", "max_tokens"}) {
      if (max_tokens || !body.contains(k) || body[k].is_null()) continue;
      if (!body[k].is_number_integer() || body[k].get<long long>() < 1 || body[k].get<long long>() > INT32_MAX) {
        SendError(fd, req, 400, std::string(k) + " must be an integer of at least 1, got " + body[k].dump());
        return;
      }
      max_tokens = body[k].get<int>();
    }

    // What LiteRT-LM's conversation API cannot give, said rather than dropped: it returns one reply per message, with no
    // token log-probabilities (those exist only on its lower-level session API), so n above 1 got one choice and
    // logprobs none, both with a 200.
    {
      auto has = [&](const char* k) { return body.contains(k) && !body[k].is_null(); };
      std::string why;
      if (has("n") && !(body["n"].is_number_integer() && body["n"].get<long long>() == 1)) {
        why = "n " + body["n"].dump() + ": this server gives one choice per request";
      } else if (has("logprobs") && !(body["logprobs"].is_boolean() && !body["logprobs"].get<bool>())) {
        why = "logprobs " + body["logprobs"].dump() + ": LiteRT-LM gives no token log-probabilities";
      } else if (has("top_logprobs") && !(body["top_logprobs"].is_number_integer() && body["top_logprobs"].get<long long>() == 0)) {
        why = "top_logprobs " + body["top_logprobs"].dump() + ": LiteRT-LM gives no token log-probabilities";
      }
      if (!why.empty()) {
        SendError(fd, req, 400, "Unsupported parameter: " + why);
        return;
      }
    }

    // openai_chat_completions.parse_sampler_config. A value the runtime cannot take is the request's mistake, a 400 that
    // names the field: it was a 500 with the runtime's words ("INVALID_ARGUMENT: p must be in [0, 1].", "Temperature
    // must be >= 0"), and a top_k or seed with a fraction was cut to an integer. top_k 0 or below is no top-k limit,
    // as in llama.cpp (TurboLLM's Top K 0 means disabled; vLLM uses -1): see SamplingFor.
    bool has_sampler = false;
    for (const char* k : {"temperature", "top_p", "top_k", "seed"}) {
      if (body.contains(k) && !body[k].is_null()) {
        if (!body[k].is_number()) {
          SendError(fd, req, 400, std::string("Invalid sampler parameters: ValueError: ") + k + " must be a number");
          return;
        }
        const double v = body[k].get<double>();
        const bool integral = std::isfinite(v) && v == std::floor(v) && v >= INT32_MIN && v <= INT32_MAX;
        std::string why;
        if (std::string(k) == "temperature" && !(v >= 0)) why = "temperature must be at least 0";
        if (std::string(k) == "top_p" && !(v >= 0 && v <= 1)) why = "top_p must be between 0 and 1";
        if ((std::string(k) == "top_k" || std::string(k) == "seed") && !integral) why = std::string(k) + " must be an integer";
        if (!why.empty()) {
          SendError(fd, req, 400, "Invalid sampler parameters: ValueError: " + why + ", got " + body[k].dump());
          return;
        }
        has_sampler = true;
      }
    }
    std::optional<Thinking> thinking = ParseThinking(body, mc);
    std::optional<Penalties> penalties = ParsePenalties(body);
    std::string note_headers;  // headers that tell the client what the server did with its request
    // min_p, which LiteRT-LM has no sampler for, was dropped without a word. A 400 for it would fail every chat of a
    // thread or model whose saved settings have a Min P, chats that worked, so it is still not applied, but the reply
    // says so (X-Unapplied-Parameters), and the log once per model (below, under the engine lock).
    bool min_p_unapplied = false;
    if (body.contains("min_p") && !body["min_p"].is_null()) {
      if (!body["min_p"].is_number() || !std::isfinite(body["min_p"].get<double>())) {
        SendError(fd, req, 400, "Invalid sampler parameters: ValueError: min_p must be a number");
        return;
      }
      if (body["min_p"].get<double>() > 0) {
        note_headers += "X-Unapplied-Parameters: min_p\r\n";
        min_p_unapplied = true;
      }
    }
    auto constraint = ParseResponseFormat(body);

    json tools = json::array();
    if (body.contains("tools") && body["tools"].is_array()) {
      for (const auto& t : body["tools"]) {
        if (t.is_object() && t.value("type", "") == "function") tools.push_back(t);
      }
    }
    // tool_choice was ignored, so "none", which the daemon sends to make a model answer in text (its gateway after a
    // repeated call, and its chat's retry after an empty answer), still offered the model the tools and got tool calls
    // back. "none" now leaves the tools out of the conversation; the history keeps its earlier calls and results.
    // "auto", or none at all, is what it was. LiteRT-LM 0.18.0's C API has no way to make a model call a tool (its
    // constrained decoding keeps a call well formed, and Gemma's "function call only" mode is set by the model file,
    // not per conversation), so "required" or a named function is a 400 that says so rather than a reply that may
    // not call it.
    if (body.contains("tool_choice") && !body["tool_choice"].is_null()) {
      const json& choice = body["tool_choice"];
      const std::string mode = choice.is_string() ? choice.get<std::string>() : "";
      if (mode == "none") {
        tools = json::array();
      } else if (mode == "required" || choice.is_object()) {
        SendError(fd, req, 400, "Invalid tool_choice: this server cannot make the model call a tool (" + choice.dump() +
                                    "); LiteRT-LM can only offer it the tools, as tool_choice \"auto\" does");
        return;
      } else if (mode != "auto") {
        SendError(fd, req, 400, "Invalid tool_choice: " + choice.dump() + " (none, auto, required or a function)");
        return;
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
    // OpenAI's stop: a string or an array of them. It was read nowhere, so the user's stop sequences (the daemon sends
    // a thread's) and API clients' were silently ignored. The C API has no stop strings of its own, only the model's
    // stop tokens, so the server ends the reply at the first of them itself, in the drain loop below.
    std::vector<std::string> stops;
    if (body.contains("stop") && !body["stop"].is_null()) {
      const json& st = body["stop"];
      for (const json& one : st.is_array() ? st : json::array({st})) {
        if (!one.is_string()) {
          SendError(fd, req, 400, "stop must be a string or an array of strings");
          return;
        }
        if (!one.get<std::string>().empty()) stops.push_back(one.get<std::string>());
      }
    }

    // The parts TranslateMessage made of image_url and input_audio parts, or that the client sent as LiteRT-LM's own.
    InputNeeds needs;
    auto note_parts = [&](const json& m) {
      if (!m.is_object() || !m.contains("content") || !m["content"].is_array()) return;
      for (const auto& part : m["content"]) {
        if (!part.is_object() || !part.contains("type") || !part["type"].is_string()) continue;
        needs.image |= part["type"] == "image";
        needs.audio |= part["type"] == "audio";
      }
    };
    for (const auto& m : translated) note_parts(m);
    note_parts(prompt);

    std::lock_guard<std::mutex> engine_lock(g_engine_mu);
    // Thinking turned on for a model that does not think is refused before anything loads, as an image for a model
    // without vision is (GetOrCreateEngine): refused once loaded, it first released the model that was loaded and
    // loaded this one (gemma-3-270m-it with reasoning_effort "high": a 400 after 0.6 s, and the next request to the
    // model before it paid its whole load again). A file whose header the server cannot read is left to the check
    // below, once the runtime has loaded it, as before.
    if (thinking && thinking->enable) {
      const ModelFileInfo& info = FileInfo(ref);
      if (info.capabilities_known && !Thinks(info)) throw ThinkingRefused(info, *thinking, ref.id);
    }
    LiteRtLmEngine* engine = GetOrCreateEngine(ref, mc, needs);
    // Under the engine lock, as every other once-per-model note: each connection has its own thread, and two requests
    // with a Min P at once (a chat and its title) inserted into this set together, which can corrupt it.
    if (min_p_unapplied) {
      static std::set<std::string> told;
      if (told.insert(ref.path).second) Log("min_p is not applied to " + ref.id + ": LiteRT-LM has no min-p sampling");
    }

    // Engine.create_conversation, with the options the serve path passes, but constrained decoding only with tools
    // (below).
    LiteRtLmSessionConfig* sc = L.litert_lm_session_config_create();
    if (auto sampling = SamplingFor(body, g_engine.info, g_engine.backend, ref.id, has_sampler)) {
      LiteRtLmSamplerParams* sp = L.litert_lm_sampler_params_create(kLiteRtLmSamplerTypeTopP);
      L.litert_lm_sampler_params_set_top_k(sp, sampling->top_k);
      L.litert_lm_sampler_params_set_top_p(sp, sampling->top_p);
      L.litert_lm_sampler_params_set_temperature(sp, sampling->temperature);
      L.litert_lm_sampler_params_set_seed(sp, sampling->seed);
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
    // Constrained decoding is for tools only: with it, a data processor that can (Gemma 3's, Gemma 4's and
    // FunctionGemma's, with the FST constraint library) builds a constraint from the tools so that the calls the model
    // writes fit them (Conversation::CreateDecodeConfig, runtime/conversation/conversation.cc:401-413 in LiteRT-LM
    // 0.18.0); the others build none (Qwen3's and the generic one never do: model_data_processor.h:101-103), and
    // without tools none is built. A response_format does not use it: its constraint comes from the provider set
    // below (conversation.cc:397-400). The Python server turns it on for every request, as this one did, and
    // Conversation::Create passes it to the data processor, which may refuse it: LFM2's always does
    // ("FAILED_PRECONDITION: Constrained decoding is not supported for Lfm2DataProcessor.",
    // lfm2_data_processor.cc:53-56), and Gemma 3's, Gemma 4's and FunctionGemma's ("INTERNAL: Failed to create
    // GemmaModelConstraintProvider.", gemma3_data_processor.cc:146-150, gemma4_data_processor.cc:96-101 and
    // function_gemma_data_processor.cc:219-224) when the FST constraint library rejects the model's vocabulary, as it
    // does gemma-3-270m-it's. Every request to such a model failed with a 500 before anything was generated.
    const bool constrain_tools = !tools.empty() && !g_engine.tools_unconstrained;
    L.litert_lm_conversation_config_set_enable_constrained_decoding(cc, constrain_tools);
    LiteRtLmConstraintProviderType provider = kLiteRtLmConstraintProviderTypeLlGuidance;
    if (constraint) L.litert_lm_conversation_config_set_constraint_provider(cc, &provider);
    // The model's thinking. A model that does not think (its metadata says so, or it has neither the template's switch
    // nor a thinking channel: gemma-3-270m-it) cannot be asked to: a 400, before it loads (above) when the server could
    // read its header, else here. Turning it off there changes nothing, so nothing is set. A model whose file does not
    // say, whose template has no switch, but that has a channel (LFM2.5-230M, which does not think, and
    // LFM2.5-1.2B-Thinking, which always does) gets what was asked as asked: LiteRT-LM 0.18.0 can neither turn on nor
    // stop thinking there by itself (a budget of 0 sets no constraint, tasks.cc), and nothing in such a file tells the
    // two apart, so a 400 for one would refuse the other. The server says so in its log once and, since a client sees
    // nothing of that log, in every such reply's X-Thinking header ("unverified").
    if (thinking) {
      const ModelFileInfo& m = g_engine.info;
      const bool thinks = Thinks(m);
      if (!thinks && thinking->enable) {
        L.litert_lm_conversation_config_delete(cc);
        throw ThinkingRefused(m, *thinking, ref.id);
      }
      if (!thinks) {
        thinking.reset();
      } else if (!m.supports_thinking && !m.thinking_switch) {
        note_headers += "X-Thinking: unverified\r\n";
        static std::set<std::string> told;
        if (told.insert(ref.path).second) {
          Log(ref.id + " does not say whether it thinks, and its template has no switch for it; thinking " +
              (thinking->enable ? "on" : "off") + " (" + thinking->from + ") is passed to it as asked");
        }
      }
    }
    if (thinking) {
      LiteRtLmThinkingConfig* t = MakeThinking(thinking->enable, thinking->budget);
      L.litert_lm_conversation_config_set_thinking_config(cc, t);
      L.litert_lm_thinking_config_delete(t);
    }
    // chat_template_kwargs is the template's extra context, for the preface and for the message, and its
    // enable_thinking is what was decided above, which the template reads in place of the thinking config's
    // (conversation.cc), so that the template and the decoder's budget agree.
    json extra_context = body.contains("chat_template_kwargs") && body["chat_template_kwargs"].is_object()
                             ? body["chat_template_kwargs"]
                             : json::object();
    if (thinking && !extra_context.empty()) extra_context["enable_thinking"] = thinking->enable;
    const std::string extra_context_str = extra_context.dump();
    if (!extra_context.empty()) L.litert_lm_conversation_config_set_extra_context(cc, extra_context_str.c_str());
    // Declared before the conversation so it is freed after it: deleting the conversation waits for its generation to
    // end (~SessionAdvanced's WaitUntilDone), and until then the runtime may call StreamCallback with this queue, even
    // after a chunk marked final (see the error branch of the drain loop below).
    auto q_owner = std::make_unique<StreamQueue>();
    StreamQueue& q = *q_owner;
    auto create = [&] {
      L.litert_lm_clear_last_error();
      return std::unique_ptr<LiteRtLmConversation, ConvDeleter>(L.litert_lm_conversation_create(engine, cc));
    };
    std::unique_ptr<LiteRtLmConversation, ConvDeleter> conv = create();
    if (!conv && constrain_tools) {
      // A model that refuses it still takes tools, unconstrained: the model writes its calls freely and its data
      // processor parses them as always, as under `litert-lm run`, which never turns it on. Whether the flag was the
      // cause is told by creating the conversation without it, not from the message, which differs by model.
      std::string why = LastRuntimeError();
      L.litert_lm_conversation_config_set_enable_constrained_decoding(cc, false);
      conv = create();
      if (conv) {
        g_engine.tools_unconstrained = true;
        Log("constrained decoding is not available for " + ref.id + (why.empty() ? "" : " (" + why + ")") +
            "; its tool calls are not constrained");
      }
    }
    std::optional<std::string> provider_refused;
    if (!conv && constraint) {
      // A response_format needs the LLGuidance constraint provider, which Conversation::Create builds from the model's
      // tokenizer and stop tokens (conversation.cc:433-440) and which fails for a model with no single-token stop
      // sequence (constraint_provider_factory.cc:43-56) or a tokenizer llguidance cannot take
      // (llg_constraint_provider.cc:127-132). If the conversation can be created without it, the request asked for
      // what this model cannot do: a 400 that says why, not a 500.
      std::string why = LastRuntimeError();
      L.litert_lm_conversation_config_set_constraint_provider(cc, nullptr);
      if (create()) provider_refused = why;
    }
    L.litert_lm_conversation_config_delete(cc);
    if (provider_refused) {
      throw HttpError(400, "Invalid response_format parameters: this model cannot follow a response_format" +
                               (provider_refused->empty() ? "" : ": " + *provider_refused));
    }
    if (!conv) {
      std::string why = LastRuntimeError();
      throw HttpError(500, "RuntimeError: Failed to create conversation" + (why.empty() ? "" : ": " + why));
    }

    LiteRtLmConversationOptionalArgs* oa = L.litert_lm_conversation_optional_args_create();
    if (max_tokens) L.litert_lm_conversation_optional_args_set_max_output_tokens(oa, *max_tokens);
    if (constraint) L.litert_lm_conversation_optional_args_set_constraint(oa, constraint->first, constraint->second.c_str());
    if (penalties) {
      LiteRtLmRepetitionPenaltyConfig* rp = L.litert_lm_repetition_penalty_config_create();
      L.litert_lm_repetition_penalty_config_set_repetition_penalty(rp, penalties->repetition);
      L.litert_lm_repetition_penalty_config_set_presence_penalty(rp, penalties->presence);
      L.litert_lm_repetition_penalty_config_set_frequency_penalty(rp, penalties->frequency);
      L.litert_lm_repetition_penalty_config_set_window_size(rp, penalties->window);
      L.litert_lm_conversation_optional_args_set_repetition_penalty_config(oa, rp);  // copied
      L.litert_lm_repetition_penalty_config_delete(rp);
    }

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
    g_engine.state_used = true;  // even a send that fails may have prefilled part of the message
    int rc = L.litert_lm_conversation_send_message_stream(conv.get(), msg_str.c_str(), extra_context_str.c_str(), oa,
                                                          StreamCallback, &q);
    L.litert_lm_conversation_optional_args_delete(oa);
    // Declared after the queue and the conversation, so it runs before either is destroyed.
    StreamGuard guard(q_owner, conv.get());
    if (rc != 0) {
      guard.Finished(); // nothing was started
      std::string why = LastRuntimeError();
      // A response_format whose regex or JSON schema the provider cannot compile fails here, before anything is
      // generated: the constraint is built as the message is sent (conversation.cc:397-400), and the provider reports
      // "Failed to create LLGuidance constraint: <why>" (llg_constraint_provider.cc:155-160), so the request is at
      // fault: a 400, not a 500.
      if (constraint && why.find("Failed to create LLGuidance constraint") != std::string::npos) {
        throw HttpError(400, "Invalid response_format parameters: " + why);
      }
      throw HttpError(500, "RuntimeError: litert_lm_conversation_send_message_stream failed" + (why.empty() ? "" : ": " + why));
    }

    // The context window the reply was made in, and the larger one asked for, if any, for the daemon to show.
    std::string window_headers;
    if (g_engine.window > 0) window_headers += "X-Context-Window: " + std::to_string(g_engine.window) + "\r\n";
    if (g_engine.window_asked > 0) window_headers += "X-Context-Window-Requested: " + std::to_string(g_engine.window_asked) + "\r\n";
    if (stream) {
      std::string head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n" +
                         note_headers + window_headers + CorsHeaders(req) + "\r\n";
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
    bool prompt_too_long = false;
    // The stop strings apply to the reply's text only, as OpenAI's do, not to its thinking or its tool calls. Text that
    // could be the start of one is held back until the next chunk tells; at a stop string the reply ends before it,
    // never showing it, and the generation is cancelled, which the loop below then takes as the quiet end it is.
    std::string held;
    bool stopped = false;
    auto apply_stops = [&](std::string text) {
      if (stops.empty()) return text;
      if (stopped) return std::string();
      held += text;
      size_t at = std::string::npos;
      for (const auto& stop : stops) at = std::min(at, held.find(stop));
      if (at != std::string::npos) {
        text = held.substr(0, at);
        held.clear();
        stopped = true;
        if (!cancelled) {
          cancelled = true;
          L.litert_lm_conversation_cancel_process(conv.get());
        }
        return text;
      }
      size_t keep = 0;
      for (const auto& stop : stops) {
        for (size_t n = std::min(stop.size() - 1, held.size()); n > keep; --n) {
          if (held.compare(held.size() - n, n, stop, 0, n) == 0) {
            keep = n;
            break;
          }
        }
      }
      text = held.substr(0, held.size() - keep);
      held.erase(0, held.size() - keep);
      return text;
    };

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
        } else if (item.text.rfind("INVALID_ARGUMENT: Input token ids are too long", 0) == 0) {
          // The prompt alone does not fit the context window: the request's mistake, said with the window's size.
          prompt_too_long = true;
          error = "the prompt does not fit the model's context window" +
                  (g_engine.window > 0 ? " of " + std::to_string(g_engine.window) + " tokens" : std::string()) + " (" +
                  item.text + ")";
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
      p.text = apply_stops(p.text);
      if (stopped) p.tool_calls = json::array();
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
    // The end of a reply that only looked as if it might become a stop string.
    if (!held.empty() && !error) {
      if (stream && !client_gone) {
        SendAll(fd, Sse(make_chunk(json::array({{{"index", 0}, {"delta", {{"content", held}}}, {"finish_reason", nullptr}}}))));
      }
      text_all += held;
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
      if (finish == "stop" && !stopped && max_tokens && usage["completion_tokens"].get<int>() >= *max_tokens) finish = "length";
      // A full context window ends the turn with an ordinary final chunk: the conversation API reports it as an error
      // only with return_error_on_max_tokens_reached, which defaults to off and which LiteRT-LM 0.18.0's C API has no
      // way to turn on. So it is told from the counts. The runtime stops once the KV cache holds max_num_tokens, and
      // this request's conversation is new, so its cache holds just this prompt (earlier messages included) and the
      // reply. The window is the engine's own, the one LiteRT-LM gives it (RuntimeWindow): the model's size when it is
      // smaller than what was asked, and what the runtime picks when nothing was asked.
      const int window = g_engine.window;
      if (finish == "stop" && !stopped && window > 0 && usage["total_tokens"].get<int>() >= window) finish = "length";
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

    if (error && prompt_too_long) throw HttpError(400, "Invalid messages: " + *error);
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
    SendJson(fd, req, 200, resp, note_headers + window_headers);
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
    json entry = {{"id", g_engine.key.path}, {"object", "model"}, {"created", mtime}, {"owned_by", "litert-lm"}};
    // The loaded model's context window (vLLM's name for it), and the larger one that was asked for, if any.
    if (g_engine.window > 0) entry["max_model_len"] = g_engine.window;
    if (g_engine.window_asked > 0) entry["max_model_len_requested"] = g_engine.window_asked;
    data.push_back(entry);
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
