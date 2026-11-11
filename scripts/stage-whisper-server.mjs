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
// With --vulkan it also builds the GPU server, <out>/arm64-v8a/libwhisper_server_vk.so: the same program with ggml's
// Vulkan backend linked in, which the daemon runs instead when "Use the GPU" is on in Settings → Voice (and goes back
// to the CPU one if it cannot start). A second file, not a flag on the first, for the reason the Vulkan llama.cpp
// engine is one (build-static-engine.sh): a Vulkan failure must not be able to take the CPU path with it. What that
// build adds:
//  - It is linked for API 28 (ggml-vulkan calls vkGetPhysicalDeviceFeatures2), so it cannot start below Android 9;
//    and for arm64-v8a only, the one ABI with a GPU worth using.
//  - Two more pinned inputs, fetched by commit: Khronos' Vulkan-Headers (the NDK has Vulkan's C header, not the C++
//    one ggml-vulkan includes) and SPIRV-Headers, which ggml's CMake asks for.
//  - A patch to ggml-vulkan.cpp (engines/whisper/vulkan-pre-1.2-devices.patch), the one this project's llama.cpp
//    fork carries: without it the backend crashes on phones whose driver is below Vulkan 1.2.
//  - A host C++ compiler (gcc or clang): ggml compiles its shaders with a program it builds for the build machine.
//    Gradle asks for the GPU server in every build (-PturbollmWhisperVulkan=false leaves it out), so a machine
//    without one, which a Windows machine usually is, builds it once under WSL into the same folder: the stamp names
//    the NDK by its version, not its path, so the Windows build then finds that file up to date.
//
// Usage (Gradle runs this; see stageWhisperServer in composeApp/build.gradle.kts):
//   node scripts/stage-whisper-server.mjs --ndk <ndk dir> --out <dir> [--abis arm64-v8a,x86_64] [--vulkan]
//                                         [--cmake <cmake>] [--ninja <ninja>] [--cache <dir>]
//                                         [--source <whisper.cpp checkout>]
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import {
  copyFileSync, existsSync, mkdirSync, readFileSync, realpathSync, renameSync, rmSync, statSync, writeFileSync,
} from 'node:fs'
import { basename, dirname, join } from 'node:path'
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

// ── The GPU (Vulkan) server ──────────────────────────────────────────────────────────────
export const SERVER_GPU = 'libwhisper_server_vk.so'
export const GPU_ABIS = ['arm64-v8a']
/** ggml-vulkan needs vkGetPhysicalDeviceFeatures2, which the NDK only has from API 28. */
export const GPU_MIN_SDK = 28
/** The GPU server also needs the system's Vulkan loader, which every device with Vulkan has. */
export const ALLOWED_NEEDED_GPU = [...ALLOWED_NEEDED, 'libvulkan.so']
/** Khronos' headers, by commit (Vulkan-Headers v1.4.368 and the SPIRV-Headers of the same week). Bump them together
 *  with whisper.cpp when its ggml asks for newer ones. */
export const KHRONOS = [
  { name: 'Vulkan-Headers', repo: 'https://github.com/KhronosGroup/Vulkan-Headers', commit: '88487f687f3100ebe1d3559a8a34d971ee85b977' },
  { name: 'SPIRV-Headers', repo: 'https://github.com/KhronosGroup/SPIRV-Headers', commit: '6efe5ae6b7c6ad29da3f2cc3f164b9c3d72b78b1' },
]
export const GPU_PATCH = fileURLToPath(new URL('../engines/whisper/vulkan-pre-1.2-devices.patch', import.meta.url))

/** The CMake configure arguments for one ABI, after `-S <source> -B <build dir>`. Exported so the
 *  test can pin the ones that decide what kind of file comes out. `gpu` ({ khronos, glslc }: the
 *  folder Khronos' headers are installed in, and the NDK's shader compiler) makes it the Vulkan
 *  build. */
export function cmakeArgs(abi, { toolchain, ninja, gpu }) {
  return [
    '-G', 'Ninja',
    `-DCMAKE_MAKE_PROGRAM=${ninja}`,
    `-DCMAKE_TOOLCHAIN_FILE=${toolchain}`,
    `-DANDROID_ABI=${abi}`,
    `-DANDROID_PLATFORM=android-${gpu ? GPU_MIN_SDK : MIN_SDK}`,
    '-DANDROID_STL=c++_static',
    '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON',
    '-DCMAKE_BUILD_TYPE=Release',
    '-DBUILD_SHARED_LIBS=OFF',
    '-DGGML_OPENMP=OFF',
    '-DGGML_NATIVE=OFF',
    '-DWHISPER_BUILD_TESTS=OFF',
    '-DWHISPER_BUILD_EXAMPLES=ON',
    '-DWHISPER_BUILD_SERVER=ON',
    ...(gpu ? [
      '-DGGML_VULKAN=ON',
      // The backend is linked in, not loaded at run time: a loadable libggml-vulkan.so is what
      // killed the CPU engine on a Mali phone by merely being in the folder (build-static-engine.sh).
      '-DGGML_BACKEND_DL=OFF',
      // The headers are outside the NDK's sysroot, where a cross build does not look by default.
      `-DCMAKE_PREFIX_PATH=${gpu.khronos}`,
      '-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH',
      '-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH',
      `-DVulkan_INCLUDE_DIR=${join(gpu.khronos, 'include')}`,
      `-DVulkan_GLSLC_EXECUTABLE=${gpu.glslc}`,
    ] : []),
  ]
}

/** What a build depends on, as one string: when it matches the stamp left beside a staged file,
 *  that file is still what this script would build. */
export function buildStamp(abi, { commit, ndk, script, gpu }) {
  const flags = cmakeArgs(abi, { toolchain: '<toolchain>', ninja: '<ninja>', gpu: gpu ? { khronos: '<khronos>', glslc: '<glslc>' } : undefined }).join(' ')
  // The GPU build is also made of the patch it applies and the headers it is compiled against.
  const more = gpu ? [gpu.patch, ...KHRONOS.map((k) => k.commit)] : []
  return createHash('sha256').update([commit, ndk, script, flags, ...more].join('\n')).digest('hex')
}

/** The NDK's version (`Pkg.Revision` in its source.properties), or its path where that cannot be read. What a
 *  build is stamped with: the same NDK is one path under WSL and another on Windows, and a file built with it in
 *  either is the same file. */
export function ndkRevision(ndk) {
  try {
    const revision = /^Pkg\.Revision\s*=\s*(\S+)/m.exec(readFileSync(join(ndk, 'source.properties'), 'utf8'))?.[1]
    if (revision) return revision
  } catch {
    /* not an NDK with one: the path will do */
  }
  return ndk
}

/** What to do about a GPU server that could not be built. */
function gpuHelp(abi) {
  return [
    `${SERVER_GPU} is part of every build. Building it needs a host C++ compiler on PATH (gcc or clang: ggml compiles its`,
    'shader generator for this machine) and github.com, for Khronos\' headers.',
    'On a machine without one (Windows, usually), build it once under WSL into this same folder, with the same NDK',
    'version, and this build will find it up to date:',
    `  node scripts/stage-whisper-server.mjs --vulkan --abis ${abi} --ndk <the Linux NDK> --out <this --out, as WSL sees it>`,
    '-PturbollmWhisperVulkan=false builds the app without it: speech-to-text then runs on the CPU only.',
  ].join('\n')
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
    else if (k === '--vulkan') { out.vulkan = true; continue } // takes no value
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

// ── Khronos' headers, for the GPU build (cached, commit-pinned) ───────────────────────────
// Each is fetched by its commit and installed, by its own CMake, into one folder the build is
// pointed at: ggml's CMake finds SPIRV-Headers as a package, which only an install provides.
function khronosHeaders(cacheDir, cmake) {
  const prefix = join(cacheDir, `khronos-${createHash('sha256').update(KHRONOS.map((k) => k.commit).join()).digest('hex').slice(0, 12)}`)
  if (existsSync(join(prefix, 'include', 'vulkan', 'vulkan.hpp')) && existsSync(join(prefix, '.complete'))) return prefix
  rmSync(prefix, { recursive: true, force: true })
  for (const { name, repo, commit } of KHRONOS) {
    const src = join(cacheDir, `${name}-${commit.slice(0, 12)}`)
    if (checkoutCommit(src) !== commit) {
      rmSync(src, { recursive: true, force: true })
      mkdirSync(src, { recursive: true })
      console.log(`fetching ${name}…`)
      try {
        git(['init', '--quiet'], src)
        git(['-c', 'core.autocrlf=false', 'fetch', '--quiet', '--depth', '1', repo, commit], src)
        git(['-c', 'advice.detachedHead=false', 'checkout', '--quiet', 'FETCH_HEAD'], src)
      } catch (e) {
        rmSync(src, { recursive: true, force: true })
        throw new Error(`could not fetch ${name} ${commit} from ${repo}: ${String(e.stderr ?? e.message).trim()}`)
      }
      if (checkoutCommit(src) !== commit) throw new Error(`${name}: fetched ${checkoutCommit(src)}, not the pinned ${commit}`)
    }
    try {
      execFileSync(cmake, ['-S', src, '-B', join(cacheDir, `${name}-build`), `-DCMAKE_INSTALL_PREFIX=${prefix}`], { stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' })
      execFileSync(cmake, ['--install', join(cacheDir, `${name}-build`)], { stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' })
    } catch (e) {
      throw new Error(`installing ${name} failed:\n${e.stdout ?? ''}${e.stderr ?? e.message}`)
    }
  }
  if (!existsSync(join(prefix, 'include', 'vulkan', 'vulkan.hpp'))) throw new Error(`Vulkan-Headers installed no vulkan.hpp under ${prefix}`)
  writeFileSync(join(prefix, '.complete'), '')
  return prefix
}

/** Applies the GPU build's patch to `source`, unless it is already in. The CPU build compiles none of what it
 *  touches (ggml-vulkan.cpp), so the one checkout serves both. */
function applyGpuPatch(source) {
  const apply = (extra) => execFileSync('git', ['apply', ...extra, GPU_PATCH], { cwd: source, stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' })
  try {
    apply(['--reverse', '--check'])
    return // already applied
  } catch {
    /* not applied yet */
  }
  try {
    apply([])
  } catch (e) {
    throw new Error(`${GPU_PATCH} does not apply to ${source}: ${String(e.stderr ?? e.message).trim()}\nIt was written against whisper.cpp ${WHISPER_TAG}; a bump has to carry it forward, or drop it once upstream has the fix.`)
  }
}

/** Where the GPU build is told to find its headers and its shader compiler. */
function gpuInputs(opts, cmake) {
  const { clang } = ndkToolchain(opts.ndk)
  const hostTag = basename(dirname(dirname(clang))) // linux-x86_64, darwin-x86_64
  const glslc = join(opts.ndk, 'shader-tools', hostTag, process.platform === 'win32' ? 'glslc.exe' : 'glslc')
  if (!existsSync(glslc)) throw new Error(`the NDK has no shader compiler at ${glslc}`)
  return { khronos: khronosHeaders(opts.cache, cmake), glslc }
}

function build(abi, source, opts, { gpu = false } = {}) {
  const toolchain = join(opts.ndk, 'build', 'cmake', 'android.toolchain.cmake')
  if (!existsSync(toolchain)) throw new Error(`no NDK toolchain at ${toolchain}`)
  const cmake = findTool(opts.cmake, 'cmake')
  const ninja = findTool(opts.ninja, 'ninja')
  const buildDir = join(opts.out, '..', gpu ? `cmake-${abi}-vk` : `cmake-${abi}`)
  console.log(`building ${gpu ? SERVER_GPU : SERVER} for ${abi}…`)
  const args = cmakeArgs(abi, { toolchain, ninja, gpu: gpu ? gpuInputs(opts, cmake) : undefined })
  try {
    // CMake's own chatter (a screenful per ABI) is kept out of the Gradle log; a failure prints it.
    execFileSync(cmake, ['-S', source, '-B', buildDir, ...args], { stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' })
  } catch (e) {
    const hint = gpu ? '\nThe Vulkan build compiles its shader generator for this machine, so it needs a host C++ compiler (gcc or clang) on PATH.' : ''
    throw new Error(`configuring whisper.cpp for ${abi} failed:\n${e.stdout ?? ''}${e.stderr ?? e.message}${hint}`)
  }
  try {
    // The Vulkan build's four hundred steps are mostly one shader each: their names are not worth a Gradle log, so
    // its output is kept and only the end of it shown, when it fails.
    execFileSync(cmake, ['--build', buildDir, '--target', 'whisper-server'], {
      stdio: ['ignore', gpu ? 'pipe' : 'inherit', 'inherit'], encoding: 'utf8', maxBuffer: 256 * 1024 * 1024,
    })
  } catch (e) {
    const said = gpu ? `:\n${String(e.stdout ?? '').trimEnd().split('\n').slice(-30).join('\n')}` : " (the compiler's or linker's message is above)"
    throw new Error(`building whisper-server for ${abi} failed${said}`)
  }
  const built = join(buildDir, 'bin', 'whisper-server')
  if (!existsSync(built)) throw new Error(`the build produced no ${built}`)
  return built
}

/** Fails unless `path` is what may go into nativeLibraryDir as the server: the right
 *  architecture, 16 KB-aligned, and needing nothing but bionic. */
export function checkServer(path, abi, { gpu = false } = {}) {
  checkElf(path, ABIS[abi].elfMachine)
  const needed = readDynamic(readFileSync(path), path).needed.map((n) => n.name)
  const extra = needed.filter((n) => !(gpu ? ALLOWED_NEEDED_GPU : ALLOWED_NEEDED).includes(n))
  if (extra.length) {
    throw new Error(`${path} needs ${extra.join(', ')}: it must be one self-contained file, since a library shipped beside it would share nativeLibraryDir with llama.cpp's`)
  }
  if (gpu && !needed.includes('libvulkan.so')) throw new Error(`${path} does not use libvulkan.so: the Vulkan backend was not linked in`)
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

  const stage = (abi, { gpu }) => {
    const name = gpu ? SERVER_GPU : SERVER
    const dir = join(opts.out, abi)
    const staged = join(dir, name)
    const stampFile = join(opts.out, '..', `whisper-server-${abi}${gpu ? '-vk' : ''}.stamp`)
    const patch = gpu ? createHash('sha256').update(readFileSync(GPU_PATCH)).digest('hex') : ''
    const stamp = buildStamp(abi, { commit: sourceCommit, ndk: ndkRevision(opts.ndk), script, gpu: gpu ? { patch } : undefined })
    if (existsSync(staged) && existsSync(stampFile) && readFileSync(stampFile, 'utf8').trim() === stamp) {
      console.log(`${name} for ${abi} is up to date`)
      return
    }
    source ??= opts.source ?? fetchSource(opts.cache)
    let built
    try {
      if (gpu) applyGpuPatch(source)
      built = build(abi, source, opts, { gpu })
    } catch (e) {
      throw gpu ? new Error(`${e instanceof Error ? e.message : String(e)}\n\n${gpuHelp(abi)}`) : e
    }
    mkdirSync(dir, { recursive: true })
    // Stripped and checked beside its final name, and renamed once it has passed: a failed build
    // leaves no server the APK would carry, and the packaging check in build.gradle.kts stops it.
    const tmp = `${staged}.tmp`
    rmSync(tmp, { force: true })
    copyFileSync(built, tmp)
    try {
      execFileSync(strip, ['--strip-all', tmp], { stdio: ['ignore', 'inherit', 'inherit'] })
    } catch {
      fail(`stripping ${name} for ${abi} failed (llvm-strip's message is above)`)
    }
    try {
      checkServer(tmp, abi, { gpu })
    } catch (e) {
      rmSync(tmp, { force: true })
      fail(e instanceof Error ? e.message : String(e))
    }
    renameSync(tmp, staged)
    writeFileSync(stampFile, `${stamp}\n`)
    console.log(`staged ${name} for ${abi}: ${(statSync(staged).size / 1e6).toFixed(1)} MB`)
  }

  for (const abi of opts.abis) stage(abi, { gpu: false })
  // The GPU server, for the ABIs it exists for. Without --vulkan one staged earlier is removed: the APK packages
  // whatever is in this folder, and a build that did not ask for it must not carry it.
  for (const abi of opts.abis.filter((a) => GPU_ABIS.includes(a))) {
    if (opts.vulkan) stage(abi, { gpu: true })
    else rmSync(join(opts.out, abi, SERVER_GPU), { force: true })
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
