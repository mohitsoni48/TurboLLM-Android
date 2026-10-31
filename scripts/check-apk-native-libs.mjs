#!/usr/bin/env node
// Checks that an APK carries the native libraries its engine flags promise, and none they leave out:
//   node scripts/check-apk-native-libs.mjs <apk> <profile>
// Profiles name the build flags (composeApp/build.gradle.kts):
//   default              no engine flags: the SDK, plus the CPU llama-server fallback and its tether (Option B)
//   no-spawned-engines   -PturbollmSpawnedEngines=false: the SDK alone
//   litert-lm-engine     -PturbollmLitertLmEngine=true: the default, plus the LiteRT-LM server opt-in
// Every shipped ABI is checked. It prints the APK's lib/ entries, then PASS, or FAIL and each problem (exit 1).
import { readFileSync, realpathSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

export const ABIS = ['arm64-v8a', 'x86_64']

const SDK_ENGINES = ['libturbollm_llama_base.so', 'liblitertlm_jni.so']
const SPAWNED_CPU_ENGINE = [
  'libllama_server.so', 'libllama-server-impl.so', 'libllama.so', 'libllama-common.so',
  'libggml.so', 'libggml-base.so', 'libggml-cpu.so', 'libmtmd.so', 'libomp.so',
]
const ENGINE_TETHER = 'libtether_exec.so'
const LITERT_LM_SERVER = ['liblitertlm_server.so', 'liblitert-lm.so', 'liblrtandr.so']
const VULKAN_ENGINE = 'libllama_server_vk.so'

export const PROFILES = {
  default: {
    required: [...SDK_ENGINES, ...SPAWNED_CPU_ENGINE, ENGINE_TETHER],
    forbidden: [...LITERT_LM_SERVER, VULKAN_ENGINE],
  },
  'no-spawned-engines': {
    required: SDK_ENGINES,
    forbidden: [...SPAWNED_CPU_ENGINE, ENGINE_TETHER, ...LITERT_LM_SERVER, VULKAN_ENGINE],
  },
  'litert-lm-engine': {
    required: [...SDK_ENGINES, ...SPAWNED_CPU_ENGINE, ENGINE_TETHER, ...LITERT_LM_SERVER],
    forbidden: [VULKAN_ENGINE],
  },
}

// One problem per ABI and library, required ones first; an empty list means the APK matches the profile.
export function nativeLibProblems(entryNames, profileName) {
  const profile = PROFILES[profileName]
  if (!profile) {
    throw new Error(`unknown profile "${profileName}"; known: ${Object.keys(PROFILES).join(', ')}`)
  }
  const packaged = new Set(entryNames)
  return ABIS.flatMap((abi) => {
    const has = (lib) => packaged.has(`lib/${abi}/${lib}`)
    return [
      ...profile.required.filter((lib) => !has(lib)).map((lib) => `${abi}: ${lib} is missing`),
      ...profile.forbidden.filter(has).map((lib) => `${abi}: ${lib} must not be packaged`),
    ]
  })
}

const END_OF_DIRECTORY = 0x06054b50
const DIRECTORY_ENTRY = 0x02014b50
const END_RECORD_SIZE = 22
const MAX_COMMENT = 0xffff

// The entry names of a zip (an APK is one), read from its central directory. Zip64 archives are refused.
export function zipEntryNames(zip) {
  const end = findEndOfDirectory(zip)
  const count = zip.readUInt16LE(end + 10)
  const directoryOffset = zip.readUInt32LE(end + 16)
  if (directoryOffset === 0xffffffff) throw new Error('zip64 archives are not supported')
  const names = []
  let at = directoryOffset
  for (let i = 0; i < count; i++) {
    if (zip.readUInt32LE(at) !== DIRECTORY_ENTRY) throw new Error(`not a zip: broken directory entry ${i}`)
    const nameLength = zip.readUInt16LE(at + 28)
    const skipped = zip.readUInt16LE(at + 30) + zip.readUInt16LE(at + 32)
    names.push(zip.toString('utf8', at + 46, at + 46 + nameLength))
    at += 46 + nameLength + skipped
  }
  return names
}

function findEndOfDirectory(zip) {
  const earliest = Math.max(0, zip.length - END_RECORD_SIZE - MAX_COMMENT)
  for (let at = zip.length - END_RECORD_SIZE; at >= earliest; at--) {
    if (zip.readUInt32LE(at) === END_OF_DIRECTORY) return at
  }
  throw new Error('not a zip: no end-of-directory record')
}

function main([apk, profile] = process.argv.slice(2)) {
  if (!apk || !profile) throw new Error(`usage: check-apk-native-libs.mjs <apk> <${Object.keys(PROFILES).join('|')}>`)
  const entries = zipEntryNames(readFileSync(apk))
  for (const entry of entries.filter((name) => name.startsWith('lib/')).sort()) console.log(entry)
  const problems = nativeLibProblems(entries, profile)
  if (problems.length === 0) {
    console.log(`PASS ${profile}`)
    return
  }
  console.error(`FAIL ${profile}\n${problems.join('\n')}`)
  process.exitCode = 1
}

// Run as a script; the test imports the functions above without reading any APK.
if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) {
  try {
    main()
  } catch (e) {
    console.error(`check-apk-native-libs: ${e instanceof Error ? e.message : String(e)}`)
    process.exitCode = 1
  }
}
