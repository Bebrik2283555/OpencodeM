#!/system/bin/sh
# Runs one Alpine command through a real chroot. Executed as root via su by liblinux.so.
# The command is passed in MARGY_CMD_FILE because quoting it through su is fragile.
R=$MARGY_ROOTFS
F=$MARGY_FILES
[ -n "$R" ] && [ -f "$R/etc/alpine-release" ] || { echo 'Alpine is not installed.' >&2; exit 127; }
[ -f "$MARGY_CMD_FILE" ] || { echo 'No command to run.' >&2; exit 2; }
cmd=$(cat "$MARGY_CMD_FILE")
rm -f "$MARGY_CMD_FILE"
cd "$PWD" || exit 1
# Keep the app workspace, home, and shared storage at the same paths inside the rootfs
# without mounting the parent data dir into itself (which creates an infinite mount loop).
data=${F%/files}
package=${data##*/}
for pair in "/dev:/dev" "/proc:/proc" "/sys:/sys" "/storage/emulated/0:/storage/emulated/0" \
    "$F/workspace:$data/files/workspace" "$F/workspace:/data/data/$package/files/workspace" \
    "$F/home:$data/files/home" "$F/home:/data/data/$package/files/home"; do
    src=${pair%%:*}; dst=$R${pair#*:}
    mkdir -p "$dst" 2>/dev/null
    grep -q " $dst " /proc/mounts || /system/bin/mount --bind "$src" "$dst" 2>/dev/null
done
exec /system/bin/chroot "$R" /usr/bin/env -i \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    HOME="$HOME" TMPDIR=/tmp LANG=C.UTF-8 TERM=xterm-256color SHELL=/bin/sh \
    MARGY_PWD="$PWD" MARGY_CMD="$cmd" \
    /bin/sh -c 'cd "$MARGY_PWD" || exit 1; exec /bin/sh -c "$MARGY_CMD"'
