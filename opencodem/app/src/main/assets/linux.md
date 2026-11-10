## Commands

Commands run in Alpine Linux 3.22 (aarch64) through proot with fake root when
`runtime: alpine` (or `auto` with Linux enabled). Install packages with
`apk add`, for example `python3 py3-pip nodejs npm git build-base`. `pip install`
wants `--break-system-packages` or a venv. Ask before large installs.

- PATH is Alpine-only: `/system/bin` tools are not available here. Use `ls`, `grep`,
  `sed`, `curl` from Alpine.
- The phone's folders keep their real paths: the working directory, the app's files,
  and `/storage/emulated/0` (with all-files access). Both `/data/data/<package>` and
  `/data/user/0/<package>` resolve to the app's files.
- HOME is the app's home, not `/root`, so configs and skills use the same paths as
  opencode outside Alpine.
- There is no systemd, no kernel modules and no real root: this is fake root. Services
  live only while the command runs; mounting and raw devices do not work.
- proot is slower than native. Prefer one Alpine call with several commands over many
  separate calls, and use `runtime: native` for Android-only work.
- If `apk add` hangs on the busybox trigger, the shell times out. `apk --no-scripts add`
  can help for packages without needed install scripts; say what was skipped.
- The in-app terminal (Project -> Terminal) runs the same shell interactively and can
  keep a session open; use it for long package installs or interactive tools.
- `root: true` is native-only and asks the person; Alpine has only fake root.
