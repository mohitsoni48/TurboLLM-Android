# TurboLLM Android

Standalone Android app for TurboLLM: install, open, chat — no Termux, no `pkg install`,
no visible terminal. WebView shell + an embedded Node runtime (`nodejs-mobile`) + the same
daemon and NDK-cross-compiled `llama-server` already proven working end-to-end on a real
phone in the main repo's R&D session (2026-09).

**This folder is intentionally gitignored in the main `llama-turbo` repo** — it's going to
become its own git repository (`git init` already done here) so the Android app has a
clean, independent history separate from the daemon/desktop product repo. Copy or clone
this folder out wherever the standalone app work should actually live; the daemon and
`llama-server` binary it depends on are consumed as external artifacts (npm-published
`turbollm` build output + a Hugging Face-hosted binary), not vendored source.

## Start here

Read **[BLUEPRINT.md](BLUEPRINT.md)** — the full end-to-end step-by-step plan, phased,
with an explicit spike list (Phase 0) before any real app code gets written. Nothing here
has been executed yet; this is the plan for a *new chat* to work through, spike by spike,
phase by phase.

## Why this exists / prior art

This plan is the direct continuation of R&D done in the main repo (not duplicated here in
full — see the main repo for the underlying evidence):

- `docs/decisions/decision-log.md` — ADR-390 (guided Termux build), ADR-391 (NDK
  cross-compile + hosting), ADR-395 (live end-to-end verification on a real phone: daemon
  boots, engine loads, real inference, real UI render).
- `docs/specs/28-android-native-app.md` — the original standalone-app design (Termux-fork
  shell). **Superseded by this plan's approach** (`nodejs-mobile` instead of forking
  Termux) — see [BLUEPRINT.md §0](BLUEPRINT.md) for why.
- `turbollm/src/engines/{download.ts,catalog.ts,compat.ts,build-runner.ts,probe.ts}` —
  the real, shipped Android platform support (Termux-path) this plan reuses pieces of
  (the NDK build recipe, the `probe.ts` `LD_LIBRARY_PATH` fix, the catalog Android entry).
- `.github/workflows/android-llama-build.yml` — the CI recipe for the NDK cross-compile;
  this plan runs the same commands locally in WSL instead (founder direction: no CI for
  now).

## Where the actual daemon/engine code lives

This repo does **not** vendor `turbollm`'s source. It's a separate product repo, consumed
as an external dependency (build output bundled into the APK's assets, per BLUEPRINT.md
§7) — a fresh chat working only inside this repo needs to be pointed at it explicitly:

- **Repo**: [`mohitsoni48/TurboLLM`](https://github.com/mohitsoni48/TurboLLM) (private).
  Local clone on this machine: `D:\llama-turbo` (branch `main`).
- **Node version**: `turbollm/package.json`'s `engines` field pins `>=22.13.0` — this is
  the floor Spike B (BLUEPRINT.md §4) checks the embedded `nodejs-mobile` runtime against.
- **Status as of 2026-09-03**: the Android platform support this blueprint builds on
  (ADR-390/391 — `download.ts`'s Android backend resolution, the NDK-cross-compile CI
  workflow, the `probe.ts` fix, the catalog entry) was sitting as *uncommitted* changes in
  a git worktree earlier today. It's now merged to `main`
  ([PR #203](https://github.com/mohitsoni48/TurboLLM/pull/203), merge commit `b54a370`) —
  a plain `git pull` on `D:\llama-turbo` (or a fresh clone of `main`) has everything
  BLUEPRINT.md references.
- **Files BLUEPRINT.md names directly**: `turbollm/src/engines/download.ts` (§3.2's
  `availableBackends()`/`provisionBackend()` change), `turbollm/src/hf/hf.ts` (the
  existing `fileUrl()` HF client §3.2 reuses), `turbollm/src/engines/{catalog,compat,
  probe,build-runner,build-prereqs}.ts` (already-shipped Android support, §7 reuses
  as-is), `turbollm/package.json` (the `node-pty`/`node:sqlite` dependencies behind
  Spike C/D's risk).
