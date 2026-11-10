#!/system/bin/sh
# The agent's shell: Alpine Linux from the app's files, run through proot (fake root,
# hard links turned into symlinks, which Android forbids). The phone's own folders are
# bound at the same paths, so the working directory means the same inside and out.
export PROOT_LOADER="$MARGY_PROOT_LOADER"
# Each proot needs its own scratch dir: a shared one deadlocks when the agent runs commands in parallel.
tmp="$MARGY_PROOT_TMP/$$"
mkdir -p "$tmp" || exit 126
PROOT_TMP_DIR="$tmp"
export PROOT_TMP_DIR
trap 'rm -rf "$tmp"' EXIT
data=${MARGY_FILES%/files}
package=${data##*/}
if [ "$MARGY_ALPINE_ROOT" = 1 ]; then
    if [ ! -x /system/bin/su ] && [ ! -x /system/xbin/su ]; then
        echo 'Alpine root mode requires root (/system/bin/su missing). Disable "Alpine through root" in Linux settings.' >&2
        exit 127
    fi
    if [ "$1" = -c ]; then cmd=$2; else cmd='exec /bin/sh -i'; fi
    [ -n "$cmd" ] || { echo 'No command to run.' >&2; exit 2; }
    MARGY_CMD_FILE="$tmp/cmd"
    printf '%s' "$cmd" > "$MARGY_CMD_FILE" || exit 126
    export MARGY_CMD_FILE
    exec /system/bin/su -c "$MARGY_CHROOT"
fi
export PROOT_NO_SECCOMP=1
exec "$MARGY_PROOT" --kill-on-exit -0 --link2symlink -r "$MARGY_ROOTFS" \
    -b /dev -b /proc -b /sys -b /system -b /apex -b /storage \
    -b "$data:/data/user/0/$package" -b "$data:/data/data/$package" -w "$PWD" \
    /usr/bin/env -u LD_LIBRARY_PATH -u LD_PRELOAD \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    HOME="$HOME" TMPDIR=/tmp LANG=C.UTF-8 TERM=xterm-256color SHELL=/bin/sh \
    /bin/sh "$@"
