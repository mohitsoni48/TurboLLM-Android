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
