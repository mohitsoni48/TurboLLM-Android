#!/usr/bin/env node
// Assemble the self-contained daemon payload that ships INSIDE the APK.
//
// Why this exists: the app must work for someone who installs it from the Play Store, with
// nothing pushed to the device by hand. Before this, `MainActivity.pullDaemonFromSdcard()`
// copied the daemon off /sdcard — a dev-only helper (its own comment said so) that also
// cannot work any more, since the app declares no storage permission. A fresh install
// therefore crashed at boot with:
//
//     Error [ERR_MODULE_NOT_FOUND]: Cannot find package '@hono/node-server'
//         imported from /data/data/com.turbollm.android/files/daemon/dist/cli.js
//
// ...because the npm build deliberately leaves `dependencies` external for npm to install,
// and a phone has no `npm install`. Shipping the production tree instead is not viable: it
// is 362 MB, ~220 MB of which is the pi coding-agent stack behind the Code feature that is
// cut from Android entirely. So the daemon is rebuilt for Android with its dependencies
// bundled (turbollm/tsup.android.config.ts, ~6 MB) and staged here.
//
// Layout produced (mirrors what Node expects once extracted to filesDir/daemon):
//   daemon/package.json                     {"type":"module","version":…} — without the type
//                                           Node reads the ESM bundle as CommonJS and dies on
//                                           `import`; the version is what the daemon reports
//   daemon/dist/…                           the bundle + android-preload.cjs + webdist/ (with
//                                           build-inputs.json, the stamp of the web/ files it
//                                           was built from)
//   daemon/node_modules/sql.js/…            NOT bundled on purpose: sql.js resolves its own
//                                           .wasm relative to its package dir at runtime
//
// Usage: node scripts/stage-daemon-assets.mjs [path-to-turbollm-checkout]
import { execSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import {
  cpSync, existsSync, lstatSync, mkdirSync, realpathSync, rmSync, writeFileSync, readFileSync, readdirSync, statSync,
} from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// ── The web UI ────────────────────────────────────────────────────────────────────────────
//
// The APK carries turbollm/src/webdist, the web UI's build output, which nothing in git holds.
// Copied as found, an APK built after a pull or a branch switch paired the daemon it had just
// bundled with whatever web UI was last built in that checkout, with no sign of it anywhere.
// So the web UI is built again here whenever the files it is built from are not the ones it was
// last built from here: a hash of them is kept in webdist/build-inputs.json (a name without a
// leading dot, which AGP would leave out of the APK's assets). A build with nothing changed under
// web/ costs that hash, under 50 ms; one with a change costs a web build, 40-55 s.

/** The stamp in webdist: the web/ files the web UI there was built from. */
export const WEB_STAMP = 'build-inputs.json'
const STAMP_VERSION = 1

// What the web build reads is every file under web/ (Tailwind builds its CSS from the class names
// in all of them, tests included, and vite reads web/.env*), except its packages, which
// web/package-lock.json stands for; dot-directories (vite's and other caches); what the test runs
// write; and the files a desktop puts in folders it shows.
const NOT_INPUT_DIRS = new Set(['test-results', 'playwright-report', 'blob-report', 'coverage'])
const NOT_INPUT_FILES = new Set(['.DS_Store', 'Thumbs.db', 'desktop.ini'])

/** A SHA-256 over every web build input under [web]: its path relative to web/ with '/' between
 *  names, its length and its bytes, in the code-unit order of the paths, so Windows, macOS and
 *  Linux hash the same tree alike. A symlink to a file counts as that file; a dangling one, and a
 *  symlink to a directory, are left alone (the walk never follows one). On Windows, what a folder
 *  listing calls a link is any reparse point, which a OneDrive folder or file is too: lstat tells
 *  the links from those, and those count as the folder or file they are. A file or folder that goes
 *  away while the hash is taken (an editor's safe write moves the file it saves aside for a moment)
 *  is left out rather than failing the build: that only changes the hash, so the worst it can do is
 *  cost a web build. */
export function webInputs(web) {
  const files = []
  const walk = (dir, rel) => {
    let entries
    try {
      entries = readdirSync(dir, { withFileTypes: true })
    } catch (e) {
      if (rel && e.code === 'ENOENT') return
      throw e
    }
    for (const e of entries) {
      const path = join(dir, e.name)
      const name = rel ? `${rel}/${e.name}` : e.name
      // On Windows a listing types every reparse point as a link (libuv's scandir looks only at
      // FILE_ATTRIBUTE_REPARSE_POINT), and so a OneDrive folder or file (a cloud files placeholder),
      // a deduplicated file and the like say "link" too. Taken for links, every folder of sources in
      // a OneDrive-synced checkout was skipped and a change in one went unseen. lstat calls only
      // symlinks and junctions links and reports the rest as the folder or file they are. An entry
      // lstat cannot read is handled as a link, as before.
      let type = e
      if (e.isSymbolicLink()) {
        try {
          type = lstatSync(path)
        } catch {}
      }
      if (type.isSymbolicLink()) {
        let target
        try {
          target = statSync(path)
        } catch {
          continue
        }
        if (target.isFile()) files.push([name, path])
      } else if (type.isDirectory()) {
        if (e.name === 'node_modules' || e.name.startsWith('.') || (!rel && NOT_INPUT_DIRS.has(e.name))) continue
        walk(path, name)
      } else if (type.isFile() && !NOT_INPUT_FILES.has(e.name)) {
        files.push([name, path])
      }
    }
  }
  walk(web, '')
  files.sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
  const hash = createHash('sha256')
  let hashed = 0
  for (const [name, path] of files) {
    let bytes
    try {
      bytes = readFileSync(path)
    } catch (e) {
      if (e.code === 'ENOENT') continue
      throw e
    }
    hash.update(`${name}\0${bytes.length}\0`)
    hash.update(bytes)
    hashed++
  }
  return { hash: hash.digest('hex'), files: hashed }
}

/** Why the web UI in [webdist] is not the build of [inputs], or null when it is. */
function webStale(webdist, inputs) {
  if (!existsSync(join(webdist, 'index.html'))) return 'there is no web UI build'
  let stamp
  try {
    stamp = JSON.parse(readFileSync(join(webdist, WEB_STAMP), 'utf8'))
  } catch {
    return 'the web UI build there has no record of what it was built from'
  }
  if (stamp?.version !== STAMP_VERSION || stamp.inputs !== inputs.hash) {
    const then = stamp?.files ?? '?'
    return `web/ has changed since the web UI was last built here (${then} files then, ${inputs.files} now)`
  }
  return null
}

/** Why web/node_modules is not what web/package-lock.json asks for, or null when it is. npm
 *  records what it installed in node_modules/.package-lock.json; every version there must be the
 *  lockfile's, and every package the lockfile has must be there, but for an optional one (another
 *  platform's esbuild or rollup binary). A pulled lockfile change built with the old packages would
 *  ship a UI built from other packages under a stamp that names the new ones. */
export function webDepsProblem(web) {
  const nodeModules = join(web, 'node_modules')
  if (!existsSync(nodeModules)) return 'web/node_modules is missing'
  let locked, installed
  try {
    locked = JSON.parse(readFileSync(join(web, 'package-lock.json'), 'utf8')).packages ?? {}
  } catch {
    return 'web/package-lock.json cannot be read'
  }
  try {
    installed = JSON.parse(readFileSync(join(nodeModules, '.package-lock.json'), 'utf8')).packages ?? {}
  } catch {
    return 'web/node_modules has no record of what npm installed there (node_modules/.package-lock.json)'
  }
  for (const [key, want] of Object.entries(locked)) {
    if (!key.startsWith('node_modules/')) continue // web/ itself, or a linked folder
    const name = key.slice(key.lastIndexOf('node_modules/') + 'node_modules/'.length)
    const have = installed[key]
    if (!have) {
      if (want.optional || want.devOptional) continue
      return `${name} ${want.version} is in web/package-lock.json but not in web/node_modules`
    }
    if (have.version !== want.version) {
      return `web/node_modules has ${name} ${have.version}, web/package-lock.json ${want.version}`
    }
  }
  return null
}

// `npm run build` in web/: what TurboLLM's build:web runs once web/'s packages are in (`cd web &&
// npm ci && npm run build`), without the npm ci, which needs the network and whose job
// webDepsProblem has just checked. Through the shell, like `npx tsup` below: that is what finds
// npm.cmd on Windows, and the command holds no path that would need quoting.
function npmRunBuild(web) {
  execSync('npm run build', { cwd: web, stdio: 'inherit' })
}

/** Builds the web UI in [turbollm] (into src/webdist, where web/'s vite config puts it) unless it
 *  is the build of web/ as it is now, then stamps it. Answers whether it built. Throws, with what
 *  to do, when web/node_modules does not match its lockfile or the build fails. */
export function ensureWebUi(turbollm, { build = npmRunBuild, log = console.log } = {}) {
  const web = join(turbollm, 'web')
  const webdist = join(turbollm, 'src', 'webdist')
  if (!existsSync(join(web, 'package.json'))) throw new Error(`no web UI sources at ${web}`)
  const inputs = webInputs(web)
  const stale = webStale(webdist, inputs)
  if (!stale) {
    log(`web UI up to date (built from these ${inputs.files} files under web/)`)
    return false
  }
  const deps = webDepsProblem(web)
  if (deps) {
    throw new Error(
      `the web UI has to be built, as ${stale}, but ${deps}.\n` +
        `Install web/'s packages, then build the app again:\n  npm --prefix "${web}" ci`,
    )
  }
  log(`building the web UI: ${stale}…`)
  // Gone before the build starts, so an interrupted or failed build leaves no stamp vouching for it.
  rmSync(join(webdist, WEB_STAMP), { force: true })
  const index = join(webdist, 'index.html')
  const before = statSync(index, { throwIfNoEntry: false })
  try {
    build(web)
  } catch {
    throw new Error(`the web UI failed to build in ${web} (its output is above)`)
  }
  // The build has to have written index.html here: one whose output went elsewhere (web/'s vite
  // config moved its outDir, or its build script changed) would leave the old web UI in place,
  // and the stamp would then vouch for it. A new index.html shows by its mtime: a real web build
  // takes far longer than any file system's timestamp step (the inode can be the old file's).
  const after = statSync(index, { throwIfNoEntry: false })
  if (!after || (before && after.mtimeMs === before.mtimeMs && after.ino === before.ino)) {
    throw new Error(`the web UI build did not write ${index}`)
  }
  // The hash from before the build: a file changed while it ran makes the next build build again.
  const stamp = { version: STAMP_VERSION, inputs: inputs.hash, files: inputs.files }
  writeFileSync(join(webdist, WEB_STAMP), `${JSON.stringify(stamp)}\n`)
  return true
}

function main() {
  const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
  const turbollm = resolve(process.argv[2] ?? join(appRoot, '..', 'turbollm'))
  const assets = join(appRoot, 'composeApp', 'src', 'androidMain', 'assets', 'daemon')

  const bundle = join(turbollm, 'dist-android')
  const webdist = join(turbollm, 'src', 'webdist')
  const preload = join(turbollm, 'src', 'util', 'android-preload.cjs')
  // Only the Node build of sql.js. The package also carries asm.js and browser variants plus
  // debug builds — 24 MB in total, against ~1 MB for the two files actually loaded here.
  const sqlJs = join(turbollm, 'node_modules', 'sql.js')

  if (!existsSync(turbollm)) {
    console.error(`FATAL: no turbollm checkout at ${turbollm}\nPass its path: node scripts/stage-daemon-assets.mjs <path-to-turbollm>`)
    process.exit(1)
  }

  // Always rebuild the Android bundle rather than reusing whatever is on disk. It takes ~200 ms,
  // and the alternative is the failure mode this whole pipeline exists to prevent: an APK that
  // looks fine and silently ships a daemon older than the source. The web UI is rebuilt below
  // only when web/ has changed since its last build (ensureWebUi): it takes 40-55 s.
  console.log('building the Android daemon bundle…')
  try {
    execSync('npx tsup --config tsup.android.config.ts', { cwd: turbollm, stdio: 'inherit' })
  } catch {
    console.error(`FATAL: the Android daemon bundle failed to build in ${turbollm}`)
    process.exit(1)
  }

  for (const [label, p] of [
    ['android bundle', bundle],
    ['android preload', preload],
    ['sql.js (run: npm install in turbollm)', sqlJs],
  ]) {
    if (!existsSync(p)) {
      console.error(`FATAL: missing ${label}: ${p}`)
      process.exit(1)
    }
  }

  try {
    ensureWebUi(turbollm)
  } catch (e) {
    console.error(`FATAL: ${e.message}`)
    process.exit(1)
  }

  rmSync(assets, { recursive: true, force: true })
  mkdirSync(join(assets, 'dist'), { recursive: true })

  cpSync(bundle, join(assets, 'dist'), { recursive: true })
  cpSync(webdist, join(assets, 'dist', 'webdist'), { recursive: true })
  cpSync(preload, join(assets, 'dist', 'android-preload.cjs'))

  // sql.js's JS ends up bundled (the Android pass bundles everything), but its .wasm cannot be —
  // it is a binary the module loads at runtime via `__dirname + "/sql-wasm.wasm"`. The banner in
  // tsup.android.config.ts points `__dirname` at this dist/ directory, so the wasm goes here
  // rather than into a node_modules tree. Only the wasm is needed; the package's other 23 MB is
  // asm.js, browser and debug variants.
  cpSync(join(sqlJs, 'dist', 'sql-wasm.wasm'), join(assets, 'dist', 'sql-wasm.wasm'))

  // `ws` is reached through a real runtime `require('ws')` (registerTerminalWs), not a static
  // import, so esbuild cannot bundle it and it has to exist as a resolvable package — Node walks
  // up from dist/ and finds daemon/node_modules/. It is the ONLY such case that matters here:
  // the source has exactly three bare requires — 'net' (a builtin), 'node-pty' (native, and only
  // reached by the Code feature, which is cut from Android) and this one. `ws` itself has no
  // runtime dependencies, so copying the package alone is sufficient.
  const wsSrc = join(turbollm, 'node_modules', 'ws')
  if (!existsSync(wsSrc)) {
    console.error(`FATAL: missing ws: ${wsSrc}`)
    process.exit(1)
  }
  cpSync(wsSrc, join(assets, 'node_modules', 'ws'), { recursive: true })

  // The daemon bundle is ESM. Node decides that by walking up for the nearest package.json,
  // and once extracted to filesDir there is no other one above it. It is also where the daemon
  // reads its own version (cli.ts: `<dir of cli.js>/../package.json`) — without one it fell back
  // to '0.1.1' in the UI, telemetry, Turbo Link hello and the update check on every Android install.
  const { version } = JSON.parse(readFileSync(join(turbollm, 'package.json'), 'utf8'))
  if (typeof version !== 'string' || !version) {
    console.error(`FATAL: no version in ${join(turbollm, 'package.json')}`)
    process.exit(1)
  }
  writeFileSync(join(assets, 'package.json'), `${JSON.stringify({ type: 'module', version })}\n`)

  function du(dir) {
    let total = 0
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name)
      total += e.isDirectory() ? du(p) : statSync(p).size
    }
    return total
  }
  console.log(`staged daemon assets → ${assets}`)
  console.log(`payload: ${(du(assets) / 1e6).toFixed(1)} MB`)
}

// Run as a script; the test imports the web UI check without staging anything.
if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) main()
