#!/system/bin/sh
mode=${MARGY_CALL_RUNTIME:-${MARGY_DEFAULT_RUNTIME:-auto}}
if [ "${MARGY_CALL_ROOT:-0}" = 1 ]; then
    [ "$mode" != alpine ] || { echo 'Real root is native-only.' >&2; exit 2; }
    if [ ! -x /system/bin/su ] && [ ! -x /system/xbin/su ]; then
        echo 'Root is not available on this device (su missing).' >&2
        exit 127
    fi
    exec /system/bin/su -s /system/bin/sh "$@"
fi
if [ "$mode" = auto ]; then
    # Alpine keeps Android's /system/bin out of PATH, so a native run of e.g. `ls /` would
    # hit Android permissions instead of the Linux layout. Prefer Alpine whenever it is ready.
    if [ -n "$MARGY_ROOTFS" ] && [ -f "$MARGY_ROOTFS/etc/alpine-release" ]; then
        mode=alpine
    else
        mode=native
    fi
fi
case "$mode" in
    native) exec /system/bin/sh "$@" ;;
    alpine)
        [ -n "$MARGY_ROOTFS" ] || { echo 'Alpine is not installed. Install it in MargyCode > Linux.' >&2; exit 127; }
        exec "$MARGY_ALPINE" "$@"
        ;;
    *) echo "Invalid runtime: $mode" >&2; exit 2 ;;
esac
