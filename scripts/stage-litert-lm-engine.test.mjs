// Tests for the libandroid.so replacement in stage-litert-lm-engine.mjs: reading the library's
// dynamic section and the NDK's stubs, deciding what each shim defines, renaming the DT_NEEDED
// entry, naming the shim and removing a stale one. They run on small synthetic ELF files, so they
// need neither the NDK nor the wheel:
//   node --test scripts/stage-litert-lm-engine.test.mjs
// (Every staging run also checks the real library: the shim build, the link probe and the pinned
// hash of the patched file.)
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'

import {
  isShimFileName, ndkStubs, planShims, readDynamic, removeStaleShims, renameNeeded, shimFileName, shimSource,
} from './stage-litert-lm-engine.mjs'

const GLOBAL = 1
const WEAK = 2
const OBJECT = 1
const FUNC = 2

// A minimal 64-bit little-endian shared object, laid out as a linker would for the parts the
// script reads: one PT_LOAD over the whole file (vaddr = file offset), PT_DYNAMIC, .dynstr,
// .dynsym with its section header, optional version needs, and some bytes after them standing in
// for .rodata. Identical strings share one .dynstr entry, as lld emits them.
function fakeElf({ needed, symbols, verneed = [], rodata = '' }) {
  const strings = ['']
  const offsets = new Map([['', 0]])
  let strsz = 1
  const strOff = (s) => {
    if (!offsets.has(s)) {
      offsets.set(s, strsz)
      strings.push(s)
      strsz += Buffer.byteLength(s) + 1
    }
    return offsets.get(s)
  }
  for (const n of needed) strOff(n)
  for (const s of symbols) strOff(s.name)
  for (const v of verneed) [v.file, ...v.versions].forEach(strOff)
  const dynstr = Buffer.from(strings.map((s) => `${s}\0`).join(''), 'latin1')

  const align = (n) => (n + 7) & ~7
  const strtabOff = 64 + 2 * 56
  const symtabOff = align(strtabOff + dynstr.length)
  const verneedOff = symtabOff + 24 * (symbols.length + 1)
  const verneedSize = verneed.reduce((n, v) => n + 16 + 16 * v.versions.length, 0)
  const dynamicOff = align(verneedOff + verneedSize)
  const dyn = [
    ...needed.map((n) => [1, strOff(n)]),
    [5, strtabOff], [10, dynstr.length], [6, symtabOff],
    ...(verneed.length ? [[0x6ffffffe, verneedOff], [0x6fffffff, verneed.length]] : []),
    [0, 0],
  ]
  const rodataOff = dynamicOff + 16 * dyn.length
  const shoff = align(rodataOff + rodata.length)
  const b = Buffer.alloc(shoff + 2 * 64)

  b.writeUInt32BE(0x7f454c46, 0)
  b[4] = 2 // ELFCLASS64
  b[5] = 1 // ELFDATA2LSB
  b[6] = 1
  b.writeUInt16LE(3, 16) // ET_DYN
  b.writeUInt16LE(0xb7, 18)
  b.writeUInt32LE(1, 20)
  b.writeBigUInt64LE(64n, 32) // e_phoff
  b.writeBigUInt64LE(BigInt(shoff), 40)
  b.writeUInt16LE(64, 52)
  b.writeUInt16LE(56, 54)
  b.writeUInt16LE(2, 56)
  b.writeUInt16LE(64, 58)
  b.writeUInt16LE(2, 60)
  const phdr = (o, type, off, size) => {
    b.writeUInt32LE(type, o)
    b.writeBigUInt64LE(BigInt(off), o + 8)
    b.writeBigUInt64LE(BigInt(off), o + 16)
    b.writeBigUInt64LE(BigInt(size), o + 32)
    b.writeBigUInt64LE(BigInt(size), o + 40)
  }
  phdr(64, 1, 0, b.length) // PT_LOAD
  phdr(64 + 56, 2, dynamicOff, 16 * dyn.length) // PT_DYNAMIC

  dynstr.copy(b, strtabOff)
  symbols.forEach((s, i) => {
    const o = symtabOff + 24 * (i + 1)
    b.writeUInt32LE(strOff(s.name), o)
    b[o + 4] = (s.bind << 4) | s.type
    b.writeUInt16LE(s.defined ? 1 : 0, o + 6)
  })
  let o = verneedOff
  verneed.forEach((v, i) => {
    b.writeUInt16LE(1, o)
    b.writeUInt16LE(v.versions.length, o + 2)
    b.writeUInt32LE(strOff(v.file), o + 4)
    b.writeUInt32LE(16, o + 8)
    b.writeUInt32LE(i < verneed.length - 1 ? 16 + 16 * v.versions.length : 0, o + 12)
    v.versions.forEach((name, j) => {
      const a = o + 16 + 16 * j
      b.writeUInt32LE(strOff(name), a + 8)
      b.writeUInt32LE(j < v.versions.length - 1 ? 16 : 0, a + 12)
    })
    o += 16 + 16 * v.versions.length
  })
  dyn.forEach(([tag, val], i) => {
    b.writeBigUInt64LE(BigInt(tag), dynamicOff + 16 * i)
    b.writeBigUInt64LE(BigInt(val), dynamicOff + 16 * i + 8)
  })
  b.write(rodata, rodataOff, 'latin1')
  const sh = shoff + 64 // [0] is the null section header
  b.writeUInt32LE(11, sh + 4) // SHT_DYNSYM
  b.writeBigUInt64LE(BigInt(symtabOff), sh + 24)
  b.writeBigUInt64LE(BigInt(24 * (symbols.length + 1)), sh + 32)
  b.writeBigUInt64LE(24n, sh + 56)
  return b
}

const fn = (name, bind = GLOBAL) => ({ name, bind, type: FUNC })

// Stand-ins for the NDK's stub libraries: each library's symbols, with their type and the API level
// the library gains them at. A library has no stub below the level of its first symbol, as the
// NDK has no libnativewindow.so below API 26 (where it and libandroid.so both gain
// AHardwareBuffer_acquire).
const STUBS = {
  'libandroid.so': { AAsset_close: [FUNC, 24], AAssetManager_open: [FUNC, 24], ALooper_forThread: [FUNC, 24], AHardwareBuffer_acquire: [FUNC, 26] },
  'libc.so': { malloc: [FUNC, 24], stderr: [OBJECT, 24], dprintf: [FUNC, 24] },
  'liblog.so': { __android_log_print: [FUNC, 24] },
  'libnativewindow.so': { AHardwareBuffer_acquire: [FUNC, 26] },
}
const APIS = [24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35]
const withExtra = (extra = {}) => Object.fromEntries([...new Set([...Object.keys(STUBS), ...Object.keys(extra)])]
  .map((lib) => [lib, { ...STUBS[lib], ...extra[lib] }]))
const exportsAt = (spec, lib, api) => {
  const syms = Object.entries(spec[lib] ?? {}).filter(([, [, since]]) => since <= api)
  return syms.length ? new Map(syms.map(([name, [type]]) => [name, type])) : undefined
}
const stubs = (extra) => (lib, api) => exportsAt(withExtra(extra), lib, api)

// Shaped like LiteRT-LM 0.18.0's liblitert-lm.so: libandroid.so first among its dependencies,
// APK-asset functions imported from it, the rest from bionic, AHardwareBuffer_* imported weak.
const litertLike = (extraSymbols = [], extra = {}) => ({
  needed: ['libandroid.so', 'liblog.so', 'libc.so'],
  symbols: [
    { name: 'litert_lm_engine_create', bind: GLOBAL, type: FUNC, defined: true },
    fn('AAsset_close'), fn('AAssetManager_open'), fn('malloc'), fn('__android_log_print'),
    { name: 'stderr', bind: GLOBAL, type: OBJECT },
    fn('AHardwareBuffer_acquire', WEAK),
    ...extraSymbols,
  ],
  rodata: 'ANEURALNETWORKS_NO_ERROR\0libandroid.so\0',
  ...extra,
})
const plan = (spec, extraStubs) => planShims(readDynamic(fakeElf(spec), 'liblitert-lm.so'), stubs(extraStubs), APIS)

test('readDynamic reads the DT_NEEDED entries, the dynamic symbols and the version needs', () => {
  const lib = readDynamic(fakeElf(litertLike([], { verneed: [{ file: 'libc.so', versions: ['LIBC'] }] })), 'x')
  assert.deepEqual(lib.needed.map((n) => n.name), ['libandroid.so', 'liblog.so', 'libc.so'])
  assert.deepEqual(lib.symbols.find((s) => s.name === 'stderr'), { name: 'stderr', bind: GLOBAL, type: OBJECT, defined: false })
  assert.equal(lib.symbols.find((s) => s.name === 'litert_lm_engine_create').defined, true)
  assert.deepEqual([...lib.versionedFrom], ['libc.so'])
  assert.throws(() => readDynamic(Buffer.from('not an ELF file at all, just some bytes'.repeat(3)), 'x'), /not a 64-bit/)
})

test('libandroid.so gets a shim with exactly the functions only it provides', () => {
  assert.deepEqual(plan(litertLike()), [{ replaced: 'libandroid.so', shim: 'liblrtandr.so', functions: ['AAssetManager_open', 'AAsset_close'] }])
})

test('a new libandroid.so-only import adds a function to the shim', () => {
  assert.deepEqual(plan(litertLike([fn('ALooper_forThread')]))[0].functions, ['AAssetManager_open', 'AAsset_close', 'ALooper_forThread'])
})

test('a library that needs only loadable libraries gets no shim', () => {
  assert.deepEqual(plan({ needed: ['libc.so'], symbols: [fn('malloc')] }), [])
})

test('a data object only libandroid.so provides fails the build', () => {
  const extra = { 'libandroid.so': { AConfiguration_table: [OBJECT, 24] } }
  assert.throws(() => plan(litertLike([{ name: 'AConfiguration_table', bind: GLOBAL, type: OBJECT }]), extra),
    /AConfiguration_table: a data object from libandroid.so; a shim can only stand in for functions/)
})

test('a GLOBAL import no library provides fails the build', () => {
  assert.throws(() => plan(litertLike([fn('mystery_function')])), /mystery_function: no library provides it at API 24-35$/m)
})

test('a GLOBAL import both libandroid.so and a loadable library provide fails the build', () => {
  const extra = { 'libandroid.so': { dprintf: [FUNC, 30] } }
  assert.throws(() => plan(litertLike([fn('dprintf')]), extra), /dprintf: libandroid.so and libc.so both provide it \(API 30-35\)/)
})

test('a WEAK import only libandroid.so provides fails the build; one a loadable library also provides does not', () => {
  const extra = { 'libandroid.so': { ANativeActivity_finish: [FUNC, 35] } }
  assert.throws(() => plan(litertLike([fn('ANativeActivity_finish', WEAK)]), extra),
    /ANativeActivity_finish \(weak\): only libandroid.so provides it \(API 35\)/)
  // AHardwareBuffer_acquire, in litertLike, is the second case (libnativewindow.so from API 26).
  assert.doesNotThrow(() => plan(litertLike()))
})

// Writes the stub libraries spec describes (shaped like STUBS) as the NDK lays them out,
// <dir>/<api>/<lib>, for each of apis, plus a file beside the level directories as the NDK has.
function stubTree(dir, spec, apis) {
  for (const api of apis) {
    mkdirSync(join(dir, String(api)))
    for (const lib of Object.keys(spec)) {
      const syms = exportsAt(spec, lib, api)
      if (!syms) continue
      const symbols = [...syms].map(([name, type]) => ({ name, bind: GLOBAL, type, defined: true }))
      writeFileSync(join(dir, String(api), lib), fakeElf({ needed: [], symbols }))
    }
  }
  writeFileSync(join(dir, 'libc++_shared.so'), 'not an API level')
}

test('every API level from minSdk to the newest stubs is checked: a WEAK import libandroid.so gains before a loadable library does fails the build', () => {
  const dir = mkdtempSync(join(tmpdir(), 'stage-litert-lm-stubs-'))
  try {
    // libandroid.so gains AHardwareBuffer_lockPlanes at 26, libnativewindow.so only at 29: on
    // API 26-28 it would be left unresolved. API 24 and 30, the first and the newest level, both
    // look fine. No libandroid.so stub exists below 24 (STUBS starts it there), so checking 21-23
    // would fail the build too.
    stubTree(dir, withExtra({
      'libandroid.so': { AHardwareBuffer_lockPlanes: [FUNC, 26] },
      'libnativewindow.so': { AHardwareBuffer_lockPlanes: [FUNC, 29] },
    }), [21, 22, 23, 24, 25, 26, 27, 28, 29, 30])
    const { apis, exportsOf } = ndkStubs(dir, 24)
    assert.deepEqual(apis, [24, 25, 26, 27, 28, 29, 30])
    const lib = (extraSymbols) => readDynamic(fakeElf(litertLike(extraSymbols)), 'liblitert-lm.so')
    assert.deepEqual(planShims(lib([]), exportsOf, apis).map((sh) => sh.functions), [['AAssetManager_open', 'AAsset_close']])
    assert.throws(() => planShims(lib([fn('AHardwareBuffer_lockPlanes', WEAK)]), exportsOf, apis),
      /AHardwareBuffer_lockPlanes \(weak\): only libandroid.so provides it \(API 26-28\)/)
    assert.throws(() => ndkStubs(dir, 20), /no stub libraries for API 20/)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('a replaced library imported with symbol versions fails the build', () => {
  assert.throws(() => plan(litertLike([], { verneed: [{ file: 'libandroid.so', versions: ['LIBANDROID'] }] })),
    /libandroid.so: imports from it carry symbol versions/)
})

test('renameNeeded changes the DT_NEEDED string and nothing else', () => {
  const before = fakeElf(litertLike())
  const after = Buffer.from(before)
  const lib = readDynamic(after, 'x')
  renameNeeded(after, lib, 'libandroid.so', 'liblrtandr.so')
  const changed = [...after.keys()].filter((i) => after[i] !== before[i])
  assert.deepEqual(changed, [3, 4, 5, 6, 7, 8, 9].map((i) => lib.strtab + lib.needed[0].off + i))
  const reread = readDynamic(after, 'x')
  assert.deepEqual(reread.needed.map((n) => n.name), ['liblrtandr.so', 'liblog.so', 'libc.so'])
  assert.deepEqual(reread.symbols, lib.symbols)
  assert.ok(after.includes('ANEURALNETWORKS_NO_ERROR\0libandroid.so\0'), 'the .rodata copy is left alone')
})

test('renameNeeded refuses a string another reference shares', () => {
  // A symbol with the same name shares the string (lld emits identical strings once).
  const same = fakeElf(litertLike([fn('libandroid.so')]))
  assert.throws(() => renameNeeded(same, readDynamic(same, 'x'), 'libandroid.so', 'liblrtandr.so'), /symbol \d+ share\(s\) the string/)
  // So does one that points into its tail ("android.so").
  const tail = fakeElf(litertLike())
  const lib = readDynamic(tail, 'x')
  const symtab = Number(tail.readBigUInt64LE(Number(tail.readBigUInt64LE(40)) + 64 + 24))
  tail.writeUInt32LE(lib.needed[0].off + 3, symtab + 24 * 2) // symbol 2, AAsset_close
  assert.throws(() => renameNeeded(tail, readDynamic(tail, 'x'), 'libandroid.so', 'liblrtandr.so'), /symbol 2 share\(s\) the string/)
  assert.throws(() => renameNeeded(Buffer.from(tail), lib, 'libandroid.so', 'liblrtandr2.so'), /cannot rename/)
})

test('renameNeeded refuses a DT_NEEDED entry that is the tail of a longer string', () => {
  // Point DT_NEEDED at the "libandroid.so" that ends the symbol name my_libandroid.so: no other
  // reference starts inside it, but renaming it would rename that symbol.
  const b = fakeElf(litertLike([fn('my_libandroid.so')]))
  const { strtab } = readDynamic(b, 'x')
  const dynamic = Number(b.readBigUInt64LE(64 + 56 + 8)) // PT_DYNAMIC's p_offset
  b.writeBigUInt64LE(BigInt(b.indexOf('my_libandroid.so\0', strtab) - strtab + 3), dynamic + 8) // entry 0
  const lib = readDynamic(b, 'x')
  assert.equal(lib.needed[0].name, 'libandroid.so')
  const before = Buffer.from(b)
  assert.throws(() => renameNeeded(b, lib, 'libandroid.so', 'liblrtandr.so'), /DT_NEEDED libandroid.so is not a string of its own/)
  assert.ok(b.equals(before), 'nothing is written')
})

test('shim names keep the length of the name they replace and stay unique', () => {
  assert.equal(shimFileName('libandroid.so', new Set()), 'liblrtandr.so')
  assert.equal(shimFileName('libfoo.so', new Set()), 'liblrt.so')
  const taken = new Set()
  for (const lib of ['libmediandk.so', 'libmediaxyz.so', 'libmediaabc.so', 'libabc.so', 'libxyz.so', 'libcamera2ndk.so']) {
    const name = shimFileName(lib, taken)
    assert.equal(name.length, lib.length, name)
    assert.match(name, /^lib[0-9a-z]+\.so$/)
    assert.ok(!taken.has(name), name)
    taken.add(name)
  }
  assert.deepEqual([...taken].slice(0, 5), ['liblrtmedia.so', 'liblrtmedi1.so', 'liblrtmedi2.so', 'liblrt.so', 'liblr1.so'])
  assert.throws(() => shimFileName('android.so', new Set()), /not a lib\*\.so name/)
})

test('isShimFileName accepts every name shimFileName can produce, and not the libraries beside the shims', () => {
  // Stems of two, three and four characters, until no name is left: the counter takes over the
  // most characters there.
  for (const replaced of ['libab.so', 'libxyz.so', 'libandr.so']) {
    const taken = new Set()
    for (;;) {
      let name
      try {
        name = shimFileName(replaced, taken)
      } catch (e) {
        assert.match(e.message, /no free shim name/)
        break
      }
      assert.ok(isShimFileName(name), `${replaced}: ${name}`)
      taken.add(name)
    }
    assert.equal(taken.size, { 'libab.so': 1, 'libxyz.so': 35, 'libandr.so': 1260 }[replaced], replaced)
  }
  assert.throws(() => shimFileName('libx.so', new Set()), /too short/)
  // What src/androidMain/jniLibs holds beside them (the llama.cpp engines) and the staged files.
  for (const other of ['libggml-base.so', 'libggml.so', 'libllama.so', 'libllama_server.so', 'libllama_server_vk.so',
    'libmtmd.so', 'libomp.so', 'liblog.so', 'liblitert-lm.so', 'liblitertlm_server.so', 'liblr0.so', 'liblrAB.so']) {
    assert.ok(!isShimFileName(other), other)
  }
})

test('removeStaleShims removes the shims an earlier run staged and nothing else in the directory', () => {
  const dir = mkdtempSync(join(tmpdir(), 'stage-litert-lm-out-'))
  try {
    // As if --out were composeApp/src/androidMain/jniLibs: the llama.cpp engines stay.
    const others = ['libggml-base.so', 'libggml-cpu.so', 'libggml.so', 'libllama-common.so', 'libllama-server-impl.so',
      'libllama.so', 'libllama_server.so', 'libllama_server_vk.so', 'libmtmd.so', 'libomp.so', 'README.txt',
      'liblitert-lm.so', 'liblitertlm_server.so', 'liblrtandr.so']
    for (const f of [...others, 'liblrtjnigraph.so', 'liblr1.so']) writeFileSync(join(dir, f), f)
    mkdirSync(join(dir, 'liblrtdir.so')) // a directory, which no run stages
    assert.deepEqual(removeStaleShims(dir, new Set(['liblrtandr.so'])).sort(), ['liblr1.so', 'liblrtjnigraph.so'])
    assert.deepEqual(readdirSync(dir).sort(), [...others, 'liblrtdir.so'].sort())
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('the shim source defines each function to report itself and abort', () => {
  const src = shimSource({ replaced: 'libandroid.so', shim: 'liblrtandr.so', functions: ['AAssetManager_open', 'AAsset_close'] }, 'test')
  assert.deepEqual(src.match(/^SHIM\(.*\)$/gm), ['SHIM(AAssetManager_open)', 'SHIM(AAsset_close)'])
  assert.match(src, /libandroid.so cannot be loaded in the engine process \(liblrtandr.so stands in for it\)/)
  assert.match(src, /abort\(\);/)
  assert.doesNotMatch(src.replace(/^\/\/.*$/gm, ''), /\breturn\b/, 'no function returns')
})
