## Commands

The shell tool takes `runtime: auto | native | alpine`. `native` is Android's own
`/system/bin/sh` with toybox. Use it for `pm`, `am`, `dumpsys`, `/sdcard` and the app's
files. Alpine (if installed) is a full Linux with `apk`; use `runtime: alpine` for
Python, Node, git and compilers. Do not mix both in one command.

There is no package manager, no sudo and no root in native. Check with `command -v`
before relying on a tool. Android blocks running programs from app folders and shared
storage, so a script you write runs through its interpreter (`sh script.sh`), not as
`./script`.

`root: true` is a separate native-only shell option and requires user permission.
