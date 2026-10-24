// Tests what the LiteRT-LM server asks the runtime for: what each model itself needs and supports, and the settings a
// request makes (src/main.cc):
//   node --test engines/litert-lm-server/model-settings.test.mjs
// The server is built for this machine with c++ (testdata/harness.mjs) and runs against testdata/fake_litert_lm.cc, a
// stand-in liblitert-lm.so that logs what it is asked for (FAKE_LITERT_LM_CALLS). Linux only, and skipped without a C
// and a C++ compiler. Building takes a few seconds.
//
// Given a liblitert-lm.so that runs on this machine (the one in the manylinux litert-lm-api wheel of the version
// scripts/stage-litert-lm-engine.mjs pins) and .litertlm models, the tests named "a real model ..." also run the
// server on each model (see constrained-decoding.test.mjs):
//   LITERT_LM_TEST_LIBRARY=<liblitert-lm.so> LITERT_LM_TEST_MODELS=<model.litertlm>[,<model.litertlm>...] \
//     node --test engines/litert-lm-server/model-settings.test.mjs
// (an LFM2 one: litert-community/LFM2.5-230M on Hugging Face; a Gemma one: litert-community/gemma-3-270m-it).
// LITERT_LM_TEST_VISION_MODELS (the same form) names models that take images (litert-community/LFM2.5-VL-450M).
// Every server a test starts is killed by pid when that test ends, or at the latest when the tests end. Every wait is
// bounded.
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, join } from 'node:path'
import { deflateSync } from 'node:zlib'
import { after, before, test } from 'node:test'
import {
  buildServer, calls, chat, fb, flatbuffer, killServers, litertlmFile, logged, proto, realLibrary, realModels, skip,
  skipReal, startServer, stopServer,
} from './testdata/harness.mjs'

const visionModels = (process.env.LITERT_LM_TEST_VISION_MODELS ?? '').split(',').filter(Boolean)
const skipVision = skip || (!realLibrary || !visionModels.length
  ? 'set LITERT_LM_TEST_LIBRARY and LITERT_LM_TEST_VISION_MODELS to run the server on models that take images' : false)

const work = mkdtempSync(join(tmpdir(), 'litert-lm-server-ms-test-'))
// What before() is still doing. With a --test-name-pattern that leaves no test in this file, node runs after() while
// before() is starting the server, and removing `work` under it failed the file (spawn ... ENOENT); so after() waits.
let ready = Promise.resolve()
after(async () => {
  await ready.catch(() => {})
  killServers()
  rmSync(work, { recursive: true, force: true })
})

const callLog = join(work, 'calls.jsonl')
let binary
let fake

before(() => (ready = (async () => {
  if (skip) return
  binary = buildServer(work)
  fake = await startServer(binary, { ...process.env, LITERT_LM_LIBRARY: '', FAKE_LITERT_LM_CALLS: callLog }, ['--verbose'])
})()), { timeout: 300_000 })

// The calls the stand-in logged while `fn` ran.
async function callsDuring(fn) {
  const before = calls(callLog).length
  await fn()
  return calls(callLog).slice(before)
}

const hello = [{ role: 'user', content: 'Hello' }]

// The header sections of the stand-in files (runtime/proto/llm_metadata.proto, executor_metadata.proto).
const llmMetadata = (modelType) => ({ type: 5, bytes: proto.field(6, proto.field(modelType, [])) }) // llm_model_type
const stateBuffer = (name, type) => proto.field(2, [proto.field(1, name), proto.varint(6, type)])
const executorMetadata = (...buffers) => ({ type: 9, bytes: proto.field(1, buffers) })
const GENERIC = 1
const LFM2 = 11

test('a model whose decoder keeps a recurrent state gets a new engine for each conversation, and only such a model', { skip, timeout: 60_000 }, async () => {
  const models = {
    // llm_model_type lfm2, as litert-community's LFM2 files have since 2026-10-14
    'lfm2-typed': [llmMetadata(LFM2)],
    // generic_model, with LFM2's conv caches declared (litert-community's LFM2.5-230M before 2026-10-14)
    'conv-generic': [llmMetadata(GENERIC), executorMetadata(stateBuffer('kv_cache_c_0', 5), stateBuffer('kv_cache_k_1', 1), stateBuffer('kv_cache_v_1', 2))],
    // a linear-attention state under another name
    'linear-attention': [executorMetadata(stateBuffer('state_0', 5), stateBuffer('kv_cache_k_1', 1))],
    // a KV cache only, global and local
    'kv-only': [llmMetadata(GENERIC), executorMetadata(stateBuffer('kv_cache_k_0', 1), stateBuffer('kv_cache_v_0', 2), stateBuffer('kv_cache_k_1', 3))],
    // no header the server can read: left to the runtime, as before
    'no-header': null,
  }
  for (const [name, sections] of Object.entries(models)) {
    const path = join(work, `${name}.litertlm`)
    if (sections) litertlmFile(path, sections)
    else writeFileSync(path, 'stand-in model')
    const engines = []
    for (let i = 0; i < 3; i++) {
      const log = await callsDuring(async () => assert.equal((await chat(fake, { model: path, messages: hello })).status, 200))
      engines.push(log.filter((c) => c.call === 'engine_create').length)
    }
    const recurrent = !['kv-only', 'no-header'].includes(name)
    assert.deepEqual(engines, recurrent ? [1, 1, 1] : [1, 0, 0], name)
    const said = (await logged(fake)).filter((l) => l.startsWith(`${name}.litertlm keeps a recurrent state`))
    assert.equal(said.length, recurrent ? 1 : 0, fake.stderr)
  }
  // The reason is given once per model, not once per load.
  await chat(fake, { model: join(work, 'kv-only.litertlm'), messages: hello })
  for (let i = 0; i < 2; i++) await chat(fake, { model: join(work, 'lfm2-typed.litertlm'), messages: hello })
  assert.equal((await logged(fake)).filter((l) => l.startsWith('lfm2-typed.litertlm keeps a recurrent state')).length, 1)
})

test('a model whose decoder keeps a recurrent state still finds out once per load that it refuses constrained decoding', { skip, timeout: 60_000 }, async () => {
  const path = litertlmFile(join(work, 'lfm2-tools.litertlm'), [llmMetadata(LFM2)])
  const tools = [{ type: 'function', function: { name: 'f', parameters: { type: 'object', properties: {} } } }]
  for (let i = 0; i < 3; i++) assert.equal((await chat(fake, { model: path, messages: hello, tools })).status, 200)
  const lines = (await logged(fake)).filter((l) => l.startsWith('constrained decoding is not available for lfm2-tools.litertlm'))
  assert.equal(lines.length, 1, fake.stderr)
})

// The probe: a pirate persona, a question, a title request, then the question's second turn.
const pirate = [{ role: 'system', content: 'You are a pirate. Always talk like a pirate.' }, { role: 'user', content: 'Tell me about the sea.' }]
const t1 = [{ role: 'system', content: 'Answer in one word.' }, { role: 'user', content: 'What is the capital of France?' }]
const title = [
  { role: 'system', content: 'Write a short title for this conversation. Reply with the title only.' },
  { role: 'user', content: 'User: What is the capital of France?\nAssistant: Paris' },
]
const t2 = [...t1, { role: 'assistant', content: 'Paris' }, { role: 'user', content: 'And of Italy?' }]

for (const path of realModels.length ? realModels : ['<model>']) {
  test(`a real model answers a conversation as it would on a fresh engine, whatever came before: ${path}`, { skip: skipReal, timeout: 900_000 }, async () => {
    const config = join(work, 'no-cache.json') // no compiled-model cache beside the model
    writeFileSync(config, JSON.stringify({ default: { cache: 'no' } }))
    const env = { ...process.env, LITERT_LM_LIBRARY: realLibrary }
    const ms = 300_000
    // A seed, so that a model that samples answers the same twice (each conversation seeds its own generator).
    const ask = (server, messages) => chat(server, { model: `${path},cpu`, messages, seed: 7, max_completion_tokens: 32 }, ms)
    const fresh = {}
    for (const [name, messages] of Object.entries({ t1, t2 })) {
      const server = await startServer(binary, env, ['--config', config])
      try {
        fresh[name] = await ask(server, messages)
        assert.equal(fresh[name].status, 200, fresh[name].error)
      } finally {
        stopServer(server)
      }
    }
    const server = await startServer(binary, env, ['--config', config])
    try {
      for (const messages of [pirate, t1]) assert.equal((await ask(server, messages)).status, 200)
      const again = await ask(server, t1)
      assert.equal(again.content, fresh.t1.content, `${basename(path)}: the same question asked again`)
      assert.equal((await ask(server, title)).status, 200)
      const second = await ask(server, t2)
      assert.equal(second.content, fresh.t2.content, `${basename(path)}: the second turn after a title request`)
    } finally {
      stopServer(server)
    }
  })
}

// A 64x64 red PNG.
const redPng = (() => {
  const crcTable = Array.from({ length: 256 }, (_, n) => {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    return c >>> 0
  })
  const crc = (b) => { let c = 0xffffffff; for (const x of b) c = crcTable[(c ^ x) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0 }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
    const body = Buffer.concat([Buffer.from(type), data])
    const sum = Buffer.alloc(4); sum.writeUInt32BE(crc(body))
    return Buffer.concat([len, body, sum])
  }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(64, 0); ihdr.writeUInt32BE(64, 4); ihdr.set([8, 2, 0, 0, 0], 8)
  const rows = Buffer.concat(Array.from({ length: 64 }, () => Buffer.concat([Buffer.from([0]), Buffer.alloc(192, Buffer.from([255, 0, 0]))])))
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))])
})()
const imageMessage = (text) => [{ role: 'user', content: [
  { type: 'image_url', image_url: { url: `data:image/png;base64,${redPng.toString('base64')}` } },
  { type: 'text', text },
] }]
const audioMessage = [{ role: 'user', content: [{ type: 'input_audio', input_audio: { data: 'AAAA', format: 'wav' } }, { type: 'text', text: 'What is this?' }] }]

// A stand-in file whose header lists `sections`: by default those `kinds` gives for its name (below), else a text
// decoder alone. TFLite sections have their model_type and, when given, their backend_constraint, as LiteRT-LM's
// builder writes them. The server reads the header; the stand-in library reads only the file's name.
const tflite = (model_type, backend_constraint) => ({ type: 3, items: { model_type, ...(backend_constraint ? { backend_constraint } : {}) } })
const decoder = tflite('tf_lite_prefill_decode')
const readable = (name, sections = kinds[name] ?? [decoder]) => litertlmFile(join(work, `${name}.litertlm`), sections)
// One with no header the server can read, which is left to the runtime.
const unreadable = (name) => {
  const path = join(work, `${name}.litertlm`)
  writeFileSync(path, 'stand-in model')
  return path
}
const kinds = {
  text: [decoder],
  vl: [decoder, tflite('tf_lite_embedder'), tflite('tf_lite_vision_encoder'), tflite('tf_lite_vision_adapter')], // LFM2.5-VL-450M's
  'gpu-vision': [decoder, tflite('tf_lite_vision_encoder', 'gpu')],
  audio: [decoder, tflite('tf_lite_audio_encoder_hw', 'cpu')],
  omni: [decoder, tflite('tf_lite_vision_encoder', 'cpu, GPU'), tflite('tf_lite_audio_adapter', 'cpu')],
}
const settingsOf = (log) => log.filter((c) => c.call === 'engine_settings_create').map(({ backend, vision_backend, audio_backend }) => ({ backend, vision_backend, audio_backend }))

test('a model that takes images or audio gets its encoders, on the backend it prefers, and a model that does not gets none', { skip, timeout: 60_000 }, async () => {
  const expected = {
    text: { backend: 'cpu', vision_backend: null, audio_backend: null },
    vl: { backend: 'cpu', vision_backend: 'cpu', audio_backend: null },
    'gpu-vision': { backend: 'cpu', vision_backend: 'gpu', audio_backend: null },
    audio: { backend: 'cpu', vision_backend: null, audio_backend: 'cpu' },
    omni: { backend: 'cpu', vision_backend: 'cpu', audio_backend: 'cpu' },
    unreadable: { backend: 'cpu', vision_backend: null, audio_backend: null },
  }
  for (const [name, settings] of Object.entries(expected)) {
    const path = name === 'unreadable' ? unreadable(name) : readable(name)
    const log = await callsDuring(async () => assert.equal((await chat(fake, { model: `${path},cpu`, messages: hello })).status, 200))
    assert.deepEqual(settingsOf(log), [settings], name)
  }
})

test('the config\'s vision_backend and audio_backend are used for a model that takes that input, and said to be unused otherwise', { skip, timeout: 60_000 }, async () => {
  const config = join(work, 'encoders.json')
  writeFileSync(config, JSON.stringify({
    default: { audio_backend: 'gpu' },
    models: { 'vl-configured.litertlm': { vision_backend: 'GPU' }, 'text-configured.litertlm': { vision_backend: 'gpu' } },
  }))
  const server = await startServer(binary, { ...process.env, LITERT_LM_LIBRARY: '', FAKE_LITERT_LM_CALLS: callLog }, ['--config', config, '--verbose'])
  try {
    const settings = async (name) => settingsOf(await callsDuring(async () => {
      assert.equal((await chat(server, { model: readable(name, kinds[name.split('-')[0]]), messages: hello })).status, 200)
    }))
    assert.deepEqual(await settings('vl-configured'), [{ backend: 'cpu', vision_backend: 'gpu', audio_backend: null }])
    assert.deepEqual(await settings('text-configured'), [{ backend: 'cpu', vision_backend: null, audio_backend: null }])
    assert.deepEqual(await settings('omni-configured'), [{ backend: 'cpu', vision_backend: 'cpu', audio_backend: 'gpu' }])
    const lines = await logged(server)
    assert.ok(lines.includes('vision_backend in the config is not used for text-configured.litertlm: it takes no vision input'), server.stderr)
    assert.ok(lines.includes('audio_backend in the config is not used for vl-configured.litertlm: it takes no audio input'), server.stderr)
  } finally {
    stopServer(server)
  }
})

test('an image or audio part sent to a model without that input is a 400 that says so, before the model loads', { skip, timeout: 60_000 }, async () => {
  const text = readable('text-only')
  for (const [messages, input] of [[imageMessage('What color is this?'), 'image'], [audioMessage, 'audio']]) {
    for (const stream of [false, true]) {
      const log = await callsDuring(async () => {
        const reply = await chat(fake, { model: text, messages, stream })
        assert.deepEqual(reply, { status: 400, error: `Invalid messages: text-only.litertlm takes no ${input} input` })
      })
      assert.deepEqual(settingsOf(log), [], 'nothing loaded')
    }
  }
  // An image earlier in the conversation counts too.
  const reply = await chat(fake, { model: text, messages: [...imageMessage('What color is this?'), { role: 'assistant', content: 'Red.' }, ...hello] })
  assert.equal(reply.status, 400)
  // A model that takes it, and one whose header the server cannot read (left to the runtime, as before), get it.
  assert.equal((await chat(fake, { model: readable('vl'), messages: imageMessage('What color is this?') })).status, 200)
  assert.equal((await chat(fake, { model: readable('omni'), messages: audioMessage })).status, 200)
  assert.equal((await chat(fake, { model: unreadable('unreadable'), messages: imageMessage('What color is this?') })).status, 200)
})

// The server's peak resident memory, from /proc (Linux, as the tests are).
const peakMb = (server) => Number(/^VmHWM:\s+(\d+) kB/m.exec(readFileSync(`/proc/${server.child.pid}/status`, 'utf8'))[1]) / 1024

for (const path of [...new Set([...realModels, ...visionModels])].length ? [...new Set([...realModels, ...visionModels])] : ['<model>']) {
  test(`a real model's file is read for what it takes without reading its model into memory: ${path}`, { skip: skipReal && skipVision, timeout: 300_000 }, async (t) => {
    // No compiled-model cache, as in every real-model test: a model that is loaded after all (one that takes audio, or
    // a refusal that broke) would otherwise get one beside it, as large as the model.
    const config = join(work, 'no-cache.json')
    writeFileSync(config, JSON.stringify({ default: { cache: 'no' } }))
    const server = await startServer(binary, { ...process.env, LITERT_LM_LIBRARY: realLibrary }, ['--config', config])
    try {
      const idle = peakMb(server)
      // Audio, which none of the litert-community files named above takes: refused once the file is read, before any
      // engine is created, so the peak is what reading the file cost.
      const reply = await chat(server, { model: `${path},cpu`, messages: audioMessage, max_completion_tokens: 1 }, 300_000)
      if (reply.status === 200) return t.skip(`${basename(path)} takes audio, so it was loaded`)
      assert.equal(reply.error, `Invalid messages: ${basename(path)} takes no audio input`)
      assert.ok(peakMb(server) - idle < 32, `${basename(path)}: peak ${idle.toFixed(0)} MB idle, ${peakMb(server).toFixed(0)} MB after its file was read`)
    } finally {
      stopServer(server)
    }
  })
}

for (const path of visionModels.length ? visionModels : ['<model>']) {
  test(`a real model that takes images sees one: ${path}`, { skip: skipVision, timeout: 900_000 }, async () => {
    const config = join(work, 'no-cache.json')
    writeFileSync(config, JSON.stringify({ default: { cache: 'no' } }))
    const server = await startServer(binary, { ...process.env, LITERT_LM_LIBRARY: realLibrary }, ['--config', config])
    try {
      const reply = await chat(server, { model: `${path},cpu`, messages: imageMessage('What color is this image? Answer in one word.'), max_completion_tokens: 16 }, 300_000)
      assert.equal(reply.status, 200, reply.error)
      assert.match(reply.content, /red/i)
      const plain = await chat(server, { model: `${path},cpu`, messages: [{ role: 'user', content: 'Say hello.' }], max_completion_tokens: 16 }, 300_000)
      assert.equal(plain.status, 200, plain.error)
    } finally {
      stopServer(server)
    }
  })
}

// The sampler the stand-in logged for the conversation a request was answered with (the last one created).
async function samplerFor(server, body) {
  const log = await callsDuring(async () => {
    const reply = await chat(server, body)
    assert.equal(reply.status, 200, reply.error)
  })
  const created = log.filter((c) => c.call === 'conversation_create')
  assert.ok(created.length > 0, 'no conversation created')
  return created.at(-1).config.sampler ?? null
}
const f = Math.fround
const NO_LIMIT = 2147483647
const TOP_P = 2
// LlmMetadata with the model's own sampler_params (field 4, a SamplerParameters: type 1, k 2, p 3 and temperature 4,
// the last two floats).
const float = (n, v) => { const b = Buffer.alloc(5); b[0] = (n << 3) | 5; b.writeFloatLE(v, 1); return b }
const samplerMetadata = ({ type, k, p, temperature }) => ({ type: 5, bytes: proto.field(4, [
  proto.varint(1, type), ...(k === undefined ? [] : [proto.varint(2, k)]), ...(p === undefined ? [] : [float(3, p)]),
  ...(temperature === undefined ? [] : [float(4, temperature)]),
]) })
// A main model section whose decode signature outputs "logits" of [1, 1, vocabulary], as a tflite::Model lays them
// out (subgraphs 2, signature_defs 7; SignatureDef outputs 1, signature_key 2, subgraph_index 4; TensorMap name 0,
// tensor_index 1; SubGraph tensors 0; Tensor shape 0).
const decoderWith = (vocabulary, model_type = 'tf_lite_prefill_decode', constraint) => ({
  ...tflite(model_type, constraint),
  bytes: flatbuffer(fb.table([undefined, undefined, fb.vector([fb.table([fb.vector([fb.table([fb.ints([1, 1, vocabulary])])])])]),
    undefined, undefined, undefined, undefined,
    fb.vector([fb.table([undefined, fb.vector([fb.table(['logits', fb.u32(0)])]), 'decode', undefined, fb.u32(0)])])])),
})
Object.assign(kinds, {
  'sampler-topp': [decoder, samplerMetadata({ type: TOP_P, k: 50, p: 0.9, temperature: 0.6 })],
  'sampler-topk': [decoder, samplerMetadata({ type: 1, k: 40, temperature: 0.8 })],
  'sampler-greedy': [decoder, samplerMetadata({ type: 3 })],
  'sampler-k0': [decoder, samplerMetadata({ type: TOP_P, k: 0, p: 0.95, temperature: 0.7 })],
  'plain-vocabulary': [decoderWith(32000)],
  // An Artisan text decoder, which LiteRT-LM runs on GPU as GPU_ARTISAN (gemma-4-E2B-it-gpu has one), with a sampler.
  artisan: [decoderWith(262144, 'tf_lite_artisan_text_decoder', 'gpu_artisan'), samplerMetadata({ type: TOP_P, k: 64, p: 0.95, temperature: 1 })],
})

test('the fields a request leaves out of its sampler come from the model\'s own parameters, and the ones it sets are used as set', { skip, timeout: 60_000 }, async () => {
  const model = (name, backend = 'cpu') => `${readable(name)},${backend}`
  const check = async (name, request, expected, backend) => {
    const sampler = await samplerFor(fake, { model: model(name, backend), messages: hello, ...request })
    assert.ok(sampler, `no sampler set: ${name} ${JSON.stringify(request)}`)
    const { seed, ...rest } = sampler
    assert.deepEqual(rest, { type: TOP_P, ...expected }, `${name} ${JSON.stringify(request)}`)
    assert.ok(Number.isInteger(seed))
  }
  // The model's own: TOP_P as given, TOP_K as TOP_P with p 1 (the CPU sampler has only TOP_P), GREEDY as k 1, and no k
  // as no limit.
  await check('sampler-topp', {}, { top_k: 50, top_p: f(0.9), temperature: f(0.6) })
  await check('sampler-topk', {}, { top_k: 40, top_p: 1, temperature: f(0.8) })
  await check('sampler-greedy', {}, { top_k: 1, top_p: 1, temperature: 1 })
  await check('sampler-k0', {}, { top_k: NO_LIMIT, top_p: f(0.95), temperature: f(0.7) })
  // Each field the request sets replaces only that one.
  await check('sampler-topp', { temperature: 1.2 }, { top_k: 50, top_p: f(0.9), temperature: f(1.2) })
  await check('sampler-topp', { top_p: 0.5 }, { top_k: 50, top_p: 0.5, temperature: f(0.6) })
  await check('sampler-topp', { top_k: 10 }, { top_k: 10, top_p: f(0.9), temperature: f(0.6) })
  // A model with none: LiteRT-LM's own defaults, which are greedy (k 1), so a temperature or top_p the request sets
  // alone gets no top-k limit, or it would do nothing; an explicit top_k, and temperature 0, stay greedy.
  await check('plain', {}, { top_k: 1, top_p: f(0.95), temperature: 1 })
  await check('plain', { temperature: 1.5 }, { top_k: NO_LIMIT, top_p: f(0.95), temperature: 1.5 })
  await check('plain', { top_p: 0.8 }, { top_k: NO_LIMIT, top_p: f(0.8), temperature: 1 })
  await check('plain', { temperature: 0 }, { top_k: 1, top_p: f(0.95), temperature: 0 })
  await check('plain', { temperature: 1.5, top_k: 1 }, { top_k: 1, top_p: f(0.95), temperature: 1.5 })
  // On the GPU backend, no limit is the model's vocabulary, all its tokens; where that is unknown, as on CPU.
  await check('plain-vocabulary', { temperature: 1.5 }, { top_k: 32000, top_p: f(0.95), temperature: 1.5 }, 'gpu')
  await check('plain-vocabulary', { temperature: 1.5 }, { top_k: NO_LIMIT, top_p: f(0.95), temperature: 1.5 })
  await check('plain', { temperature: 1.5 }, { top_k: NO_LIMIT, top_p: f(0.95), temperature: 1.5 }, 'gpu')
  const lines = await logged(fake)
  // Said once per model and backend, not on every request.
  assert.deepEqual(lines.filter((l) => l.startsWith("top_k is not set and plain.litertlm's")), [
    "top_k is not set and plain.litertlm's default is 1 (greedy), under which the request's temperature would have no " +
      'effect; sampling on cpu with top_k 2147483647 (no limit)',
    "top_k is not set and plain.litertlm's default is 1 (greedy), under which the request's temperature would have no " +
      'effect; sampling on gpu with top_k 2147483647 (no limit)',
  ], fake.stderr)
  assert.ok(lines.includes("top_k is not set and plain-vocabulary.litertlm's default is 1 (greedy), under which the " +
    "request's temperature would have no effect; sampling on gpu with top_k 32000 (its vocabulary: no limit)"), fake.stderr)
  // On NPU, and for an Artisan text decoder on GPU, where LiteRT-LM leaves sampling to the model, nothing is set
  // unless the request sets something.
  for (const name of ['sampler-topp,npu', 'artisan,gpu']) {
    const [kind, backend] = name.split(',')
    assert.equal(await samplerFor(fake, { model: model(kind, backend), messages: hello }), null, name)
  }
  const npu = await samplerFor(fake, { model: model('sampler-topp', 'npu'), messages: hello, temperature: 0.3 })
  assert.deepEqual({ ...npu, seed: 0 }, { type: TOP_P, top_k: 50, top_p: f(0.9), temperature: f(0.3), seed: 0 })
  const artisan = await samplerFor(fake, { model: model('artisan', 'gpu'), messages: hello, temperature: 0.3 })
  assert.deepEqual({ ...artisan, seed: 0 }, { type: TOP_P, top_k: 64, top_p: f(0.95), temperature: f(0.3), seed: 0 })
})

test('a request without a seed is sampled with a new random seed, and one with a seed with that seed', { skip, timeout: 60_000 }, async () => {
  const seeds = []
  for (let i = 0; i < 6; i++) seeds.push((await samplerFor(fake, { model: readable('sampler-topp'), messages: hello }))?.seed)
  for (let i = 0; i < 2; i++) seeds.push((await samplerFor(fake, { model: readable('sampler-topp'), messages: hello, temperature: 1 })).seed)
  assert.equal(new Set(seeds).size, seeds.length, `seeds repeat: ${seeds}`)
  // Not 0 or 1, which seed the CPU sampler's generator alike.
  assert.ok(seeds.every((s) => s > 1), `${seeds}`)
  assert.equal((await samplerFor(fake, { model: readable('sampler-topp'), messages: hello, seed: 7 })).seed, 7)
  assert.equal((await samplerFor(fake, { model: readable('plain'), messages: hello, seed: 0 })).seed, 0)
})

for (const path of realModels.length ? realModels : ['<model>']) {
  test(`a real model samples with a temperature sent alone, differently each time without a seed: ${path}`, { skip: skipReal, timeout: 900_000 }, async () => {
    const config = join(work, 'no-cache.json')
    writeFileSync(config, JSON.stringify({ default: { cache: 'no' } }))
    const server = await startServer(binary, { ...process.env, LITERT_LM_LIBRARY: realLibrary }, ['--config', config])
    try {
      const ask = async (extra) => {
        const reply = await chat(server, { model: `${path},cpu`, messages: [{ role: 'user', content: 'Write one sentence about the sea.' }], max_completion_tokens: 24, ...extra }, 300_000)
        assert.equal(reply.status, 200, reply.error)
        return reply.reasoning + reply.content // a thinking model (Qwen3) may only think in 24 tokens
      }
      const unseeded = [await ask({ temperature: 1.5 }), await ask({ temperature: 1.5 }), await ask({ temperature: 1.5 })]
      assert.ok(new Set(unseeded).size > 1, `the same reply three times: ${unseeded[0]}`)
      assert.equal(await ask({ temperature: 1.5, seed: 7 }), await ask({ temperature: 1.5, seed: 7 }))
    } finally {
      stopServer(server)
    }
  })
}

test('top_k 0 or below is no top-k limit, and a sampler value the runtime cannot take is a 400 that names it', { skip, timeout: 60_000 }, async () => {
  const k = async (request, backend = 'cpu') => (await samplerFor(fake, { model: `${readable('sampler-topp')},${backend}`, messages: hello, ...request }))?.top_k
  assert.equal(await k({ top_k: 0 }), NO_LIMIT)
  assert.equal(await k({ top_k: -1, temperature: 0.7 }), NO_LIMIT)
  assert.equal(await k({ top_k: 40.0 }), 40)
  // On GPU no limit is the model's vocabulary, and a top_k above 0 is used as set: LiteRT-LM sets no limit there.
  const gpu = async (request) => (await samplerFor(fake, { model: `${readable('plain-vocabulary')},gpu`, messages: hello, ...request }))?.top_k
  assert.equal(await gpu({ top_k: 0 }), 32000)
  assert.equal(await gpu({ top_k: 65 }), 65)
  assert.equal(await gpu({ top_k: 100000 }), 100000)
  const lines = await logged(fake)
  assert.equal(lines.filter((l) => l === 'top_k 0 is no top-k limit: top_k 2147483647 for sampler-topp.litertlm (the CPU sampler takes the whole vocabulary)').length, 1, fake.stderr)
  assert.ok(lines.includes('top_k 0 is no top-k limit: top_k 32000 for plain-vocabulary.litertlm (its vocabulary)'), fake.stderr)
  const refused = async (request, backend = 'cpu') => {
    const reply = await chat(fake, { model: `${readable('sampler-topp')},${backend}`, messages: hello, ...request })
    assert.equal(reply.status, 400, JSON.stringify(request))
    return reply.error
  }
  assert.equal(await refused({ top_p: 1.5 }), 'Invalid sampler parameters: ValueError: top_p must be between 0 and 1, got 1.5')
  assert.equal(await refused({ top_p: -0.1 }), 'Invalid sampler parameters: ValueError: top_p must be between 0 and 1, got -0.1')
  assert.equal(await refused({ temperature: -1 }), 'Invalid sampler parameters: ValueError: temperature must be at least 0, got -1')
  assert.equal(await refused({ top_k: 2.5 }), 'Invalid sampler parameters: ValueError: top_k must be an integer, got 2.5')
  assert.equal(await refused({ seed: 1.5 }), 'Invalid sampler parameters: ValueError: seed must be an integer, got 1.5')
  assert.equal(await refused({ seed: 1e12 }), 'Invalid sampler parameters: ValueError: seed must be an integer, got 1000000000000')
})

for (const path of realModels.length ? realModels : ['<model>']) {
  test(`a real model takes top_k 0 and -1 as no limit, and refuses a top_p it cannot take with a 400: ${path}`, { skip: skipReal, timeout: 900_000 }, async () => {
    const config = join(work, 'no-cache.json')
    writeFileSync(config, JSON.stringify({ default: { cache: 'no' } }))
    const server = await startServer(binary, { ...process.env, LITERT_LM_LIBRARY: realLibrary }, ['--config', config])
    try {
      const ask = (extra) => chat(server, { model: `${path},cpu`, messages: [{ role: 'user', content: 'Say hello.' }], max_completion_tokens: 8, ...extra }, 300_000)
      for (const extra of [{ top_k: 0 }, { top_k: -1, temperature: 0.7 }]) {
        const reply = await ask(extra)
        assert.equal(reply.status, 200, `${JSON.stringify(extra)}: ${reply.error}`)
      }
      for (const extra of [{ top_p: 1.5 }, { temperature: -1 }]) assert.equal((await ask(extra)).status, 400, JSON.stringify(extra))
    } finally {
      stopServer(server)
    }
  })
}
