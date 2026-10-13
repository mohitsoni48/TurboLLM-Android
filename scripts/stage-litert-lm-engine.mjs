#!/usr/bin/env node
// Stage the LiteRT-LM engine that ships inside the APK: the runtime library plus the native
// OpenAI-compatible server that drives it (engines/litert-lm-server).
//
// Why it is built like this: on desktop and under Termux, TurboLLM runs LiteRT-LM as
// `python -m litert_lm_cli.main serve`, and that Python server reaches the runtime through ctypes
// into liblitert-lm.so — the one native library in Google's `litert-lm-api` wheel. The app has no
// Python and may only execute files installed with its APK, so it ships that same library unchanged
// and replaces the Python layer with a native one. Nothing here is recompiled from Google's sources:
// the library is the exact file Termux users run, taken from the pinned PyPI wheel and checked
// against the hash below.
//
// Output: <out>/<abi>/{liblitert-lm.so, liblitertlm_server.so}, which build.gradle.kts adds to the
// APK's jniLibs. Both names fit the lib*.so pattern Android requires before it will install a file
// into nativeLibraryDir — the only place an app may execute from (BUILDING-ENGINES.md).
//
// Usage (Gradle runs this; see stageLitertLmEngine in composeApp/build.gradle.kts):
//   node scripts/stage-litert-lm-engine.mjs --ndk <ndk dir> --out <dir> [--abis arm64-v8a,x86_64]
//                                           [--cmake <cmake>] [--ninja <ninja>]
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { inflateRawSync } from 'node:zlib'

// ── Pinned runtime ───────────────────────────────────────────────────────────────────────
// Bumping LiteRT-LM = change the version and both hashes together (PyPI lists them under
// https://pypi.org/pypi/litert-lm-api/<version>/json, "digests.sha256"), then rebuild. The server
// talks to the library through its C API, so check c/engine.h and c/conversation.h for changes
// to the functions listed in engines/litert-lm-server/src/litert_lm_api.h.
export const LITERT_LM_VERSION = '0.18.0'
const WHEELS = {
  'arm64-v8a': {
    file: `litert_lm_api-${LITERT_LM_VERSION}-py3-none-android_23_arm64_v8a.whl`,
    sha256: 'a045af401dc199c35b1f44c29e8899cecbf2712a8194c9e35489ab5e2a95d47d',
    elfMachine: 0xb7, // EM_AARCH64
  },
  x86_64: {
    file: `litert_lm_api-${LITERT_LM_VERSION}-py3-none-android_23_x86_64.whl`,
    sha256: 'db5268c5eb8e60f5a7cdecea310f8084426ae174fdbe8efe2991c47f54d74889',
    elfMachine: 0x3e, // EM_X86_64
  },
}
const LIB_IN_WHEEL = 'litert_lm/liblitert-lm.so'

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const serverSrc = join(appRoot, 'engines', 'litert-lm-server')

function parseArgs(argv) {
  const out = { abis: Object.keys(WHEELS) }
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i]
    const v = argv[i + 1]
    if (k === '--ndk') out.ndk = v
    else if (k === '--out') out.out = v
    else if (k === '--abis') out.abis = v.split(',').map((s) => s.trim()).filter(Boolean)
    else if (k === '--cmake') out.cmake = v
    else if (k === '--ninja') out.ninja = v
    else if (k === '--cache') out.cache = v
    else throw new Error(`unknown argument ${k}`)
    i++
  }
  if (!out.ndk) throw new Error('--ndk <dir> is required')
  if (!out.out) throw new Error('--out <dir> is required')
  out.cache ??= join(out.out, '..', 'cache')
  return out
}

function fail(msg) {
  console.error(`FATAL: ${msg}`)
  process.exit(1)
}

function sha256(buf) {
  return createHash('sha256').update(buf).digest('hex')
}

// ── Wheel download (cached, hash-pinned) ───────────────────────────────────────────────────
async function fetchWheel(abi, cacheDir) {
  const w = WHEELS[abi]
  const dest = join(cacheDir, w.file)
  if (existsSync(dest) && sha256(readFileSync(dest)) === w.sha256) return dest

  // Resolve the download URL from PyPI's JSON API rather than hardcoding a CDN path; the hash
  // pinned above is what makes the result trustworthy, not the URL.
  const metaRes = await fetch(`https://pypi.org/pypi/litert-lm-api/${LITERT_LM_VERSION}/json`)
  if (!metaRes.ok) fail(`PyPI metadata for litert-lm-api ${LITERT_LM_VERSION}: HTTP ${metaRes.status}`)
  const meta = await metaRes.json()
  const entry = (meta.urls ?? []).find((u) => u.filename === w.file)
  if (!entry) fail(`PyPI has no ${w.file}`)
  console.log(`downloading ${w.file} (${(entry.size / 1e6).toFixed(1)} MB)…`)
  const res = await fetch(entry.url)
  if (!res.ok) fail(`download of ${w.file} failed: HTTP ${res.status}`)
  const buf = Buffer.from(await res.arrayBuffer())
  const got = sha256(buf)
  if (got !== w.sha256) fail(`${w.file}: sha256 ${got} does not match the pinned ${w.sha256}`)
  mkdirSync(cacheDir, { recursive: true })
  writeFileSync(`${dest}.part`, buf)
  renameSync(`${dest}.part`, dest)
  return dest
}

// ── Minimal zip reader: a wheel is a zip, and only one member is needed ────────────────────
function extractFromZip(zipPath, member) {
  const zip = readFileSync(zipPath)
  // End-of-central-directory record: last 22+ bytes, signature 0x06054b50.
  let eocd = -1
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 65557); i--) {
    if (zip.readUInt32LE(i) === 0x06054b50) {
      eocd = i
      break
    }
  }
  if (eocd < 0) fail(`${zipPath} is not a zip file`)
  const entries = zip.readUInt16LE(eocd + 10)
  let p = zip.readUInt32LE(eocd + 16)
  for (let n = 0; n < entries; n++) {
    if (zip.readUInt32LE(p) !== 0x02014b50) fail(`${zipPath}: corrupt central directory`)
    const method = zip.readUInt16LE(p + 10)
    const compSize = zip.readUInt32LE(p + 20)
    const size = zip.readUInt32LE(p + 24)
    const nameLen = zip.readUInt16LE(p + 28)
    const extraLen = zip.readUInt16LE(p + 30)
    const commentLen = zip.readUInt16LE(p + 32)
    const localOff = zip.readUInt32LE(p + 42)
    const name = zip.toString('utf8', p + 46, p + 46 + nameLen)
    if (name === member) {
      const lNameLen = zip.readUInt16LE(localOff + 26)
      const lExtraLen = zip.readUInt16LE(localOff + 28)
      const start = localOff + 30 + lNameLen + lExtraLen
      const raw = zip.subarray(start, start + compSize)
      const data = method === 0 ? raw : method === 8 ? inflateRawSync(raw) : fail(`${member}: unsupported zip method ${method}`)
      if (data.length !== size) fail(`${member}: extracted ${data.length} bytes, expected ${size}`)
      return data
    }
    p += 46 + nameLen + extraLen + commentLen
  }
  fail(`${zipPath} has no ${member}`)
}

// ── ELF checks: right architecture, and 16 KB-aligned LOAD segments ────────────────────────
// A 4 KB-aligned file fails to load on a 16 KB-page device, and Play rejects updates that
// carry one (targetSdk 35+). Checked here so a bad file stops the build, not a user's launch.
function checkElf(path, machine) {
  const b = readFileSync(path)
  if (b.readUInt32BE(0) !== 0x7f454c46) fail(`${path}: not an ELF file`)
  if (b[4] !== 2) fail(`${path}: not a 64-bit ELF`)
  const eMachine = b.readUInt16LE(18)
  if (eMachine !== machine) fail(`${path}: ELF machine 0x${eMachine.toString(16)}, expected 0x${machine.toString(16)}`)
  const phoff = Number(b.readBigUInt64LE(32))
  const phentsize = b.readUInt16LE(54)
  const phnum = b.readUInt16LE(56)
  for (let i = 0; i < phnum; i++) {
    const o = phoff + i * phentsize
    if (b.readUInt32LE(o) !== 1) continue // PT_LOAD
    const align = Number(b.readBigUInt64LE(o + 48))
    if (align < 16384) fail(`${path}: LOAD segment aligned to ${align} bytes; 16 KB-page devices need 16384`)
  }
}

function findTool(explicit, name) {
  if (explicit && existsSync(explicit)) return explicit
  return process.platform === 'win32' ? `${name}.exe` : name // fall back to PATH
}

function buildServer(abi, opts) {
  const toolchain = join(opts.ndk, 'build', 'cmake', 'android.toolchain.cmake')
  if (!existsSync(toolchain)) fail(`no NDK toolchain at ${toolchain}`)
  const cmake = findTool(opts.cmake, 'cmake')
  const ninja = findTool(opts.ninja, 'ninja')
  const buildDir = join(opts.out, '..', `cmake-${abi}`)
  const configure = [
    '-S', serverSrc, '-B', buildDir, '-G', 'Ninja',
    `-DCMAKE_MAKE_PROGRAM=${ninja}`,
    `-DCMAKE_TOOLCHAIN_FILE=${toolchain}`,
    `-DANDROID_ABI=${abi}`,
    // Same floor as the app (minSdk 24); the library itself is built for API 23.
    '-DANDROID_PLATFORM=android-24',
    // Static and unexported: see engines/litert-lm-server/src/litert_lm_api.h.
    '-DANDROID_STL=c++_static',
    '-DCMAKE_BUILD_TYPE=Release',
    `-DLITERT_LM_VERSION=${LITERT_LM_VERSION}`,
  ]
  console.log(`building liblitertlm_server.so for ${abi}…`)
  execFileSync(cmake, configure, { stdio: 'inherit' })
  execFileSync(cmake, ['--build', buildDir], { stdio: 'inherit' })
  const built = join(buildDir, 'liblitertlm_server.so')
  if (!existsSync(built)) fail(`build produced no ${built}`)
  return built
}

async function main() {
  const opts = parseArgs(process.argv.slice(2))
  for (const abi of opts.abis) {
    const w = WHEELS[abi]
    if (!w) fail(`no LiteRT-LM build for ABI ${abi} (have: ${Object.keys(WHEELS).join(', ')})`)
    const outDir = join(opts.out, abi)
    mkdirSync(outDir, { recursive: true })

    const wheel = await fetchWheel(abi, opts.cache)
    const lib = join(outDir, 'liblitert-lm.so')
    writeFileSync(lib, extractFromZip(wheel, LIB_IN_WHEEL))
    checkElf(lib, w.elfMachine)

    const server = join(outDir, 'liblitertlm_server.so')
    copyFileSync(buildServer(abi, opts), server)
    checkElf(server, w.elfMachine)

    console.log(`staged LiteRT-LM ${LITERT_LM_VERSION} for ${abi}: ` +
      `liblitert-lm.so ${(statSync(lib).size / 1e6).toFixed(1)} MB, ` +
      `liblitertlm_server.so ${(statSync(server).size / 1e3).toFixed(0)} KB`)
  }
}

main().catch((e) => fail(e instanceof Error ? e.message : String(e)))
