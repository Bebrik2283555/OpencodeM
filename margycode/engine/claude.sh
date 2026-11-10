#!/system/bin/sh
# Claude Code as Anthropic publishes it for Linux (the musl build from npm), run on
# Android through musl's own loader. That loader is patched to read DNS servers from
# /proc/self/fd//9 instead of /etc/resolv.conf, which Android doesn't have; the app
# writes the phone's servers to MARGY_RESOLV and it is handed over on fd 9 here.
exec "$MARGY_LDMUSL" "$MARGY_CLAUDE_BIN" "$@" 9<"$MARGY_RESOLV"
