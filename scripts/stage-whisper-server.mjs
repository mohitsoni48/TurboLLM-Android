#!/usr/bin/env node
// Build the speech-to-text server that ships inside the APK: whisper.cpp's `whisper-server`, as
// libwhisper_server.so, for each ABI the APK carries.
//
// The web UI's microphone turns a recording into text by sending it to the daemon, which runs
// whisper-server for it (TurboLLM's src/voice). On desktop the daemon installs whisper.cpp's own
// release build; in the app it may only execute files installed with its APK, so the server is
// built here, from the same release the desktop install is pinned to, and the daemon looks for it
// in nativeLibraryDir before anything else (src/voice/whisper-binary.ts, ANDROID_WHISPER_SERVER).
// Only the server is bundled: the user picks a whisper model in Settings → Voice and the daemon
// downloads it at run time.
//
// How it is built, and why:
//  - From source at a pinned tag whose commit is checked after the fetch. A tag can be moved; the
//    commit id is a hash of the tree, so what is compiled is the source this file was written
//    against, or the build stops.
//  - One static file: BUILD_SHARED_LIBS=OFF and a static C++ runtime. whisper.cpp builds its own
//    ggml, and nativeLibraryDir is flat: a libggml.so of its own would replace llama.cpp's there
//    (BUILDING-ENGINES.md, gotcha 1). The staged file may need only what every process can load
//    (libc, libm, libdl), and that is checked.
//  - No OpenMP (it would be one more shared library, or a second copy of the one llama.cpp
//    ships), no GGML_NATIVE (the build machine's CPU is not the phone's), no GPU backend.
//  - For the app's minSdk, as a PIE with 16 KB-aligned LOAD segments, stripped, and checked with
//    the LiteRT-LM staging's checkElf, like the engine tether.
//
// Output: <out>/<abi>/libwhisper_server.so, which build.gradle.kts adds to the APK's jniLibs. The
// name fits the lib*.so pattern Android requires before it will install a file into
// nativeLibraryDir. Nothing else in <out> is touched. The source checkout goes in the cache
// (<out>/../cache unless --cache says otherwise) and the CMake build directories in <out>/..;
// both are reused, so a run with nothing to do costs well under a second.
//
// Usage (Gradle runs this; see stageWhisperServer in composeApp/build.gradle.kts):
//   node scripts/stage-whisper-server.mjs --ndk <ndk dir> --out <dir> [--abis arm64-v8a,x86_64]
//                                         [--cmake <cmake>] [--ninja <ninja>] [--cache <dir>]
//                                         [--source <whisper.cpp checkout>]
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import {
  copyFileSync, existsSync, mkdirSync, readFileSync, realpathSync, renameSync, rmSync, statSync, writeFileSync,
} from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { checkElf, MIN_SDK, ndkToolchain, readDynamic } from './stage-litert-lm-engine.mjs'

// ── Pinned source ────────────────────────────────────────────────────────────────────────
// The release the daemon's desktop install uses (WHISPER_BUILD in turbollm/src/voice/
// whisper-binary.ts), so the app and the desktop speak to the same server. Bumping it = change
// the tag and the commit together (`git ls-remote https://github.com/ggml-org/whisper.cpp
// refs/tags/<tag>`), rebuild, and run the server once: its command line and its /inference and
// /health routes are what the daemon depends on (turbollm/src/voice/whisper-server.ts).
export const WHISPER_REPO = 'https://github.com/ggml-org/whisper.cpp'
export const WHISPER_TAG = 'b5454'
export const WHISPER_COMMIT = 'd1be6fde11ac6e0407606b4e42fe72d34add8037'

export const SERVER = 'libwhisper_server.so'
export const ABIS = {
  'arm64-v8a': { elfMachine: 0xb7 }, // EM_AARCH64
  x86_64: { elfMachine: 0x3e }, // EM_X86_64
}

// What the staged file may need at run time: bionic's own libraries, which every process has.
// Anything else would have to be shipped beside it, in a folder it shares with every other engine.
export const ALLOWED_NEEDED = ['libc.so', 'libm.so', 'libdl.so']

/** The CMake configure arguments for one ABI, after `-S <source> -B <build dir>`. Exported so the
 *  test can pin the ones that decide what kind of file comes out. */
export function cmakeArgs(abi, { toolchain, ninja }) {
  return [
    '-G', 'Ninja',
    `-DCMAKE_MAKE_PROGRAM=${ninja}`,
    `-DCMAKE_TOOLCHAIN_FILE=${toolchain}`,
    `-DANDROID_ABI=${abi}`,
    `-DANDROID_PLATFORM=android-${MIN_SDK}`,
    '-DANDROID_STL=c++_static',
    '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON',
    '-DCMAKE_BUILD_TYPE=Release',
    '-DBUILD_SHARED_LIBS=OFF',
    '-DGGML_OPENMP=OFF',
    '-DGGML_NATIVE=OFF',
    '-DWHISPER_BUILD_TESTS=OFF',
    '-DWHISPER_BUILD_EXAMPLES=ON',
    '-DWHISPER_BUILD_SERVER=ON',
  ]
}

/** What a build depends on, as one string: when it matches the stamp left beside a staged file,
 *  that file is still what this script would build. */
export function buildStamp(abi, { commit, ndk, script }) {
  const flags = cmakeArgs(abi, { toolchain: '<toolchain>', ninja: '<ninja>' }).join(' ')
  return createHash('sha256').update([commit, ndk, script, flags].join('\n')).digest('hex')
}

function parseArgs(argv) {
  const out = { abis: Object.keys(ABIS) }
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i]
    const v = argv[i + 1]
    if (k === '--ndk') out.ndk = v
    else if (k === '--out') out.out = v
    else if (k === '--abis') out.abis = v.split(',').map((s) => s.trim()).filter(Boolean)
    else if (k === '--cmake') out.cmake = v
    else if (k === '--ninja') out.ninja = v
    else if (k === '--cache') out.cache = v
    else if (k === '--source') out.source = v
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

function findTool(explicit, name) {
  if (explicit && existsSync(explicit)) return explicit
  return process.platform === 'win32' ? `${name}.exe` : name // fall back to PATH
}

function git(args, cwd) {
  return execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
}

/** The commit a checkout is at, or null when `dir` is not a git checkout (or there is no git). */
export function checkoutCommit(dir) {
  try {
    return git(['rev-parse', 'HEAD'], dir)
  } catch {
    return null
  }
}

// ── Source (cached, commit-pinned) ───────────────────────────────────────────────────────
// A shallow clone of the tag, then the commit it gave is compared with the pinned one. Cloned
// into a sibling and renamed once checked, so an interrupted or mismatching fetch never leaves a
// directory a later run would take for the pinned source.
function fetchSource(cacheDir) {
  const dest = join(cacheDir, `whisper.cpp-${WHISPER_COMMIT.slice(0, 12)}`)
  if (checkoutCommit(dest) === WHISPER_COMMIT) return dest
  rmSync(dest, { recursive: true, force: true })
  const tmp = `${dest}.tmp`
  rmSync(tmp, { recursive: true, force: true })
  mkdirSync(cacheDir, { recursive: true })
  console.log(`fetching whisper.cpp ${WHISPER_TAG}…`)
  try {
    execFileSync('git', [
      // The clone is read, never committed to: no line-ending rewriting on Windows, and no advice
      // about the detached HEAD a tag checkout always is.
      '-c', 'core.autocrlf=false', '-c', 'advice.detachedHead=false',
      'clone', '--quiet', '--depth', '1', '--branch', WHISPER_TAG, WHISPER_REPO, tmp,
    ], { stdio: ['ignore', 'inherit', 'inherit'] })
  } catch {
    fail(
      `could not fetch whisper.cpp ${WHISPER_TAG} from ${WHISPER_REPO} (git's message is above). ` +
        'The first build needs git and network access; --source <dir> builds from a checkout you already have, ' +
        'and -PturbollmWhisperServer=false builds the app without speech-to-text.',
    )
  }
  const got = checkoutCommit(tmp)
  if (got !== WHISPER_COMMIT) {
    rmSync(tmp, { recursive: true, force: true })
    fail(`whisper.cpp tag ${WHISPER_TAG} is at commit ${got}, not the pinned ${WHISPER_COMMIT}: the tag has moved, or this is not the repository it was pinned from`)
  }
  renameSync(tmp, dest)
  return dest
}

function build(abi, source, opts) {
  const toolchain = join(opts.ndk, 'build', 'cmake', 'android.toolchain.cmake')
  if (!existsSync(toolchain)) fail(`no NDK toolchain at ${toolchain}`)
  const cmake = findTool(opts.cmake, 'cmake')
  const ninja = findTool(opts.ninja, 'ninja')
  const buildDir = join(opts.out, '..', `cmake-${abi}`)
  console.log(`building ${SERVER} for ${abi}…`)
  try {
    // CMake's own chatter (a screenful per ABI) is kept out of the Gradle log; a failure prints it.
    execFileSync(cmake, ['-S', source, '-B', buildDir, ...cmakeArgs(abi, { toolchain, ninja })], { stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' })
  } catch (e) {
    fail(`configuring whisper.cpp for ${abi} failed:\n${e.stdout ?? ''}${e.stderr ?? e.message}`)
  }
  try {
    execFileSync(cmake, ['--build', buildDir, '--target', 'whisper-server'], { stdio: ['ignore', 'inherit', 'inherit'] })
  } catch {
    fail(`building whisper-server for ${abi} failed (the compiler's or linker's message is above)`)
  }
  const built = join(buildDir, 'bin', 'whisper-server')
  if (!existsSync(built)) fail(`the build produced no ${built}`)
  return built
}

/** Fails unless `path` is what may go into nativeLibraryDir as the server: the right
 *  architecture, 16 KB-aligned, and needing nothing but bionic. */
export function checkServer(path, abi) {
  checkElf(path, ABIS[abi].elfMachine)
  const needed = readDynamic(readFileSync(path), path).needed.map((n) => n.name)
  const extra = needed.filter((n) => !ALLOWED_NEEDED.includes(n))
  if (extra.length) {
    throw new Error(`${path} needs ${extra.join(', ')}: it must be one self-contained file, since a library shipped beside it would share nativeLibraryDir with llama.cpp's`)
  }
}

function main() {
  const opts = parseArgs(process.argv.slice(2))
  const unknown = opts.abis.filter((abi) => !ABIS[abi])
  if (unknown.length) fail(`no whisper-server build for ABI ${unknown.join(', ')} (have: ${Object.keys(ABIS).join(', ')})`)
  const { clang } = ndkToolchain(opts.ndk)
  const strip = join(dirname(clang), process.platform === 'win32' ? 'llvm-strip.exe' : 'llvm-strip')
  const script = createHash('sha256').update(readFileSync(fileURLToPath(import.meta.url))).digest('hex')

  // A checkout of the user's own is built as it is (an offline machine, a patch being tried): its
  // commit goes into the stamp, so switching back to the pinned source rebuilds.
  let source = null
  const sourceCommit = opts.source ? (checkoutCommit(opts.source) ?? 'unversioned') : WHISPER_COMMIT
  if (opts.source && !existsSync(join(opts.source, 'CMakeLists.txt'))) fail(`--source ${opts.source} is not a whisper.cpp checkout (no CMakeLists.txt)`)
  if (opts.source && sourceCommit !== WHISPER_COMMIT) console.warn(`WARNING: building whisper-server from ${opts.source} at ${sourceCommit}, not the pinned ${WHISPER_TAG} (${WHISPER_COMMIT})`)

  for (const abi of opts.abis) {
    const dir = join(opts.out, abi)
    const staged = join(dir, SERVER)
    const stampFile = join(opts.out, '..', `whisper-server-${abi}.stamp`)
    const stamp = buildStamp(abi, { commit: sourceCommit, ndk: opts.ndk, script })
    if (existsSync(staged) && existsSync(stampFile) && readFileSync(stampFile, 'utf8').trim() === stamp) {
      console.log(`${SERVER} for ${abi} is up to date`)
      continue
    }
    source ??= opts.source ?? fetchSource(opts.cache)
    const built = build(abi, source, opts)
    mkdirSync(dir, { recursive: true })
    // Stripped and checked beside its final name, and renamed once it has passed: a failed build
    // leaves no server the APK would carry, and the packaging check in build.gradle.kts stops it.
    const tmp = `${staged}.tmp`
    rmSync(tmp, { force: true })
    copyFileSync(built, tmp)
    try {
      execFileSync(strip, ['--strip-all', tmp], { stdio: ['ignore', 'inherit', 'inherit'] })
    } catch {
      fail(`stripping ${SERVER} for ${abi} failed (llvm-strip's message is above)`)
    }
    try {
      checkServer(tmp, abi)
    } catch (e) {
      rmSync(tmp, { force: true })
      fail(e instanceof Error ? e.message : String(e))
    }
    renameSync(tmp, staged)
    writeFileSync(stampFile, `${stamp}\n`)
    console.log(`staged ${SERVER} for ${abi}: ${(statSync(staged).size / 1e6).toFixed(1)} MB`)
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
