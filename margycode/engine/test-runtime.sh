#!/bin/sh
set -eu
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/runtime" <<'SH'
#!/bin/sh
printf '%s\n' "${BUN_BE_BUN:-}" "$@"
SH
chmod +x "$tmp/runtime"
export MARGY_RUNTIME="$tmp/runtime"
export MARGY_NPX="$tmp/npx.mjs"
for name in node npx bunx python3; do
    cp "$here/mcp-runtime.sh" "$tmp/$name"
done
test "$(sh "$tmp/node" 'file with spaces.js' 'a b')" = "$(printf '1\nfile with spaces.js\na b')"
test "$(sh "$tmp/npx" -y '@modelcontextprotocol/server-memory')" = "$(printf '1\n%s\n@modelcontextprotocol/server-memory' "$MARGY_NPX")"
test "$(sh "$tmp/bunx" '@modelcontextprotocol/server-filesystem' 'a b')" = "$(printf '1\n%s\n@modelcontextprotocol/server-filesystem\na b' "$MARGY_NPX")"
unset MARGY_ROOTFS
if sh "$tmp/python3" server.py 2> "$tmp/error"; then exit 1; fi
grep -q 'Alpine Linux' "$tmp/error"
export MARGY_ROOTFS="$tmp/rootfs" MARGY_ALPINE="$tmp/runtime"
test "$(sh "$tmp/python3" 'server with spaces.py')" = "$(printf '\n-c\nexec "$@"\nsh\npython3\nserver with spaces.py')"
printf 'runtime dispatch: 5 checks passed\n'
