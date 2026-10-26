#!/usr/bin/env node
// Build the engine tether that ships inside the APK: libtether_exec.so, from
// engines/tether-exec/tether_exec.c, for each ABI the APK carries.
//
// The daemon starts every engine through it (TurboLLM's util/tether.ts): it arms
// PR_SET_PDEATHSIG(SIGKILL) and execs the engine, so the kernel kills the engine the moment the
// daemon's thread ends, however the app process ends. The daemon checks it once at boot by
// watching it work, and uses a /system/bin/sh lifeline instead when the check fails or the file is
// missing (BUILDING-ENGINES.md, "The engine tether").
//
// Output: <out>/<abi>/libtether_exec.so, which build.gradle.kts adds to the APK's jniLibs. The
// name fits the lib*.so pattern Android requires before it will install a file into
// nativeLibraryDir, the only place an app may execute from (BUILDING-ENGINES.md). Nothing else in
// <out> is touched. Built with the NDK's clang for the app's minSdk, as a PIE with 16 KB-aligned
// LOAD segments, stripped, and checked with the LiteRT-LM staging's checkElf.
//
// Usage (Gradle runs this; see stageTetherExec in composeApp/build.gradle.kts):
//   node scripts/stage-tether-exec.mjs --ndk <ndk dir> --out <dir> [--abis arm64-v8a,x86_64]
import { execFileSync } from 'node:child_process'
import { mkdirSync, realpathSync, renameSync, rmSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { checkElf, MIN_SDK, ndkToolchain } from './stage-litert-lm-engine.mjs'

export const HELPER = 'libtether_exec.so'
export const ABIS = {
  'arm64-v8a': { triple: 'aarch64-linux-android', elfMachine: 0xb7 }, // EM_AARCH64
  x86_64: { triple: 'x86_64-linux-android', elfMachine: 0x3e }, // EM_X86_64
}
// Every build of the helper, the NDK's here and the test's host build, uses these.
export const CFLAGS = ['-O2', '-Wall', '-Wextra', '-Werror']

export const SOURCE = join(resolve(dirname(fileURLToPath(import.meta.url)), '..'), 'engines', 'tether-exec', 'tether_exec.c')

function parseArgs(argv) {
  const out = { abis: Object.keys(ABIS) }
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i]
    const v = argv[i + 1]
    if (k === '--ndk') out.ndk = v
    else if (k === '--out') out.out = v
    else if (k === '--abis') out.abis = v.split(',').map((s) => s.trim()).filter(Boolean)
    else throw new Error(`unknown argument ${k}`)
    i++
  }
  if (!out.ndk) throw new Error('--ndk <dir> is required')
  if (!out.out) throw new Error('--out <dir> is required')
  return out
}

function fail(msg) {
  console.error(`FATAL: ${msg}`)
  process.exit(1)
}

function main() {
  const opts = parseArgs(process.argv.slice(2))
  const unknown = opts.abis.filter((abi) => !ABIS[abi])
  if (unknown.length) fail(`no tether build for ABI ${unknown.join(', ')} (have: ${Object.keys(ABIS).join(', ')})`)
  const { clang } = ndkToolchain(opts.ndk)
  for (const abi of opts.abis) {
    const dir = join(opts.out, abi)
    mkdirSync(dir, { recursive: true })
    const staged = join(dir, HELPER)
    // Built beside its final name and renamed once checked, so a failed build leaves no helper the
    // APK would carry: the packaging check in build.gradle.kts then stops the build.
    const tmp = `${staged}.tmp`
    rmSync(tmp, { force: true })
    try {
      execFileSync(clang, [
        `--target=${ABIS[abi].triple}${MIN_SDK}`, ...CFLAGS, '-fPIE', '-pie',
        '-Wl,-z,max-page-size=16384', '-s', '-o', tmp, SOURCE,
      ], { stdio: ['ignore', 'inherit', 'inherit'] })
    } catch {
      fail(`building ${HELPER} for ${abi} failed (the compiler's message is above)`)
    }
    checkElf(tmp, ABIS[abi].elfMachine)
    renameSync(tmp, staged)
    console.log(`staged ${HELPER} for ${abi}: ${(statSync(staged).size / 1e3).toFixed(1)} KB`)
  }
}

// Run as a script; the test imports the constants above without staging anything.
if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) {
  try {
    main()
  } catch (e) {
    fail(e instanceof Error ? e.message : String(e))
  }
}
