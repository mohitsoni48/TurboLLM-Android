#!/usr/bin/env node
// Fetch the embedded Node runtime (nodejs-mobile) into composeApp/src/androidMain/cpp/libnode/,
// where CMake (native-lib) and jniLibs expect it. That directory is gitignored, so before this
// a fresh clone could not build at all, and nobody could say where the files on disk came from
// (PROVENANCE.md).
//
// Source: the digidem fork of nodejs-mobile, release v18.20.4 — the same Node 18.20.4 the app has
// always embedded, rebuilt with 16 KB-aligned LOAD segments. The upstream nodejs-mobile build is
// 4 KB-aligned, which Android 16 flags on every launch of a debuggable build and 16 KB-page
// devices cannot load at all; upstream's 16 KB re-release (nodejs-mobile/nodejs-mobile#155) never
// shipped. The archive is pinned by SHA-256 and every libnode.so is checked for alignment.
//
// Usage: node scripts/fetch-libnode.mjs [--force]
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const URL_ = 'https://github.com/digidem/nodejs-mobile/releases/download/v18.20.4/nodejs-mobile-v18.20.4-android.zip'
const SHA256 = '16f70c90946f24c45858fdc0943b8b8d1c0a70c153d7fd19f9a55442d1f38e26'

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const dest = join(appRoot, 'composeApp', 'src', 'androidMain', 'cpp', 'libnode')
const stamp = join(dest, '.source')

function fail(msg) {
  console.error(`FATAL: ${msg}`)
  process.exit(1)
}

function loadAlignOk(path) {
  const b = readFileSync(path)
  const phoff = Number(b.readBigUInt64LE(32))
  const phentsize = b.readUInt16LE(54)
  for (let i = 0; i < b.readUInt16LE(56); i++) {
    const o = phoff + i * phentsize
    if (b.readUInt32LE(o) === 1 && Number(b.readBigUInt64LE(o + 48)) < 16384) return false
  }
  return true
}

if (!process.argv.includes('--force') && existsSync(stamp) && readFileSync(stamp, 'utf8').trim() === SHA256) {
  console.log('libnode already present (digidem nodejs-mobile v18.20.4)')
  process.exit(0)
}

console.log(`downloading ${URL_}…`)
const res = await fetch(URL_)
if (!res.ok) fail(`download failed: HTTP ${res.status}`)
const zip = Buffer.from(await res.arrayBuffer())
const got = createHash('sha256').update(zip).digest('hex')
if (got !== SHA256) fail(`sha256 ${got} does not match the pinned ${SHA256}`)

rmSync(dest, { recursive: true, force: true })
mkdirSync(dest, { recursive: true })
const tmp = join(dest, '..', 'libnode.zip')
writeFileSync(tmp, zip)
try {
  // tar reads zip archives on Windows 10+ (bsdtar) and macOS; Linux tar does not, so use unzip there.
  if (process.platform === 'linux') execFileSync('unzip', ['-q', tmp, '-d', dest], { stdio: 'inherit' })
  else execFileSync('tar', ['-xf', tmp, '-C', dest], { stdio: 'inherit' })
} finally {
  rmSync(tmp, { force: true })
}
for (const abi of ['arm64-v8a', 'x86_64']) {
  const so = join(dest, 'bin', abi, 'libnode.so')
  if (!existsSync(so)) fail(`archive has no bin/${abi}/libnode.so`)
  if (!loadAlignOk(so)) fail(`${so} is not 16 KB-aligned`)
}
writeFileSync(stamp, `${SHA256}\n`)
console.log(`libnode installed into ${dest}`)
