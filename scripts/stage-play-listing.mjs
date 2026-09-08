#!/usr/bin/env node
// Assemble Gradle Play Publisher's expected `src/main/play/` layout from this project's own
// source-of-truth files (`play-store-assets/listing-copy.md` + its icon/feature-graphic/
// screenshots), so `publishReleaseListing` (and `publishReleaseApps`, which runs it alongside
// the bundle) always uploads whatever `play-store-assets/` currently says — no manual paste
// into Play Console, and no risk of the two drifting apart (ADR-413).
//
// GPP reads `src/<sourceSet>/play` (docs: github.com/Triple-T/gradle-play-publisher). Generated
// wholesale into `composeApp/src/main/play/` — gitignored, same reasoning as the vulkan engine
// .so and the raw play-store-assets images: build/stage locally each time, never commit binaries.
//
// listing-copy.md's Title/Short description/Full description sections are hand-written prose in
// fenced code blocks (not machine-readable frontmatter) — parsed here with a small regex rather
// than restructuring that file, since it's written for a human to read and edit first.
import { existsSync, mkdirSync, readFileSync, writeFileSync, copyFileSync, rmSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const assetsDir = join(appRoot, 'play-store-assets')
const playDir = join(appRoot, 'composeApp', 'src', 'main', 'play')
const locale = 'en-US'
const listingDir = join(playDir, 'listings', locale)
const graphicsDir = join(listingDir, 'graphics')

const listingCopyPath = join(assetsDir, 'listing-copy.md')
if (!existsSync(listingCopyPath)) {
  console.error(`FATAL: missing ${listingCopyPath}`)
  process.exit(1)
}
const md = readFileSync(listingCopyPath, 'utf8')

// Extracts the first fenced ``` block that follows a `## <heading>` line matching `headingRe`.
function extractFenced(headingRe) {
  const headingMatch = md.match(headingRe)
  if (!headingMatch) return null
  const after = md.slice(headingMatch.index + headingMatch[0].length)
  const fenced = after.match(/```\n([\s\S]*?)\n```/)
  return fenced ? fenced[1].trim() : null
}

const title = extractFenced(/^## Title.*$/m)
const shortDescription = extractFenced(/^## Short description.*$/m)
const fullDescription = extractFenced(/^## Full description.*$/m)

for (const [label, value, max] of [
  ['title', title, 30],
  ['short description', shortDescription, 80],
  ['full description', fullDescription, 4000],
]) {
  if (!value) {
    console.error(`FATAL: could not parse "${label}" out of ${listingCopyPath} — check its heading text matches what this script's regex expects.`)
    process.exit(1)
  }
  if (value.length > max) {
    console.error(`FATAL: ${label} is ${value.length} chars, over Play's ${max}-char limit.`)
    process.exit(1)
  }
}

rmSync(playDir, { recursive: true, force: true })
mkdirSync(listingDir, { recursive: true })
mkdirSync(join(graphicsDir, 'icon'), { recursive: true })
mkdirSync(join(graphicsDir, 'feature-graphic'), { recursive: true })
mkdirSync(join(graphicsDir, 'phone-screenshots'), { recursive: true })

writeFileSync(join(playDir, 'default-locale.txt'), `${locale}\n`)
writeFileSync(join(listingDir, 'title.txt'), title)
writeFileSync(join(listingDir, 'short-description.txt'), shortDescription)
writeFileSync(join(listingDir, 'full-description.txt'), fullDescription)

const icon = join(assetsDir, 'icon', 'app-icon-512.png')
const featureGraphic = join(assetsDir, 'feature-graphic', 'feature-graphic-1024x500.png')
// The WRAPPED (1080x1920, 16:9) screenshots, not the raw 1080x2400 captures — Play rejects a
// screenshot whose longer side exceeds 2x the shorter (2400/1080 = 2.22x), documented in
// listing-copy.md's own Screenshots section. Order matches that section's stated sequence.
const screenshots = ['final-1-chat.png', 'final-2-discover.png', 'final-3-usage.png', 'final-4-settings.png']
  .map((f) => join(assetsDir, 'screenshots', f))

for (const [label, p] of [['icon', icon], ['feature graphic', featureGraphic], ...screenshots.map((p) => ['screenshot', p])]) {
  if (!existsSync(p)) {
    console.error(`FATAL: missing ${label}: ${p}`)
    process.exit(1)
  }
}

copyFileSync(icon, join(graphicsDir, 'icon', 'icon.png'))
copyFileSync(featureGraphic, join(graphicsDir, 'feature-graphic', 'featureGraphic.png'))
screenshots.forEach((p, i) => copyFileSync(p, join(graphicsDir, 'phone-screenshots', `${i + 1}.png`)))

console.log(`staged Play listing (${locale}) → ${playDir}`)
console.log(`title: "${title}" (${title.length}/30)`)
console.log(`short description: ${shortDescription.length}/80 chars`)
console.log(`full description: ${fullDescription.length}/4000 chars`)
console.log(`graphics: 1 icon, 1 feature graphic, ${screenshots.length} screenshots`)
