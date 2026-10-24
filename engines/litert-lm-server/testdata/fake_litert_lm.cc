// A stand-in liblitert-lm.so for constrained-decoding.test.mjs. It implements the C API calls around creating a
// conversation and sending it a message the way LiteRT-LM 0.18.0 answers them for the kinds of model below, so the
// server's choices can be checked without a model. The test adds every other function the server resolves as one
// that does nothing.
//
// The kind of model is told by the start of the file name in the request's `model`:
//   lfm2             refuses constrained decoding, as Lfm2DataProcessor::Create always does
//                    (runtime/conversation/model_data_processor/lfm2_data_processor.cc:53-56)
//   fst-unsupported  refuses it as Gemma3DataProcessor::Create does when the FST constraint library rejects the
//                    model's vocabulary (gemma3_data_processor.cc:124-152; gemma-3-270m-it is one)
//   no-llg           has no LLGuidance constraint provider (constraint_provider_factory.cc:43-56)
//   broken           cannot create any conversation
//   anything else    takes both (a Gemma model whose vocabulary the FST library supports)
// and, whatever the file's header says: an engine for gpu-broken cannot be created on gpu, as on a phone whose GPU
// path fails, and one for no-engine on no backend at all.
// Each conversation created is logged with what its config asked for: {"call": "conversation_create", "model": ...,
// "config": {"sampler": {...}, ...}}.
// A conversation answers with one chunk of text that says how it was created and sent, for example
// "constrained=1 tools=1 provider=0 constraint=0 creates=3", where creates counts every litert_lm_conversation_create
// call in the process; a message whose text starts with "say:" is answered with the rest of its text instead, three
// bytes to a chunk, as a model streams, and one whose text is "too long" fails as a prompt longer than the context
// window does. A regex that does not compile fails the send as llguidance does
// (llg_constraint_provider.cc:155-160).
//
// With FAKE_LITERT_LM_CALLS set to a file, the calls that say what the server asked the runtime for are appended to it,
// one JSON object per line ({"call": "<function without litert_lm_>", ...its arguments}), for
// model-settings.test.mjs to read.
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <string>

#include "c/conversation.h"
#include "c/engine.h"
#include "c/error_reporter.h"
#include "nlohmann/json.hpp"

struct LiteRtLmEngineSettings {
  std::string model, backend;
};
struct LiteRtLmEngine {
  std::string model;
};
struct LiteRtLmSamplerParams {
  nlohmann::json j;
};
struct LiteRtLmSessionConfig {
  nlohmann::json sampler;
};
struct LiteRtLmThinkingConfig {
  nlohmann::json j = nlohmann::json::object();
};
struct LiteRtLmConversationConfig {
  bool constrained = false, tools = false, provider = false;
  nlohmann::json config = nlohmann::json::object();
};
struct LiteRtLmConversation {
  LiteRtLmConversationConfig config;
};
struct LiteRtLmConversationOptionalArgs {
  LiteRtLmConstraintType type = kLiteRtLmConstraintTypeNone;
  std::string pattern;
  nlohmann::json j = nlohmann::json::object();  // the other arguments, as logged with the message
};
struct LiteRtLmRepetitionPenaltyConfig {
  nlohmann::json j = {{"repetition_penalty", 1.0}, {"presence_penalty", 0.0}, {"frequency_penalty", 0.0}, {"window_size", 0}};
};
struct LiteRtLmStreamChunk {
  std::string text;
  const char* error;
  bool final;
};

namespace {
std::string g_error;
int g_creates = 0;

void Record(const nlohmann::json& call) {
  static std::mutex mu;
  const char* path = std::getenv("FAKE_LITERT_LM_CALLS");
  if (!path || !*path) return;
  std::lock_guard<std::mutex> lock(mu);
  if (FILE* f = std::fopen(path, "a")) {
    std::fputs((call.dump() + "\n").c_str(), f);
    std::fclose(f);
  }
}

bool Is(const std::string& model, const char* kind) { return model.rfind(kind, 0) == 0; }

std::string Basename(const char* path) {
  std::string p = path ? path : "";
  size_t slash = p.find_last_of('/');
  return slash == std::string::npos ? p : p.substr(slash + 1);
}
}  // namespace

extern "C" {

const char* litert_lm_get_last_error_message(void) { return g_error.c_str(); }
void litert_lm_clear_last_error(void) { g_error.clear(); }

LiteRtLmEngineSettings* litert_lm_engine_settings_create(const char* model_path, const char* backend,
                                                         const char* vision_backend, const char* audio_backend) {
  auto name = [](const char* b) { return b ? nlohmann::json(b) : nlohmann::json(nullptr); };
  Record({{"call", "engine_settings_create"}, {"model", Basename(model_path)}, {"backend", name(backend)},
          {"vision_backend", name(vision_backend)}, {"audio_backend", name(audio_backend)}});
  return new LiteRtLmEngineSettings{Basename(model_path), backend ? backend : ""};
}
void litert_lm_engine_settings_set_max_num_tokens(LiteRtLmEngineSettings* s, int n) {
  Record({{"call", "engine_settings_set_max_num_tokens"}, {"model", s->model}, {"max_num_tokens", n}});
}
void litert_lm_engine_settings_delete(LiteRtLmEngineSettings* s) { delete s; }
LiteRtLmEngine* litert_lm_engine_create(const LiteRtLmEngineSettings* s) {
  Record({{"call", "engine_create"}, {"model", s->model}, {"backend", s->backend}});
  if (Is(s->model, "gpu-broken") && s->backend == "gpu") {
    g_error = "UNAVAILABLE: no usable OpenCL device";
    return nullptr;
  }
  if (Is(s->model, "no-engine")) {
    g_error = "INTERNAL: cannot load the model on " + s->backend;
    return nullptr;
  }
  return new LiteRtLmEngine{s->model};
}
void litert_lm_engine_delete(LiteRtLmEngine* e) { delete e; }

LiteRtLmSessionConfig* litert_lm_session_config_create() { return new LiteRtLmSessionConfig; }
void litert_lm_session_config_delete(LiteRtLmSessionConfig* c) { delete c; }
LiteRtLmSamplerParams* litert_lm_sampler_params_create(LiteRtLmSamplerType type) {
  return new LiteRtLmSamplerParams{{{"type", type}}};
}
void litert_lm_sampler_params_delete(LiteRtLmSamplerParams* p) { delete p; }
void litert_lm_sampler_params_set_top_k(LiteRtLmSamplerParams* p, int32_t k) { p->j["top_k"] = k; }
void litert_lm_sampler_params_set_top_p(LiteRtLmSamplerParams* p, float v) { p->j["top_p"] = v; }
void litert_lm_sampler_params_set_temperature(LiteRtLmSamplerParams* p, float v) { p->j["temperature"] = v; }
void litert_lm_sampler_params_set_seed(LiteRtLmSamplerParams* p, int32_t seed) { p->j["seed"] = seed; }
void litert_lm_session_config_set_sampler_params(LiteRtLmSessionConfig* c, const LiteRtLmSamplerParams* p) {
  c->sampler = p->j;
}
void litert_lm_conversation_config_set_session_config(LiteRtLmConversationConfig* c, const LiteRtLmSessionConfig* s) {
  c->config["sampler"] = s->sampler;
}

LiteRtLmThinkingConfig* litert_lm_thinking_config_create() { return new LiteRtLmThinkingConfig; }
void litert_lm_thinking_config_delete(LiteRtLmThinkingConfig* c) { delete c; }
void litert_lm_thinking_config_set_enable_thinking(LiteRtLmThinkingConfig* c, bool enable) { c->j["enable"] = enable; }
void litert_lm_thinking_config_set_thinking_token_budget(LiteRtLmThinkingConfig* c, int32_t budget) {
  c->j["budget"] = budget;
}
void litert_lm_conversation_config_set_thinking_config(LiteRtLmConversationConfig* c, const LiteRtLmThinkingConfig* t) {
  c->config["thinking"] = t->j;
}
void litert_lm_conversation_config_set_messages(LiteRtLmConversationConfig* c, const char* messages_json) {
  c->config["messages"] = nlohmann::json::parse(messages_json ? messages_json : "null", nullptr, false);
}
void litert_lm_conversation_config_set_extra_context(LiteRtLmConversationConfig* c, const char* extra_context_json) {
  c->config["extra_context"] = nlohmann::json::parse(extra_context_json ? extra_context_json : "null", nullptr, false);
}

LiteRtLmConversationConfig* litert_lm_conversation_config_create() { return new LiteRtLmConversationConfig; }
void litert_lm_conversation_config_delete(LiteRtLmConversationConfig* c) { delete c; }
void litert_lm_conversation_config_set_enable_constrained_decoding(LiteRtLmConversationConfig* c, bool enable) {
  c->constrained = enable;
}
void litert_lm_conversation_config_set_tools(LiteRtLmConversationConfig* c, const char* tools_json) {
  c->tools = tools_json && *tools_json;
}
void litert_lm_conversation_config_set_constraint_provider(LiteRtLmConversationConfig* c,
                                                           const LiteRtLmConstraintProviderType* type) {
  c->provider = type != nullptr;
}

// Conversation::Create's order: the data processor (which gets the constrained-decoding flag), then the provider.
LiteRtLmConversation* litert_lm_conversation_create(LiteRtLmEngine* engine, LiteRtLmConversationConfig* c) {
  ++g_creates;
  const std::string& m = engine->model;
  Record({{"call", "conversation_create"}, {"model", m}, {"config", c->config}});
  if (Is(m, "broken")) {
    g_error = "INTERNAL: cannot create a session";
  } else if (c->constrained && Is(m, "lfm2")) {
    g_error = "FAILED_PRECONDITION: Constrained decoding is not supported for Lfm2DataProcessor.";
  } else if (c->constrained && Is(m, "fst-unsupported")) {
    g_error = "INTERNAL: Failed to create GemmaModelConstraintProvider.";
  } else if (c->provider && Is(m, "no-llg")) {
    g_error =
        "INVALID_ARGUMENT: LlGuidanceConfig::eos_id wasn't set and no valid stop token was found in SessionConfig.";
  } else {
    return new LiteRtLmConversation{*c};
  }
  return nullptr;
}
void litert_lm_conversation_delete(LiteRtLmConversation* conv) { delete conv; }
void litert_lm_conversation_cancel_process(LiteRtLmConversation*) { Record({{"call", "cancel_process"}}); }
LiteRtLmBenchmarkInfo* litert_lm_conversation_get_benchmark_info(LiteRtLmConversation*) { return nullptr; }

LiteRtLmConversationOptionalArgs* litert_lm_conversation_optional_args_create() {
  return new LiteRtLmConversationOptionalArgs;
}
void litert_lm_conversation_optional_args_delete(LiteRtLmConversationOptionalArgs* a) { delete a; }
void litert_lm_conversation_optional_args_set_max_output_tokens(LiteRtLmConversationOptionalArgs* a, int n) {
  a->j["max_output_tokens"] = n;
}
LiteRtLmRepetitionPenaltyConfig* litert_lm_repetition_penalty_config_create() { return new LiteRtLmRepetitionPenaltyConfig; }
void litert_lm_repetition_penalty_config_delete(LiteRtLmRepetitionPenaltyConfig* c) { delete c; }
void litert_lm_repetition_penalty_config_set_repetition_penalty(LiteRtLmRepetitionPenaltyConfig* c, float v) {
  c->j["repetition_penalty"] = v;
}
void litert_lm_repetition_penalty_config_set_presence_penalty(LiteRtLmRepetitionPenaltyConfig* c, float v) {
  c->j["presence_penalty"] = v;
}
void litert_lm_repetition_penalty_config_set_frequency_penalty(LiteRtLmRepetitionPenaltyConfig* c, float v) {
  c->j["frequency_penalty"] = v;
}
void litert_lm_repetition_penalty_config_set_window_size(LiteRtLmRepetitionPenaltyConfig* c, int n) {
  c->j["window_size"] = n;
}
void litert_lm_conversation_optional_args_set_repetition_penalty_config(LiteRtLmConversationOptionalArgs* a,
                                                                        const LiteRtLmRepetitionPenaltyConfig* c) {
  a->j["repetition_penalty_config"] = c ? c->j : nlohmann::json(nullptr);
}
void litert_lm_conversation_optional_args_set_constraint(LiteRtLmConversationOptionalArgs* a,
                                                         LiteRtLmConstraintType type, const char* pattern) {
  a->type = type;
  a->pattern = pattern ? pattern : "";
}

int litert_lm_conversation_send_message_stream(LiteRtLmConversation* conv, const char* message,
                                               const char* extra_context, const LiteRtLmConversationOptionalArgs* a,
                                               LiteRtLmStreamCallback callback, void* data) {
  Record({{"call", "send_message"},
          {"message", nlohmann::json::parse(message ? message : "null", nullptr, false)},
          {"extra_context", nlohmann::json::parse(extra_context ? extra_context : "null", nullptr, false)},
          {"args", a ? a->j : nlohmann::json(nullptr)}});
  const LiteRtLmConstraintType type = a ? a->type : kLiteRtLmConstraintTypeNone;
  if (type == kLiteRtLmConstraintTypeRegex && a->pattern == "(") {
    g_error = "INTERNAL: Failed to create LLGuidance constraint: invalid regex \"(\": unclosed group";
    return -1;
  }
  const LiteRtLmConversationConfig& c = conv->config;
  std::string text = "constrained=" + std::to_string(c.constrained) + " tools=" + std::to_string(c.tools) +
                     " provider=" + std::to_string(c.provider) + " constraint=" + std::to_string(type) +
                     " creates=" + std::to_string(g_creates);
  nlohmann::json m = nlohmann::json::parse(message ? message : "null", nullptr, false);
  std::string said;
  if (m.is_object() && m.contains("content")) {
    const nlohmann::json& content = m["content"];
    if (content.is_string()) said = content.get<std::string>();
    if (content.is_array() && !content.empty() && content[0].is_object()) said = content[0].value("text", "");
  }
  if (said == "too long") {
    LiteRtLmStreamChunk failed{"", "INVALID_ARGUMENT: Input token ids are too long. Exceeding the maximum number of "
                                   "tokens allowed: 4209 >= 4096",
                               true};
    callback(data, &failed);
    return 0;
  }
  std::vector<std::string> chunks = {text};
  if (said.rfind("say:", 0) == 0) {
    chunks.clear();
    for (size_t i = 4; i < said.size(); i += 3) chunks.push_back(said.substr(i, 3));
  }
  for (const auto& piece : chunks) {
    nlohmann::json out = {{"role", "assistant"}, {"content", {{{"type", "text"}, {"text", piece}}}}};
    LiteRtLmStreamChunk reply{out.dump(), nullptr, false};
    callback(data, &reply);
  }
  LiteRtLmStreamChunk end{"", nullptr, true};
  callback(data, &end);
  return 0;
}

const char* litert_lm_stream_chunk_get_error(const LiteRtLmStreamChunk* chunk) { return chunk->error; }
const char* litert_lm_stream_chunk_get_text(const LiteRtLmStreamChunk* chunk) { return chunk->text.c_str(); }
bool litert_lm_stream_chunk_is_final(const LiteRtLmStreamChunk* chunk) { return chunk->final; }

}  // extern "C"
