#!/usr/bin/env node
// Stage the LiteRT-LM engine that ships inside the APK: the runtime library plus the native
// OpenAI-compatible server that drives it (engines/litert-lm-server).
//
// Why it is built like this: on desktop and under Termux, TurboLLM runs LiteRT-LM as
// `python -m litert_lm_cli.main serve`, and that Python server reaches the runtime through ctypes
// into liblitert-lm.so — the one native library in Google's `litert-lm-api` wheel. The app has no
// Python and may only execute files installed with its APK, so it ships that same library and
// replaces the Python layer with a native one. Nothing here is recompiled from Google's sources:
// the library is the file Termux users run, taken from the pinned PyPI wheel and checked against
// the hash below, with one change. Its dependency on libandroid.so, which the engine process
// cannot load, is renamed to a shim generated from the library itself (see "The engine process
// cannot load libandroid.so" below), and the patched file is pinned as well.
//
// Output: <out>/<abi>/{liblitert-lm.so, liblitertlm_server.so, <shim>} (the shim is liblrtandr.so
// today), which build.gradle.kts adds to the APK's jniLibs. Every name fits the lib*.so pattern
// Android requires before it will install a file into nativeLibraryDir — the only place an app may
// execute from (BUILDING-ENGINES.md). In <out>/<abi> the script writes those files and removes
// only shims an earlier run staged that this one does not (regular files named liblr*.so that
// shimFileName could have produced); it leaves everything else there alone, so <out> may also hold
// other libraries, such as the llama.cpp engines in composeApp/src/androidMain/jniLibs. It does
// overwrite a file of its own names. The build directories (cmake-<abi>, shim-<abi>) and, unless
// --cache says otherwise, the wheel cache (cache) go in <out>/.. .
//
// Usage (Gradle runs this; see stageLitertLmEngine in composeApp/build.gradle.kts):
//   node scripts/stage-litert-lm-engine.mjs --ndk <ndk dir> --out <dir> [--abis arm64-v8a,x86_64]
//                                           [--cmake <cmake>] [--ninja <ninja>] [--cache <dir>]
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import {
  copyFileSync, existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, realpathSync, renameSync, rmSync, statSync,
  writeFileSync,
} from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { inflateRawSync } from 'node:zlib'

// ── Pinned runtime ───────────────────────────────────────────────────────────────────────
// Bumping LiteRT-LM = change the version and all four hashes together, then rebuild: each wheel's
// (PyPI lists them under https://pypi.org/pypi/litert-lm-api/<version>/json, "digests.sha256")
// and each patched library's (the build stops and prints the new one, after its checks below
// have passed). The server talks to the library through its C API, so check c/engine.h and
// c/conversation.h for changes to the functions listed in engines/litert-lm-server/src/litert_lm_api.h.
export const LITERT_LM_VERSION = '0.18.0'
const WHEELS = {
  'arm64-v8a': {
    file: `litert_lm_api-${LITERT_LM_VERSION}-py3-none-android_23_arm64_v8a.whl`,
    sha256: 'a045af401dc199c35b1f44c29e8899cecbf2712a8194c9e35489ab5e2a95d47d',
    // liblitert-lm.so after the DT_NEEDED rename below; nothing else in it differs from the wheel's.
    patchedSha256: 'ae7e50538d9298e113686bc2ac07ebf33aac1325e6e3cbff299a1024a1b16d6a',
    elfMachine: 0xb7, // EM_AARCH64
    triple: 'aarch64-linux-android',
  },
  x86_64: {
    file: `litert_lm_api-${LITERT_LM_VERSION}-py3-none-android_23_x86_64.whl`,
    sha256: 'db5268c5eb8e60f5a7cdecea310f8084426ae174fdbe8efe2991c47f54d74889',
    patchedSha256: '681f770323d7021ca47a0481ae49744699d80a62c79f759cd1ce443e21becb22',
    elfMachine: 0x3e, // EM_X86_64
    triple: 'x86_64-linux-android',
  },
}
const LIB_IN_WHEEL = 'litert_lm/liblitert-lm.so'
// The app's minSdk. The server and the shims are built for it, and the NDK's stub libraries for
// it say what every supported device provides; the library itself is built for API 23. Also the
// engine tether's (stage-tether-exec.mjs), as are ndkToolchain and checkElf.
export const MIN_SDK = 24

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
export function checkElf(path, machine) {
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

// ── The engine process cannot load libandroid.so ─────────────────────────────────────────
// liblitert-lm.so is built to be loaded by an app into its own process, and it lists
// libandroid.so among its dependencies. The server is not an app process: the daemon executes it
// from nativeLibraryDir, and in a freshly executed process the linker has to find libandroid.so's
// whole dependency tree itself (libandroid_runtime, libhwui, the media stack), which an app,
// forked from the zygote, already has loaded. On the owner's phone that failed, so no .litertlm
// ever loaded:
//   dlopen failed: library "libstagefright.so" not found: needed by /system/lib64/libmediandk.so
//   in namespace (default)
// The library uses libandroid.so only to read APK assets (AAssetManager_*, AAsset_*), which the
// server never asks for: it loads models from file paths. So the build replaces that dependency,
// working from the file it ships rather than from a list written for one LiteRT-LM version:
//   - LOADABLE_LIBS names the libraries a standalone process can load. Every other DT_NEEDED entry
//     is replaced.
//   - Each GLOBAL import that, going by the NDK's stub libraries, only a replaced library provides
//     gets a function in a generated shim that, if it is ever called, names itself on stderr and in
//     logcat and aborts. It never returns a value it would have to make up. WEAK imports, which
//     the library allows to be absent, get none; those a replaced library provides must also come
//     from a loadable one (AHardwareBuffer_*, which libandroid.so also defines from API 26, now
//     bind to libnativewindow.so's, which libEGL.so needs). The build fails, naming the symbols, if
//     a shimmed import is data rather than a function, if a GLOBAL import has no provider at all,
//     if a GLOBAL import comes from both a replaced and a loadable library (it would bind
//     elsewhere), or if a WEAK one comes only from a replaced library (it would be left unresolved).
//     Each of those is checked at every API level from minSdk to the newest the NDK has stubs for,
//     since a device of any of them may run the app.
//   - The DT_NEEDED string is renamed in place to the shim's name, which has the same length
//     (libandroid.so -> liblrtandr.so). Nothing else in the file changes, and the result must
//     match the pinned patchedSha256.
//   - A probe executable is then linked against the staged library, the shims and the NDK stubs
//     with --no-allow-shlib-undefined, so a GLOBAL import that nothing in the library's dependency
//     list provides fails the build with the linker's own message.

// The libraries a standalone executable can load: bionic's own (libc, libm, libdl, which every
// process has), liblog and libz, and the graphics libraries native system daemons link (EGL,
// GLES, Vulkan, nativewindow). On the owner's phone the linker, which loads breadth-first, got
// through every library liblitert-lm.so needs directly and failed levels down in libandroid.so's
// tree. libandroid.so is the framework's app-facing library, and so are the other NDK libraries
// left out here (media, camera, ...). Add one only with a reason.
export const LOADABLE_LIBS = [
  'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libz.so', 'libEGL.so', 'libGLESv1_CM.so',
  'libGLESv2.so', 'libGLESv3.so', 'libnativewindow.so', 'libvulkan.so',
]
// The other names a shim must not take.
const STAGED_FILES = ['liblitert-lm.so', 'liblitertlm_server.so']

const DT_NEEDED = 1
const DT_STRTAB = 5
const DT_SYMTAB = 6
const DT_STRSZ = 10
const DT_VERDEF = 0x6ffffffc
const DT_VERDEFNUM = 0x6ffffffd
const DT_VERNEED = 0x6ffffffe
const DT_VERNEEDNUM = 0x6fffffff
// Every dynamic tag whose value is a .dynstr offset: NEEDED, SONAME, RPATH, RUNPATH, AUXILIARY, FILTER.
const DT_STRING_TAGS = new Set([DT_NEEDED, 14, 15, 29, 0x7ffffffd, 0x7fffffff])
const STB_GLOBAL = 1
const STB_WEAK = 2
const STT_NOTYPE = 0
const STT_FUNC = 2
const SYMBOL_TYPES = { 0: 'untyped symbol', 1: 'data object', 2: 'function', 6: 'TLS variable', 10: 'IFUNC' }

// The dynamic linking view of a 64-bit little-endian ELF file (what both ABIs ship): its DT_NEEDED
// entries, its dynamic symbols, the libraries it needs symbol versions from, and every .dynstr
// offset anything refers to, so a rename can prove it changes one string only. Found through the
// program headers, as the device's linker finds it; the symbol count comes from the .dynsym
// section header, which the dynamic section does not record.
export function readDynamic(b, what) {
  if (b.length < 64 || b.readUInt32BE(0) !== 0x7f454c46 || b[4] !== 2 || b[5] !== 1) {
    throw new Error(`${what}: not a 64-bit little-endian ELF file`)
  }
  const u64 = (o) => Number(b.readBigUInt64LE(o))
  const loads = []
  let dynamic
  for (let i = 0; i < b.readUInt16LE(56); i++) {
    const o = u64(32) + i * b.readUInt16LE(54)
    const seg = { type: b.readUInt32LE(o), off: u64(o + 8), vaddr: u64(o + 16), filesz: u64(o + 32) }
    if (seg.type === 1) loads.push(seg) // PT_LOAD
    else if (seg.type === 2) dynamic = seg // PT_DYNAMIC
  }
  if (!dynamic) throw new Error(`${what}: no dynamic section`)
  const fileOffset = (vaddr) => {
    const seg = loads.find((l) => vaddr >= l.vaddr && vaddr < l.vaddr + l.filesz)
    if (!seg) throw new Error(`${what}: address 0x${vaddr.toString(16)} is in no loaded segment`)
    return seg.off + vaddr - seg.vaddr
  }
  const entries = []
  for (let o = dynamic.off; o + 16 <= dynamic.off + dynamic.filesz && u64(o) !== 0; o += 16) {
    entries.push({ tag: u64(o), val: u64(o + 8) })
  }
  const value = (tag) => entries.find((e) => e.tag === tag)?.val
  if ([DT_STRTAB, DT_STRSZ, DT_SYMTAB].some((t) => value(t) === undefined)) {
    throw new Error(`${what}: dynamic section without DT_STRTAB, DT_STRSZ or DT_SYMTAB`)
  }
  const strtab = fileOffset(value(DT_STRTAB))
  const strsz = value(DT_STRSZ)
  const refs = []
  const str = (off, by) => {
    if (off >= strsz) throw new Error(`${what}: ${by} points outside .dynstr`)
    const ref = { off, by }
    refs.push(ref)
    return { name: b.toString('utf8', strtab + off, b.indexOf(0, strtab + off)), ref }
  }

  const needed = []
  for (const e of entries) {
    if (!DT_STRING_TAGS.has(e.tag)) continue
    const s = str(e.val, `dynamic tag 0x${e.tag.toString(16)}`)
    if (e.tag === DT_NEEDED) needed.push({ name: s.name, off: e.val, ref: s.ref })
  }

  const symtab = fileOffset(value(DT_SYMTAB))
  let count
  for (let i = 0; i < b.readUInt16LE(60); i++) {
    const o = u64(40) + i * b.readUInt16LE(58)
    if (b.readUInt32LE(o + 4) === 11 && u64(o + 24) === symtab) count = u64(o + 32) / 24 // SHT_DYNSYM
  }
  if (count === undefined) throw new Error(`${what}: no .dynsym section header for DT_SYMTAB`)
  const symbols = []
  for (let i = 1; i < count; i++) { // 0 is the null symbol
    const o = symtab + i * 24
    symbols.push({
      name: str(b.readUInt32LE(o), `symbol ${i}`).name,
      bind: b[o + 4] >> 4,
      type: b[o + 4] & 0xf,
      defined: b.readUInt16LE(o + 6) !== 0,
    })
  }

  // Elf64_Verneed {vn_version, vn_cnt, vn_file, vn_aux, vn_next} and its Elf64_Vernaux entries
  // {vna_hash, vna_flags, vna_other, vna_name, vna_next}; Elf64_Verdef {vd_version, vd_flags,
  // vd_ndx, vd_cnt, vd_hash, vd_aux, vd_next} and its Elf64_Verdaux {vda_name, vda_next}.
  const versionedFrom = new Set()
  const verneeds = value(DT_VERNEEDNUM) ?? 0
  for (let i = 0, o = verneeds && fileOffset(value(DT_VERNEED)); i < verneeds; i++, o += b.readUInt32LE(o + 12)) {
    versionedFrom.add(str(b.readUInt32LE(o + 4), 'a version need').name)
    for (let j = 0, a = o + b.readUInt32LE(o + 8); j < b.readUInt16LE(o + 2); j++, a += b.readUInt32LE(a + 12)) {
      str(b.readUInt32LE(a + 8), 'a version need entry')
    }
  }
  const verdefs = value(DT_VERDEFNUM) ?? 0
  for (let i = 0, o = verdefs && fileOffset(value(DT_VERDEF)); i < verdefs; i++, o += b.readUInt32LE(o + 16)) {
    for (let j = 0, a = o + b.readUInt32LE(o + 12); j < b.readUInt16LE(o + 6); j++, a += b.readUInt32LE(a + 4)) {
      str(b.readUInt32LE(a), 'a version definition')
    }
  }
  return { what, strtab, needed, symbols, refs, versionedFrom }
}

// The NDK's stub libraries for one ABI, in dir (sysroot/usr/lib/<triple>): apis, every API level
// it has stubs for from minApi up, in order, and exportsOf(lib, api), the symbols the stub of lib
// at that level exports (name -> st_type), or undefined when there is no such stub.
export function ndkStubs(dir, minApi) {
  const apis = readdirSync(dir).filter((d) => /^\d+$/.test(d)).map(Number).filter((a) => a >= minApi).sort((a, b) => a - b)
  if (apis[0] !== minApi) throw new Error(`${dir}: no stub libraries for API ${minApi}`)
  const exported = new Map()
  const exportsOf = (name, api) => {
    const f = join(dir, String(api), name)
    if (!exported.has(f)) {
      const syms = existsSync(f) ? readDynamic(readFileSync(f), f).symbols : undefined
      exported.set(f, syms && new Map(syms.filter((s) => s.defined && (s.bind === STB_GLOBAL || s.bind === STB_WEAK)).map((s) => [s.name, s.type])))
    }
    return exported.get(f)
  }
  return { apis, exportsOf }
}

// Which of the library's DT_NEEDED entries are replaced, and the functions each shim must define.
// exportsOf(lib, api) gives the NDK stub's exported symbols (name -> st_type), or undefined when
// the NDK has no such stub at that API level; apis lists the levels to check, minSdk first, up to
// the newest the NDK has. Every check runs at each of them, because a device of any of them may
// run the app, and a symbol can move between libraries at any level in between: a WEAK import
// libandroid.so gains at API N and a loadable library only at a later M goes unresolved on
// N..M-1, which comparing only the first and the last level would miss.
export function planShims(lib, exportsOf, apis) {
  const [minApi] = apis
  const replaced = lib.needed.map((n) => n.name).filter((n) => !LOADABLE_LIBS.includes(n))
  const problems = new Set()
  const head = `${lib.what}: ${replaced.length ? `cannot stand in for ${replaced.join(', ')}` : 'imports nothing provides'}:`
  for (const r of replaced) {
    if (!exportsOf(r, minApi)) problems.add(`${r} has no NDK stub at API ${minApi}, so what only it provides is unknown`)
    if (lib.versionedFrom.has(r)) problems.add(`${r}: imports from it carry symbol versions, which a shim does not define`)
  }
  if (problems.size) throw new Error(`${head}\n  ${[...problems].join('\n  ')}`)
  const from = (libs, name, api) => libs.filter((l) => exportsOf(l, api)?.has(name))
  // A problem found at several levels is reported once, with them. In its text \0, which no name
  // read from .dynstr can contain, stands for the levels.
  const levels = new Map()
  const note = (problem, api) => levels.set(problem, [...(levels.get(problem) ?? []), api])
  const shims = replaced.map((r) => ({ replaced: r, functions: [] }))
  for (const s of lib.symbols) {
    if (s.defined || (s.bind !== STB_GLOBAL && s.bind !== STB_WEAK)) continue
    const weak = s.bind === STB_WEAK
    for (const api of apis) {
      const owners = from(replaced, s.name, api)
      const loadable = from(LOADABLE_LIBS, s.name, api)
      if (!weak && !owners.length && !loadable.length) note(`${s.name}: no library provides it at API \0`, api)
      if (!weak && owners.length && loadable.length) {
        note(`${s.name}: ${owners.join(', ')} and ${loadable.join(', ')} both provide it (API \0); without ${owners[0]} it would bind elsewhere`, api)
      }
      if (weak && owners.length && !loadable.length) {
        note(`${s.name} (weak): only ${owners.join(', ')} provides it (API \0); without it, it would be left unresolved`, api)
      }
    }
    const owner = from(replaced, s.name, minApi)[0]
    if (weak || !owner) continue
    const type = s.type !== STT_NOTYPE ? s.type : exportsOf(owner, minApi).get(s.name)
    if (type !== STT_FUNC) {
      problems.add(`${s.name}: a ${SYMBOL_TYPES[type] ?? `type ${type} symbol`} from ${owner}; a shim can only stand in for functions`)
    } else if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(s.name)) {
      problems.add(`${s.name}: from ${owner}, not a C identifier`)
    } else {
      shims.find((sh) => sh.replaced === owner).functions.push(s.name)
    }
  }
  for (const [problem, at] of levels) problems.add(problem.replace('\0', apiRanges(at, apis)))
  if (problems.size) throw new Error(`${head}\n  ${[...problems].join('\n  ')}`)
  const taken = new Set([...lib.needed.map((n) => n.name), ...LOADABLE_LIBS, ...STAGED_FILES])
  for (const sh of shims) {
    sh.shim = shimFileName(sh.replaced, taken)
    taken.add(sh.shim)
    sh.functions.sort()
  }
  return shims
}

// The levels in at, in the order of apis, as runs of consecutive entries of it: "26-28, 31".
function apiRanges(at, apis) {
  const runs = []
  for (const api of at) {
    const run = runs[runs.length - 1]
    if (run && apis.indexOf(api) === apis.indexOf(run[1]) + 1) run[1] = api
    else runs.push([api, api])
  }
  return runs.map(([first, last]) => (first === last ? `${first}` : `${first}-${last}`)).join(', ')
}

// A shim's file name is exactly as long as the name it replaces, so the DT_NEEDED string can be
// rewritten in place: "lrt" and the start of the old name (libandroid.so -> liblrtandr.so), with
// a base-36 counter over its last characters while that is taken. The counter never covers the
// "lr", so every shim name starts with liblr: that is what lets isShimFileName tell a shim an
// earlier run staged from another library in the same directory (such as libomp.so, which a
// counter over all three letters of a libxyz.so could otherwise have been named).
export function shimFileName(replaced, taken) {
  const m = /^lib(.+)\.so$/.exec(replaced)
  if (!m) throw new Error(`${replaced}: not a lib*.so name`)
  const n = m[1].length
  if (n < 2) throw new Error(`${replaced}: too short for a shim name of its length (liblr*.so)`)
  const base = `lrt${m[1]}`.slice(0, n)
  for (let i = 0; i < 36 ** Math.min(n - 2, 3); i++) {
    const tag = i ? i.toString(36) : ''
    const name = `lib${base.slice(0, n - tag.length)}${tag}.so`
    if (!taken.has(name)) return name
  }
  throw new Error(`no free shim name of ${replaced.length} characters for ${replaced}`)
}

// Whether shimFileName can produce name: liblr, then either t and anything, or nothing, or a
// counter of up to three base-36 digits; then .so.
export function isShimFileName(name) {
  return /^liblr(t.*|[1-9a-z][0-9a-z]{0,2})?\.so$/s.test(name)
}

// Removes from dir the shims an earlier run staged that this one does not (a LiteRT-LM bump can
// rename or drop one, and it would otherwise stay in the APK), and nothing else: only regular
// files whose name isShimFileName accepts. dir can hold other libraries; with --out pointed at
// composeApp/src/androidMain/jniLibs it holds the llama.cpp engines, which are slow to rebuild.
export function removeStaleShims(dir, staged) {
  const removed = []
  for (const f of readdirSync(dir)) {
    if (staged.has(f) || !isShimFileName(f) || !lstatSync(join(dir, f)).isFile()) continue
    rmSync(join(dir, f))
    removed.push(f)
  }
  return removed
}

// Renames one DT_NEEDED entry in place, refusing if any other reference into .dynstr (a symbol
// name, a version entry, another dynamic tag) shares its bytes: those, and every other copy of the
// name in the file, such as the one in .rodata next to TFLite's NNAPI strings, are left alone.
export function renameNeeded(b, lib, from, to) {
  const n = lib.needed.find((e) => e.name === from)
  if (!n || to.length !== from.length) throw new Error(`${lib.what}: cannot rename DT_NEEDED ${from} to ${to}`)
  const at = lib.strtab + n.off
  if (b.toString('latin1', at, at + from.length + 1) !== `${from}\0` || b[at - 1] !== 0) {
    throw new Error(`${lib.what}: DT_NEEDED ${from} is not a string of its own at .dynstr+${n.off}`)
  }
  const shared = lib.refs.filter((r) => r !== n.ref && r.off >= n.off && r.off < n.off + from.length)
  if (shared.length) {
    throw new Error(`${lib.what}: ${shared.map((r) => r.by).join(', ')} share(s) the string of DT_NEEDED ${from}`)
  }
  b.write(to, at, 'latin1')
}

export function shimSource({ replaced, shim, functions }, origin) {
  return `// Generated by scripts/stage-litert-lm-engine.mjs for ${origin}. Do not edit.
//
// liblitert-lm.so lists ${replaced} as a dependency, and the engine process cannot load
// ${replaced}, so the staging script renamed that dependency to ${shim}: this library. It
// defines the ${functions.length} function(s) liblitert-lm.so imports from ${replaced} and nothing else. None
// of them should run in the engine; if one does, it says which, on stderr and in logcat, and
// aborts: it does not return a value it would have to make up.
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#define WHY "which ${replaced} provides, but ${replaced} cannot be loaded in the engine process (${shim} stands in for it); stopping."

__attribute__((noreturn)) static void stop(const char* function) {
  dprintf(STDERR_FILENO, "liblitert-lm.so called %s(), %s\\n", function, WHY);
  __android_log_print(ANDROID_LOG_FATAL, "litert-lm-server", "liblitert-lm.so called %s(), %s", function, WHY);
  abort();
}

#define SHIM(name)                                                  \\
  __attribute__((visibility("default"), noreturn)) void name(void); \\
  void name(void) { stop(#name); }

${functions.map((f) => `SHIM(${f})`).join('\n')}
`
}

export function ndkToolchain(ndk) {
  const prebuilt = join(ndk, 'toolchains', 'llvm', 'prebuilt')
  const exe = process.platform === 'win32' ? 'clang.exe' : 'clang'
  const host = existsSync(prebuilt) ? readdirSync(prebuilt).find((h) => existsSync(join(prebuilt, h, 'bin', exe))) : undefined
  if (!host) fail(`no NDK clang under ${prebuilt}`)
  return { clang: join(prebuilt, host, 'bin', exe), libs: join(prebuilt, host, 'sysroot', 'usr', 'lib') }
}

function run(cmd, args, what) {
  try {
    execFileSync(cmd, args, { stdio: ['ignore', 'inherit', 'inherit'] })
  } catch {
    fail(`${what} failed (the compiler's or linker's message is above)`)
  }
}

// Builds a generated shim and checks that it exports exactly its functions and needs only
// loadable libraries.
function buildShim(sh, abi, tc, workDir) {
  const src = join(workDir, sh.shim.replace(/\.so$/, '.c'))
  const out = join(workDir, sh.shim)
  writeFileSync(src, shimSource(sh, `LiteRT-LM ${LITERT_LM_VERSION}, ${abi}`))
  run(tc.clang, [
    `--target=${WHEELS[abi].triple}${MIN_SDK}`, '-shared', '-fPIC', '-O2', '-fvisibility=hidden',
    '-Wall', '-Wextra', '-Werror', `-Wl,-soname,${sh.shim}`, '-Wl,--no-undefined',
    '-Wl,--exclude-libs,ALL', '-Wl,-z,max-page-size=16384', '-s', '-o', out, src, '-llog',
  ], `building ${sh.shim} for ${abi}`)
  const built = readDynamic(readFileSync(out), out)
  const exported = built.symbols.filter((s) => s.defined && (s.bind === STB_GLOBAL || s.bind === STB_WEAK)).map((s) => s.name)
  if (exported.sort().join() !== sh.functions.join()) fail(`${out} exports ${exported.join(', ') || 'nothing'}, expected ${sh.functions.join(', ') || 'nothing'}`)
  const extra = built.needed.map((n) => n.name).filter((n) => !LOADABLE_LIBS.includes(n))
  if (extra.length) fail(`${out} needs ${extra.join(', ')}, which the engine process may not load`)
  return out
}

// lld checks every undefined symbol of a shared library whose DT_NEEDED entries are all on the
// link line (--no-allow-shlib-undefined), against what is on that line. So the probe links the
// staged library, its shims and the NDK stubs of exactly the other libraries it needs, and the
// link fails if any GLOBAL import has no provider among them, as the device's linker would.
function probeLink(libPath, abi, tc, shimPaths, workDir) {
  const lib = readDynamic(readFileSync(libPath), libPath)
  const deps = lib.needed.map((n) => shimPaths.get(n.name) ?? join(tc.libs, WHEELS[abi].triple, String(MIN_SDK), n.name))
  const missing = deps.filter((d) => !existsSync(d))
  if (missing.length) fail(`${libPath}: no ${missing.join(', ')} to check it against`)
  const probe = join(workDir, 'probe.c')
  writeFileSync(probe, 'int main(void) { return 0; }\n')
  run(tc.clang, [
    `--target=${WHEELS[abi].triple}${MIN_SDK}`, '-o', join(workDir, 'probe'), probe, libPath, ...deps,
    '-Wl,--no-allow-shlib-undefined', '-Wl,--no-undefined',
  ], `the link check of ${libPath} against its dependencies`)
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
    // Same floor as the app; the library itself is built for API 23.
    `-DANDROID_PLATFORM=android-${MIN_SDK}`,
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
  const tc = ndkToolchain(opts.ndk)
  for (const abi of opts.abis) {
    const w = WHEELS[abi]
    if (!w) fail(`no LiteRT-LM build for ABI ${abi} (have: ${Object.keys(WHEELS).join(', ')})`)
    const outDir = join(opts.out, abi)
    const workDir = join(opts.out, '..', `shim-${abi}`)
    mkdirSync(outDir, { recursive: true })
    mkdirSync(workDir, { recursive: true })

    const wheel = await fetchWheel(abi, opts.cache)
    const data = Buffer.from(extractFromZip(wheel, LIB_IN_WHEEL))
    const parsed = readDynamic(data, `${LIB_IN_WHEEL} (${abi})`)
    const { apis, exportsOf } = ndkStubs(join(tc.libs, w.triple), MIN_SDK)
    const shims = planShims(parsed, exportsOf, apis)
    for (const sh of shims) renameNeeded(data, parsed, sh.replaced, sh.shim)

    const lib = join(outDir, 'liblitert-lm.so')
    writeFileSync(lib, data)
    checkElf(lib, w.elfMachine)
    const shimPaths = new Map()
    for (const sh of shims) {
      const staged = join(outDir, sh.shim)
      copyFileSync(buildShim(sh, abi, tc, workDir), staged)
      checkElf(staged, w.elfMachine)
      shimPaths.set(sh.shim, staged)
      console.log(`liblitert-lm.so (${abi}): ${sh.replaced} replaced by ${sh.shim}, ` +
        `which aborts if any of its ${sh.functions.length} function(s) is called: ${sh.functions.join(', ')}`)
    }
    probeLink(lib, abi, tc, shimPaths, workDir)
    // Last, so that a LiteRT-LM bump learns the new hash only once every check above has passed.
    const patched = sha256(data)
    if (patched !== w.patchedSha256) {
      fail(`${LIB_IN_WHEEL} (${abi}) with ${shims.map((sh) => `${sh.replaced} replaced by ${sh.shim}`).join(', ') || 'nothing replaced'}: ` +
        `sha256 ${patched} does not match the pinned patchedSha256 ${w.patchedSha256}`)
    }

    const server = join(outDir, 'liblitertlm_server.so')
    copyFileSync(buildServer(abi, opts), server)
    checkElf(server, w.elfMachine)

    for (const f of removeStaleShims(outDir, new Set(shimPaths.keys()))) {
      console.log(`removed ${join(outDir, f)}: a shim an earlier run staged and this one does not`)
    }

    console.log(`staged LiteRT-LM ${LITERT_LM_VERSION} for ${abi}: ` +
      `liblitert-lm.so ${(statSync(lib).size / 1e6).toFixed(1)} MB, ` +
      `liblitertlm_server.so ${(statSync(server).size / 1e3).toFixed(0)} KB` +
      [...shimPaths].map(([name, f]) => `, ${name} ${(statSync(f).size / 1e3).toFixed(0)} KB`).join(''))
  }
}

// Run as a script; the test imports the functions above without staging anything. Real paths on
// both sides, so a symlinked checkout cannot make a Gradle run skip the staging and still pass.
if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) {
  main().catch((e) => fail(e instanceof Error ? e.message : String(e)))
}
