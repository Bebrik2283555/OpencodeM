import { createHash } from 'node:crypto';
import { readFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export function packageName(spec) {
  if (!/^(@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*(?:@[a-z0-9.*^~+<>=|-]+)?$/i.test(spec ?? ''))
    throw new Error('Use npx [-y] package[@version] [arguments] for a JavaScript npm package.');
  return spec.replace(/@[^@/]+$/, '');
}

export function executable(directory, metadata) {
  const bin = metadata.bin;
  const candidates = typeof bin === 'string' ? [bin] : Object.values(bin ?? {});
  const chosen = typeof bin === 'object' ? bin[metadata.name?.split('/').at(-1)] ?? (candidates.length === 1 ? candidates[0] : null) : bin;
  if (typeof chosen !== 'string') throw new Error('The package must have one default JavaScript executable.');
  const entry = path.resolve(directory, chosen);
  if (!entry.startsWith(path.resolve(directory) + path.sep)) throw new Error('Package executable is outside its directory.');
  return entry;
}

async function main() {
  const rawArgs = process.argv.slice(2);
  let i = 0;
  while (rawArgs[i] === '-y' || rawArgs[i] === '--yes') i++;
  const [spec, ...args] = rawArgs.slice(i);
  const name = packageName(spec);
  const cache = process.env.XDG_CACHE_HOME ?? path.join(process.env.HOME, '.cache');
  const directory = path.join(cache, 'margy-npx', createHash('sha256').update(spec).digest('hex').slice(0, 24));
  const packageDir = path.join(directory, 'node_modules', name);
  const manifest = path.join(packageDir, 'package.json');
  let metadata;
  try { metadata = JSON.parse(await readFile(manifest, 'utf8')); }
  catch (error) {
    if (error.code !== 'ENOENT') throw error;
    await mkdir(directory, { recursive: true });
    const install = Bun.spawn([process.execPath, 'add', '--no-save', '--ignore-scripts', spec], {
      cwd: directory, env: { ...process.env, BUN_BE_BUN: '1' }, stdin: 'ignore', stdout: 'pipe', stderr: 'inherit',
    });
    // Package-manager output must not corrupt MCP's stdout transport.
    process.stderr.write(await new Response(install.stdout).text());
    if (await install.exited !== 0) throw new Error(`Could not install ${spec}`);
    metadata = JSON.parse(await readFile(manifest, 'utf8'));
  }
  const entry = executable(packageDir, metadata);
  process.argv = [process.execPath, entry, ...args];
  // Android cannot exec app-writable files; Bun can read the JS entry directly.
  await import(pathToFileURL(entry).href);
}

if (import.meta.main) await main().catch(error => { console.error(error.message); process.exitCode = 1; });
