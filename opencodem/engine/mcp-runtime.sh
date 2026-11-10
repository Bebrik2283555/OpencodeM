#!/system/bin/sh
# Installed in nativeLibraryDir; app-writable directories cannot hold Android executables.
name=${0##*/}
case "$name" in
    node|bun)
        export BUN_BE_BUN=1
        exec "$MARGY_RUNTIME" "$@"
        ;;
    npx|bunx)
        [ "$1" != "-y" ] && [ "$1" != "--yes" ] || shift
        export BUN_BE_BUN=1
        exec "$MARGY_RUNTIME" "$MARGY_NPX" "$@"
        ;;
    python|python3|uv|uvx|npm|git)
        if [ -z "$MARGY_ROOTFS" ]; then
            echo "$name needs Alpine Linux enabled in MargyCode; install the runtime there first." >&2
            exit 127
        fi
        exec "$MARGY_ALPINE" -c 'exec "$@"' sh "$name" "$@"
        ;;
    *) echo "Unknown runtime: $name" >&2; exit 127 ;;
esac
