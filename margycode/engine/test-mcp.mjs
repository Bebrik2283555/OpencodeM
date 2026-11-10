// Host smoke test for the runtime wrapper with Bun's compiled-executable mode.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { copyFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';

assert.ok(process.env.MARGY_RUNTIME, 'Set MARGY_RUNTIME to an opencode/Bun executable');
const dir = mkdtempSync(join(tmpdir(), 'margy-mcp-'));
const wrapper = join(dir, 'npx').replaceAll('\\', '/');
copyFileSync(new URL('./mcp-runtime.sh', import.meta.url), wrapper);
try {
  for (const [pkg, args, tool, input] of [
    ['server-memory', [], 'read_graph', {}],
    ['server-sequential-thinking', [], 'sequentialthinking', {
      thought: 'Runtime smoke test', thoughtNumber: 1, totalThoughts: 1, nextThoughtNeeded: false,
    }],
    ['server-filesystem', [dir], 'list_allowed_directories', {}],
  ]) {
    const child = spawn(process.env.BASH || 'bash', [wrapper, '-y', `@modelcontextprotocol/${pkg}`, ...args], {
      env: { ...process.env, MARGY_NPX: fileURLToPath(new URL('../app/src/main/assets/npx.mjs', import.meta.url)),
        MEMORY_FILE_PATH: join(dir, 'memory.json') }, stdio: ['pipe', 'pipe', 'pipe'],
    });
    let stderr = '';
    child.stderr.on('data', chunk => { stderr = (stderr + chunk).slice(-4000); });
    const pending = new Map();
    child.on('exit', code => {
      for (const finish of [...pending.values()]) finish({ error: { message: `Process exited (${code}): ${stderr}` } });
    });
    let id = 0;
    const lines = createInterface({ input: child.stdout });
    lines.on('line', line => {
      try {
        const result = JSON.parse(line);
        pending.get(result.id)?.(result);
      } catch { /* Package-manager output is not a protocol response. */ }
    });
    function call(method, params) {
      return new Promise((resolve, reject) => {
        const key = ++id;
        const timeout = setTimeout(() => { pending.delete(key); reject(new Error(`${pkg} ${method}: ${stderr}`)); }, 120_000);
        pending.set(key, result => {
          clearTimeout(timeout);
          pending.delete(key);
          result.error ? reject(new Error(JSON.stringify(result.error))) : resolve(result.result);
        });
        child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: key, method, params }) + '\n');
      });
    }
    try {
      child.on('error', error => { stderr += error.message; });
      const initialized = await call('initialize', {
        protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'margy-runtime-test', version: '1' },
      });
      assert.ok(initialized.serverInfo);
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
      const list = await call('tools/list', {});
      assert.ok(list.tools.some(item => item.name === tool));
      const result = await call('tools/call', { name: tool, arguments: input });
      assert.ok(!result.isError, JSON.stringify(result));
      assert.ok(Array.isArray(result.content));
      console.log(`${pkg}: initialize, tools/list, ${tool} passed`);
    } finally {
      lines.close();
      child.stdin.end();
      child.kill();
    }
  }
} finally {
  rmSync(dir, { recursive: true, force: true });
}
