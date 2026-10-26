// Tests for HEIGHT_FIX_JS, the script the app's WebView runs after each page load
// (composeApp/src/androidMain/kotlin/com/turbollm/app/DaemonWebView.kt):
//   node --test scripts/webview-height-fix.test.mjs
// The script is read out of DaemonWebView.kt and turned into the string Kotlin builds from it (its ${'$'} is a $), so
// this runs what ships. It is run in Chromium on a page shaped like the web UI's Tailwind output, at the phone size
// the bug was reported at, under two hosts: a WebView that lays the page out at its own height (what MATCH_PARENT
// layout params give), and one that lays it out at height 0 (what WRAP_CONTENT gave: Chromium's
// --blink-settings=forceZeroLayoutHeight=true is the same Blink setting). Both are compared with the same page in
// plain Chromium with no script. Needs Playwright (require('playwright'), so NODE_PATH works) and the Chromium it
// launches, or the one CHROME_PATH names; those tests are skipped without them.
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'

const KT = new URL('../composeApp/src/androidMain/kotlin/com/turbollm/app/DaemonWebView.kt', import.meta.url)

// HEIGHT_FIX_JS as the app evaluates it. A `$` in a Kotlin raw string starts a template, so the only one allowed is
// the ${'$'} that stands for a literal $.
function heightFixJs() {
  const m = /private const val HEIGHT_FIX_JS = """([\s\S]*?)"""/.exec(readFileSync(KT, 'utf8'))
  assert.ok(m, 'HEIGHT_FIX_JS not found in DaemonWebView.kt')
  const js = m[1].replaceAll("${'$'}", '$')
  const template = /\$[\w{]/.exec(js)
  assert.equal(template, null, `Kotlin would fill in a template in HEIGHT_FIX_JS: ${template && js.slice(template.index, template.index + 20)}`)
  return js
}

test('HEIGHT_FIX_JS is plain JavaScript once Kotlin has built it', () => {
  assert.doesNotThrow(() => new Function(heightFixJs()))
})

// The fix itself, which no browser here can run: Chromium lays out the page of a WebView whose layout params are
// WRAP_CONTENT high at height 0, and AndroidView's holder adds a view that has none with WRAP_CONTENT, as soon as the
// factory returns. So the one WebView the factory builds (on first load and after a renderer crash) is given
// MATCH_PARENT both ways first.
test('the factory gives the WebView MATCH_PARENT layout params before AndroidView adds it', () => {
  const kt = readFileSync(KT, 'utf8')
  assert.equal(kt.match(/WebView\(context\)/g)?.length, 1, 'one place builds the WebView')
  const first = /WebView\(context\)\.apply \{\s*(?:\/\/.*\s*)*layoutParams = ViewGroup\.LayoutParams\(\s*ViewGroup\.LayoutParams\.MATCH_PARENT,\s*ViewGroup\.LayoutParams\.MATCH_PARENT,?\s*\)/
  assert.ok(first.test(kt), 'the first statement in WebView(context).apply { } sets MATCH_PARENT layout params')
})

// Playwright makes its folders in the temp folder and leaves them there when Chromium does not start (the skip
// below), so this file's go in a temp folder of their own, removed when its process exits. Not in an after() hook:
// node:test runs those once the tests above are done, while the skip check below may still be launching Chromium.
const ownTmp = mkdtempSync(join(tmpdir(), 'webview-height-fix-'))
for (const k of ['TMPDIR', 'TMP', 'TEMP']) process.env[k] = ownTmp
process.on('exit', () => rmSync(ownTmp, { recursive: true, force: true }))

let chromium = null
try {
  ;({ chromium } = createRequire(import.meta.url)('playwright'))
} catch {}
const launchOptions = (zero) => ({
  executablePath: process.env.CHROME_PATH || undefined,
  args: zero ? ['--blink-settings=forceZeroLayoutHeight=true'] : [],
})
const skip = await (async () => {
  if (!chromium) return 'Playwright is not installed'
  try {
    await (await chromium.launch(launchOptions(false))).close()
    return false
  } catch (e) {
    return `Chromium does not start: ${String(e.message).split('\n')[0]}`
  }
})()

// The web UI's shapes, with the CSS Tailwind v4 (4.3.3) writes for them, in its utilities layer: html/body/#root at
// 100% (index.css), the model picker (ModelLoadMenu.tsx), arbitrary values with vh inside calc() and clamp(), calc()
// spelt with '_' for a space and without (Tailwind then spaces the operators out itself, so the class name is not the
// CSS), a vh term after a percentage, Tailwind's '!' (important), vmin, a custom property whose name has digits and
// "vh" in it, and the named utilities. One class's rule is in a stylesheet from another origin, which a page script
// cannot read. `pad` is the 4px padding and 1px border of the picker's menu, which is all that was left of it at
// height 0.
const OTHER_ORIGIN_CSS = 'http://other-origin.test/utilities.css'
const PAGE = `<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>
html, body, #root { height: 100%; margin: 0 }
.pad { padding: 4px; border: 1px solid; box-sizing: border-box; overflow: auto }
.tall { height: 2000px }
@layer utilities {
.max-h-\\[min\\(60vh\\,var\\(--radix-dropdown-menu-content-available-height\\)\\)\\] { max-height: min(60vh, var(--radix-dropdown-menu-content-available-height)) }
.max-h-\\[60vh\\] { max-height: 60vh }
.h-\\[45vh\\] { height: 45vh }
.h-\\[calc\\(100vh_-_2rem\\)\\] { height: calc(100vh - 2rem) }
.h-\\[calc\\(100vh-2rem\\)\\] { height: calc(100vh - 2rem) }
.h-\\[calc\\(100\\%-10vh\\)\\] { height: calc(100% - 10vh) }
.h-\\[50vh\\]\\! { height: 50vh !important }
.max-h-\\[clamp\\(10rem\\,50svh\\,40rem\\)\\] { max-height: clamp(10rem, 50svh, 40rem) }
.min-h-\\[20vmin\\] { min-height: 20vmin }
.h-\\[var\\(--a-10vh\\)\\] { height: var(--a-10vh) }
.min-h-dvh { min-height: 100dvh }
.h-screen { height: 100vh }
}
</style><link rel="stylesheet" href="${OTHER_ORIGIN_CSS}"></head><body><div id="root" style="--a-10vh: 30px">
<div style="--radix-dropdown-menu-content-available-height: 879.5px"><div id="picker" class="max-h-[min(60vh,var(--radix-dropdown-menu-content-available-height))] pad"><div class="tall"></div></div></div>
<div id="h45" class="h-[45vh]"></div>
<div id="calc" class="h-[calc(100vh_-_2rem)]"></div>
<div id="calcSpaced" class="h-[calc(100vh-2rem)]"></div>
<div id="percent" class="h-[calc(100%-10vh)]"></div>
<div id="important" class="h-[50vh]!"></div>
<div id="unreadable" class="h-[calc(100vh_-_3rem)]"></div>
<div id="clamp" class="max-h-[clamp(10rem,50svh,40rem)] pad"><div class="tall"></div></div>
<div id="vmin" class="min-h-[20vmin]"></div>
<div id="varname" class="h-[var(--a-10vh)]"></div>
<div id="shell" class="min-h-dvh"></div>
<div id="screen" class="h-screen"></div>
<div id="reclassed" class="pad"><div class="tall"></div></div>
</div></body></html>`
const OTHER_ORIGIN_RULES = '@layer utilities { .h-\\[calc\\(100vh_-_3rem\\)\\] { height: calc(100vh - 3rem) } }'

const IDS = ['root', 'picker', 'h45', 'calc', 'calcSpaced', 'percent', 'important', 'unreadable', 'clamp', 'vmin',
  'varname', 'shell', 'screen', 'portal', 'reclassed']

// Loads PAGE in a 412x925 phone, runs HEIGHT_FIX_JS twice (onPageFinished can come twice for one page) unless js is
// null, then does what the UI does later: a Radix portal with a vh class is added, and an existing element is given
// one (React reusing an element with a new className). Heights are measured then, after the class is taken away
// again, and after a keyboard-like resize to 412x600. With `unsized`, HEIGHT_FIX_JS runs as on a page that finished
// loading before the WebView was laid out: window.innerHeight reads 0 while it runs, and then the window gets its
// height (a resize event). `signal` is the test's: a test that times out leaves this waiting where it was (a
// page.evaluate whose promise never settles), so its finally never closes Chromium, and an open Chromium keeps
// `node --test` running. Closing it when the signal aborts ends that wait, so the run ends, the test failed by name.
async function run(zero, js, { unsized = false, signal } = {}) {
  const browser = await chromium.launch(launchOptions(zero))
  const close = () => { browser.close().catch(() => {}) }
  signal?.addEventListener('abort', close, { once: true })
  if (signal?.aborted) close()
  try {
    const page = await (await browser.newContext({
      viewport: { width: 412, height: 925 }, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true,
    })).newPage()
    const warnings = []
    page.on('console', (m) => { if (m.type() === 'warning') warnings.push(m.text()) })
    await page.route(OTHER_ORIGIN_CSS, (route) => route.fulfill({ contentType: 'text/css', body: OTHER_ORIGIN_RULES }))
    await page.setContent(PAGE, { waitUntil: 'load' })
    const vh100 = await page.evaluate(() => {
      const d = document.createElement('div')
      d.style.height = '100vh'
      document.body.appendChild(d)
      const h = d.getBoundingClientRect().height
      d.remove()
      return h
    })
    if (unsized) {
      await page.evaluate(() => {
        window.realInnerHeight = Object.getOwnPropertyDescriptor(window, 'innerHeight')
        Object.defineProperty(window, 'innerHeight', { get: () => 0, configurable: true, enumerable: true })
      })
    }
    const first = js === null ? undefined : await page.evaluate(js)
    const second = js === null ? undefined : await page.evaluate(js)
    if (unsized) {
      await page.evaluate(() => {
        Object.defineProperty(window, 'innerHeight', window.realInnerHeight)
        window.dispatchEvent(new Event('resize'))
      })
    }
    const decided = await page.evaluate(() => window.__turbollmHeightFix)
    await page.evaluate(() => {
      const portal = document.createElement('div')
      portal.innerHTML = '<div id="portal" class="max-h-[60vh] pad"><div class="tall"></div></div>'
      document.body.appendChild(portal)
      document.getElementById('reclassed').className = 'max-h-[60vh] pad'
    })
    const heights = () => page.evaluate((ids) => Object.fromEntries(ids.map((id) =>
      [id, document.getElementById(id).getBoundingClientRect().height])), IDS)
    const at925 = await heights()
    await page.evaluate(() => { document.getElementById('reclassed').className = 'pad' })
    const unclassed = (await heights()).reclassed
    await page.setViewportSize({ width: 412, height: 600 })
    await page.waitForFunction(() => window.innerHeight === 600)
    await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))))
    const at600 = await heights()
    const important = await page.evaluate(() => document.querySelectorAll('[style*="important"]').length)
    const styles = await page.evaluate(() => document.querySelectorAll('style').length)
    return { vh100, first, second, decided, warnings, at925, at600, unclassed, important, styles }
  } finally {
    signal?.removeEventListener('abort', close)
    await browser.close()
  }
}

function sameHeights(actual, expected, what) {
  for (const id of IDS) {
    assert.ok(Math.abs(actual[id] - expected[id]) <= 0.5, `${what}: #${id} is ${actual[id]}px, ${expected[id]}px in plain Chromium`)
  }
}

let reference = null
const plain = async (signal) => (reference ??= await run(false, null, { signal }))

const slow = { skip, timeout: 120_000 }

test('in a WebView that lays the page out at its own height, HEIGHT_FIX_JS answers false and changes nothing', slow, async (t) => {
  const ref = await plain(t.signal)
  const r = await run(false, heightFixJs(), { signal: t.signal })
  assert.equal(r.vh100, 925)
  assert.equal(r.first, false, 'the first run answers false: the probe found 100vh at its real height')
  assert.equal(r.second, null, 'a second run on the same page does nothing')
  assert.equal(r.important, 0, 'no inline style was set')
  assert.equal(r.styles, ref.styles, 'no stylesheet was added')
  sameHeights(r.at925, ref.at925, '412x925')
  sameHeights(r.at600, ref.at600, '412x600')
  assert.equal(r.unclassed, ref.unclassed)
})

test('in a WebView that lays the page out at height 0, HEIGHT_FIX_JS answers true and every height is as in Chromium', slow, async (t) => {
  const ref = await plain(t.signal)
  const r = await run(true, heightFixJs(), { signal: t.signal })
  assert.equal(r.vh100, 0, 'the host lays the page out at height 0 (100vh measures 0 before HEIGHT_FIX_JS runs)')
  assert.equal(r.first, true, 'the first run answers true, which onPageFinished logs')
  assert.equal(r.second, null, 'a second run on the same page does nothing')
  assert.equal(r.styles, ref.styles + 1, 'one stylesheet for the named utilities, however many times it ran')
  // The model picker's menu was 10px (its padding and border) with max-height min(0px, …).
  assert.equal(Math.round(r.at925.picker), 555)
  sameHeights(r.at925, ref.at925, '412x925')
  sameHeights(r.at600, ref.at600, '412x600, after a resize')
  assert.equal(r.unclassed, ref.unclassed, 'a class taken away takes its height with it')
})

test('on a page that loaded before the WebView had a height, HEIGHT_FIX_JS decides at the first resize that gives it one', slow, async (t) => {
  const ref = await plain(t.signal)
  const sized = await run(false, heightFixJs(), { unsized: true, signal: t.signal })
  assert.equal(sized.first, null, 'no answer while the window has no height')
  assert.equal(sized.second, null)
  assert.equal(sized.decided, false, 'the probe ran once the window had its height, and found 100vh real')
  assert.equal(sized.important, 0)
  assert.equal(sized.styles, ref.styles)
  assert.deepEqual(sized.warnings, [])
  sameHeights(sized.at925, ref.at925, 'own height, 412x925')

  const zero = await run(true, heightFixJs(), { unsized: true, signal: t.signal })
  assert.equal(zero.first, null, 'no answer while the window has no height')
  assert.equal(zero.second, null)
  assert.equal(zero.decided, true, 'the probe ran once the window had its height, and found 100vh at 0px')
  assert.equal(zero.warnings.length, 1, 'onPageFinished has had its answer, so the page logs the warning itself')
  assert.match(zero.warnings[0], /^100vh is 0px in this WebView/)
  assert.equal(Math.round(zero.at925.picker), 555)
  sameHeights(zero.at925, ref.at925, 'height 0, 412x925')
  sameHeights(zero.at600, ref.at600, 'height 0, 412x600, after a resize')
})
