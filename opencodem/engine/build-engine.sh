#!/bin/sh
# Builds opencode for Android: upstream source (engine/opencode, a tag of
# anomalyco/opencode) compiled onto the official Bun for Android from oven-sh.
# Output: app/src/main/jniLibs/arm64-v8a/libopencode.so, a plain executable
# named like a library so Android unpacks it where it may be run from.
set -e
here=$(cd "$(dirname "$0")" && pwd)
BUN_VERSION=1.4.4
OPENCODE_TAG=v2.2.5
cd "$here"
mkdir -p dl
fetch() {
    [ -f "dl/$1" ] || curl -fsSL -o "dl/$1" "https://github.com/oven-sh/bun/releases/download/bun-v$BUN_VERSION/$1"
}
fetch bun-linux-aarch64-android.zip
fetch bun-linux-x64.zip
curl -fsSL -o dl/SHASUMS256.txt "https://github.com/oven-sh/bun/releases/download/bun-v$BUN_VERSION/SHASUMS256.txt"
(cd dl && grep -E '  (bun-linux-aarch64-android|bun-linux-x64)\.zip$' SHASUMS256.txt | sha256sum -c -)
[ -x bun-android/bun-linux-aarch64-android/bun ] || unzip -oq dl/bun-linux-aarch64-android.zip -d bun-android
[ -x bun-x64/bun-linux-x64/bun ] || unzip -oq dl/bun-linux-x64.zip -d bun-x64
export PATH="$here/bun-x64/bun-linux-x64:$PATH"

if [ ! -d opencode ]; then
    git clone -q --depth 1 --branch "$OPENCODE_TAG" https://github.com/anomalyco/opencode.git opencode
    (cd opencode && git apply ../patches/android-bun.patch && bun install)
    # the arm64 builds of opencode's native helpers, as its own build fetches them
    (cd opencode/packages/cli && bun install --os="*" --cpu="*" --libc="*" @opentui/core@$(bun -e 'console.log(require("./package.json").dependencies["@opentui/core"])') @opencode/pty@$(bun -e 'console.log(require("./package.json").dependencies["@opencode/pty"])'))
fi
cd opencode/packages/cli
MARGY_BUN_EXECUTABLE="$here/bun-android/bun-linux-aarch64-android/bun" OPENCODE_TARGET=linux-arm64 \
    OPENCODE_CHANNEL=latest OPENCODE_VERSION="${OPENCODE_TAG#v}" \
    bun run script/build.ts --skip-install --skip-web-ui --outdir="$here/out"
mkdir -p "$here/../app/src/main/jniLibs/arm64-v8a"
cp "$here/out/cli-linux-arm64/bin/opencode" "$here/../app/src/main/jniLibs/arm64-v8a/libopencode.so"
ls -la "$here/../app/src/main/jniLibs/arm64-v8a/libopencode.so"
