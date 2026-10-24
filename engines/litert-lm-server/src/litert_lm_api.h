// The LiteRT-LM C API, resolved at runtime with dlopen(RTLD_LOCAL) instead of linked.
//
// liblitert-lm.so carries its own statically linked C++ runtime and EXPORTS it (std::runtime_error,
// __cxa_* and friends). Linking against the library lets the linker satisfy this program's own C++
// runtime references from those exports — the library comes before the toolchain's runtime on the
// link line — so an exception thrown here was destroyed by the library's runtime. AddressSanitizer
// caught it as an operator-new/free mismatch in ~runtime_error: two C++ runtimes sharing one object.
// Loading the library RTLD_LOCAL keeps every one of its symbols out of this program; it is linked
// -Bsymbolic, so its own references stay inside it either way. Only the C functions below cross.
#pragma once

#include <string>

#include "c/conversation.h"
#include "c/engine.h"
#include "c/error_reporter.h"

// Every C API function this server calls. Adding a call means adding its name here.
#define LITERT_LM_API_FUNCTIONS(X) \
  X(litert_lm_benchmark_info_delete) \
  X(litert_lm_benchmark_info_get_decode_token_count_at) \
  X(litert_lm_benchmark_info_get_decode_tokens_per_sec_at) \
  X(litert_lm_benchmark_info_get_num_decode_turns) \
  X(litert_lm_benchmark_info_get_num_prefill_turns) \
  X(litert_lm_benchmark_info_get_prefill_token_count_at) \
  X(litert_lm_benchmark_info_get_prefill_tokens_per_sec_at) \
  X(litert_lm_clear_last_error) \
  X(litert_lm_conversation_cancel_process) \
  X(litert_lm_conversation_config_create) \
  X(litert_lm_conversation_config_delete) \
  X(litert_lm_conversation_config_set_constraint_provider) \
  X(litert_lm_conversation_config_set_enable_constrained_decoding) \
  X(litert_lm_conversation_config_set_extra_context) \
  X(litert_lm_conversation_config_set_messages) \
  X(litert_lm_conversation_config_set_session_config) \
  X(litert_lm_conversation_config_set_thinking_config) \
  X(litert_lm_conversation_config_set_tools) \
  X(litert_lm_conversation_create) \
  X(litert_lm_conversation_delete) \
  X(litert_lm_conversation_get_benchmark_info) \
  X(litert_lm_conversation_optional_args_create) \
  X(litert_lm_conversation_optional_args_delete) \
  X(litert_lm_conversation_optional_args_set_constraint) \
  X(litert_lm_conversation_optional_args_set_max_output_tokens) \
  X(litert_lm_conversation_send_message_stream) \
  X(litert_lm_engine_create) \
  X(litert_lm_engine_delete) \
  X(litert_lm_engine_settings_create) \
  X(litert_lm_engine_settings_delete) \
  X(litert_lm_engine_settings_enable_benchmark) \
  X(litert_lm_engine_settings_set_cache_dir) \
  X(litert_lm_engine_settings_set_enable_speculative_decoding) \
  X(litert_lm_engine_settings_set_gpu_decode_steps_per_sync) \
  X(litert_lm_engine_settings_set_max_num_tokens) \
  X(litert_lm_engine_settings_set_num_threads) \
  X(litert_lm_engine_settings_set_use_ringbuffers_local_attention) \
  X(litert_lm_get_last_error_message) \
  X(litert_lm_sampler_params_create) \
  X(litert_lm_sampler_params_delete) \
  X(litert_lm_sampler_params_set_seed) \
  X(litert_lm_sampler_params_set_temperature) \
  X(litert_lm_sampler_params_set_top_k) \
  X(litert_lm_sampler_params_set_top_p) \
  X(litert_lm_session_config_create) \
  X(litert_lm_session_config_delete) \
  X(litert_lm_session_config_set_sampler_params) \
  X(litert_lm_set_min_log_level) \
  X(litert_lm_stream_chunk_get_error) \
  X(litert_lm_stream_chunk_get_text) \
  X(litert_lm_stream_chunk_is_final) \
  X(litert_lm_thinking_config_create) \
  X(litert_lm_thinking_config_delete) \
  X(litert_lm_thinking_config_set_enable_thinking) \
  X(litert_lm_thinking_config_set_thinking_token_budget)

struct LiteRtLmApi {
#define LITERT_LM_DECLARE(name) decltype(&::name) name = nullptr;
  LITERT_LM_API_FUNCTIONS(LITERT_LM_DECLARE)
#undef LITERT_LM_DECLARE
};

// Loads liblitert-lm.so — from LITERT_LM_LIBRARY if set, else next to this executable, else via the
// normal search path — and resolves every function above. Returns "" on success, else the reason.
std::string LoadLiteRtLm(LiteRtLmApi& api, std::string* loaded_from);
