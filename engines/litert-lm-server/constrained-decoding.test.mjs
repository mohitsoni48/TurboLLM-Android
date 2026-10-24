// Tests which conversations the LiteRT-LM server asks the runtime for, and what it answers when one cannot be made
// (src/main.cc, HandleChatCompletions):
//   node --test engines/litert-lm-server/constrained-decoding.test.mjs
// The server is built for this machine with c++, as in parent-death.test.mjs, and runs against
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
import { spawn, spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('.', import.meta.url))
const work = mkdtempSync(join(tmpdir(), 'litert-lm-server-cd-test-'))
const servers = []
// What before() is still doing. With a --test-name-pattern that leaves no test in this file, node runs after() while
// before() is starting the server, and removing `work` under it failed the file (spawn ... ENOENT); so after() waits.
let ready = Promise.resolve()
after(async () => {
  await ready.catch(() => {})
  for (const s of servers) {
    try {
      process.kill(s.pid, 'SIGKILL')
    } catch {}
  }
  rmSync(work, { recursive: true, force: true })
})

// A compiler that does not answer in 10 s counts as missing (the timeout sets .error), so the file skips cleanly.
const missing = ['cc', 'c++'].filter((c) => spawnSync(c, ['--version'], { stdio: 'ignore', timeout: 10_000 }).error)
const skip = process.platform !== 'linux' ? 'the server is tested on Linux only'
  : missing.length ? `no ${missing.join(' or ')} on this machine` : false
const realLibrary = process.env.LITERT_LM_TEST_LIBRARY
const realModels = (process.env.LITERT_LM_TEST_MODELS ?? '').split(',').filter(Boolean)
const skipReal = skip || (!realLibrary || !realModels.length
  ? 'set LITERT_LM_TEST_LIBRARY and LITERT_LM_TEST_MODELS to run the server on real models' : false)
const binary = join(work, 'litertlm_server')

// One file per kind of model the stand-in knows (see testdata/fake_litert_lm.cc); it reads only the name. None starts
// with "LITERTLM", so the server leaves it to the runtime.
const model = (kind) => {
  const path = join(work, `${kind}.litertlm`)
  writeFileSync(path, 'stand-in model')
  return path
}

let fake

before(() => (ready = (async () => {
  if (skip) return
  // The stand-in: testdata/fake_litert_lm.cc, and every other function the server resolves (src/litert_lm_api.h) as
  // one that does nothing.
  const header = readFileSync(join(root, 'src', 'litert_lm_api.h'), 'utf8')
  const names = [...header.matchAll(/^\s*X\((\w+)\)/gm)].map((m) => m[1])
  assert.ok(names.length > 40, 'the C API list in litert_lm_api.h was not found')
  const source = readFileSync(join(root, 'testdata', 'fake_litert_lm.cc'), 'utf8')
  const defined = new Set([...source.matchAll(/^\S.*?\b(litert_lm_\w+)\(/gm)].map((m) => m[1]))
  writeFileSync(join(work, 'rest.c'), names.filter((n) => !defined.has(n)).map((n) => `void ${n}(void) {}\n`).join(''))
  // Each build is bounded by itself: spawnSync holds the event loop, so the hook's own timeout cannot end one.
  const cxx = (args) => {
    const r = spawnSync('c++', args, { encoding: 'utf8', timeout: 120_000 })
    assert.equal(r.status, 0, r.error ? `c++: ${r.error.message}` : r.stderr)
  }
  cxx([
    '-std=c++17', '-shared', '-fPIC', '-x', 'c++', join(root, 'testdata', 'fake_litert_lm.cc'), '-x', 'c', join(work, 'rest.c'),
    '-I', join(root, 'third_party', 'litert_lm'), '-o', join(work, 'liblitert-lm.so'),
  ])
  cxx([
    '-std=c++17', '-O0', '-w', '-I', join(root, 'third_party', 'litert_lm'), '-I', join(root, 'third_party'),
    '-DLITERT_LM_VERSION="test"', join(root, 'src', 'main.cc'), join(root, 'src', 'litert_lm_api.cc'),
    '-ldl', '-pthread', '-o', binary,
  ])
  // Beside the server, where LoadLiteRtLm looks first after LITERT_LM_LIBRARY. --verbose for logged() below.
  fake = await startServer({ ...process.env, LITERT_LM_LIBRARY: '' }, ['--verbose'])
})()), { timeout: 300_000 })

function freePort() {
  return new Promise((resolve, reject) => {
    const s = createServer().once('error', reject)
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address()
      s.close(() => resolve(port))
    })
  })
}

async function startServer(env, args = []) {
  const port = await freePort()
  const child = spawn(binary, ['serve', '--host', '127.0.0.1', '--port', String(port), ...args], { env, stdio: ['ignore', 'ignore', 'pipe'] })
  servers.push(child)
  const server = { port, stderr: '', child }
  child.stderr.setEncoding('utf8')
  child.stderr.on('data', (d) => { server.stderr += d })
  await new Promise((resolve, reject) => {
    // It logs this before it loads any model, so 30 s is plenty; the server is killed when the tests end.
    const timer = setTimeout(() => reject(new Error(`the server did not start in 30 s: ${server.stderr}`)), 30_000)
    const onData = () => {
      if (server.stderr.includes('Starting OpenAI-compatible API server')) {
        clearTimeout(timer)
        resolve()
      }
    }
    child.stderr.on('data', onData)
    child.once('exit', (code) => {
      clearTimeout(timer)
      reject(new Error(`the server exited ${code}: ${server.stderr}`))
    })
  })
  return server
}

// fetch, bounded: without a signal undici waits 300 s for a reply's headers and then says only "fetch failed".
async function request(url, init, ms) {
  const signal = AbortSignal.timeout(ms)
  try {
    const res = await fetch(url, { ...init, signal })
    return { status: res.status, text: await res.text() }
  } catch (err) {
    if (!signal.aborted) throw err
    throw new Error(`${init?.method ?? 'GET'} ${url} ${init?.body ?? ''}: no reply in ${ms / 1000} s`)
  }
}

// A stand-in answers at once; the real-model test gives each request longer, since a model loads on its first one.
async function chat(server, body, ms = 30_000) {
  const res = await request(`http://127.0.0.1:${server.port}/v1/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }, ms)
  const text = res.text
  if (body.stream && res.status === 200) {
    const events = text.split('\n\n').filter((e) => e.startsWith('data: ')).map((e) => e.slice(6))
    assert.equal(events.at(-1), '[DONE]', text)
    const chunks = events.slice(0, -1).map((e) => JSON.parse(e))
    return {
      status: res.status,
      content: chunks.map((c) => c.choices[0]?.delta?.content ?? '').join(''),
      reasoning: chunks.map((c) => c.choices[0]?.delta?.reasoning_content ?? '').join(''),
      finish: chunks.at(-1).choices[0].finish_reason,
    }
  }
  const json = JSON.parse(text)
  if (res.status !== 200) return { status: res.status, error: json.error.message }
  return {
    status: res.status,
    content: json.choices[0].message.content ?? '',
    reasoning: json.choices[0].message.reasoning_content ?? '',
    tool_calls: json.choices[0].message.tool_calls,
    finish: json.choices[0].finish_reason,
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
// What the server has logged, all of it up to now: a reply can arrive before the lines logged while it was made. With
// --verbose the server logs each request as it reads it, so once a GET /health made now shows up, all are in.
async function logged(server) {
  const marks = () => server.stderr.split('\n').filter((l) => l === 'GET /health').length
  const seen = marks()
  assert.equal((await request(`http://127.0.0.1:${server.port}/health`, undefined, 5000)).status, 200)
  for (const end = Date.now() + 5000; marks() === seen && Date.now() < end;) await sleep(10)
  assert.ok(marks() > seen, 'the server did not log GET /health')
  return server.stderr.split('\n')
}

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
    const real = await startServer({ ...process.env, LITERT_LM_LIBRARY: realLibrary }, ['--config', config])
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
      // This model's server goes now, not when the file ends: each one holds its model in memory.
      try {
        process.kill(real.child.pid, 'SIGKILL')
      } catch {}
    }
  })
}
