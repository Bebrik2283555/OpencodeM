#!/bin/sh
# musl's dynamic loader from Alpine (MIT), for running Claude Code's linux-arm64-musl
# build on Android, and the shell wrapper opencode calls as `claude`.
# Output: app/src/main/jniLibs/arm64-v8a/libldmusl.so and libclaude.so
set -e
here=$(cd "$(dirname "$0")" && pwd)
APK=musl-1.2.5-r13.apk
SHA=$(cat "$here/musl.sha256")
mkdir -p "$here/dl" "$here/../app/src/main/jniLibs/arm64-v8a"
[ -f "$here/dl/$APK" ] || curl -fsSL -o "$here/dl/$APK" "https://dl-cdn.alpinelinux.org/alpine/v3.22/main/aarch64/$APK"
echo "$SHA  $here/dl/$APK" | sha256sum -c - >/dev/null
tmp=$(mktemp -d)
tar xzf "$here/dl/$APK" -C "$tmp" 2>/dev/null
python3 - "$tmp/lib/ld-musl-aarch64.so.1" "$here/../app/src/main/jniLibs/arm64-v8a/libldmusl.so" <<'PY'
import sys
d = open(sys.argv[1], 'rb').read()
a, b = b'/etc/resolv.conf', b'/proc/self/fd//9'
assert len(a) == len(b) and d.count(a) == 1
open(sys.argv[2], 'wb').write(d.replace(a, b))
PY
rm -rf "$tmp"
cp "$here/claude.sh" "$here/../app/src/main/jniLibs/arm64-v8a/libclaude.so"
chmod +x "$here/../app/src/main/jniLibs/arm64-v8a/"*.so
ls -la "$here/../app/src/main/jniLibs/arm64-v8a/"
