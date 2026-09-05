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
//   daemon/package.json                     {"type":"module"} — without it Node reads the
//                                           ESM bundle as CommonJS and dies on `import`
//   daemon/dist/…                           the bundle + android-preload.cjs + webdist/
//   daemon/node_modules/sql.js/…            NOT bundled on purpose: sql.js resolves its own
//                                           .wasm relative to its package dir at runtime
//
// Usage: node scripts/stage-daemon-assets.mjs [path-to-turbollm-checkout]
import { cpSync, existsSync, mkdirSync, rmSync, writeFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const turbollm = resolve(process.argv[2] ?? join(appRoot, '..', 'turbollm'))
const assets = join(appRoot, 'composeApp', 'src', 'androidMain', 'assets', 'daemon')

const bundle = join(turbollm, 'dist-android')
const webdist = join(turbollm, 'src', 'webdist')
const preload = join(turbollm, 'src', 'util', 'android-preload.cjs')
// Only the Node build of sql.js. The package also carries asm.js and browser variants plus
// debug builds — 24 MB in total, against ~1 MB for the two files actually loaded here.
const sqlJs = join(turbollm, 'node_modules', 'sql.js')

for (const [label, p] of [['android bundle (run: npx tsup --config tsup.android.config.ts)', bundle], ['web UI', webdist], ['android preload', preload], ['sql.js', sqlJs]]) {
  if (!existsSync(p)) {
    console.error(`FATAL: missing ${label}: ${p}`)
    process.exit(1)
  }
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
// and once extracted to filesDir there is no other one above it.
writeFileSync(join(assets, 'package.json'), '{"type":"module"}\n')

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
