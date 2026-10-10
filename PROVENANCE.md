# Vendored binary provenance

`composeApp/src/androidMain/cpp/libnode/` holds `nodejs-mobile`'s prebuilt `libnode.so` for
three ABIs, plus its Node headers — real build input the Gradle/CMake config expects on
disk, gitignored (never committed — see `.gitignore`).

## What's confirmed

- **Node version: 18.20.4**, verified directly from the vendored
  `libnode/include/node/node_version.h` (`NODE_MAJOR_VERSION 18`, `NODE_MINOR_VERSION 20`,
  `NODE_PATCH_VERSION 4`, `NODE_VERSION_IS_RELEASE 1`) — not read off a label or assumed.
  This is `nodejs-mobile`'s actual last-published Node line (the project is
  community-maintained now; Node 18 is what it ships).
- **This is below `turbollm`'s `node:sqlite` floor** (`>=22.13.0`) — confirms BLUEPRINT.md
  Spike C's risk as real, not hypothetical: the daemon's chat store needs a platform-gated
  fallback (WASM SQLite) for Android, exactly as that spike already anticipated.
- File sizes/hashes recorded below, so a future re-fetch (or the eventual real
  provenance-tracked download step) can confirm it's fetching the identical artifact:

  | ABI | Size | SHA-256 |
  |---|---|---|
  | arm64-v8a | 62,475,584 bytes | `7c907316beb6e78e34495926c9ac1befe079369b14650d508ea812929258250c` |
  | armeabi-v7a | 58,720,948 bytes | `d60451f64718354b1a3d4857d20fbe56cc3a3dbe2c319c2911342aea2bbef92d` |
  | x86_64 | 65,361,944 bytes | `9acba7e26a1e864f13b78f1b7121773643f3f33a4c6a2fe7d4f63eb06d4d3af1` |

## What's NOT confirmed

No download URL, fetch script, or checksum was recorded when these first landed in the
project — they simply appeared as untracked files with no audit trail. The Node-version
match against `nodejs-mobile`'s real, known release line is real evidence they're
legitimate, but it isn't proof of exact origin. **Before this goes further than a local
spike**, replace this with a real, scripted, checksummed fetch from
`nodejs-mobile`'s official GitHub releases (`github.com/nodejs-mobile/nodejs-mobile`) so
the dependency is reproducible and auditable rather than "already present, trust it."

---

# LiteRT-LM runtime (`liblitert-lm.so`)

Not vendored: `scripts/stage-litert-lm-engine.mjs` fetches it at build time from Google's
`litert-lm-api` wheel on PyPI and refuses any file whose SHA-256 differs from the pinned value.

| ABI | Wheel | SHA-256 (wheel) |
|---|---|---|
| arm64-v8a | `litert_lm_api-0.18.0-py3-none-android_23_arm64_v8a.whl` | `a045af401dc199c35b1f44c29e8899cecbf2712a8194c9e35489ab5e2a95d47d` |
| x86_64 | `litert_lm_api-0.18.0-py3-none-android_23_x86_64.whl` | `db5268c5eb8e60f5a7cdecea310f8084426ae174fdbe8efe2991c47f54d74889` |

The extracted arm64 `liblitert-lm.so` (39.9 MB, sha256 `3d591b09…dfd9`) is byte-identical to the
one the Termux path installs. License: Apache-2.0 (google-ai-edge/LiteRT-LM).

Vendored in `engines/litert-lm-server/third_party/`: LiteRT-LM's C API headers from tag
`v0.18.0` (`c/engine.h`, `c/conversation.h`, `c/error_reporter.h`, `c/api_export.h`;
Apache-2.0, LICENSE alongside) and nlohmann/json 3.11.3 (`json.hpp`, sha256 `9bea4c80…3ea6`; MIT,
LICENSE.MIT alongside).
