// Tests which conversations the LiteRT-LM server asks the runtime for, and what it answers when one cannot be made
// (src/main.cc, HandleChatCompletions):
//   node --test engines/litert-lm-server/constrained-decoding.test.mjs
// The server is built for this machine with c++, as in parent-death.test.mjs (testdata/harness.mjs), and runs against
// testdata/fake_litert_lm.cc, a stand-in liblitert-lm.so that answers the conversation calls as LiteRT-LM 0.18.0 does
// for an LFM2 model, for Gemma models and for a few failures, and whose reply says how its conversation was created.
// Linux only, like that test, and skipped without a C and a C++ compiler. Building takes a few seconds.
//
// Given a liblitert-lm.so that runs on this machine (the one in the manylinux litert-lm-api wheel of the version
// scripts/stage-litert-lm-engine.mjs pins) and .litertlm models, the last tests also run the server on each model:
//   LITERT_LM_TEST_LIBRARY=<liblitert-lm.so> LITERT_LM_TEST_MODELS=<model.litertlm>[,<model.litertlm>...] \
//     node --test engines/litert-lm-server/constrained-decoding.test.mjs
// (an LFM2 one: litert-community/LFM2.5-230M on Hugging Face; a Gemma one: litert-community/gemma-3-270m-it).
// Every server a test starts is killed by pid when that test ends, or at the latest when the tests end. Every wait is
// bounded (the compiler check, the build, a server's start, each request, each test), so a server that hangs fails the
// test by name instead of holding the run.
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, before, test } from 'node:test'
import { buildServer, chat, killServers, logged, realLibrary, realModels, skip, skipReal, startServer, stopServer } from './testdata/harness.mjs'

const work = mkdtempSync(join(tmpdir(), 'litert-lm-server-cd-test-'))
// What before() is still doing. With a --test-name-pattern that leaves no test in this file, node runs after() while
// before() is starting the server, and removing `work` under it failed the file (spawn ... ENOENT); so after() waits.
let ready = Promise.resolve()
after(async () => {
  await ready.catch(() => {})
  killServers()
  rmSync(work, { recursive: true, force: true })
})

// One file per kind of model the stand-in knows (see testdata/fake_litert_lm.cc); it reads only the name. None starts
// with "LITERTLM", so the server leaves it to the runtime.
const model = (kind) => {
  const path = join(work, `${kind}.litertlm`)
  writeFileSync(path, 'stand-in model')
  return path
}

let binary
let fake

before(() => (ready = (async () => {
  if (skip) return
  binary = buildServer(work)
  // --verbose for logged().
  fake = await startServer(binary, { ...process.env, LITERT_LM_LIBRARY: '' }, ['--verbose'])
})()), { timeout: 300_000 })

// The stand-in's reply: how the conversation was created and sent, and how many conversations the process has created.
function created(reply) {
  assert.equal(reply.status, 200, reply.error)
  return Object.fromEntries(reply.content.split(' ').map((kv) => kv.split('=')).map(([k, v]) => [k, Number(v)]))
}
// The same without the count.
function how(reply) {
  const { creates, ...rest } = created(reply)
  return rest
}

const hello = [{ role: 'user', content: 'Hello' }]
const tools = [{
  type: 'function',
  function: { name: 'get_weather', description: 'Weather in a city', parameters: { type: 'object', properties: { city: { type: 'string' } }, required: ['city'] } },
}]
const jsonSchema = {
  type: 'json_schema',
  json_schema: { name: 'city', schema: { type: 'object', properties: { city: { type: 'string' } }, required: ['city'] } },
}
const notSupported = 'constrained decoding is not available for'

test('a chat with an LFM2 model is answered, streamed or not, without constrained decoding', { skip, timeout: 60_000 }, async () => {
  const lfm2 = model('lfm2-a')
  assert.deepEqual(how(await chat(fake, { model: lfm2, messages: hello })), { constrained: 0, tools: 0, provider: 0, constraint: 0 })
  const streamed = await chat(fake, { model: lfm2, messages: hello, stream: true })
  assert.equal(streamed.status, 200)
  assert.equal(streamed.finish, 'stop')
  assert.match(streamed.content, /^constrained=0 tools=0 provider=0 constraint=0 /)
})

test('constrained decoding is turned on for a request with tools, and only for one', { skip, timeout: 60_000 }, async () => {
  const gemma = model('gemma')
  assert.equal(created(await chat(fake, { model: gemma, messages: hello, tools })).constrained, 1)
  assert.equal(created(await chat(fake, { model: gemma, messages: hello })).constrained, 0)
  assert.equal(created(await chat(fake, { model: gemma, messages: hello, response_format: jsonSchema })).constrained, 0)
  assert.equal((await logged(fake)).some((l) => l.startsWith(notSupported)), false)
})

test('a request with tools to a model that refuses constrained decoding is answered without it, found out once per load', { skip, timeout: 60_000 }, async () => {
  for (const kind of ['lfm2-b', 'fst-unsupported']) {
    const path = model(kind)
    const before = created(await chat(fake, { model: path, messages: hello })).creates
    const first = created(await chat(fake, { model: path, messages: hello, tools }))
    assert.deepEqual({ ...first, creates: first.creates - before },
      { constrained: 0, tools: 1, provider: 0, constraint: 0, creates: 2 }, 'refused, then created without it')
    const second = created(await chat(fake, { model: path, messages: hello, tools }))
    assert.deepEqual({ ...second, creates: second.creates - first.creates },
      { constrained: 0, tools: 1, provider: 0, constraint: 0, creates: 1 }, 'created without it at once')
    const lines = (await logged(fake)).filter((l) => l.startsWith(`${notSupported} ${kind}.litertlm`))
    assert.equal(lines.length, 1, fake.stderr)
  }
  const lines = await logged(fake)
  assert.ok(lines.includes('constrained decoding is not available for lfm2-b.litertlm (FAILED_PRECONDITION: Constrained ' +
    'decoding is not supported for Lfm2DataProcessor.); its tool calls are not constrained'), fake.stderr)
  assert.ok(lines.includes('constrained decoding is not available for fst-unsupported.litertlm (INTERNAL: Failed to ' +
    'create GemmaModelConstraintProvider.); its tool calls are not constrained'), fake.stderr)
  // Found out again once the model is loaded again (the file may since hold another model).
  const lfm2 = model('lfm2-b')
  await chat(fake, { model: model('gemma'), messages: hello })
  assert.equal(created(await chat(fake, { model: lfm2, messages: hello, tools })).constrained, 0)
  assert.equal((await logged(fake)).filter((l) => l.startsWith(`${notSupported} lfm2-b.litertlm`)).length, 2)
})

test('a response_format is followed by a model that refuses constrained decoding', { skip, timeout: 60_000 }, async () => {
  const lfm2 = model('lfm2-c')
  assert.deepEqual(how(await chat(fake, { model: lfm2, messages: hello, response_format: jsonSchema })),
    { constrained: 0, tools: 0, provider: 1, constraint: 2 })
  assert.deepEqual(how(await chat(fake, { model: lfm2, messages: hello, tools, response_format: { type: 'regex', regex: 'yes|no' } })),
    { constrained: 0, tools: 1, provider: 1, constraint: 1 })
})

test('a response_format the model cannot follow is a 400 that says why', { skip, timeout: 60_000 }, async () => {
  const noLlg = model('no-llg')
  for (const extra of [{}, { tools }]) {
    const reply = await chat(fake, { model: noLlg, messages: hello, response_format: { type: 'regex', regex: 'yes|no' }, ...extra })
    assert.equal(reply.status, 400)
    assert.equal(reply.error, 'Invalid response_format parameters: this model cannot follow a response_format: ' +
      "INVALID_ARGUMENT: LlGuidanceConfig::eos_id wasn't set and no valid stop token was found in SessionConfig.")
  }
  assert.equal(created(await chat(fake, { model: noLlg, messages: hello, tools })).constrained, 1)
})

test('a response_format whose regex does not compile is a 400 that says why', { skip, timeout: 60_000 }, async () => {
  for (const kind of ['gemma', 'lfm2-d']) {
    const reply = await chat(fake, { model: model(kind), messages: hello, response_format: { type: 'regex', regex: '(' } })
    assert.equal(reply.status, 400)
    assert.equal(reply.error, 'Invalid response_format parameters: INTERNAL: Failed to create LLGuidance constraint: ' +
      'invalid regex "(": unclosed group')
  }
})

test('a conversation that cannot be created at all is still a 500 with the runtime\'s reason', { skip, timeout: 60_000 }, async () => {
  const broken = model('broken')
  for (const extra of [{}, { tools }, { response_format: jsonSchema }, { tools, response_format: jsonSchema }]) {
    const reply = await chat(fake, { model: broken, messages: hello, ...extra })
    assert.deepEqual(reply, { status: 500, error: 'RuntimeError: Failed to create conversation: INTERNAL: cannot create a session' })
  }
  assert.equal((await logged(fake)).some((l) => l.startsWith(`${notSupported} broken.litertlm`)), false)
})

for (const path of realModels.length ? realModels : ['<model>']) {
  test(`a real model answers a chat, a stream, tools and a response_format: ${path}`, { skip: skipReal, timeout: 600_000 }, async () => {
    // No compiled-model cache: by default the runtime writes one beside the model, as large as the model.
    const config = join(work, 'no-cache.json')
    writeFileSync(config, JSON.stringify({ default: { cache: 'no' } }))
    const real = await startServer(binary, { ...process.env, LITERT_LM_LIBRARY: realLibrary }, ['--config', config])
    try {
      const ms = 300_000 // per request: the first one loads the model
      const ref = `${path},cpu`
      const plain = await chat(real, { model: ref, messages: [{ role: 'user', content: 'Say hello.' }], max_completion_tokens: 16 }, ms)
      assert.equal(plain.status, 200, plain.error)
      assert.ok(plain.content || plain.reasoning, 'no text') // a thinking model (Qwen3) may only think in 16 tokens
      const streamed = await chat(real, { model: ref, messages: [{ role: 'user', content: 'Count to three.' }], max_completion_tokens: 16, stream: true }, ms)
      assert.equal(streamed.status, 200, streamed.error)
      assert.ok(streamed.content || streamed.reasoning, 'no text streamed')
      const withTools = await chat(real, { model: ref, messages: [{ role: 'user', content: 'What is the weather in Paris?' }], tools, max_completion_tokens: 48 }, ms)
      assert.equal(withTools.status, 200, withTools.error)
      const yesNo = await chat(real, { model: ref, messages: [{ role: 'user', content: 'Is the sky blue? Answer yes or no.' }], response_format: { type: 'regex', regex: '(yes|no)' }, max_completion_tokens: 8 }, ms)
      assert.equal(yesNo.status, 200, yesNo.error)
      assert.match(yesNo.content, /^(yes|no)$/)
      const badRegex = await chat(real, { model: ref, messages: hello, response_format: { type: 'regex', regex: '(' }, max_completion_tokens: 8 }, ms)
      assert.equal(badRegex.status, 400)
      assert.match(badRegex.error, /^Invalid response_format parameters: INTERNAL: Failed to create LLGuidance constraint: /)
    } finally {
      stopServer(real) // now, not when the file ends: each one holds its model in memory
    }
  })
}
