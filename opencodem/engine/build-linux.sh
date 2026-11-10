#!/bin/sh
# proot (Termux's fork, with its Android fixes) and its loader, built with the NDK, plus
# the shell wrapper opencode runs commands through. Alpine itself is downloaded by the app.
# Output: app/src/main/jniLibs/arm64-v8a/libproot.so, libprootloader.so, liblinux.so
set -e
here=$(cd "$(dirname "$0")" && pwd)
PROOT_COMMIT=52d65a4dc140ca554d241a1438ddd4f6b00c9813
TALLOC=talloc_2.4.2.orig.tar.gz
TALLOC_SHA=$(cat "$here/talloc.sha256")
NDK=${NDK:-$HOME/.zcode/android-build/sdk/ndk/28.1.13356709}/toolchains/llvm/prebuilt/linux-x86_64/bin
out="$here/../app/src/main/jniLibs/arm64-v8a"
cd "$here/linux"
[ -d proot ] || git clone -q https://github.com/termux/proot.git proot
git -C proot checkout -q "$PROOT_COMMIT"
[ -f talloc.tar.gz ] || curl -fsSL -o talloc.tar.gz "https://deb.debian.org/debian/pool/main/t/talloc/$TALLOC"
echo "$TALLOC_SHA  talloc.tar.gz" | sha256sum -c - >/dev/null
[ -d talloc-2.4.2 ] || tar xzf talloc.tar.gz
mkdir -p build
cp "$here/talloc-replace.h" build/replace.h
"$NDK/aarch64-linux-android28-clang" -O2 -fPIC -Ibuild -Italloc-2.4.2 -c talloc-2.4.2/talloc.c -o build/talloc.o
rm -rf pbuild && cp -r proot pbuild
make -s -C pbuild/src -j8 CC="$NDK/aarch64-linux-android28-clang" STRIP="$NDK/llvm-strip" OBJCOPY="$NDK/llvm-objcopy" OBJDUMP="$NDK/llvm-objdump" \
    CFLAGS="-I$here/linux/talloc-2.4.2 -O2 -Wno-error=implicit-function-declaration -Wno-error=int-conversion -DPROOT_UNBUNDLE_LOADER=\\\".\\\"" \
    LDFLAGS="$here/linux/build/talloc.o -Wl,-z,noexecstack" PROOT_UNBUNDLE_LOADER=.
mkdir -p "$out"
cp pbuild/src/proot "$out/libproot.so"
cp pbuild/src/loader/loader "$out/libprootloader.so"
cp "$here/linux.sh" "$out/liblinux.so"
chmod +x "$out/"*.so
ls -la "$out"
