# Vendored binary provenance

## Embedded Node runtime (`libnode.so`)

`composeApp/src/androidMain/cpp/libnode/` holds nodejs-mobile's prebuilt `libnode.so` per ABI plus its Node headers.
It is gitignored and never committed: `scripts/fetch-libnode.mjs` installs it, run by Gradle's `preBuild` and by the
Play alpha workflow, and refuses anything that does not match the hashes below.

**Source:** the digidem fork's nodejs-mobile **v18.20.4** release,
`https://github.com/digidem/nodejs-mobile/releases/download/v18.20.4/nodejs-mobile-v18.20.4-android.zip`.
It is the same Node 18.20.4 the app has always embedded (`include/node/node_version.h`: 18 / 20 / 4, release),
rebuilt with 16 KB-aligned LOAD segments. The upstream nodejs-mobile build is 4 KB-aligned: Android 15+ shows a
compatibility warning for it on every launch of a debuggable build, 16 KB-page devices cannot load it at all, and
Play requires 16 KB alignment for apps targeting Android 15+. Upstream's 16 KB re-release
(nodejs-mobile/nodejs-mobile#155) has not shipped.

| File | SHA-256 |
|---|---|
| archive `nodejs-mobile-v18.20.4-android.zip` | `16f70c90946f24c45858fdc0943b8b8d1c0a70c153d7fd19f9a55442d1f38e26` |
| `bin/arm64-v8a/libnode.so` | `8ce0cc6d1753afbd92fc0e3656789f8af50f786a162c2f157b2b61d5e742b7a1` |
| `bin/armeabi-v7a/libnode.so` (not shipped) | `182b5227b4beb7c5fd30c00f23e8432a890e66d8b8a21aa9ae23f4467f6306fc` |
| `bin/x86_64/libnode.so` | `d0a5acb6d6deddd71d8414d89fd1cebf500c2cbedee5bed2587faeb50bbf04c5` |

The script also checks that the 64-bit libraries' LOAD segments are 16 KB-aligned.

Node 18 is below `turbollm`'s `node:sqlite` floor (`>=22.13.0`), so the daemon's chat store uses its WASM SQLite
fallback on Android (BLUEPRINT.md Spike C).

For reference, the upstream 4 KB build these replace had `libnode.so` SHA-256s
`7c907316…250c` (arm64-v8a), `d60451f6…f92d` (armeabi-v7a) and `9acba7e2…3af1` (x86_64).

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
