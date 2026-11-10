import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

process.env.XDG_STATE_HOME = mkdtempSync(join(tmpdir(), 'margy-state-'));
import plugin, { jsGlob, jsGrep, parseTscOutput } from '../app/src/main/assets/margy-runtime.mjs';

async function fixture(permission = async () => {}) {
  const hooks = {};
  const calls = [];
  const shellTool = { id: 'shell', description: 'Original shell', execute: async (input, context) => {
    const event = { shell: 'original', env: {}, command: input.command };
    await hooks['create.before'](event);
    calls.push({ input, event, context });
    const exit = input.mockExit ?? 0;
    return { output: { output: 'ok', exit }, content: [{ type: 'text', text: 'ok' }], metadata: { exit } };
  } };
  const readTool = { id: 'read', description: 'Original read', execute: async (input) => {
    return { content: [{ type: 'text', text: input.mockText ?? 'file content' }], metadata: {} };
  } };
  const editTool = { id: 'edit', description: 'Original edit', execute: async (input) => {
    return { output: { replacements: 1 }, content: [{ type: 'text', text: 'edited' }] };
  } };
  const writeTool = { id: 'write', description: 'Original write', execute: async (input) => {
    return { content: [{ type: 'text', text: 'written' }] };
  } };
  const addedTools = new Map();
  const tools = { shell: shellTool, read: readTool, edit: editTool, write: writeTool };
  const agent = { id: 'build', permissions: [{ action: 'edit', resource: '*', effect: 'deny' }] };
  const previousMode = process.env.MARGY_PERMISSION_MODE;
  process.env.MARGY_PERMISSION_MODE = 'default';
  try {
    await plugin.setup({
      shell: { hook: async (key, callback) => { hooks[key] = callback; } },
      session: { hook: async (key, callback) => { hooks[key] = callback; } },
      tool: { transform: async fn => fn({
        update: (id, callback) => { if (tools[id]) callback(tools[id]); },
        add: tool => { addedTools.set(tool.name, tool); },
      }) },
      agent: { transform: async fn => fn({ list: () => [agent], update: (_, callback) => callback(agent) }) },
      permission: { assert: permission },
    });
  } finally {
    if (previousMode === undefined) delete process.env.MARGY_PERMISSION_MODE;
    else process.env.MARGY_PERMISSION_MODE = previousMode;
  }
  return { hooks, calls, shellTool, readTool, editTool, writeTool, addedTools, agent };
}

test('runtime selection echoes runtime header and indicates failure on non-zero exit', async () => {
  const f = await fixture();
  const context = { sessionID: 'ses_test', agent: 'build', messageID: 'msg_test', id: 'tool_test' };
  const res0 = await f.shellTool.execute({ command: 'pwd', runtime: 'native', timeout: 123 }, context);
  assert.equal(res0.content[0].text, '[runtime=native root=false exit=0]\nok');

  const res1 = await f.shellTool.execute({ command: 'false', mockExit: 1, runtime: 'alpine' }, context);
  assert.match(res1.content[0].text, /\[runtime=alpine root=false exit=1\]/);
  assert.match(res1.content[0].text, /--- exit code indicates failure ---/);
});

test('root rejection stops execution and Alpine cannot request real root', async () => {
  const f = await fixture(async () => { throw new Error('Denied'); });
  await assert.rejects(f.shellTool.execute({ command: 'id', runtime: 'native', root: true }, {}), /Denied/);
  await assert.rejects(f.shellTool.execute({ command: 'id', runtime: 'alpine', root: true }, {}), /native-only/);
  assert.equal(f.calls.length, 0);
});

test('a non-root command that calls su asks for root permission', async () => {
  let asked = null;
  const f = await fixture(async input => { asked = input; });
  await f.shellTool.execute({ command: 'su -c id', runtime: 'native' }, { sessionID: 's1', agent: 'build', messageID: 'm1', id: 'c1' });
  assert.equal(asked?.action, 'root');
});

test('parallel calls do not leak runtime selection', async () => {
  const f = await fixture();
  await Promise.all(['native', 'alpine'].map(runtime => f.shellTool.execute({ command: runtime, runtime }, {})));
  assert.deepEqual(f.calls.map(c => [c.input.command, c.event.env.MARGY_CALL_RUNTIME]), [['native', 'native'], ['alpine', 'alpine']]);
});

test('read tool truncates text output exceeding 64 KB and sets truncated metadata', async () => {
  const f = await fixture();
  const bigText = 'A'.repeat(100 * 1024);
  const res = await f.readTool.execute({ mockText: bigText }, {});
  assert.equal(res.content[0].text.length < bigText.length, true);
  assert.match(res.content[0].text, /Truncated: showing 64 KB of 100 KB/);
  assert.equal(res.metadata.truncated, true);
});

test('undo tool restores previous file contents', async () => {
  const f = await fixture();
  const testDir = join(tmpdir(), 'undo-test-' + Date.now());
  mkdirSync(testDir, { recursive: true });
  const testFile = join(testDir, 'sample.txt');
  writeFileSync(testFile, 'initial content');

  const oldCwd = process.cwd();
  process.chdir(testDir);
  try {
    // Read and then edit
    await f.readTool.execute({ path: 'sample.txt' }, {});
    await f.editTool.execute({ path: 'sample.txt', oldString: 'initial', newString: 'modified' }, {});
    writeFileSync(testFile, 'modified content');

    const undoTool = f.addedTools.get('undo');
    assert.ok(undoTool);
    const undoRes = await undoTool.execute({});
    assert.match(undoRes.content, /Reverted 'sample.txt'/);
  } finally {
    process.chdir(oldCwd);
    rmSync(testDir, { recursive: true, force: true });
  }
});

test('project notes from .opencode/notes.md and context budget are injected into system context', async () => {
  const f = await fixture();
  const notesDir = join(tmpdir(), 'notes-test-' + Date.now(), '.opencode');
  mkdirSync(notesDir, { recursive: true });
  writeFileSync(join(notesDir, 'notes.md'), 'Important project architecture note');
  const oldCwd = process.cwd();
  process.chdir(join(notesDir, '..'));
  try {
    const event = {
      model: { providerID: 'remote', model: { limit: { context: 100000 } } },
      inputTokens: { measured: 25000 },
      agent: 'build',
      system: [{ type: 'text', text: 'base' }],
    };
    await f.hooks.context(event);
    assert.equal(event.system.some(p => p.text.includes('Important project architecture note')), true);
    assert.equal(event.system.some(p => p.text.includes('[Context budget: ~25k / 100k tokens (25% used)]')), true);
  } finally {
    process.chdir(oldCwd);
    rmSync(join(notesDir, '..'), { recursive: true, force: true });
  }
});

test('compaction hook injects priority rules for mobile compaction', async () => {
  const f = await fixture();
  const event = { system: [{ type: 'text', text: 'base' }] };
  await f.hooks.compaction(event);
  assert.equal(event.system.some(p => p.text.includes('PRIORITY RULES FOR MOBILE COMPACTION')), true);
});

test('local titles do not invoke inference and other titles are untouched', async () => {
  const f = await fixture();
  const event = { model: { providerID: 'phone' }, messages: [{ role: 'user', content: [{ type: 'text', text: ' hello\n world ' }] }] };
  f.hooks.title(event);
  assert.equal(event.result, 'hello world');
  const remote = { model: { providerID: 'remote' }, messages: [] };
  f.hooks.title(remote);
  assert.equal(remote.result, undefined);
});

test('plain local chat disables thinking without consuming the original request', async () => {
  const f = await fixture();
  const request = new Request('http://localhost/v1/chat/completions', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ messages: [], chat_template_kwargs: { other: true } }) });
  const event = { model: { providerID: 'phone' }, agent: 'chat', request };
  await f.hooks['http.request'](event);
  assert.deepEqual((await event.request.json()).chat_template_kwargs, { other: true, enable_thinking: false });
  assert.deepEqual((await request.json()).chat_template_kwargs, { other: true });
});

test('plain chat keeps user instructions and history while removing unused tool guidance', async () => {
  const f = await fixture();
  const history = [{ role: 'user', content: [{ type: 'text', text: 'remember this' }] }];
  const event = { model: { providerID: 'phone' }, agent: 'chat', tools: { skill: {} }, messages: history,
    system: [{ type: 'text', text: 'USER RULES\nENVIRONMENT', metadata: { experimental: { instructions: [
      { key: 'core/instructions', text: 'USER RULES' }, { key: 'core/environment', text: 'ENVIRONMENT' },
    ] } } }, { type: 'text', text: 'PLUGIN RULE' }] };
  await f.hooks.context(event);
  assert.deepEqual(event.tools, {});
  assert.equal(event.messages, history);
  assert.deepEqual(event.system.map(p => p.text), ['USER RULES', 'PLUGIN RULE']);
  const remote = { model: { providerID: 'remote' }, agent: 'chat', tools: { skill: {} } };
  await f.hooks.context(remote);
  assert.deepEqual(remote.tools, { skill: {} });
});

test('jsGlob and jsGrep pure JS fallback finds files and matching content', async () => {
  const testDir = join(tmpdir(), 'grep-test-' + Date.now());
  mkdirSync(testDir, { recursive: true });
  writeFileSync(join(testDir, 'alpha.ts'), 'export const hello = "world";\nconsole.log(123);\n');
  writeFileSync(join(testDir, 'beta.txt'), 'some test text without code\n');
  const oldCwd = process.cwd();
  process.chdir(testDir);
  try {
    const globRes = await jsGlob({ pattern: '*.ts' });
    assert.equal(globRes.output.length, 1);
    assert.equal(globRes.output[0].path, 'alpha.ts');

    const dotStarGlob = await jsGlob({ pattern: 'alpha.*' });
    assert.equal(dotStarGlob.output.length, 1);
    assert.equal(dotStarGlob.output[0].path, 'alpha.ts');

    const fileGrepRes = await jsGrep({ path: 'alpha.ts', pattern: 'hello', literal: true });
    assert.equal(fileGrepRes.output.length, 1);
    assert.equal(fileGrepRes.output[0].entry.path, 'alpha.ts');

    const grepRes = await jsGrep({ pattern: 'hello', literal: true });
    assert.equal(grepRes.output.length, 1);
    assert.equal(grepRes.output[0].entry.path, 'alpha.ts');
    assert.equal(grepRes.output[0].line, 1);
    assert.match(grepRes.content[0].text, /Line 1: export const hello = "world";/);

    const regexGrepRes = await jsGrep({ pattern: 'console\\.\\w+', literal: false });
    assert.equal(regexGrepRes.output.length, 1);
    assert.equal(regexGrepRes.output[0].line, 2);
  } finally {
    process.chdir(oldCwd);
    rmSync(testDir, { recursive: true, force: true });
  }
});

test('root safety guard blocks destructive system partition commands', async () => {
  const f = await fixture();
  const dangerous = [
    'dd if=/dev/zero of=/dev/block/bootdevice/by-name/boot',
    'rm -rf /system',
    'rm -rf /data/system',
    'mount -o remount,rw /',
    'reboot bootloader',
    'reboot recovery',
  ];
  for (const cmd of dangerous) {
    await assert.rejects(
      async () => {
        await f.shellTool.execute({ command: cmd, root: true }, { sessionID: 's1', agent: 'build', messageID: 'm1', id: 'c1' });
      },
      /Security Exception: Root command blocked by Android System Integrity Guard/
    );
  }
});

test('session_info tool retrieves session metadata', async () => {
  const f = await fixture();
  const sessionInfoTool = f.addedTools.get('session_info');
  assert.ok(sessionInfoTool);
  const res = await sessionInfoTool.execute({}, { sessionID: 'test-session-1' });
  assert.equal(res.output.sessionID, 'test-session-1');
  assert.match(res.content[0].text, /Session: test-session-1/);
});

test('diagnostics explains missing tsconfig instead of reporting zero errors', async () => {
  const f = await fixture();
  const diagnosticsTool = f.addedTools.get('diagnostics');
  assert.ok(diagnosticsTool);
  const res = await diagnosticsTool.execute({}, { sessionID: 's1' });
  assert.deepEqual(res.output.diagnostics, []);
  assert.match(res.content[0].text, /No tsconfig\.json found/);
});

test('parseTscOutput parses diagnostics and caps the shown list', () => {
  const out = 'a.ts(1,2): error TS2322: Type string is not assignable.\nb.ts(3,4): warning TS1005: Something.';
  const parsed = parseTscOutput(out, 1);
  assert.equal(parsed.total, 2);
  assert.equal(parsed.truncated, true);
  assert.equal(parsed.diagnostics.length, 1);
  assert.deepEqual(parsed.diagnostics[0], {
    file: 'a.ts', line: 1, column: 2, severity: 'error', code: 'TS2322', message: 'Type string is not assignable.',
  });
});

test('edit snapshot is persisted so undo survives a plugin reload', async () => {
  const f = await fixture();
  const dir = mkdtempSync(join(tmpdir(), 'margy-file-'));
  const file = join(dir, 'a.txt');
  writeFileSync(file, 'before');
  await f.editTool.execute({ path: file }, { sessionID: 'ses_persist' });
  const saved = JSON.parse(readFileSync(join(process.env.XDG_STATE_HOME, 'opencode', 'undo', 'ses_persist.json'), 'utf8'));
  assert.equal(saved.at(-1).content, 'before');
});

test('undo removes a file created by write', async () => {
  const f = await fixture();
  const dir = mkdtempSync(join(tmpdir(), 'margy-new-'));
  const file = join(dir, 'new.txt');
  await f.writeTool.execute({ path: file }, { sessionID: 'ses_new' });
  writeFileSync(file, 'created');
  const undo = f.addedTools.get('undo');
  assert.ok(undo);
  await undo.execute({ path: file }, { sessionID: 'ses_new' });
  assert.equal(existsSync(file), false);
});
