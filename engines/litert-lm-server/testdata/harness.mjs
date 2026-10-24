// What the server's tests share (constrained-decoding.test.mjs, model-settings.test.mjs): building the server for this
// machine with c++, as parent-death.test.mjs does, against testdata/fake_litert_lm.cc, a stand-in liblitert-lm.so;
// starting it; sending it requests, every wait bounded; and writing stand-in .litertlm files.
// Linux only, and skipped without a C and a C++ compiler.
import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

export const root = fileURLToPath(new URL('..', import.meta.url))

// A compiler that does not answer in 10 s counts as missing (the timeout sets .error), so a file skips cleanly.
const missing = ['cc', 'c++'].filter((c) => spawnSync(c, ['--version'], { stdio: 'ignore', timeout: 10_000 }).error)
export const skip = process.platform !== 'linux' ? 'the server is tested on Linux only'
  : missing.length ? `no ${missing.join(' or ')} on this machine` : false
export const realLibrary = process.env.LITERT_LM_TEST_LIBRARY
export const realModels = (process.env.LITERT_LM_TEST_MODELS ?? '').split(',').filter(Boolean)
export const skipReal = skip || (!realLibrary || !realModels.length
  ? 'set LITERT_LM_TEST_LIBRARY and LITERT_LM_TEST_MODELS to run the server on real models' : false)

// Builds the stand-in library and the server into `work`; returns the server's path. The stand-in is
// testdata/fake_litert_lm.cc, and every other function the server resolves (src/litert_lm_api.h) as one that does
// nothing. Beside the server, where LoadLiteRtLm looks first after LITERT_LM_LIBRARY. Each build is bounded by
// itself: spawnSync holds the event loop, so a hook's own timeout cannot end one.
export function buildServer(work) {
  const header = readFileSync(join(root, 'src', 'litert_lm_api.h'), 'utf8')
  const names = [...header.matchAll(/^\s*X\((\w+)\)/gm)].map((m) => m[1])
  assert.ok(names.length > 40, 'the C API list in litert_lm_api.h was not found')
  const source = readFileSync(join(root, 'testdata', 'fake_litert_lm.cc'), 'utf8')
  const defined = new Set([...source.matchAll(/^\S.*?\b(litert_lm_\w+)\(/gm)].map((m) => m[1]))
  writeFileSync(join(work, 'rest.c'), names.filter((n) => !defined.has(n)).map((n) => `void ${n}(void) {}\n`).join(''))
  const cxx = (args) => {
    const r = spawnSync('c++', args, { encoding: 'utf8', timeout: 120_000 })
    assert.equal(r.status, 0, r.error ? `c++: ${r.error.message}` : r.stderr)
  }
  cxx([
    '-std=c++17', '-shared', '-fPIC', '-x', 'c++', join(root, 'testdata', 'fake_litert_lm.cc'), '-x', 'c', join(work, 'rest.c'),
    '-I', join(root, 'third_party', 'litert_lm'), '-I', join(root, 'third_party'), '-o', join(work, 'liblitert-lm.so'),
  ])
  const binary = join(work, 'litertlm_server')
  cxx([
    '-std=c++17', '-O0', '-w', '-I', join(root, 'third_party', 'litert_lm'), '-I', join(root, 'third_party'),
    '-DLITERT_LM_VERSION="test"', join(root, 'src', 'main.cc'), join(root, 'src', 'litert_lm_api.cc'),
    '-ldl', '-pthread', '-o', binary,
  ])
  return binary
}

// Every server started here, so a file's after() can kill what its tests left.
export const servers = []
export function killServers() {
  for (const s of servers) {
    try {
      process.kill(s.pid, 'SIGKILL')
    } catch {}
  }
}

export function freePort() {
  return new Promise((resolve, reject) => {
    const s = createServer().once('error', reject)
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address()
      s.close(() => resolve(port))
    })
  })
}

export async function startServer(binary, env, args = []) {
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

export function stopServer(server) {
  try {
    process.kill(server.child.pid, 'SIGKILL')
  } catch {}
}

// fetch, bounded: without a signal undici waits 300 s for a reply's headers and then says only "fetch failed".
export async function request(url, init, ms) {
  const signal = AbortSignal.timeout(ms)
  try {
    const res = await fetch(url, { ...init, signal })
    return { status: res.status, text: await res.text(), headers: res.headers }
  } catch (err) {
    if (!signal.aborted) throw err
    throw new Error(`${init?.method ?? 'GET'} ${url} ${init?.body ?? ''}: no reply in ${ms / 1000} s`)
  }
}

// A stand-in answers at once; a real model gets longer, since it loads on its first request.
export async function chat(server, body, ms = 30_000) {
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
      chunks,
      headers: res.headers,
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
    usage: json.usage,
    headers: res.headers,
  }
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// What the server has logged, all of it up to now: a reply can arrive before the lines logged while it was made. With
// --verbose the server logs each request as it reads it, so once a GET /health made now shows up, all are in.
export async function logged(server) {
  const marks = () => server.stderr.split('\n').filter((l) => l === 'GET /health').length
  const seen = marks()
  assert.equal((await request(`http://127.0.0.1:${server.port}/health`, undefined, 5000)).status, 200)
  for (const end = Date.now() + 5000; marks() === seen && Date.now() < end;) await sleep(10)
  assert.ok(marks() > seen, 'the server did not log GET /health')
  return server.stderr.split('\n')
}

// The calls the stand-in library logged (FAKE_LITERT_LM_CALLS, one JSON object per line), in order.
export function calls(file) {
  return existsSync(file) ? readFileSync(file, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l)) : []
}

// A FlatBuffer, laid out front to back from a tree of values: every uoffset points forward, as FlatBuffers requires,
// each vtable sits right before its table, and each table starts 8-aligned. fb.table([...]) lists a table's fields by
// index (undefined leaves one out); a field is fb.u8(v), fb.u32(v), fb.i32(v) or fb.u64(v), a string, a table, a
// vector of tables or strings (fb.vector([...])) or of int32s (fb.ints([...])).
export const fb = {
  u8: (v) => ({ size: 1, put: (b, at) => b.writeUInt8(v, at) }),
  u32: (v) => ({ size: 4, put: (b, at) => b.writeUInt32LE(v, at) }),
  i32: (v) => ({ size: 4, put: (b, at) => b.writeInt32LE(v, at) }),
  u64: (v) => ({ size: 8, put: (b, at) => b.writeBigUInt64LE(BigInt(v), at) }),
  table: (fields) => ({ table: fields }),
  vector: (items) => ({ vector: items }),
  ints: (values) => ({ ints: values }),
}
export function flatbuffer(root) {
  const parts = []
  let pos = 0
  const put = (b) => { parts.push(b); pos += b.length; return pos - b.length }
  const pad = (to, extra = 0) => { const n = (to - ((pos + extra) % to)) % to; if (n) put(Buffer.alloc(n)) }
  const patches = [] // [position of a uoffset, position of its target]
  // Lays out `value` (a table, vector or string) and what it points to; returns its position.
  const place = (value) => {
    if (typeof value === 'string') {
      pad(4)
      const b = Buffer.alloc(4 + Buffer.byteLength(value) + 1)
      b.writeUInt32LE(Buffer.byteLength(value))
      b.write(value, 4)
      return put(b)
    }
    if (value.ints) {
      pad(4)
      const b = Buffer.alloc(4 + 4 * value.ints.length)
      b.writeUInt32LE(value.ints.length)
      value.ints.forEach((v, k) => b.writeInt32LE(v, 4 + 4 * k))
      return put(b)
    }
    if (value.vector) {
      pad(4)
      const at = put(Buffer.alloc(4 + 4 * value.vector.length))
      parts.at(-1).writeUInt32LE(value.vector.length)
      value.vector.forEach((item, k) => patches.push([at + 4 + 4 * k, place(item)]))
      return at
    }
    // A table: its soffset, then each field at its own alignment; the vtable gives each field's place in it.
    const slots = []
    let size = 4
    for (const field of value.table) {
      if (field === undefined) { slots.push(0); continue }
      const width = field.size ?? 4
      size += (width - (size % width)) % width
      slots.push(size)
      size += width
    }
    size += (4 - (size % 4)) % 4
    const vtable = Buffer.alloc(4 + 2 * slots.length)
    vtable.writeUInt16LE(vtable.length, 0)
    vtable.writeUInt16LE(size, 2)
    slots.forEach((at, k) => vtable.writeUInt16LE(at, 4 + 2 * k))
    pad(8, vtable.length)
    put(vtable)
    const table = Buffer.alloc(size)
    table.writeInt32LE(vtable.length, 0)
    const at = put(table)
    const later = []
    value.table.forEach((field, k) => {
      if (field === undefined) return
      if (field.put) field.put(table, slots[k])
      else later.push([at + slots[k], field])
    })
    for (const [slot, child] of later) patches.push([slot, place(child)])
    return at
  }
  put(Buffer.alloc(8)) // the root uoffset, and room before the root's vtable
  patches.push([0, place(root)])
  const out = Buffer.concat(parts)
  for (const [at, target] of patches) out.writeUInt32LE(target - at, at)
  return out
}

// A stand-in .litertlm the server can read as the runtime does: "LITERTLM", three uint32 versions, padding, the
// uint64 header end, then the header FlatBuffer (schema/core/litertlm_header_schema.fbs) listing `sections`, each
// { type, bytes, items } (3 = TFLiteModel, 5 = LlmMetadataProto, 9 = ExecutorMetadataProto; items, the section's
// string key-value pairs, such as model_type), whose bytes follow at 16 KiB steps as in real files. The stand-in
// library does not read it.
export function litertlmFile(path, sections) {
  let next = 16384
  const placed = sections.map((s) => {
    const bytes = s.bytes ?? Buffer.alloc(0)
    const begin = next
    next = begin + Math.ceil(Math.max(bytes.length, 1) / 16384) * 16384
    return { ...s, bytes, begin }
  })
  const item = ([key, value]) => fb.table([key, fb.u8(9), fb.table([value])]) // KeyValuePair, its value a StringValue
  const header = flatbuffer(fb.table([undefined, fb.table([fb.vector(placed.map((s) => fb.table([
    s.items ? fb.vector(Object.entries(s.items).map(item)) : undefined, fb.u64(s.begin), fb.u64(s.begin + s.bytes.length), fb.u8(s.type),
  ])))])]))
  assert.ok(32 + header.length <= 16384, 'the header must fit in 16 KiB')
  const u32 = (v) => { const b = Buffer.alloc(4); b.writeUInt32LE(v); return b }
  const u64 = (v) => { const b = Buffer.alloc(8); b.writeBigUInt64LE(BigInt(v)); return b }
  const preamble = Buffer.concat([Buffer.from('LITERTLM'), u32(1), u32(0), u32(0), u32(0), u64(32 + header.length)])
  const file = Buffer.alloc(next)
  Buffer.concat([preamble, header]).copy(file)
  for (const s of placed) s.bytes.copy(file, s.begin)
  writeFileSync(path, file)
  return path
}

// Protobuf encoding, for the sections above: field(n, bytes or a nested array of fields), varint(n, v).
export const proto = {
  varint(n, v) {
    const out = [n << 3]
    for (; v > 127; v >>>= 7) out.push((v & 127) | 128)
    out.push(v)
    return Buffer.from(out)
  },
  field(n, value) {
    const body = Buffer.isBuffer(value) ? value : Array.isArray(value) ? Buffer.concat(value) : Buffer.from(value)
    const len = []
    for (let v = body.length; ; v >>>= 7) {
      if (v > 127) len.push((v & 127) | 128)
      else { len.push(v); break }
    }
    return Buffer.concat([Buffer.from([(n << 3) | 2, ...len]), body])
  },
}
