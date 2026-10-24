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
// A conversation answers with one chunk of text that says how it was created and sent, for example
// "constrained=1 tools=1 provider=0 constraint=0 creates=3", where creates counts every litert_lm_conversation_create
// call in the process. A regex that does not compile fails the send as llguidance does
// (llg_constraint_provider.cc:155-160).
#include <cstdlib>
#include <string>

#include "c/conversation.h"
#include "c/engine.h"
#include "c/error_reporter.h"

struct LiteRtLmEngineSettings {
  std::string model;
};
struct LiteRtLmEngine {
  std::string model;
};
struct LiteRtLmSessionConfig {};
struct LiteRtLmThinkingConfig {};
struct LiteRtLmConversationConfig {
  bool constrained = false, tools = false, provider = false;
};
struct LiteRtLmConversation {
  LiteRtLmConversationConfig config;
};
struct LiteRtLmConversationOptionalArgs {
  LiteRtLmConstraintType type = kLiteRtLmConstraintTypeNone;
  std::string pattern;
};
struct LiteRtLmStreamChunk {
  std::string text;
  const char* error;
  bool final;
};

namespace {
std::string g_error;
int g_creates = 0;

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

LiteRtLmEngineSettings* litert_lm_engine_settings_create(const char* model_path, const char*, const char*, const char*) {
  return new LiteRtLmEngineSettings{Basename(model_path)};
}
void litert_lm_engine_settings_delete(LiteRtLmEngineSettings* s) { delete s; }
LiteRtLmEngine* litert_lm_engine_create(const LiteRtLmEngineSettings* s) { return new LiteRtLmEngine{s->model}; }
void litert_lm_engine_delete(LiteRtLmEngine* e) { delete e; }

LiteRtLmSessionConfig* litert_lm_session_config_create() { return new LiteRtLmSessionConfig; }
void litert_lm_session_config_delete(LiteRtLmSessionConfig* c) { delete c; }
LiteRtLmThinkingConfig* litert_lm_thinking_config_create() { return new LiteRtLmThinkingConfig; }
void litert_lm_thinking_config_delete(LiteRtLmThinkingConfig* c) { delete c; }

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
LiteRtLmBenchmarkInfo* litert_lm_conversation_get_benchmark_info(LiteRtLmConversation*) { return nullptr; }

LiteRtLmConversationOptionalArgs* litert_lm_conversation_optional_args_create() {
  return new LiteRtLmConversationOptionalArgs;
}
void litert_lm_conversation_optional_args_delete(LiteRtLmConversationOptionalArgs* a) { delete a; }
void litert_lm_conversation_optional_args_set_constraint(LiteRtLmConversationOptionalArgs* a,
                                                         LiteRtLmConstraintType type, const char* pattern) {
  a->type = type;
  a->pattern = pattern ? pattern : "";
}

int litert_lm_conversation_send_message_stream(LiteRtLmConversation* conv, const char*, const char*,
                                               const LiteRtLmConversationOptionalArgs* a,
                                               LiteRtLmStreamCallback callback, void* data) {
  const LiteRtLmConstraintType type = a ? a->type : kLiteRtLmConstraintTypeNone;
  if (type == kLiteRtLmConstraintTypeRegex && a->pattern == "(") {
    g_error = "INTERNAL: Failed to create LLGuidance constraint: invalid regex \"(\": unclosed group";
    return -1;
  }
  const LiteRtLmConversationConfig& c = conv->config;
  std::string text = "constrained=" + std::to_string(c.constrained) + " tools=" + std::to_string(c.tools) +
                     " provider=" + std::to_string(c.provider) + " constraint=" + std::to_string(type) +
                     " creates=" + std::to_string(g_creates);
  LiteRtLmStreamChunk reply{"{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}",
                            nullptr, false};
  callback(data, &reply);
  LiteRtLmStreamChunk end{"", nullptr, true};
  callback(data, &end);
  return 0;
}

const char* litert_lm_stream_chunk_get_error(const LiteRtLmStreamChunk* chunk) { return chunk->error; }
const char* litert_lm_stream_chunk_get_text(const LiteRtLmStreamChunk* chunk) { return chunk->text.c_str(); }
bool litert_lm_stream_chunk_is_final(const LiteRtLmStreamChunk* chunk) { return chunk->final; }

}  // extern "C"
