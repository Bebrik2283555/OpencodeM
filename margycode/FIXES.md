# OpencodeM 0.1.1-fix

> Следующая сборка с проверкой на реальном телефоне: [0.1.2-fix](FIXES-0.1.2.md).

Package: `com.opencode`, versionCode: **2**, Android 9+, ARM64.

## Installation and data

The original APK is signed by Narezany. This build is signed with a newly generated **local key**, not the author's key.
Android will **not** accept it as an update over the official app. Keeping the package ID does not bypass signature checking.

Before replacing the official app, export chats and save your configuration/rules/skills and any other needed app-private files.
Uninstalling normally deletes app-private chats, credentials, downloaded models and Alpine. Chat export is not a full backup.
Do not uninstall until you have checked your backups. No device was modified during this work.

Keep `keys/OpencodeM.jks` and `keys/OpencodeM.pass` private and backed up. They are excluded from git.
Future builds signed with this same local key and a higher versionCode can update this patched app.

## Changes corresponding to TODO

- **Forms:** OpenCode's `multiselect` is sent as a JSON array, not a string. Checkboxes, custom answers, typed numbers/booleans, defaults, conditional fields and external acknowledgements are supported. Submitted forms hide immediately; on failure the form reopens with its answers and inline error. Only one question/notice dialog is displayed at a time.
- **Stuck busy:** HTTP requests and execution are tracked separately. After 20 seconds of missing session activity, status is reconciled with `/api/session/active`; silence alone does not cancel a legitimately slow tool/model. Reconnect reloads status, forms and the queue. Stop is a separate button.
- **Queue:** uses OpenCode 2.2.5's durable inbox (`delivery: queue`), not an app-only list that disappears on restart. Composer remains available during execution; queued text/attachments can be cancelled individually.
- **Chat drawer:** sessions and providers load concurrently; session list is replaced atomically and cached in app cache. Full model catalogue is lazy, loaded when opening Models/Providers. The default model and a cached catalogue remain usable without fetching the entire list on every boot.
- **Local errors:** bounded health-check timeouts, startup timeout is a real failure, early process exit is detected. Local Models shows common tensor/memory errors and an expandable last-15-lines log, including later router model-load failures.
- **Bonsai:** both `base` and `i8mm` binaries rebuilt from `PrismML-Eng/llama.cpp` commit `62c418ccd7aefee37997e94c2d9fac1eed7fc89b`. Router flags, subprocess support and tensor type 143 verified. Original library names retained; Android API 28, static C++ runtime, 16 KB ELF alignment.
- **Skills/MCP reload:** refresh invokes `POST /api/location/reload`; reload failures are not swallowed. The page refreshes status while open because discovery settles asynchronously. Installation/removal of skills reloads automatically, as did the original app.
- **Local MCP:** config uses `mcp.servers`. Runtime wrappers in Android's executable native-library directory expose `node`, `bun`, `npx`, `bunx`, plus Alpine-backed `python`, `python3`, `uv`, `uvx`, `npm` through PATH. Node-compatible JS runs on the embedded Bun via `BUN_BE_BUN=1`; npx invokes `bun x --bun`.
- Added missing `ACCESS_NETWORK_STATE` for DNS detection. Two existing Compose lint false positives are suppressed locally, with explanation; lint is not globally disabled.

## MCP limitations

This does not embed Python or promise that **every** npm MCP server works on Android. JS packages requiring incompatible native modules, a real Node executable, external tools or platform-specific libraries may still fail.
Python/uv/npm require Alpine installed and enabled and their packages installed there, e.g. from the app's Linux console.
Complex commands with quoted arguments should be configured as a JSON command array in `opencode.json` rather than the existing whitespace-splitting UI input.
Reload cancels pending forms/permissions in OpenCode; do it between steps, not while answering a pending question.

## Verification

- 22 JUnit tests: archives, forms, download resume, busy reconciliation, version comparison, signer checks, model and draft parsing.
- `:app:testDebugUnitTest`, `:app:lintRelease`, `:app:assembleRelease`.
- `engine/test-runtime.sh`: 4 runtime-dispatch checks.
- `engine/test-mcp.mjs`: memory, sequential-thinking, filesystem; initialize, tools/list and one tool call each on Windows with the same compiled OpenCode/Bun 2.2.5/1.4.4 runtime.
- Isolated host HTTP smoke test: string multiselect rejected, array accepted, next form preserved; queued FIFO order and cancellation; idle interrupt/active status; new skill discovered after reload.
- APK manifest/package/version and signature verified after signing. Native OpenCode/proot/musl/Claude/Linux binaries reused from the supplied APK; llama binaries rebuilt.

**Not verified on a phone:** Compose interactions, process survival in background, Android runtime wrappers/proot, Bonsai/Gemma inference, performance and installation. No ADB devices were connected. Host MCP/API tests are not Android end-to-end tests.

## Rebuild on this Windows machine

```powershell
cd C:\OpencodeMfix\OpencodeM
.\build.ps1
```

Uses JDK 17, Gradle 8, SDK Build Tools 35.0.0 and the local signing key; runs tests and lint before signing.
`engine/build-llama.sh` supports Git Bash with `NDK` and `NINJA` set to the installed SDK paths.
Do not commit `jniLibs/`, build output, downloaded engines or signing keys.
