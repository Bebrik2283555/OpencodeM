import { AsyncLocalStorage } from 'node:async_hooks';
import { readFile, writeFile, unlink, stat, readdir, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { registerPhone } from './phone-tools.mjs';

const fileMtimes = new Map();

/** Keep the mtime map bounded; it only guards against overwriting a file changed after read. */
function rememberMtime(file, mtimeMs) {
  fileMtimes.set(file, mtimeMs);
  if (fileMtimes.size > 2000) fileMtimes.delete(fileMtimes.keys().next().value);
}

const undoHistory = new Map();

function undoKey(sessionID) {
  return String(sessionID ?? 'default').replace(/[^\w.-]/g, '_');
}

function undoFile(sessionID) {
  const state = process.env.XDG_STATE_HOME ?? path.join(process.env.HOME ?? process.cwd(), '.local', 'state');
  return path.join(state, 'opencode', 'undo', `${undoKey(sessionID)}.json`);
}

async function loadUndo(sessionID) {
  if (undoHistory.has(sessionID)) return;
  try {
    const saved = JSON.parse(await readFile(undoFile(sessionID), 'utf8'));
    if (Array.isArray(saved)) undoHistory.set(sessionID, saved);
  } catch { /* no saved history */ }
}

const UNDO_MAX_ITEMS = 10;
const UNDO_MAX_BYTES = 2 * 1024 * 1024;

/** Keep the newest snapshots, bounded by both count and bytes. */
function trimUndo(stack) {
  let bytes = 0;
  let keepFrom = stack.length;
  for (let i = stack.length - 1; i >= 0; i--) {
    const size = stack[i].content ? stack[i].content.length : 0;
    if (stack.length - i > UNDO_MAX_ITEMS || bytes + size > UNDO_MAX_BYTES) break;
    bytes += size;
    keepFrom = i;
  }
  if (keepFrom > 0) stack.splice(0, keepFrom);
}

async function saveUndo(sessionID, stack) {
  try {
    // shortcut: last 10 snapshots / 2 MB survive a reload; raise if longer undo is needed.
    trimUndo(stack);
    await mkdir(path.dirname(undoFile(sessionID)), { recursive: true });
    await writeFile(undoFile(sessionID), JSON.stringify(stack));
  } catch { /* history is best effort */ }
}

async function getUndoStack(sessionID = 'default') {
  await loadUndo(sessionID);
  let stack = undoHistory.get(sessionID);
  if (!stack) {
    stack = [];
    undoHistory.set(sessionID, stack);
  }
  trimUndo(stack);
  if (undoHistory.size > 10) {
    const oldest = undoHistory.keys().next().value;
    undoHistory.delete(oldest);
  }
  return stack;
}

function globToRegex(glob) {
  const escaped = glob
    .replace(/\*\*/g, '§DOUBLESTAR§')
    .replace(/[-[\]{}()+?.,\\^$|#\s]/g, '\\$&')
    .replace(/\*/g, '[^/]*')
    .replace(/§DOUBLESTAR§/g, '.*');
  return new RegExp('^' + escaped + '$');
}

async function walkFiles(dir, maxDepth = 15, currentDepth = 0) {
  if (currentDepth > maxDepth) return [];
  const files = [];
  try {
    const entries = await readdir(dir, { withFileTypes: true });
    for (const e of entries) {
      if (e.name === '.git' || e.name === 'node_modules' || e.name === '.cache' || e.name === 'build' || e.name === '.gradle') continue;
      const full = path.join(dir, e.name);
      if (e.isDirectory()) {
        files.push(...await walkFiles(full, maxDepth, currentDepth + 1));
      } else if (e.isFile()) {
        files.push(full);
      }
    }
  } catch {}
  return files;
}

export async function jsGlob(input = {}, baseDir = process.cwd()) {
  const searchDir = path.resolve(baseDir, input.path ?? '.');
  const st = await stat(searchDir).catch(() => null);
  const allFiles = st?.isFile() ? [searchDir] : await walkFiles(searchDir);
  const pattern = input.pattern ?? '*';
  const reg = globToRegex(pattern);
  const matches = [];
  const limit = input.limit ?? 100;
  for (const f of allFiles) {
    const rel = path.relative(searchDir, f).replaceAll('\\', '/');
    const base = path.basename(f);
    if (reg.test(rel) || reg.test(base)) {
      matches.push({
        path: path.relative(baseDir, f).replaceAll('\\', '/'),
        type: 'file',
      });
      if (matches.length >= limit) break;
    }
  }
  const lines = matches.length === 0 ? ['No files found'] : matches.map(m => m.path);
  if (matches.length >= limit) {
    lines.push('', `(Results are truncated: showing first ${matches.length} results.)`);
  }
  return {
    output: matches,
    content: [{ type: 'text', text: lines.join('\n') }],
    metadata: { count: matches.length, truncated: matches.length >= limit },
  };
}

export async function jsGrep(input = {}, baseDir = process.cwd()) {
  const searchDir = path.resolve(baseDir, input.path ?? '.');
  const st = await stat(searchDir).catch(() => null);
  const allFiles = st?.isFile() ? [searchDir] : await walkFiles(searchDir);
  const includeReg = input.include ? globToRegex(input.include) : null;
  const literal = input.literal === true;
  const caseSensitive = input.caseSensitive !== false;
  let regex = null;
  if (!literal) {
    try {
      regex = new RegExp(input.pattern ?? '', caseSensitive ? 'g' : 'gi');
    } catch (e) {
      throw new Error(`Invalid regex pattern: ${e.message}`);
    }
  }
  const targetStr = caseSensitive ? (input.pattern ?? '') : (input.pattern ?? '').toLowerCase();
  const limit = input.limit ?? 100;
  const matches = [];
  for (const f of allFiles) {
    if (includeReg) {
      const rel = path.relative(searchDir, f).replaceAll('\\', '/');
      const base = path.basename(f);
      if (!includeReg.test(rel) && !includeReg.test(base)) continue;
    }
    try {
      const fst = await stat(f);
      if (fst.size > 2 * 1024 * 1024) continue;
      const content = await readFile(f, 'utf8');
      if (content.includes('\0')) continue;
      const lines = content.split('\n');
      const relPath = path.relative(baseDir, f).replaceAll('\\', '/');
      for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        let isMatch = false;
        if (literal) {
          const check = caseSensitive ? line : line.toLowerCase();
          isMatch = check.includes(targetStr);
        } else if (regex) {
          regex.lastIndex = 0;
          isMatch = regex.test(line);
        }
        if (isMatch) {
          matches.push({
            entry: { path: relPath, type: 'file' },
            line: i + 1,
            offset: 0,
            text: line.trimEnd().slice(0, 500),
            submatches: [],
          });
          if (matches.length >= limit) break;
        }
      }
    } catch {}
    if (matches.length >= limit) break;
  }
  const lines = matches.length === 0 ? ['No matches found'] : [`Found ${matches.length} matches`];
  let current = '';
  for (const match of matches) {
    if (current !== match.entry.path) {
      if (current) lines.push('');
      current = match.entry.path;
      lines.push(`${match.entry.path}:`);
    }
    lines.push(`  Line ${match.line}: ${match.text}`);
  }
  if (matches.length >= limit) {
    lines.push('', `(Results are truncated: showing first ${matches.length} results.)`);
  }
  return {
    output: matches,
    content: [{ type: 'text', text: lines.join('\n') }],
    metadata: { matches: matches.length, truncated: matches.length >= limit },
  };
}

export function parseTscOutput(out = '', limit = 50) {
  const diagnostics = [];
  const re = /([^\s()]+)\((\d+),(\d+)\):\s*(error|warning)\s*(TS\d+)?:?\s*(.*)/g;
  let m;
  while ((m = re.exec(out)) !== null) {
    diagnostics.push({
      file: m[1].replaceAll('\\', '/'),
      line: parseInt(m[2], 10),
      column: parseInt(m[3], 10),
      severity: m[4],
      code: m[5] ?? '',
      message: (m[6] ?? '').trim().slice(0, 200),
    });
  }
  return { diagnostics: diagnostics.slice(0, limit), total: diagnostics.length, truncated: diagnostics.length > limit };
}

async function findTsProject(start) {
  let dir = start;
  const st = await stat(dir).catch(() => null);
  if (st?.isFile()) dir = path.dirname(dir);
  for (let i = 0; i < 10; i++) {
    if (await stat(path.join(dir, 'tsconfig.json')).catch(() => null)) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  return null;
}

async function findTsc(dir) {
  for (let i = 0; i < 10; i++) {
    const local = path.join(dir, 'node_modules', 'typescript', 'bin', 'tsc');
    if (await stat(local).catch(() => null)) return local;
    const parent = path.dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  return null;
}

const BLOCKED_ROOT_COMMANDS = [
  /\/dev\/block/i,
  /\/dev\/(mtd|nvme|mmcblk)/i,
  /\bmount\b.*-o.*remount.*rw/i,
  /\b(dd\s+if=.*of=\/dev|mkfs|format\s+\/|flash_image|blockdev|setenforce\s+0)/i,
  /\brm\s+-[a-zA-Z]*r[a-zA-Z]*\s+(\/|\/\*|\/system|\/vendor|\/apex|\/product|\/metadata|\/data|\/data\/system|\/data\/dalvik-cache)\b/i,
  /(^|\s)>\s*\/(system|vendor|apex|product)\//i,
  /\bmv\s+[^\n]*\s+\/(data|system|vendor)\b/i,
  /\breboot\s+(bootloader|fastboot|recovery|edl)\b/i,
];

function assertSafeRootCommand(cmd = '') {
  for (const pattern of BLOCKED_ROOT_COMMANDS) {
    if (pattern.test(cmd)) {
      throw new Error(`Security Exception: Root command blocked by Android System Integrity Guard (matches ${pattern})`);
    }
  }
}

export default {
  id: 'margy.runtime',
  async setup(ctx) {
    const turbo = process.env.MARGY_PERMISSION_MODE === 'turbo';
    await registerPhone(ctx, turbo);
    const calls = new AsyncLocalStorage();
    const appPrompt = ctx.options?.appPrompt;
    const appInstructions = appPrompt ? await readFile(appPrompt, 'utf8') : '';

    async function getSessionDir(sessionID) {
      if (sessionID && ctx.session?.get) {
        try {
          const s = await ctx.session.get({ sessionID });
          if (s?.location?.directory) return s.location.directory;
        } catch {}
      }
      return process.cwd();
    }

    // Fast health-check timeout for remote MCP requests (5s timeout instead of 60s hang)
    if (!globalThis.__margy_fetch_wrapped) {
      globalThis.__margy_fetch_wrapped = true;
      const originalFetch = globalThis.fetch;
      if (typeof originalFetch === 'function') {
        globalThis.fetch = function (resource, init = {}) {
          const url = typeof resource === 'string' ? resource : resource?.url;
          if (typeof url === 'string' && (url.includes('/mcp') || url.includes('/sse')) && !init.signal) {
            const controller = new AbortController();
            const timer = setTimeout(() => controller.abort(new Error('MCP server connection timeout (5s)')), 5000);
            return originalFetch(resource, { ...init, signal: controller.signal })
              .finally(() => clearTimeout(timer));
          }
          return originalFetch(resource, init);
        };
      }
    }

    await ctx.shell.hook('create.before', event => {
      const call = calls.getStore();
      if (!call) return;
      event.shell = process.env.MARGY_SHELL;
      event.env.MARGY_CALL_RUNTIME = call.runtime;
      event.env.MARGY_CALL_ROOT = call.root ? '1' : '0';
    });

    await ctx.agent.transform(editor => {
      for (const agent of editor.list()) editor.update(agent.id, draft => {
        if (agent.id === 'chat') return;
        draft.permissions = [...(draft.permissions ?? [])];
        if (turbo) {
          draft.permissions = [{ action: '*', resource: '*', effect: 'allow' }];
          return;
        }
        for (const action of ['root', 'phone']) if (!draft.permissions.some(rule => rule.action === action))
          draft.permissions.push({ action, resource: '*', effect: 'ask' });
        if (!draft.permissions.some(rule => rule.action === 'todowrite'))
          draft.permissions.push({ action: 'todowrite', resource: '*', effect: 'allow' });
      });
    });

    await ctx.tool.transform(editor => {
      // 1. Shell tool: echo runtime, root, exit code, and error markers
      editor.update('shell', tool => {
        const execute = tool.execute;
        tool.description += ' On Android, runtime=auto uses native tools for simple available commands; use native or alpine explicitly for compound commands. root=true is native-only and always requests separate permission.';
        tool.input = {
          type: 'object', properties: {
            command: { type: 'string' }, workdir: { type: 'string' },
            timeout: { type: 'integer', minimum: 0 }, background: { type: 'boolean' },
            runtime: { type: 'string', enum: ['auto', 'native', 'alpine'] }, root: { type: 'boolean' },
          }, required: ['command'], additionalProperties: false,
        };
        tool.execute = async ({ runtime = process.env.MARGY_DEFAULT_RUNTIME ?? 'auto', root = false, ...input }, context) => {
          if (!['auto', 'native', 'alpine'].includes(runtime)) throw new Error('Invalid runtime');
          if (root && runtime === 'alpine') throw new Error('Real root is native-only; Alpine has fake root.');
          // A normal command can still call su itself; treat that as a root request too.
          const elevates = !root && /(^|[;&|]\s*|\s)(su|sudo)(\s|$)/.test(input.command);
          if (root || elevates) assertSafeRootCommand(input.command);
          if ((root || elevates) && !turbo) await ctx.permission.assert({
            sessionID: context.sessionID, agent: context.agent,
            source: { type: 'tool', messageID: context.messageID, id: context.id },
            action: 'root', resources: [input.command], save: [input.command],
          });
          const res = await calls.run({ runtime, root }, () => execute(input, context));
          const exit = res?.output?.exit ?? res?.metadata?.exit ?? 0;
          let header = `[runtime=${runtime} root=${root} exit=${exit}]\n`;
          if (exit !== 0) {
            header += '--- exit code indicates failure ---\n';
          }
          if (res) {
            if (Array.isArray(res.content)) {
              const first = res.content.find(p => p.type === 'text');
              if (first) first.text = header + first.text;
              else res.content.unshift({ type: 'text', text: header.trim() });
            } else if (typeof res.content === 'string') {
              res.content = header + res.content;
            }
            if (res.output && typeof res.output.output === 'string') {
              res.output.output = header + res.output.output;
            }
          }
          return res;
        };
      });

      // 2. Read tool: 64 KB cap and mtime tracking
      try {
        editor.update('read', tool => {
          const execute = tool.execute;
          tool.execute = async (input, context) => {
            const res = await execute(input, context);
            try {
              if (input.path) {
                const baseDir = await getSessionDir(context?.sessionID);
                const abs = path.resolve(baseDir, input.path);
                const st = await stat(abs).catch(() => null);
                if (st) rememberMtime(abs, st.mtimeMs);
              }
            } catch { /* stat optional */ }
            const MAX_READ_CHARS = 64 * 1024;
            if (res && Array.isArray(res.content)) {
              for (const part of res.content) {
                if (part && part.type === 'text' && typeof part.text === 'string' && part.text.length > MAX_READ_CHARS) {
                  const totalKB = Math.round(part.text.length / 1024);
                  part.text = part.text.slice(0, MAX_READ_CHARS) +
                    `\n\n[Truncated: showing 64 KB of ${totalKB} KB. Use 'offset' or 'limit' to read remaining lines.]`;
                  if (res.metadata) res.metadata.truncated = true;
                  if (res.output && typeof res.output === 'object') res.output.truncated = true;
                }
              }
            }
            return res;
          };
        });
      } catch { /* read tool might not be registered */ }

      // 3. Edit tool: changed-since-last-read protection and undo snapshot
      try {
        editor.update('edit', tool => {
          const execute = tool.execute;
          tool.execute = async (input, context) => {
            const baseDir = await getSessionDir(context?.sessionID);
            if (input.path) {
              const abs = path.resolve(baseDir, input.path);
              const lastRead = fileMtimes.get(abs);
              if (lastRead !== undefined) {
                const st = await stat(abs).catch(() => null);
                if (st && st.mtimeMs > lastRead + 200) {
                  throw new Error(`File '${input.path}' has changed on disk since last read (modified at ${new Date(st.mtimeMs).toISOString()}). Re-read the file before applying edits.`);
                }
              }
              const before = await readFile(abs, 'utf8').catch(() => null);
              if (before === null || before.length <= 512 * 1024) {
                const undoStack = await getUndoStack(context?.sessionID);
                undoStack.push({ path: abs, content: before, time: Date.now() });
                if (undoStack.length > 10) undoStack.shift();
                await saveUndo(context?.sessionID, undoStack);
              }
            }
            const res = await execute(input, context);
            if (input.path) {
              const abs = path.resolve(baseDir, input.path);
              const stAfter = await stat(abs).catch(() => null);
              if (stAfter) rememberMtime(abs, stAfter.mtimeMs);
            }
            return res;
          };
        });
      } catch { /* edit tool might not be registered */ }

      // 4. Write tool: undo snapshot
      try {
        editor.update('write', tool => {
          const execute = tool.execute;
          tool.execute = async (input, context) => {
            const baseDir = await getSessionDir(context?.sessionID);
            if (input.path) {
              const abs = path.resolve(baseDir, input.path);
              const before = await readFile(abs, 'utf8').catch(() => null);
              if (before === null || before.length <= 512 * 1024) {
                const undoStack = await getUndoStack(context?.sessionID);
                undoStack.push({ path: abs, content: before, time: Date.now() });
                if (undoStack.length > 10) undoStack.shift();
                await saveUndo(context?.sessionID, undoStack);
              }
            }
            const res = await execute(input, context);
            if (input.path) {
              const abs = path.resolve(baseDir, input.path);
              const stAfter = await stat(abs).catch(() => null);
              if (stAfter) rememberMtime(abs, stAfter.mtimeMs);
            }
            return res;
          };
        });
      } catch { /* write tool might not be registered */ }

      // 5. Undo tool: reverts last edit or write in this session
      try {
        editor.add({
          name: 'undo',
          options: { codemode: false },
          description: 'Undo the last file modification made by edit or write tool in this project. Reverts the file to its exact previous contents.',
          input: {
            type: 'object',
            properties: {
              path: { type: 'string', description: 'Optional specific file to undo. If omitted, undoes the most recent modification.' },
            },
            additionalProperties: false,
          },
          output: {
            type: 'object',
            properties: {
              message: { type: 'string' },
            },
          },
          execute: async (input = {}, context = {}) => {
            const undoStack = await getUndoStack(context?.sessionID);
            const baseDir = await getSessionDir(context?.sessionID);
            let idx = -1;
            if (input.path) {
              const target = path.resolve(baseDir, input.path);
              idx = undoStack.findLastIndex(item => item.path === target);
            } else {
              idx = undoStack.length - 1;
            }
            if (idx === -1) {
              const msg = 'No previous file modification in undo history.';
              return { output: { message: msg }, content: msg };
            }
            const item = undoStack.splice(idx, 1)[0];
            await saveUndo(context?.sessionID, undoStack);
            const rel = path.relative(baseDir, item.path);
            let msg = '';
            if (item.content === null) {
              await unlink(item.path).catch(() => {});
              fileMtimes.delete(item.path);
              msg = `Undid file creation: deleted '${rel}'.`;
            } else {
              await writeFile(item.path, item.content, 'utf8');
              const st = await stat(item.path).catch(() => null);
              if (st) rememberMtime(item.path, st.mtimeMs);
              msg = `Reverted '${rel}' to its state before the last edit (${item.content.length} chars).`;
            }
            return { output: { message: msg }, content: msg };
          },
        });
      } catch { /* undo registration optional */ }

      // 6. Session info tool
      try {
        editor.add({
          name: 'session_info',
          options: { codemode: false },
          description: 'Get current session metadata: working directory, active model, token usage, cost, and title.',
          input: { type: 'object', properties: {}, additionalProperties: false },
          output: {
            type: 'object',
            properties: {
              sessionID: { type: 'string' },
              title: { type: 'string' },
              directory: { type: 'string' },
              model: { type: 'string' },
              cost: { type: 'number' },
            },
          },
          execute: async (input = {}, context = {}) => {
            if (!context?.sessionID) {
              const info = { sessionID: '', title: '', directory: process.cwd(), model: 'unknown', cost: 0 };
              return { output: info, content: [{ type: 'text', text: 'No session context available.' }] };
            }
            let s = null;
            if (typeof ctx.session?.get === 'function') {
              try {
                s = await ctx.session.get({ sessionID: context.sessionID });
              } catch {}
            }
            const dir = s?.location?.directory ?? process.cwd();
            const info = {
              sessionID: s?.id ?? context.sessionID,
              title: s?.title ?? '',
              directory: dir,
              model: s?.model ? `${s.model.providerID}/${s.model.id}` : 'unknown',
              tokens: s?.tokens ?? {},
              cost: s?.cost ?? 0,
            };
            return {
              output: info,
              content: [{
                type: 'text',
                text: `Session: ${info.title || info.sessionID}\nDirectory: ${info.directory}\nModel: ${info.model}\nCost: $${info.cost}\nTokens: input=${info.tokens.input ?? 0}, output=${info.tokens.output ?? 0}`
              }],
            };
          },
        });
      } catch { /* session_info optional */ }

      // 7. Diagnostics tool: real tsc from the project, capped output
      try {
        editor.add({
          name: 'diagnostics',
          options: { codemode: false },
          description: 'Get structured TypeScript compiler diagnostics for the project or a specific file. Requires TypeScript installed in the project (npm install -D typescript).',
          input: {
            type: 'object',
            properties: {
              path: { type: 'string', description: 'Optional relative path to a file or directory to check' },
              limit: { type: 'integer', minimum: 1, maximum: 500, description: 'Maximum diagnostics to show (default 50)' },
            },
            additionalProperties: false,
          },
          output: {
            type: 'object',
            properties: {
              diagnostics: {
                type: 'array',
                items: {
                  type: 'object',
                  properties: {
                    file: { type: 'string' },
                    line: { type: 'integer' },
                    column: { type: 'integer' },
                    severity: { type: 'string' },
                    code: { type: 'string' },
                    message: { type: 'string' },
                  },
                },
              },
              total: { type: 'integer' },
              truncated: { type: 'boolean' },
            },
          },
          execute: async (input = {}, context = {}) => {
            const baseDir = await getSessionDir(context?.sessionID);
            const limit = Math.max(1, Math.min(500, input.limit ?? 50));
            const start = path.resolve(baseDir, input.path ?? '.');
            const respond = (text, extra = {}) => ({
              output: { diagnostics: extra.diagnostics ?? [], total: extra.total ?? 0, truncated: !!extra.truncated },
              content: [{ type: 'text', text }],
              metadata: { total: extra.total ?? 0, truncated: !!extra.truncated, ...(extra.note ? { note: extra.note } : {}) },
            });

            const projectDir = await findTsProject(start);
            if (!projectDir) return respond(`No tsconfig.json found for ${input.path ?? '.'}.`, { note: 'no-tsconfig' });
            const tsc = await findTsc(projectDir);
            if (!tsc) return respond('TypeScript is not installed in this project. Run: npm install -D typescript', { note: 'no-typescript' });

            let stdout = '';
            let stderr = '';
            let failed = null;
            try {
              const { execFile } = await import('node:child_process');
              const { promisify } = await import('node:util');
              ({ stdout, stderr } = await promisify(execFile)(
                process.execPath,
                [tsc, '-p', projectDir, '--noEmit', '--pretty', 'false'],
                { cwd: baseDir, timeout: 60000, maxBuffer: 10 * 1024 * 1024, env: { ...process.env, BUN_BE_BUN: '1' } },
              ));
            } catch (err) {
              failed = err;
              stdout = err?.stdout ?? '';
              stderr = err?.stderr ?? '';
            }

            let { diagnostics, total } = parseTscOutput(stdout + '\n' + stderr, Infinity);
            if (input.path) {
              diagnostics = diagnostics.filter(d => {
                const f = path.resolve(baseDir, d.file);
                return f === start || f.startsWith(start + path.sep);
              });
              total = diagnostics.length;
            }
            const truncated = total > limit;
            const shown = diagnostics.slice(0, limit);

            if (failed && total === 0) {
              const detail = (stdout + '\n' + stderr).trim().split('\n').slice(0, 10).join('\n').slice(0, 1500);
              const why = failed.killed || failed.signal ? 'tsc timed out after 60s' : `tsc failed (exit ${failed.code ?? '?'})`;
              return respond(`${why}${detail ? `:\n${detail}` : ''}`, { note: 'tsc-failed' });
            }

            if (total === 0) return respond('No diagnostics reported');
            const text = `Found ${total} diagnostic(s)` + (truncated ? `, showing first ${limit}` : '') + ':\n' +
              shown.map(d => `${d.file}:${d.line}:${d.column} [${d.severity}] ${d.message}`).join('\n');
            return respond(text, { diagnostics: shown, total, truncated });
          },
        });
      } catch { /* diagnostics optional */ }

      // 7. Grep tool: JS fallback when ripgrep binary fails on Android (noexec / Bionic TLS alignment)
      try {
        editor.update('grep', tool => {
          const execute = tool.execute;
          tool.execute = async (input, context) => {
            const baseDir = await getSessionDir(context?.sessionID);
            try {
              return await execute(input, context);
            } catch (err) {
              const msg = err?.message ?? String(err);
              if (msg.includes('regex') && !msg.includes('Unable to grep')) throw err;
              return await jsGrep(input, baseDir);
            }
          };
        });
      } catch { /* grep optional */ }

      // 8. Glob tool: JS fallback when ripgrep binary fails on Android
      try {
        editor.update('glob', tool => {
          const execute = tool.execute;
          tool.execute = async (input, context) => {
            const baseDir = await getSessionDir(context?.sessionID);
            try {
              return await execute(input, context);
            } catch (err) {
              return await jsGlob(input, baseDir);
            }
          };
        });
      } catch { /* glob optional */ }
    });

    // A local model must not spend minutes generating a title in parallel with its answer.
    await ctx.session.hook('title', event => {
      if (event.model?.providerID !== 'phone') return;
      const text = event.messages.find(message => message.role === 'user')?.content
        ?.filter(part => part.type === 'text').map(part => part.text).join(' ') ?? '';
      event.result = text.replace(/\s+/g, ' ').trim().slice(0, 60) || 'Local chat';
    });

    // System context hook: scratchpad .opencode/notes.md and budget awareness
    await ctx.session.hook('context', async event => {
      try {
        const dir = await getSessionDir(event.sessionID);
        const notesFile = path.join(dir, '.opencode', 'notes.md');
        const notes = await readFile(notesFile, 'utf8').catch(() => '');
        if (notes.trim()) {
          event.system.push({
            type: 'text',
            text: `## Project Notes (.opencode/notes.md)\n\n${notes.trim()}`,
          });
        }
      } catch { /* notes file optional */ }

      if (event.agent !== 'chat' || event.model?.providerID !== 'phone') {
        try {
          let limit = event.model?.model?.limit?.context ?? 0;
          if (!limit && event.model && ctx.model?.list) {
            try {
              const list = await ctx.model.list().catch(() => null);
              const models = Array.isArray(list?.data) ? list.data : (Array.isArray(list) ? list : []);
              const found = models.find(m => m.providerID === event.model?.providerID && m.id === event.model?.id);
              limit = found?.limit?.context ?? 0;
            } catch {}
          }
          if (!limit) limit = 200000;
          let used = event.inputTokens?.measured || event.inputTokens?.estimated || 0;
          if (!used && Array.isArray(event.messages)) {
            for (let i = event.messages.length - 1; i >= 0; i--) {
              const m = event.messages[i];
              if (m?.tokens?.input) {
                used = m.tokens.input + (m.tokens.output ?? 0);
                break;
              }
            }
            if (!used) {
              let chars = 0;
              for (const m of event.messages) {
                if (typeof m?.text === 'string') chars += m.text.length;
                if (typeof m?.content === 'string') chars += m.content.length;
                else if (Array.isArray(m?.content)) {
                  for (const p of m.content) if (typeof p?.text === 'string') chars += p.text.length;
                }
              }
              used = Math.round(chars / 4);
            }
          }
          const pct = Math.min(100, Math.round((used / limit) * 100));
          const usedK = Math.max(0, Math.round(used / 1000));
          const limitK = Math.round(limit / 1000);
          event.system.push({
            type: 'text',
            text: `[Context budget: ~${usedK}k / ${limitK}k tokens (${pct}% used)]`,
          });
        } catch { /* budget info optional */ }
      }

      if (event.model?.providerID !== 'phone' || event.agent !== 'chat') return;
      event.tools = {};
      // Keep user rules and history; remove only our own coding-environment boilerplate in plain chat.
      event.system = event.system.map(part => {
        let text = part.text;
        if (typeof text !== 'string') return part;
        if (appInstructions) {
          for (const path of [appPrompt, appPrompt.replace('/data/user/0/', '/data/data/')])
            text = text.replace(`Instructions from: ${path}\n${appInstructions}`, '');
        }
        for (const instruction of part.metadata?.experimental?.instructions ?? []) {
          if (['core/environment', 'core/skill-guidance', 'core/reference-guidance', 'core/mcp-guidance', 'core/codemode'].includes(instruction.key))
            text = text.replace(instruction.text, '');
        }
        if (text === "When you create a worktree outside the current working directory and intend to use it as your primary working directory, consider using `execute` to call `tools.opencode.session_move` and make the worktree the session's working directory.") text = '';
        return { ...part, text: text.trim() };
      }).filter(part => part.text !== '');
    });

    // Compaction hook: smart preservation of files, errors, and uncompleted tasks
    await ctx.session.hook('compaction', async event => {
      event.system = event.system ?? [];
      event.system.push({
        type: 'text',
        text: `PRIORITY RULES FOR MOBILE COMPACTION:
1. Always preserve exact file paths created, modified, or inspected.
2. Preserve all recent errors, failed commands, and active blockers.
3. Preserve unfulfilled user requirements and immediate next steps.
4. Keep the summary dense and structured; omit conversational banter.`,
      });
    });

    await ctx.session.hook('http.request', async event => {
      if (event.model?.providerID !== 'phone' || event.agent !== 'chat' || event.request.method !== 'POST') return;
      const body = await event.request.clone().json();
      body.chat_template_kwargs = { ...body.chat_template_kwargs, enable_thinking: false };
      event.request = new Request(event.request, { body: JSON.stringify(body) });
    });
  },
};
