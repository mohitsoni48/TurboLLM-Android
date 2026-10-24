// Tests for the web UI step of scripts/stage-daemon-assets.mjs, which builds turbollm's web UI again whenever web/
// is not what it was last built from:
//   node --test scripts/stage-daemon-assets.test.mjs
// Everything runs on a fake turbollm checkout in a temp folder; nothing builds the real web UI. The tests of the whole
// script run it as Gradle's stageDaemonAssets does, from a copy in a temp app checkout (the script stages into the app
// checkout it sits in), against a checkout with a stand-in tsup and a web/ whose `npm run build` is a small node
// script. Those need npm and a POSIX shell for the stand-in tsup, so they are skipped on Windows.
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs, {
  chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, utimesSync, writeFileSync,
} from 'node:fs'
import { createHash } from 'node:crypto'
import { syncBuiltinESMExports } from 'node:module'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { after, test } from 'node:test'
import { fileURLToPath } from 'node:url'

import { WEB_STAMP, ensureWebUi, webDepsProblem, webInputs } from './stage-daemon-assets.mjs'

const script = fileURLToPath(new URL('./stage-daemon-assets.mjs', import.meta.url))
const work = mkdtempSync(join(tmpdir(), 'stage-daemon-assets-test-'))
after(() => rmSync(work, { recursive: true, force: true }))

function write(path, text) {
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, text)
}
const json = (path) => JSON.parse(readFileSync(path, 'utf8'))

// web/'s build does what vite does with emptyOutDir: it replaces src/webdist with its output, here an index.html
// holding web/src/app.txt, and it notes each run in builds.log. Each build dates its index.html after the last
// build's and after anything the test wrote. ensureWebUi tells a new index.html by its mtime, as a real build takes
// far longer than a file system's timestamp step; these take under a millisecond, and the new index.html can get the
// old one's inode, so where timestamps step by a clock tick (Linux before 6.13) or a second, nothing else tells them
// apart.
const FAKE_BUILD = `import { appendFileSync, mkdirSync, readFileSync, rmSync, utimesSync, writeFileSync } from 'node:fs'
rmSync('../src/webdist', { recursive: true, force: true })
mkdirSync('../src/webdist', { recursive: true })
writeFileSync('../src/webdist/index.html', readFileSync('src/app.txt'))
appendFileSync('../builds.log', 'built\\n')
const t = Date.now() / 1000 + 10 * readFileSync('../builds.log').length
utimesSync('../src/webdist/index.html', t, t)
`
// The same build in this process, for ensureWebUi's injected build.
let clock = 0
function build(web) {
  const index = join(web, '..', 'src', 'webdist', 'index.html')
  rmSync(join(web, '..', 'src', 'webdist'), { recursive: true, force: true })
  write(index, readFileSync(join(web, 'src', 'app.txt')))
  const t = Date.now() / 1000 + 10 * ++clock
  utimesSync(index, t, t)
  builds.push(web)
}
let builds = []
const quiet = { build, log: () => {} }

let made = 0
// A turbollm checkout with what the staging script reads. Its web/package-lock.json has a package for another
// platform (optional) that web/node_modules does not, as on any machine.
function fakeTurbollm() {
  const tl = join(work, `turbollm-${++made}`)
  const web = join(tl, 'web')
  write(join(tl, 'package.json'), '{"name":"turbollm","version":"1.2.3"}\n')
  write(join(tl, 'src', 'util', 'android-preload.cjs'), '\n')
  write(join(tl, 'node_modules', 'sql.js', 'dist', 'sql-wasm.wasm'), 'wasm')
  write(join(tl, 'node_modules', 'ws', 'package.json'), '{"name":"ws","version":"8.0.0"}\n')
  write(join(web, 'package.json'), `${JSON.stringify({ name: 'web', private: true, scripts: { build: 'node fake-build.mjs' } })}\n`)
  write(join(web, 'package-lock.json'), `${JSON.stringify({ name: 'web', lockfileVersion: 3, requires: true, packages: {
    '': { name: 'web' },
    'node_modules/vite': { version: '7.3.7', dev: true },
    'node_modules/@esbuild/win32-x64': { version: '0.25.0', dev: true, optional: true, os: ['win32'], cpu: ['x64'] },
  } })}\n`)
  write(join(web, 'node_modules', '.package-lock.json'), `${JSON.stringify({ name: 'web', lockfileVersion: 3, requires: true, packages: {
    'node_modules/vite': { version: '7.3.7', dev: true },
  } })}\n`)
  write(join(web, 'node_modules', 'vite', 'package.json'), '{"name":"vite","version":"7.3.7"}\n')
  write(join(web, 'fake-build.mjs'), FAKE_BUILD)
  write(join(web, 'index.html'), '<div id="root"></div>\n')
  write(join(web, 'src', 'app.txt'), 'version 1\n')
  return tl
}
const stampOf = (tl) => json(join(tl, 'src', 'webdist', WEB_STAMP))

test('a web UI built from web/ as it is now is not built again', () => {
  builds = []
  const tl = fakeTurbollm()
  assert.equal(ensureWebUi(tl, quiet), true, 'no web UI yet: built')
  assert.equal(ensureWebUi(tl, quiet), false)
  assert.equal(ensureWebUi(tl, quiet), false)
  assert.equal(builds.length, 1)
})

test('a changed file under web/ builds the web UI again, then stamps it', () => {
  builds = []
  const tl = fakeTurbollm()
  const web = join(tl, 'web')
  ensureWebUi(tl, quiet)
  write(join(web, 'src', 'app.txt'), 'version 2\n')
  assert.equal(ensureWebUi(tl, quiet), true)
  assert.equal(readFileSync(join(tl, 'src', 'webdist', 'index.html'), 'utf8'), 'version 2\n')
  assert.deepEqual(stampOf(tl), { version: 1, inputs: webInputs(web).hash, files: webInputs(web).files })
  // A file added, and a lockfile change (web/node_modules updated with it), each count as a change too.
  write(join(web, 'src', 'new.tsx'), 'export {}\n')
  assert.equal(ensureWebUi(tl, quiet), true)
  const lock = json(join(web, 'package-lock.json'))
  const installed = json(join(web, 'node_modules', '.package-lock.json'))
  lock.packages['node_modules/vite'].version = installed.packages['node_modules/vite'].version = '7.3.8'
  write(join(web, 'package-lock.json'), JSON.stringify(lock))
  write(join(web, 'node_modules', '.package-lock.json'), JSON.stringify(installed))
  assert.equal(ensureWebUi(tl, quiet), true)
  assert.equal(ensureWebUi(tl, quiet), false)
  assert.equal(builds.length, 4)
})

test('a web UI with no stamp, or a stamp of another kind, is built again', () => {
  builds = []
  const tl = fakeTurbollm()
  // Built by hand or by an older staging script: an index.html and no record of what it came from.
  write(join(tl, 'src', 'webdist', 'index.html'), 'version 0\n')
  assert.equal(ensureWebUi(tl, quiet), true)
  assert.equal(readFileSync(join(tl, 'src', 'webdist', 'index.html'), 'utf8'), 'version 1\n')
  write(join(tl, 'src', 'webdist', WEB_STAMP), JSON.stringify({ ...stampOf(tl), version: 0 }))
  assert.equal(ensureWebUi(tl, quiet), true)
  assert.equal(builds.length, 2)
})

test('a web build that fails, or that a change overtakes, is built again next time', () => {
  const tl = fakeTurbollm()
  const app = join(tl, 'web', 'src', 'app.txt')
  const index = join(tl, 'src', 'webdist', 'index.html')
  ensureWebUi(tl, quiet)
  // A build that dies halfway, after writing some of its output: no stamp is left to vouch for what is there, so
  // even with web/ back as the last stamp had it, the half-built UI is not taken for that build.
  write(app, 'version 2\n')
  const failing = { log() {}, build() { write(index, 'BROKEN\n'); throw new Error('vite died') } }
  assert.throws(() => ensureWebUi(tl, failing), /^Error: the web UI failed to build in /)
  assert.equal(existsSync(join(tl, 'src', 'webdist', WEB_STAMP)), false, 'no stamp after a failed build')
  write(app, 'version 1\n')
  assert.equal(ensureWebUi(tl, quiet), true)
  assert.equal(readFileSync(index, 'utf8'), 'version 1\n')
  // A file saved while the build runs: the UI was built from the file as it was before, which is what the stamp
  // says, so the next staging builds again.
  write(app, 'version 2\n')
  const overtaken = { log() {}, build(web) { build(web); write(app, 'version 3\n') } }
  assert.equal(ensureWebUi(tl, overtaken), true)
  assert.equal(readFileSync(index, 'utf8'), 'version 2\n')
  assert.equal(ensureWebUi(tl, quiet), true, 'built again: web/ changed while the last build ran')
  assert.equal(readFileSync(index, 'utf8'), 'version 3\n')
  assert.equal(ensureWebUi(tl, quiet), false)
})

test('a web build that writes no index.html into webdist fails, and stamps nothing', () => {
  const tl = fakeTurbollm()
  const stamp = join(tl, 'src', 'webdist', WEB_STAMP)
  // What a web/ whose build puts its output somewhere else would do: exit 0 and leave webdist as it was.
  const elsewhere = { log() {}, build() {} }
  assert.throws(() => ensureWebUi(tl, elsewhere), /^Error: the web UI build did not write .*index\.html$/)
  assert.equal(existsSync(stamp), false)
  ensureWebUi(tl, quiet)
  write(join(tl, 'web', 'src', 'app.txt'), 'version 2\n')
  assert.throws(() => ensureWebUi(tl, elsewhere), /^Error: the web UI build did not write .*index\.html$/,
    'an older build there is not the new one')
  assert.equal(existsSync(stamp), false)
  assert.equal(readFileSync(join(tl, 'src', 'webdist', 'index.html'), 'utf8'), 'version 1\n')
  assert.equal(ensureWebUi(tl, quiet), true)
})

test('missing or mismatched web/node_modules stops with the command to run, and builds nothing', () => {
  builds = []
  const tl = fakeTurbollm()
  const web = join(tl, 'web')
  const command = `npm --prefix "${web}" ci`
  const installedPath = join(web, 'node_modules', '.package-lock.json')
  const installed = readFileSync(installedPath, 'utf8')
  assert.equal(webDepsProblem(web), null, "another platform's optional package is not needed")

  write(installedPath, installed.replace('"7.3.7"', '"7.3.6"'))
  assert.throws(() => ensureWebUi(tl, quiet), (e) => {
    assert.match(e.message, /web\/node_modules has vite 7\.3\.6, web\/package-lock\.json 7\.3\.7/)
    assert.ok(e.message.endsWith(command), e.message)
    return true
  })
  write(installedPath, JSON.stringify({ packages: {} }))
  assert.throws(() => ensureWebUi(tl, quiet), /vite 7\.3\.7 is in web\/package-lock\.json but not in web\/node_modules/)
  rmSync(join(web, 'node_modules'), { recursive: true })
  assert.throws(() => ensureWebUi(tl, quiet), (e) => {
    assert.match(e.message, /^the web UI has to be built, as there is no web UI build, but web\/node_modules is missing\./)
    assert.ok(e.message.endsWith(command), e.message)
    return true
  })
  assert.equal(builds.length, 0)
  assert.equal(existsSync(join(tl, 'src', 'webdist')), false)

  // With nothing to build, web/'s packages are not needed.
  write(installedPath, installed)
  ensureWebUi(tl, quiet)
  rmSync(join(web, 'node_modules'), { recursive: true })
  assert.equal(ensureWebUi(tl, quiet), false)
})

test('a dangling symlink, a symlink to a folder, packages, caches and test output are not web build inputs', (t) => {
  const tl = fakeTurbollm()
  const web = join(tl, 'web')
  const before = webInputs(web)
  write(join(web, '.vite', 'deps', 'chunk.js'), 'cache')
  write(join(web, 'src', '.cache', 'x'), 'cache')
  write(join(web, 'node_modules', 'vite', 'index.js'), 'package')
  write(join(web, 'src', 'x', 'node_modules', 'y.js'), 'package')
  write(join(web, 'test-results', 'run.json'), '{}')
  write(join(web, 'playwright-report', 'index.html'), 'report')
  write(join(web, 'src', '.DS_Store'), 'finder')
  assert.deepEqual(webInputs(web), before)
  // A dotfile is an input, though: vite reads web/.env*.
  write(join(web, '.env.local'), 'VITE_X=1\n')
  assert.notEqual(webInputs(web).hash, before.hash)
  rmSync(join(web, '.env.local'))

  const outside = join(work, `outside-${made}`)
  write(join(outside, 'a.tsx'), 'export {}\n')
  try {
    symlinkSync(join(web, 'src', 'no-such-file.tsx'), join(web, 'src', 'gone.tsx'))
    symlinkSync(outside, join(web, 'src', 'linked'), 'dir')
  } catch (e) {
    if (process.platform === 'win32' && e.code === 'EPERM') return t.skip('symlinks need Developer Mode on Windows')
    throw e
  }
  assert.deepEqual(webInputs(web), before)
  assert.equal(ensureWebUi(tl, quiet), true, 'the walk gets through them')
  // A symlink to a file counts as that file.
  symlinkSync(join(outside, 'a.tsx'), join(web, 'src', 'a.tsx'))
  assert.notEqual(webInputs(web).hash, before.hash)
})

test('a file or folder that goes away while web/ is hashed is left out, not an error', () => {
  const tl = fakeTurbollm()
  const web = join(tl, 'web')
  const saved = join(web, 'src', 'App.tsx')
  const tmp = join(web, 'src', 'tmp')
  write(saved, 'export {}\n')
  write(join(tmp, 'x.ts'), 'export {}\n')
  // Each goes away after the walk has listed it and before it is read, as an editor's safe write moves a file aside
  // for a moment (fs's own functions, which the script's imports of node:fs follow, are wrapped for this one call).
  const { readFileSync: read, readdirSync: list } = fs
  fs.readFileSync = (path, ...rest) => {
    if (path === saved) rmSync(saved)
    return read(path, ...rest)
  }
  fs.readdirSync = (path, ...rest) => {
    if (path === tmp) rmSync(tmp, { recursive: true })
    return list(path, ...rest)
  }
  syncBuiltinESMExports()
  let during
  try {
    during = webInputs(web)
  } finally {
    Object.assign(fs, { readFileSync: read, readdirSync: list })
    syncBuiltinESMExports()
  }
  assert.equal(existsSync(saved) || existsSync(tmp), false, 'both went away during the hash')
  assert.deepEqual(during, webInputs(web), 'the hash of web/ without them')
})

test('folders and files that Windows lists as links but are not (OneDrive placeholders) are web build inputs', () => {
  const tl = fakeTurbollm()
  const web = join(tl, 'web')
  const menu = join(web, 'src', 'components', 'Menu.tsx')
  write(menu, 'export {}\n')
  write(join(web, 'src', '.DS_Store'), 'finder')
  write(join(web, '.vite', 'deps', 'chunk.js'), 'cache')
  const outside = join(work, `outside-reparse-${made}`)
  write(join(outside, 'a.tsx'), 'export {}\n')
  try {
    symlinkSync(outside, join(web, 'src', 'linked'), 'dir') // a real link, which is still not followed
  } catch (e) {
    if (process.platform !== 'win32' || e.code !== 'EPERM') throw e
  }
  const listed = webInputs(web)
  // On Windows, libuv's scandir types every entry with FILE_ATTRIBUTE_REPARSE_POINT as a link, and Node's Dirent says
  // what libuv says: in a folder OneDrive keeps, whose folders and files are placeholders (reparse points), every
  // entry is listed as a link. That is done here to every folder and file under web/ (fs's readdirSync, which the
  // script's import of node:fs follows, is wrapped for this).
  const { readdirSync: list } = fs
  fs.readdirSync = (path, options) => {
    const entries = list(path, options)
    if (!options?.withFileTypes) return entries
    const { UV_DIRENT_LINK } = fs.constants
    return entries.map((e) => (e.isDirectory() || e.isFile() ? new fs.Dirent(e.name, UV_DIRENT_LINK, path) : e))
  }
  syncBuiltinESMExports()
  let asOnWindows, edited
  try {
    const src = fs.readdirSync(web, { withFileTypes: true }).find((e) => e.name === 'src')
    assert.ok(src.isSymbolicLink() && !src.isDirectory(), 'src/ is listed as a link')
    asOnWindows = webInputs(web)
    write(menu, 'export const menu = 1\n')
    edited = webInputs(web)
  } finally {
    fs.readdirSync = list
    syncBuiltinESMExports()
  }
  assert.deepEqual(asOnWindows, listed, 'every folder walked and the same files hashed as when listed as what they are')
  assert.notEqual(edited.hash, asOnWindows.hash, 'a change in a subfolder is seen')
})

test('the inputs hash is the same on every run, whatever order the files were made in', () => {
  // 'src/C.tsx' is before 'src/a.tsx' in code-unit order (C is U+0043, a U+0061) and after it in a locale's.
  const files = { 'src/b.tsx': 'b', 'src/a/z.ts': 'z', 'index.html': '<div></div>', 'src/a.tsx': 'a', 'package.json': '{}', 'src/C.tsx': 'C' }
  const one = join(work, `order-1-${made}`)
  const two = join(work, `order-2-${made}`)
  for (const [p, text] of Object.entries(files)) write(join(one, ...p.split('/')), text)
  for (const [p, text] of Object.entries(files).reverse()) write(join(two, ...p.split('/')), text)
  const first = webInputs(one)
  assert.deepEqual(webInputs(one), first)
  assert.deepEqual(webInputs(two), first)
  // Its definition, which every platform computes alike: each file's '/'-joined path, length and bytes, sorted by path.
  const want = createHash('sha256')
  for (const p of Object.keys(files).sort()) want.update(`${p}\0${files[p].length}\0`).update(files[p])
  assert.deepEqual(first, { hash: want.digest('hex'), files: 6 })
  // A file moved to another folder is a change even with the same bytes.
  write(join(two, 'src', 'a', 'b.tsx'), 'b')
  rmSync(join(two, 'src', 'b.tsx'))
  assert.notEqual(webInputs(two).hash, first.hash)
})

// ── The whole script ──────────────────────────────────────────────────────────────────────

const npm = spawnSync('npm', ['--version'], { stdio: 'ignore', shell: process.platform === 'win32' })
const scriptSkip = process.platform === 'win32' ? 'the stand-in tsup is a POSIX executable'
  : npm.error || npm.status !== 0 ? 'no npm on this machine' : false

// The checkout of fakeTurbollm, plus a tsup that writes dist-android/cli.js, as npx finds it.
function fakeCheckout() {
  const tl = fakeTurbollm()
  write(join(tl, 'node_modules', 'tsup', 'package.json'), '{"name":"tsup","version":"8.0.0","bin":{"tsup":"cli.mjs"}}\n')
  write(join(tl, 'node_modules', 'tsup', 'cli.mjs'), `#!/usr/bin/env node
import { mkdirSync, writeFileSync } from 'node:fs'
mkdirSync('dist-android', { recursive: true })
writeFileSync('dist-android/cli.js', 'export {}\\n')
`)
  chmodSync(join(tl, 'node_modules', 'tsup', 'cli.mjs'), 0o755)
  mkdirSync(join(tl, 'node_modules', '.bin'), { recursive: true })
  symlinkSync('../tsup/cli.mjs', join(tl, 'node_modules', '.bin', 'tsup'))
  return tl
}
function stage(tl) {
  const app = join(work, `app-${made}`)
  mkdirSync(join(app, 'scripts'), { recursive: true })
  cpSync(script, join(app, 'scripts', 'stage-daemon-assets.mjs'))
  // Bounded, so a stuck npx or npm fails this test by name instead of holding the run. The timeout signals only the
  // staging script, so it leads a group of its own and a timed-out run takes its npm children with it.
  const r = spawnSync(process.execPath, [join(app, 'scripts', 'stage-daemon-assets.mjs'), tl], {
    encoding: 'utf8', env: { ...process.env, npm_config_update_notifier: 'false' }, timeout: 120_000, detached: true,
  })
  if (r.error?.code === 'ETIMEDOUT') {
    try { process.kill(-r.pid, 'SIGKILL') } catch { /* the group is gone */ }
  }
  assert.equal(r.error, undefined, `the staging script did not finish: ${r.error?.message}`)
  const staged = join(app, 'composeApp', 'src', 'androidMain', 'assets', 'daemon', 'dist', 'webdist')
  return { ...r, staged }
}
const builtTimes = (tl) => (existsSync(join(tl, 'builds.log')) ? readFileSync(join(tl, 'builds.log'), 'utf8').split('\n').length - 1 : 0)

test('the staging script builds a web UI older than web/ before it stages it, and only then', { skip: scriptSkip }, () => {
  const tl = fakeCheckout()
  // Built from older sources, the way a pull or a branch switch leaves it.
  write(join(tl, 'src', 'webdist', 'index.html'), 'version 0\n')
  let r = stage(tl)
  assert.equal(r.status, 0, r.stderr)
  assert.equal(readFileSync(join(r.staged, 'index.html'), 'utf8'), 'version 1\n', 'the APK carries the web UI of web/ as it is')
  assert.match(r.stdout, /building the web UI: the web UI build there has no record of what it was built from/)
  assert.deepEqual(json(join(r.staged, WEB_STAMP)), stampOf(tl))
  r = stage(tl)
  assert.equal(r.status, 0, r.stderr)
  assert.match(r.stdout, /web UI up to date/)
  assert.equal(builtTimes(tl), 1)
  write(join(tl, 'web', 'src', 'app.txt'), 'version 2\n')
  r = stage(tl)
  assert.equal(r.status, 0, r.stderr)
  assert.equal(readFileSync(join(r.staged, 'index.html'), 'utf8'), 'version 2\n')
  assert.equal(builtTimes(tl), 2)
})

test('the staging script stops with the command to run when web/node_modules is missing', { skip: scriptSkip }, () => {
  const tl = fakeCheckout()
  write(join(tl, 'src', 'webdist', 'index.html'), 'version 0\n')
  rmSync(join(tl, 'web', 'node_modules'), { recursive: true })
  const r = stage(tl)
  assert.equal(r.status, 1)
  assert.match(r.stderr, /FATAL: the web UI has to be built, .* but web\/node_modules is missing\./)
  assert.ok(r.stderr.includes(`npm --prefix "${join(tl, 'web')}" ci`), r.stderr)
  assert.equal(existsSync(r.staged), false, 'nothing staged')
  assert.equal(builtTimes(tl), 0)
})
