// Tests for the engine tether, libtether_exec.so (engines/tether-exec/tether_exec.c), and its
// staging script:
//   node --test scripts/stage-tether-exec.test.mjs
// The helper is built for this machine with `cc` and run on it, which needs Linux (prctl and
// /proc); without either, those tests are skipped. The staging test builds both shipped ABIs with
// the NDK when one is found (ANDROID_NDK_HOME, ANDROID_NDK_ROOT, or the pinned version under
// ANDROID_HOME or ANDROID_SDK_ROOT), and is skipped otherwise. Every process a test starts is
// killed by pid when it ends.
import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'

import { checkElf } from './stage-litert-lm-engine.mjs'
import { ABIS, CFLAGS, HELPER, SOURCE } from './stage-tether-exec.mjs'

const script = fileURLToPath(new URL('./stage-tether-exec.mjs', import.meta.url))
const work = mkdtempSync(join(tmpdir(), 'tether-exec-test-'))
after(() => rmSync(work, { recursive: true, force: true }))

const cc = spawnSync('cc', ['--version'], { stdio: 'ignore' })
const hostSkip = process.platform !== 'linux' ? 'PR_SET_PDEATHSIG and /proc are Linux only'
  : cc.error ? 'no cc on this machine' : false
const helper = join(work, 'tether_exec')
before(() => {
  if (hostSkip) return
  const built = spawnSync('cc', [...CFLAGS, '-o', helper, SOURCE], { encoding: 'utf8' })
  assert.equal(built.status, 0, `cc failed:\n${built.stderr}`)
})

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// /proc/<pid>/stat after the command name: the state and the parent pid. null when the process is gone.
function stat(pid) {
  try {
    const s = readFileSync(`/proc/${pid}/stat`, 'utf8')
    const f = s.slice(s.lastIndexOf(')') + 2).split(' ')
    return { state: f[0], ppid: Number(f[1]) }
  } catch {
    return null
  }
}
function cmdline(pid) {
  try {
    return readFileSync(`/proc/${pid}/cmdline`, 'utf8').split('\0').filter(Boolean)
  } catch {
    return null
  }
}
// A zombie counts as gone: it runs nothing, and who reaps it (PID 1 or a subreaper) is not the helper's business.
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
// 'exit' can come before the child's last output has been read from its pipes; 'close' comes once its stdio has ended
// too, so a test that checks what the child wrote waits for that. The self-test waits for 'exit': there the program
// the sh started in the background holds the sh's stdout, so 'close' would wait for the program.
function exited(child) {
  return new Promise((r) => child.once('exit', (code, signal) => r({ code, signal })))
}
function closed(child) {
  return new Promise((r) => child.once('close', (code, signal) => r({ code, signal })))
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

test('a daemon killed with SIGKILL takes the engine it started through the helper with it', { skip: hostSkip }, async () => {
  const pids = []
  try {
    // The stand-in daemon starts `sleep 300` through the helper, as the app's daemon starts an engine, and prints its pid.
    const daemon = spawn(process.execPath, ['-e', `
      const c = require('child_process').spawn(process.argv[1], [String(process.pid), 'sleep', '300'], { stdio: 'ignore' })
      console.log(c.pid); setInterval(() => {}, 1000)`, helper], { stdio: ['ignore', 'pipe', 'inherit'] })
    pids.push(daemon.pid)
    const engine = Number(await firstLine(daemon.stdout))
    pids.push(engine)
    assert.ok(await waitFor(() => cmdline(engine)?.join(' ') === 'sleep 300', 1500), 'the helper did not exec the engine in place')
    assert.equal(stat(engine).ppid, daemon.pid)
    process.kill(daemon.pid, 'SIGKILL')
    assert.ok(await waitFor(() => gone(engine), 1000), 'the engine outlived the daemon')
  } finally {
    killAll(pids)
  }
})

test('a parent that is not the daemon pid it was given exits 127 without running the program', { skip: hostSkip }, async () => {
  const pids = []
  try {
    const marker = join(work, 'ran')
    // A live process that is not the helper's parent (this process is), as a helper whose daemon died before the prctl
    // sees: its parent is then whoever adopted it.
    const other = spawn('sleep', ['30'], { stdio: 'ignore' })
    pids.push(other.pid)
    const child = spawn(helper, [String(other.pid), 'sh', '-c', `echo ran > '${marker}'`], { stdio: ['ignore', 'ignore', 'pipe'] })
    let err = ''
    child.stderr.on('data', (d) => (err += d))
    assert.deepEqual(await closed(child), { code: 127, signal: null })
    assert.equal(existsSync(marker), false, 'the program ran')
    assert.match(err, new RegExp(`the parent is ${process.pid}, not the daemon ${other.pid}`))
  } finally {
    killAll(pids)
  }
})

test('a daemon pid that is not a number above 1 exits 127 without running the program', { skip: hostSkip }, async () => {
  const marker = join(work, 'ran-bad-pid')
  for (const pid of ['1', '0', '-5', 'abc', '', `${process.pid}x`, '99999999999']) {
    const r = spawnSync(helper, [pid, 'sh', '-c', `echo ran > '${marker}'`], { encoding: 'utf8' })
    assert.equal(r.status, 127, `daemon pid ${JSON.stringify(pid)}`)
    assert.match(r.stderr, /bad daemon pid/)
  }
  assert.equal(existsSync(marker), false, 'the program ran')
  assert.equal(spawnSync(helper, [String(process.pid)]).status, 127, 'no program')
})

test('the program keeps the helper pid, stdin, stdout and exit status', { skip: hostSkip }, async () => {
  const child = spawn(helper, [String(process.pid), 'sh', '-c', 'echo "$$"; printf "a\\000b\\n"; cat; exit 7'], { stdio: 'pipe' })
  const out = []
  child.stdout.on('data', (d) => out.push(d))
  child.stdin.end('from stdin')
  assert.deepEqual(await closed(child), { code: 7, signal: null })
  assert.deepEqual(Buffer.concat(out), Buffer.from(`${child.pid}\na\0b\nfrom stdin`))

  const missing = spawnSync(helper, [String(process.pid), join(work, 'no-such-program')], { encoding: 'utf8' })
  assert.equal(missing.status, 127)
  assert.match(missing.stderr, /cannot run .*no-such-program: No such file or directory/)
})

test('a signal to the helper pid reaches the program, and its status passes through', { skip: hostSkip }, async () => {
  const pids = []
  try {
    const child = spawn(helper, [String(process.pid), 'sleep', '300'], { stdio: 'ignore' })
    pids.push(child.pid)
    assert.ok(await waitFor(() => cmdline(child.pid)?.join(' ') === 'sleep 300', 1500))
    const status = exited(child)
    child.kill('SIGTERM')
    assert.deepEqual(await status, { code: null, signal: 'SIGTERM' })
  } finally {
    killAll(pids)
  }
})

// The daemon's boot self-test (TurboLLM's util/tether.ts; BUILDING-ENGINES.md, "The engine tether"), here with this
// machine's sh and sleep: the program must be seen running under the helper, exec'd in place, while the helper's
// parent sh lives, and then be gone within 1 s of that sh exiting.
async function selfTest(sh, helperPath) {
  const pids = []
  try {
    const parent = spawn(sh, ['-c', '"$0" "$$" sleep 30 & echo "$!"; read -r _', helperPath], { stdio: ['pipe', 'pipe', 'ignore'] })
    pids.push(parent.pid)
    const program = Number(await firstLine(parent.stdout))
    pids.push(program)
    const ran = await waitFor(() => {
      const s = stat(program)
      return s && s.state !== 'Z' && s.ppid === parent.pid && cmdline(program)?.join(' ') === 'sleep 30'
    }, 1500)
    if (!ran) return 'never ran'
    const done = exited(parent)
    parent.stdin.end()
    await done
    return (await waitFor(() => gone(program), 1000)) ? 'passed' : 'outlived its parent'
  } finally {
    killAll(pids)
  }
}

test('the helper passes the self-test the daemon runs at boot, and helpers that do not work fail it', { skip: hostSkip }, async () => {
  const shells = ['/bin/sh', ...['bash', 'mksh'].filter((s) => !spawnSync(s, ['-c', 'exit 0']).error)]
  // Stand-ins for a broken build: one that execs without arming the signal, and one that never runs the program.
  const noSignal = join(work, 'no-signal.sh')
  writeFileSync(noSignal, '#!/bin/sh\nshift\nexec "$@"\n')
  chmodSync(noSignal, 0o755)
  const exits127 = join(work, 'exits-127.sh')
  writeFileSync(exits127, '#!/bin/sh\nexit 127\n')
  chmodSync(exits127, 0o755)
  for (const sh of shells) {
    assert.equal(await selfTest(sh, helper), 'passed', sh)
    assert.equal(await selfTest(sh, noSignal), 'outlived its parent', `${sh}, a helper without the signal`)
    assert.equal(await selfTest(sh, exits127), 'never ran', `${sh}, a helper that exits 127`)
  }
})

function findNdk() {
  const pinned = (sdk) => sdk && join(sdk, 'ndk', '27.0.12077973')
  const env = process.env
  return [env.ANDROID_NDK_HOME, env.ANDROID_NDK_ROOT, pinned(env.ANDROID_HOME), pinned(env.ANDROID_SDK_ROOT)]
    .find((d) => d && existsSync(join(d, 'toolchains', 'llvm', 'prebuilt')))
}
const ndk = findNdk()

test('the staging script builds a 16 KB-aligned PIE executable for each shipped ABI', { skip: !ndk && 'no NDK found' }, () => {
  const out = join(work, 'jniLibs')
  const r = spawnSync(process.execPath, [script, '--ndk', ndk, '--out', out], { encoding: 'utf8' })
  assert.equal(r.status, 0, r.stderr)
  for (const [abi, { elfMachine }] of Object.entries(ABIS)) {
    const f = join(out, abi, HELPER)
    checkElf(f, elfMachine) // exits the process, failing this file, if the architecture or the alignment is wrong
    const b = readFileSync(f)
    assert.equal(b.readUInt16LE(16), 3, `${abi}: ET_DYN, a PIE`)
    assert.equal(b.readUInt16LE(18), elfMachine, abi)
    const phoff = Number(b.readBigUInt64LE(32))
    const interp = [...Array(b.readUInt16LE(56)).keys()].map((i) => phoff + i * b.readUInt16LE(54))
      .find((o) => b.readUInt32LE(o) === 3) // PT_INTERP
    assert.ok(interp !== undefined, `${abi}: no PT_INTERP, so not an executable`)
    const off = Number(b.readBigUInt64LE(interp + 8))
    assert.equal(b.toString('latin1', off, b.indexOf(0, off)), '/system/bin/linker64', abi)
    assert.equal(existsSync(`${f}.tmp`), false)
  }

  const bad = spawnSync(process.execPath, [script, '--ndk', ndk, '--out', out, '--abis', 'armeabi-v7a'], { encoding: 'utf8' })
  assert.equal(bad.status, 1)
  assert.match(bad.stderr, /no tether build for ABI armeabi-v7a/)
})
