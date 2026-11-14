// Tests that the LiteRT-LM server ends with the process that started it (src/main.cc, the top of main):
//   node --test engines/litert-lm-server/parent-death.test.mjs
// The server is built for this machine with c++ and runs against a stand-in liblitert-lm.so whose C API functions do
// nothing, which is enough for it to start listening: no model is loaded. Linux only (PR_SET_PDEATHSIG and /proc), and
// skipped without a C and a C++ compiler. Building takes a few seconds. Every process a test starts is killed by pid
// when it ends.
import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { mkdtempSync, readdirSync, readFileSync, readlinkSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('.', import.meta.url))
const work = mkdtempSync(join(tmpdir(), 'litert-lm-server-test-'))
after(() => rmSync(work, { recursive: true, force: true }))

const missing = ['cc', 'c++'].filter((c) => spawnSync(c, ['--version'], { stdio: 'ignore' }).error)
const skip = process.platform !== 'linux' ? 'PR_SET_PDEATHSIG and /proc are Linux only'
  : missing.length ? `no ${missing.join(' or ')} on this machine` : false
const server = join(work, 'litertlm_server')

before(() => {
  if (skip) return
  // The stand-in library: every function the server resolves (src/litert_lm_api.h), each doing nothing. Beside the
  // server, where LoadLiteRtLm looks first after LITERT_LM_LIBRARY, as in the app's nativeLibraryDir.
  const header = readFileSync(join(root, 'src', 'litert_lm_api.h'), 'utf8')
  const names = [...header.matchAll(/^\s*X\((\w+)\)/gm)].map((m) => m[1])
  assert.ok(names.length > 40, 'the C API list in litert_lm_api.h was not found')
  writeFileSync(join(work, 'stub.c'), names.map((n) => `void ${n}(void) {}\n`).join(''))
  const lib = spawnSync('cc', ['-shared', '-fPIC', '-o', join(work, 'liblitert-lm.so'), join(work, 'stub.c')], { encoding: 'utf8' })
  assert.equal(lib.status, 0, lib.stderr)
  const built = spawnSync('c++', [
    '-std=c++17', '-O0', '-w', '-I', join(root, 'third_party', 'litert_lm'), '-I', join(root, 'third_party'),
    '-DLITERT_LM_VERSION="test"', join(root, 'src', 'main.cc'), join(root, 'src', 'litert_lm_api.cc'),
    '-ldl', '-pthread', '-o', server,
  ], { encoding: 'utf8' })
  assert.equal(built.status, 0, built.stderr)
})

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
function stat(pid) {
  try {
    const s = readFileSync(`/proc/${pid}/stat`, 'utf8')
    const f = s.slice(s.lastIndexOf(')') + 2).split(' ')
    return { state: f[0], ppid: Number(f[1]) }
  } catch {
    return null
  }
}
// A zombie counts as gone: it holds no model and no port.
const gone = (pid) => {
  const s = stat(pid)
  return !s || s.state === 'Z'
}
async function waitFor(check, ms) {
  for (const end = Date.now() + ms; Date.now() < end; await sleep(20)) if (check()) return true
  return check()
}
function killAll(pids) {
  for (const pid of pids) {
    try {
      process.kill(pid, 'SIGKILL')
    } catch {}
  }
}
function firstLine(stream) {
  return new Promise((resolve, reject) => {
    let buf = ''
    stream.setEncoding('utf8')
    stream.on('data', (d) => {
      buf += d
      if (buf.includes('\n')) resolve(buf.slice(0, buf.indexOf('\n')))
    })
    stream.on('end', () => reject(new Error(`no line before the end (got ${JSON.stringify(buf)})`)))
  })
}

test('a daemon killed with SIGKILL takes the server it started with it', { skip }, async () => {
  const pids = []
  try {
    // The stand-in daemon starts the server directly, as a daemon without libtether_exec.so does, and prints its pid
    // once the server says it is listening.
    const daemon = spawn(process.execPath, ['-e', `
      const c = require('child_process').spawn(process.argv[1], ['serve', '--host', '127.0.0.1', '--port', '0'], { stdio: ['ignore', 'ignore', 'pipe'] })
      let err = ''
      c.stderr.on('data', (d) => { err += d; if (err.includes('Starting OpenAI-compatible API server')) console.log(c.pid) })
      c.on('exit', (code) => { console.error('server exited ' + code + ': ' + err); process.exit(1) })
      setInterval(() => {}, 1000)`, server], { stdio: ['ignore', 'pipe', 'inherit'] })
    pids.push(daemon.pid)
    const pid = Number(await firstLine(daemon.stdout))
    pids.push(pid)
    assert.equal(stat(pid)?.ppid, daemon.pid)
    process.kill(daemon.pid, 'SIGKILL')
    assert.ok(await waitFor(() => gone(pid), 1000), 'the server outlived the daemon')
  } finally {
    killAll(pids)
  }
})

test('a server whose parent exited before it started does not start', { skip }, async (t) => {
  const pids = []
  try {
    const errFile = join(work, 'orphan.err')
    // The outer sh starts an inner one in the background, prints its pid and exits, so the inner sh is reparented
    // before it execs the server, as a server is whose daemon dies between the fork and the prctl. The inner sh waits
    // on fd 3 until this test has seen who adopted it, so it can neither exec the server nor be gone before then.
    const outer = spawn('sh', ['-c', 'sh -c "$2" "$0" >/dev/null 2>"$1" & echo $!', server, errFile,
      'read -r _ <&3; exec "$0" serve --host 127.0.0.1 --port 0 3<&-'], { stdio: ['ignore', 'pipe', 'ignore', 'pipe'] })
    pids.push(outer.pid)
    const pid = Number(await firstLine(outer.stdout))
    pids.push(pid)
    assert.ok(await waitFor(() => gone(outer.pid), 1000))
    const ppid = stat(pid)?.ppid
    assert.ok(ppid !== undefined, 'the orphaned sh was gone before it was let go')
    if (ppid !== 1) {
      t.skip(`an orphan is adopted by pid ${ppid} here, not init`)
      return
    }
    // PID 1 of a container's PID namespace is its entrypoint, whose child the server is taken for (main.cc
    // ParentExited), so an orphan it adopted starts there by design (the next test). The server is in this process's
    // namespace; one whose /proc cannot be read counts as the root one, as the server takes it.
    let ns = null
    try {
      ns = readlinkSync('/proc/self/ns/pid')
    } catch {}
    if (ns !== null && ns !== 'pid:[4026531836]') {
      t.skip('in a container\'s PID namespace, where an orphan its PID 1 adopted starts by design')
      return
    }
    outer.stdio[3].end('go\n')
    assert.ok(await waitFor(() => gone(pid), 3000), 'the server started')
    assert.match(readFileSync(errFile, 'utf8'), /the process that started turbollm-litertlm-server has already exited; not starting/)
  } finally {
    killAll(pids)
  }
})

// A PID namespace of its own, as a container has: `unshare --pid --fork` makes its command that namespace's PID 1. Root
// can make one, and elsewhere a user namespace (-r) can where the system allows those. --kill-child: unshare's child,
// the namespace's PID 1, and with it everything in the namespace, dies with unshare.
const unshare = skip ? undefined : [[], ['-r']].map((user) => [...user, '--pid', '--fork', '--kill-child', '--mount-proc'])
  .find((flags) => spawnSync('unshare', [...flags, 'true'], { stdio: 'ignore', timeout: 10_000 }).status === 0)
const skipNamespace = skip || (!unshare && 'unshare cannot make a PID namespace here')
const tini = !skip && spawnSync('tini', ['--version'], { stdio: 'ignore', timeout: 10_000 }).status === 0
// The pids whose parent is `pid`, from /proc.
function children(pid) {
  return readdirSync('/proc').filter((d) => /^\d+$/.test(d)).map(Number).filter((p) => stat(p)?.ppid === pid)
}

test('a server whose parent is PID 1 of its own PID namespace, as a container\'s entrypoint is, starts', { skip: skipNamespace }, async () => {
  // A shell that starts the server as its child, as `docker run <image> sh -c '... &'` does, and tini, as
  // `docker run --init` does.
  const inits = [['sh', '-c', 'echo "sh is pid $$" >&2; "$0" serve --host 127.0.0.1 --port 0 & wait', server]]
  if (tini) inits.push(['tini', '--', server, 'serve', '--host', '127.0.0.1', '--port', '0'])
  for (const init of inits) {
    const pids = []
    try {
      const outer = spawn('unshare', [...unshare, ...init], { stdio: ['ignore', 'ignore', 'pipe'] })
      pids.push(outer.pid)
      let err = ''
      outer.stderr.setEncoding('utf8')
      outer.stderr.on('data', (d) => { err += d })
      const started = await waitFor(() => err.includes('Starting OpenAI-compatible API server') || err.includes('not starting'), 10_000)
      assert.ok(started, `${init[0]}: no word from the server in 10 s: ${err}`)
      assert.doesNotMatch(err, /not starting/, init[0])
      if (init[0] === 'sh') assert.match(err, /^sh is pid 1$/m)
      // The server is the child of the namespace's PID 1 (unshare's child), and it ends with it.
      const [first] = children(outer.pid)
      const [serverPid] = first ? children(first) : []
      assert.ok(serverPid, `${init[0]}: the server is not the child of the namespace's PID 1`)
      pids.push(first, serverPid)
      process.kill(outer.pid, 'SIGKILL')
      assert.ok(await waitFor(() => gone(serverPid), 3000), `${init[0]}: the server outlived its namespace's PID 1`)
    } finally {
      killAll(pids)
    }
  }
})

test('--version still answers', { skip }, () => {
  const r = spawnSync(server, ['--version'], { encoding: 'utf8' })
  assert.equal(r.status, 0, r.stderr)
  assert.match(r.stdout, /^turbollm-litertlm-server .* \(LiteRT-LM test\)/)
})
