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
