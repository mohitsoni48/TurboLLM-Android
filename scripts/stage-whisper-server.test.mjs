// Tests for the whisper-server staging script (scripts/stage-whisper-server.mjs):
//   node --test scripts/stage-whisper-server.test.mjs
// The pure tests (the CMake arguments, the stamp, the pin, the command line) need nothing. The file
// checks compile tiny fixtures with the NDK's clang, and the end-to-end build needs the NDK, cmake
// and ninja on PATH and a whisper.cpp checkout named by TURBOLLM_WHISPER_SOURCE; each is skipped when
// what it needs is missing. The NDK is found as in stage-tether-exec.test.mjs (ANDROID_NDK_HOME,
// ANDROID_NDK_ROOT, or the pinned version under ANDROID_HOME or ANDROID_SDK_ROOT).
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { after, test } from 'node:test'
import { fileURLToPath } from 'node:url'

import { ndkToolchain } from './stage-litert-lm-engine.mjs'
import {
  ABIS, buildStamp, checkoutCommit, cmakeArgs, SERVER, WHISPER_COMMIT, WHISPER_TAG,
} from './stage-whisper-server.mjs'

const script = fileURLToPath(new URL('./stage-whisper-server.mjs', import.meta.url))
const scriptModule = new URL('./stage-whisper-server.mjs', import.meta.url).href
const repoRoot = resolve(fileURLToPath(new URL('..', import.meta.url)))
const work = mkdtempSync(join(tmpdir(), 'whisper-server-test-'))
after(() => rmSync(work, { recursive: true, force: true }))

function findNdk() {
  const pinned = (sdk) => sdk && join(sdk, 'ndk', '27.0.12077973')
  const env = process.env
  return [env.ANDROID_NDK_HOME, env.ANDROID_NDK_ROOT, pinned(env.ANDROID_HOME), pinned(env.ANDROID_SDK_ROOT)]
    .find((d) => d && existsSync(join(d, 'toolchains', 'llvm', 'prebuilt')))
}
const ndk = findNdk()
const noNdk = !ndk && 'no NDK found'

const run = (args) => spawnSync(process.execPath, [script, ...args], { encoding: 'utf8' })

// ── Pure ─────────────────────────────────────────────────────────────────────────────────

test('the CMake arguments pin what decides the kind of file that comes out', () => {
  for (const abi of Object.keys(ABIS)) {
    const args = cmakeArgs(abi, { toolchain: '/ndk/android.toolchain.cmake', ninja: '/sdk/ninja' })
    for (const want of [
      `-DANDROID_ABI=${abi}`, '-DANDROID_PLATFORM=android-24', '-DANDROID_STL=c++_static',
      '-DBUILD_SHARED_LIBS=OFF', '-DGGML_OPENMP=OFF', '-DGGML_NATIVE=OFF',
      '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON', '-DWHISPER_BUILD_SERVER=ON', '-DCMAKE_BUILD_TYPE=Release',
      '-DCMAKE_TOOLCHAIN_FILE=/ndk/android.toolchain.cmake', '-DCMAKE_MAKE_PROGRAM=/sdk/ninja',
    ]) assert.ok(args.includes(want), `${abi}: ${want}`)
  }
})

test('the build stamp is stable, and changes with the ABI, the commit, the NDK and the script', () => {
  const base = { commit: 'a'.repeat(40), ndk: '/ndk/27', script: 'hash' }
  const stamp = buildStamp('arm64-v8a', base)
  assert.equal(buildStamp('arm64-v8a', { ...base }), stamp)
  assert.notEqual(buildStamp('x86_64', base), stamp)
  assert.notEqual(buildStamp('arm64-v8a', { ...base, commit: 'b'.repeat(40) }), stamp)
  assert.notEqual(buildStamp('arm64-v8a', { ...base, ndk: '/ndk/28' }), stamp)
  assert.notEqual(buildStamp('arm64-v8a', { ...base, script: 'other' }), stamp)
})

test('the pin is well formed, and the script and Gradle ship the same ABIs', () => {
  assert.match(WHISPER_COMMIT, /^[0-9a-f]{40}$/)
  assert.ok(/^b\d+$/.test(WHISPER_TAG) || /^v\d/.test(WHISPER_TAG), WHISPER_TAG)
  // Android only installs lib*.so files into nativeLibraryDir.
  assert.match(SERVER, /^lib[a-z0-9_]+\.so$/)
  const gradle = readFileSync(join(repoRoot, 'composeApp', 'build.gradle.kts'), 'utf8')
  const line = gradle.match(/^val shippedAbis = listOf\((.*)\)\s*$/m)
  assert.ok(line, 'no shippedAbis line in composeApp/build.gradle.kts')
  const shipped = [...line[1].matchAll(/"([^"]+)"/g)].map((m) => m[1])
  assert.deepEqual(Object.keys(ABIS).sort(), shipped.sort())
})

// Found as build.gradle.kts finds it: -Pturbollm.dir is not known here, so the default sibling
// layout (<parent>/turbollm), then the checkout's usual name (<parent>/TurboLLM/turbollm).
const daemonBinary = [
  process.env.TURBOLLM_DIR && join(process.env.TURBOLLM_DIR, 'src', 'voice', 'whisper-binary.ts'),
  join(repoRoot, '..', 'turbollm', 'src', 'voice', 'whisper-binary.ts'),
  join(repoRoot, '..', 'TurboLLM', 'turbollm', 'src', 'voice', 'whisper-binary.ts'),
].find((f) => f && existsSync(f))

test('the whisper.cpp tag is the one the daemon installs on desktop', { skip: !daemonBinary && 'no TurboLLM checkout found' }, () => {
  const pin = readFileSync(daemonBinary, 'utf8').match(/export const WHISPER_BUILD = '([^']+)'/)
  assert.ok(pin, `no WHISPER_BUILD in ${daemonBinary}`)
  assert.equal(WHISPER_TAG, pin[1])
})

const hasGit = !spawnSync('git', ['--version'], { stdio: 'ignore' }).error

test('checkoutCommit is null outside a git checkout', { skip: !hasGit && 'no git on this machine' }, () => {
  const dir = mkdtempSync(join(work, 'plain-'))
  assert.equal(checkoutCommit(dir), null)
})

test('checkoutCommit returns the HEAD commit of a checkout', { skip: !hasGit && 'no git on this machine' }, () => {
  const dir = mkdtempSync(join(work, 'repo-'))
  const git = (...args) => spawnSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@example.invalid', ...args], { cwd: dir, encoding: 'utf8' })
  assert.equal(git('init', '--quiet').status, 0)
  writeFileSync(join(dir, 'f'), 'x\n')
  assert.equal(git('add', 'f').status, 0)
  assert.equal(git('commit', '--quiet', '-m', 'one').status, 0)
  const head = git('rev-parse', 'HEAD').stdout.trim()
  assert.match(head, /^[0-9a-f]{40}$/)
  assert.equal(checkoutCommit(dir), head)
})

test('bad arguments exit 1 with a FATAL line', () => {
  const fakeNdk = mkdtempSync(join(work, 'fake-ndk-'))
  const out = join(work, 'bad-args-out')
  const cases = [
    [['--out', out], /FATAL: --ndk <dir> is required/],
    [['--ndk', fakeNdk], /FATAL: --out <dir> is required/],
    [['--ndk', fakeNdk, '--out', out, '--bogus', 'x'], /FATAL: unknown argument --bogus/],
    // The ABI is checked before the NDK is looked at, so no real NDK is needed.
    [['--ndk', fakeNdk, '--out', out, '--abis', 'armeabi-v7a'], /FATAL: no whisper-server build for ABI armeabi-v7a/],
  ]
  for (const [args, want] of cases) {
    const r = run(args)
    assert.equal(r.status, 1, args.join(' '))
    assert.match(r.stderr, want)
  }
  assert.equal(existsSync(out), false, 'nothing is staged on a bad command line')
})

// ── checkServer, on fixtures built with the NDK ──────────────────────────────────────────

// checkServer reports a wrong architecture or alignment through checkElf, which prints FATAL and
// exits the process, so it is called in a child process to see what it does.
function check(file, abi) {
  return spawnSync(process.execPath, ['--input-type=module', '-e',
    `import { checkServer } from ${JSON.stringify(scriptModule)}
     try { checkServer(process.argv[1], process.argv[2]); console.log('ok') } catch (e) { console.error('ERROR: ' + e.message); process.exit(2) }`,
    file, abi], { encoding: 'utf8' })
}

function fixture(name, { flags = [], source = 'int main(void) { return 0; }\n' } = {}) {
  const { clang } = ndkToolchain(ndk)
  const src = join(work, `${name}.c`)
  const out = join(work, name)
  writeFileSync(src, source)
  const r = spawnSync(clang, ['--target=aarch64-linux-android24', '-fPIE', '-pie', '-O1', ...flags, '-o', out, src], { encoding: 'utf8' })
  assert.equal(r.status, 0, `clang failed:\n${r.stderr}`)
  return out
}

test('checkServer accepts a 16 KB-aligned PIE executable of the right architecture', { skip: noNdk }, () => {
  const f = fixture('good', { flags: ['-Wl,-z,max-page-size=16384'] })
  const r = check(f, 'arm64-v8a')
  assert.equal(r.status, 0, r.stderr)
  assert.match(r.stdout, /ok/)
})

test('checkServer rejects the wrong architecture', { skip: noNdk }, () => {
  const f = fixture('wrong-arch', { flags: ['-Wl,-z,max-page-size=16384'] })
  const r = check(f, 'x86_64')
  assert.notEqual(r.status, 0)
  assert.match(r.stderr, /ELF machine 0xb7, expected 0x3e/)
})

test('checkServer rejects a file whose LOAD segments are not 16 KB aligned', { skip: noNdk }, () => {
  const f = fixture('aligned-4k', { flags: ['-Wl,-z,max-page-size=4096'] })
  const r = check(f, 'arm64-v8a')
  assert.notEqual(r.status, 0)
  assert.match(r.stderr, /16 KB-page devices need 16384/)
})

test('checkServer rejects a file that needs a library other than libc, libm and libdl', { skip: noNdk }, () => {
  const f = fixture('needs-log', {
    flags: ['-Wl,-z,max-page-size=16384', '-llog'],
    source: '#include <android/log.h>\nint main(void) { return __android_log_write(4, "t", "x"); }\n',
  })
  const r = check(f, 'arm64-v8a')
  assert.equal(r.status, 2, r.stderr)
  assert.match(r.stderr, /needs liblog\.so/)
})

// ── End to end, opt-in: it builds whisper.cpp ────────────────────────────────────────────

const source = process.env.TURBOLLM_WHISPER_SOURCE
const haveTools = ['cmake', 'ninja'].every((t) => !spawnSync(t, ['--version'], { stdio: 'ignore' }).error)
const e2eSkip = !source ? 'TURBOLLM_WHISPER_SOURCE is not set'
  : !existsSync(join(source, 'CMakeLists.txt')) ? 'TURBOLLM_WHISPER_SOURCE is not a whisper.cpp checkout'
    : noNdk || (!haveTools && 'cmake and ninja are not both on PATH')

test('the script builds, checks and stages the server, and a second run finds it up to date', { skip: e2eSkip, timeout: 600000 }, () => {
  const out = join(work, 'e2e', 'jniLibs')
  mkdirSync(out, { recursive: true })
  const args = ['--ndk', ndk, '--source', source, '--abis', 'x86_64', '--out', out]
  const first = run(args)
  assert.equal(first.status, 0, first.stdout + first.stderr)
  const staged = join(out, 'x86_64', SERVER)
  assert.ok(existsSync(staged), `no ${staged}`)
  assert.equal(check(staged, 'x86_64').status, 0)
  const leftovers = readdirSync(join(out, 'x86_64')).filter((n) => n.endsWith('.tmp'))
  assert.deepEqual(leftovers, [])

  const before = statSync(staged).mtimeMs
  const second = run(args)
  assert.equal(second.status, 0, second.stdout + second.stderr)
  assert.match(second.stdout, /is up to date/)
  assert.equal(statSync(staged).mtimeMs, before)
})
