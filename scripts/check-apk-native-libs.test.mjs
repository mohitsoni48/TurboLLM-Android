// Tests for scripts/check-apk-native-libs.mjs, the check that an APK carries the native libraries its engine flags
// promise (-PturbollmSpawnedEngines, -PturbollmLitertLmEngine):
//   node --test scripts/check-apk-native-libs.test.mjs
// The APKs here are small stored zips built in memory; the CLI tests write one to a temp folder that is removed after.
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, test } from 'node:test'
import { fileURLToPath } from 'node:url'

import { ABIS, PROFILES, nativeLibProblems, zipEntryNames } from './check-apk-native-libs.mjs'

const script = fileURLToPath(new URL('./check-apk-native-libs.mjs', import.meta.url))
const work = mkdtempSync(join(tmpdir(), 'apk-native-libs-test-'))
after(() => rmSync(work, { recursive: true, force: true }))

const SPAWNED_CPU_ENGINE = [
  'libllama_server.so', 'libllama-server-impl.so', 'libllama.so', 'libllama-common.so',
  'libggml.so', 'libggml-base.so', 'libggml-cpu.so', 'libmtmd.so', 'libomp.so',
]
const SDK_ENGINES = ['libturbollm_llama_base.so', 'liblitertlm_jni.so']
const APP_LIBS = ['libnode.so', 'libnative-lib.so', 'libc++_shared.so']

function apkListing(libs, abis = ABIS) {
  return ['AndroidManifest.xml', 'classes.dex', ...abis.flatMap((abi) => libs.map((lib) => `lib/${abi}/${lib}`))]
}

const DEFAULT_LIBS = [...APP_LIBS, ...SDK_ENGINES, ...SPAWNED_CPU_ENGINE, 'libtether_exec.so']
const DEFAULT_APK = apkListing(DEFAULT_LIBS)

function storedZip(names, comment = '') {
  const locals = []
  const centrals = []
  let offset = 0
  for (const name of names) {
    const n = Buffer.from(name)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(n.length, 26)
    const central = Buffer.alloc(46)
    central.writeUInt32LE(0x02014b50, 0)
    central.writeUInt16LE(20, 4)
    central.writeUInt16LE(20, 6)
    central.writeUInt16LE(n.length, 28)
    central.writeUInt32LE(offset, 42)
    locals.push(local, n)
    centrals.push(central, n)
    offset += local.length + n.length
  }
  const directory = Buffer.concat(centrals)
  const c = Buffer.from(comment)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(names.length, 8)
  end.writeUInt16LE(names.length, 10)
  end.writeUInt32LE(directory.length, 12)
  end.writeUInt32LE(offset, 16)
  end.writeUInt16LE(c.length, 20)
  return Buffer.concat([...locals, directory, end, c])
}

function runCheck(names, profile) {
  const apk = join(work, `${profile}.apk`)
  writeFileSync(apk, storedZip(names))
  return spawnSync(process.execPath, [script, apk, profile], { encoding: 'utf8', timeout: 30_000 })
}

test('zipEntryNames lists every entry of the central directory, in order', () => {
  const names = ['AndroidManifest.xml', 'lib/arm64-v8a/libnode.so', 'lib/x86_64/libnode.so']
  assert.deepEqual(zipEntryNames(storedZip(names)), names)
})

test('zipEntryNames finds the end record behind an archive comment', () => {
  assert.deepEqual(zipEntryNames(storedZip(['a.txt'], 'signed by nobody')), ['a.txt'])
})

test('zipEntryNames refuses a file that is not a zip', () => {
  assert.throws(() => zipEntryNames(Buffer.from('not an apk at all, just text')), /not a zip/)
})

test('the default profile accepts the SDK beside the CPU llama-server fallback and its tether', () => {
  assert.deepEqual(nativeLibProblems(DEFAULT_APK, 'default'), [])
})

test('the default profile refuses the LiteRT-LM server, its runtime and its shim', () => {
  const apk = [...DEFAULT_APK, 'lib/arm64-v8a/liblitertlm_server.so', 'lib/x86_64/liblitert-lm.so',
    'lib/x86_64/liblrtandr.so']
  assert.deepEqual(nativeLibProblems(apk, 'default'), [
    'arm64-v8a: liblitertlm_server.so must not be packaged',
    'x86_64: liblitert-lm.so must not be packaged',
    'x86_64: liblrtandr.so must not be packaged',
  ])
})

test('the default profile refuses the Vulkan engine, which stays opt-in', () => {
  const apk = [...DEFAULT_APK, 'lib/arm64-v8a/libllama_server_vk.so']
  assert.deepEqual(nativeLibProblems(apk, 'default'), ['arm64-v8a: libllama_server_vk.so must not be packaged'])
})

test('the default profile needs every CPU-engine sidecar, so the fallback can start', () => {
  const apk = DEFAULT_APK.filter((entry) => entry !== 'lib/x86_64/libggml-cpu.so')
  assert.deepEqual(nativeLibProblems(apk, 'default'), ['x86_64: libggml-cpu.so is missing'])
})

test('every shipped ABI is checked, not only arm64', () => {
  const apk = DEFAULT_APK.filter((entry) => entry !== 'lib/x86_64/libturbollm_llama_base.so')
  assert.deepEqual(nativeLibProblems(apk, 'default'), ['x86_64: libturbollm_llama_base.so is missing'])
})

test('without spawned engines the SDK alone is packaged', () => {
  const apk = apkListing([...APP_LIBS, ...SDK_ENGINES])
  assert.deepEqual(nativeLibProblems(apk, 'no-spawned-engines'), [])
})

test('without spawned engines no CPU-engine file and no tether may remain', () => {
  const problems = nativeLibProblems(DEFAULT_APK, 'no-spawned-engines')
  for (const lib of [...SPAWNED_CPU_ENGINE, 'libtether_exec.so']) {
    for (const abi of ABIS) assert.ok(problems.includes(`${abi}: ${lib} must not be packaged`), `${abi} ${lib}`)
  }
  assert.equal(problems.length, ABIS.length * (SPAWNED_CPU_ENGINE.length + 1))
})

test('the exclusions never reach the SDK engine variants', () => {
  const variants = ['libturbollm_llama_dotprod.so', 'libturbollm_llama_i8mm.so']
  const apk = apkListing([...APP_LIBS, ...SDK_ENGINES, ...variants])
  assert.deepEqual(nativeLibProblems(apk, 'no-spawned-engines'), [])
})

test('the LiteRT-LM opt-in packages the server beside the SDK runtime', () => {
  const litert = ['liblitertlm_server.so', 'liblitert-lm.so', 'liblrtandr.so']
  assert.deepEqual(nativeLibProblems(apkListing([...DEFAULT_LIBS, ...litert]), 'litert-lm-engine'), [])
  assert.deepEqual(nativeLibProblems(DEFAULT_APK, 'litert-lm-engine'), ABIS.flatMap((abi) =>
    litert.map((lib) => `${abi}: ${lib} is missing`)))
})

test('an unknown profile is refused by name, with the known ones', () => {
  assert.throws(() => nativeLibProblems(DEFAULT_APK, 'vulkan'), /unknown profile "vulkan".*default/)
  assert.deepEqual(Object.keys(PROFILES), ['default', 'no-spawned-engines', 'litert-lm-engine'])
})

test('the CLI exits 0 and prints the native libraries of an APK that matches', () => {
  const run = runCheck(DEFAULT_APK, 'default')
  assert.equal(run.status, 0, run.stderr)
  assert.match(run.stdout, /lib\/x86_64\/libturbollm_llama_base\.so/)
  assert.match(run.stdout, /PASS default/)
})

test('the CLI exits 1 and names each problem of an APK that does not match', () => {
  const run = runCheck(DEFAULT_APK, 'no-spawned-engines')
  assert.equal(run.status, 1)
  assert.match(run.stderr, /arm64-v8a: libllama_server\.so must not be packaged/)
  assert.match(run.stderr, /FAIL no-spawned-engines/)
})
