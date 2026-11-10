# MargyCode / OpencodeM — working notes

Read this before changing anything. The app is a phone client that runs the real **opencode 2.2.5**
inside Android. The user-facing name is **OpencodeM**; the package is now **com.opencode**.
The old package `cat.narezany.margycode` is the previous build and still exists on the user's phone.

## Repository layout

- `margycode/` — Android app (Kotlin, Jetpack Compose, Material 3).
  - `app/src/main/kotlin/com/opencode/` — sources (`Brain.kt` is the state holder; `ui/` screens; `engine/` process wrappers).
  - `app/src/main/cpp/` — `libmargypty` (forkpty) for the terminal.
  - `app/src/main/assets/` — `app.md` (system prompt), `linux.md`, `android.md`, `terminal/` (xterm.js), `npx.mjs`, `phone-tools.mjs`, `margy-runtime.mjs` (opencode plugin).
  - `app/src/main/jniLibs/arm64-v8a/` — engine binaries (gitignored): `libopencode.so` (Bun+opencode), `libproot*.so`, `libldmusl.so`, `liblinux.so`, `libshell-runtime.so`, `liblinuxchroot.so`, `libllamaserver*.so`, `libclaude.so`.
  - `engine/` — build scripts for opencode, proot/Alpine, llama.cpp (Prism fork), runtime wrappers, host tests.
- `TODO-MargyCode.md` — bugs/plans. `margycode/FIXES-0.2.x.md` — what was fixed and verified on device.
- `OpencodeM-*.apk` at repo root — signed release builds. `margycode/keys/` — local signing key (private, gitignored).
- `opencode.json` at repo root only configures **this laptop's** opencode session (ponytail), not the app.

## Build and device

Windows host, no git repo. Paths:

- SDK: `C:\Users\Alihan\AppData\Local\Android\Sdk`
- ADB: `...\platform-tools\adb.exe`; device `49933df7` (M2102J20SG, Android 16, rooted KernelSU).
- JDK 17: `C:\Users\Alihan\.jdks\17\jdk-17.0.20+8`; Gradle 8.14.5 under `~/.gradle/wrapper/dists`.
- NDK 28.2 / CMake 3.22.1 installed in the SDK.

Build: `cd margycode; .\build.ps1` (runs JUnit, lint, release, signs with `keys/`). Output `out/OpencodeM-<version>.apk` (and root `OpencodeM-<version>.apk`).
Install: `adb -s 49933df7 install -r out/<apk>`. VersionCode must increase.

**Encoding pitfall:** Windows PowerShell 5 reads `.kt/.md/.xml` as ANSI. Never `Set-Content`/`Get-Content -Raw | Set-Content`
on files with Cyrillic. Use the `edit`/`write` tools, or `[IO.File]::ReadAllText(path,[Text.UTF8Encoding]::new($false))`
and `WriteAllText(..., utf8)`. A past bulk rename corrupted all sources; recovery was cp1251→UTF-8 round-trip.
---

## Runtime architecture

- `EngineService` (foreground service) owns `Brain`, so rotation/backgrounding does not recreate state.
  `Brain` is constructed with the service scope; UI binds to it. Notification actions: Open, Stop/Continue, Close engine.
- `Brain` holds prefs, sessions (incl. children), models, agents, commands, permissions, downloads, terminals, drafts.
- `Engine` starts `libopencode.so serve --hostname 127.0.0.1 --port <random>` with `OPENCODE_PASSWORD`, waits for `/api/info`.
  Env: HOME=files/home, XDG dirs, PATH includes `~/.local/margy-bin` wrappers and nativeLibraryDir.
- API client `engine/Api.kt`; base `http://127.0.0.1:<port>`, Basic auth `opencode:<password>`.
- The engine's API is opencode 2.2.5. Verify shapes in `opencode-dev/`
  (source checkout) or `opencode.ai/v2/openapi.json`. Do not guess endpoints.

Key API quirks already handled:

- Form fields are `multiselect` (not `array`); answers must be arrays/numbers/booleans, not strings.
- Pending forms are per location: `GET /api/form?location[directory]=...`; permissions likewise.
- Queue: `POST /session/{id}/prompt` with `delivery:"queue"`; cancel `DELETE /session/{id}/inbox/{id}`;
  resume/steer `PATCH .../inbox/{id} {delivery:"steer"}`; stop `POST .../interrupt`.
- Slash commands: `POST /session/{id}/command {name,text,...}`. Shell messages: `POST /session/{id}/shell {command}`.
- Move folder: `POST /session/{id}/move {directory}` (keeps id/history; only when idle).
- Model/agent: `POST /session/{id}/model`, `POST /session/{id}/agent`; `/api/model` omits disabled models.
- Provider disable = top-level `policies: [{action:"provider.use",resource:id,effect:"deny"}]`; model disable =
  `providers.<id>.models.<model>.disabled=true`. Custom providers use package `@opencode/ai/providers/openai-compatible`.
- Tool file results must use `data:<mime>;base64,...` (a `file:` URI fails: "Local file references must be read with Media.file").
- Sessions list includes children (`parentID`); the drawer shows only roots; Tasks shows children.
- `snapshot` variants: `/api/session/active` tells real busy state; `/slots` fields are per-prefix for llama.

## Command runtimes (important)

`libshell-runtime.so` is `SHELL`. The `shell` tool has `runtime: auto|native|alpine` and `root`.

- `native` = Android `/system/bin/sh`.
- `alpine` = Alpine rootfs via `liblinux.so` → proot (fake root) or, with `prefs.alpineRoot`,
  chroot through `liblinuxchroot.so` under `su`. `auto` prefers Alpine when installed+enabled.
- **proot on this device hangs on any pipe** (`echo a | cat`) — kernel/proot incompatibility. chroot works and is
  enabled on the user's phone (`alpineRoot=true`). Do not remove the chroot fallback.
- chroot helper bind-mounts `/data/data/<pkg>`, `/data/user/0/<pkg>` and `/storage/emulated/0`, sets HOME/PATH,
  and runs the command via `/bin/sh -c`. Interactive terminal uses `-i` → `exec /bin/sh -i`.
- Alpine `apk` busybox trigger can hang; timeout + `apk --no-scripts` workaround for curl only.
- Shell output format: first line is always `[runtime=<runtime> root=<bool> exit=<code>]`; when `exit != 0`,
  a failure marker `--- exit code indicates failure ---` is shown before command output.

## App features and files

- Screens: chat, drawer (search, project, tasks, context, limits, tools, providers, settings, local models, extensions, backup, permissions, agents, files, changes, terminal).
- Projects: multi-project manager with project selector in chat header and project screen. Chats are bound to projects.
- Composer: attachments (`+` menu: images, files, camera via FileProvider, `/` commands, `@` skills/agents, `!` shell, voice),
  Enter sends, Shift+Enter newline, queue list with cancel, draft per chat (`Draft.folder/text/files/skills/agents`).
- Folder: per-chat `move`; for an unsaved chat stored in the draft, not global prefs. SAF tree → path via
  `DocumentsContract` + `StorageManager` (`Brain.pathOfTree`).
- Providers: custom OpenAI-compatible add dialog; provider/model switches (custom providers); models list merges config
  entries so disabled models can be re-enabled.
- Permissions: `default` asks; `turbo` replaces agent rules with allow-all (and skips our root/phone assert).
- Runtime plugin (`margy-runtime.mjs`):
  - `read`: capped at 64 KB per turn with explicit truncation notice; tracks file `mtimeMs`.
  - `edit`: `changed-since-last-read` concurrency guard prevents overwriting changes made on disk after last read.
  - `undo`: per-session undo stack persisted to `$XDG_STATE_HOME/opencode/undo` (last 10 items); restores previous file contents or deletes newly created files.
  - Scratchpad `.opencode/notes.md`: automatically injected into system prompt on every turn; survives compaction and new chats.
  - Context budget: model sees `[Context budget: ~Xk / Yk tokens (Z% used)]` in system context on each turn.
  - Smart compaction: `session.compaction` hook directs summarizer to keep modified files, recent errors, and pending tasks.
  - Remote MCP timeout: 5s connection guard on fetch for HTTP/SSE MCP endpoints.
- Phone tool (`phone`) in `phone-tools.mjs`:
  - Screencap: raw RGBA capture via `/system/bin/screencap` + pure JS `deflateSync` PNG encoder (20-40 KB vs 3 MB, ~20ms, no native deps).
  - Supports `scale` (1..8, default 2 for 540x1200) and `crop` ({ x, y, width, height } in screen pixels).
  - tap/swipe/key/launch/dumpsys/apps via `su -c`; registered when `prefs.phoneControl=true`; permission action `phone`.
- MCP: config must be `mcp.servers.<name>`; `npx` is `npx.mjs` because `bun x` fails on Android; Python/npm go through Alpine.
- Extensions: npm/GitHub plugin, GitHub skill bundle, remote/local MCP. Backup: zip of chats + prefs + config (no credentials/models/Alpine).
- Local models: llama-server router (`-np 1 -b 64 -ub 32`), Prism fork for PTQ1_0/type 143; progress from slots
  (`n_prompt_tokens_total` patch). Instant model removal via `Brain.removeLocalModel(id)` with immediate `modelsCache.json` sync.
- Context compaction: `Brain.currentContextTokens()` detects compaction messages so top-bar token chips immediately drop to ~1%.

## State, notifications, background

- Closing the app from recents leaves the service/engine running by design; "Close engine" in the notification stops
  engine/Alpine/terminals, removes foreground, and the screen offers restart. Reopening calls `EngineService.start`.
- Watchdog reconciles stale busy via `/api/session/active`; stream deltas use `live`, history is re-read.

## Packaging, signing, migration

- Same signing key as previous MargyCode builds (`margycode/keys`), but package changed to `com.opencode`.
  Android treats it as a **new app**; old app data is not visible/migrated automatically.
- Migration approach used: copy old `files/` (home incl. DB, config, skills, models, linux, media, workspace, prefs)
  into new package as root, chown to new uid, then move sessions to the new workspace via `/api/session/{id}/move`.
  Copy can take minutes; ADB shell is root, no `su` needed for `cp`.
- **SELinux after restoring data:** `cp -a` keeps the old app's categories and `adb push` writes `s0` without any;
  both make files unreadable to the new app (engine silently ignores `opencode.json`, providers/models vanish).
  After any restore, set the app's own context on the data tree:
  `chcon -R u:object_r:app_data_file:s0:c137,c257,c512,c768 /data/user/0/com.opencode/files`
  (take the exact categories from a file the app created, e.g. `files/prefs.json`; `restorecon` does not fix pushed files).
- Old opencode DB keeps absolute directory paths; always `move` sessions after copying or they will target the old app dir.
- Icon: the opencode logo everywhere — adaptive `mipmap-anydpi-v26/ic_launcher.xml` with a black background and
  white `ic_launcher_foreground` / `ic_launcher_mono`, full-size `drawable/logo.xml` for the in-app `Logo()`, and
  `drawable/ic_notify.xml` for the foreground notification. App label `OpencodeM`.
  SystemUI caches the notification icon per package, so after changing it the old icon can stay until SystemUI is
  restarted (`su -c "pkill -f com.android.systemui"`) or the phone reboots.

## Tests

- Kotlin unit tests: `margycode/app/src/test/kotlin/com/opencode/...` (`:app:testDebugUnitTest` via build.ps1).
- Host JS tests: `bun test` in `margycode/engine/` (18 tests); on a host with Node the same files run with `node --test`. The runtime plugin test forces `MARGY_PERMISSION_MODE=default` itself.
- Shell: `bash engine/test-runtime.sh`. Device checks are ad-hoc scripts in `%TEMP%\opencode\margy-*` (API via adb forward,
  password from `/proc/<engine pid>/environ`).
- Always test on the phone after changes: engine boot, chat send, forms/queue, terminal, Alpine compound command,
  providers, folder move. Local Bonsai is slow; do not run tests with him.

## Current known problems

- Engine boot can fail before writing `opencode.log` if `beforeStart()` throws (missing asset, symlink, config).
  Check `files/opencode.log`, then app data dirs, then logcat.
- `prefs.lastSession` may point to a removed test chat; boot guards it.
- Limits UI cannot show quotas the API does not return (currently only workspace/budgets, often empty).
- Settings screen text was restored from an older snapshot after an encoding accident; verify wording when editing.
