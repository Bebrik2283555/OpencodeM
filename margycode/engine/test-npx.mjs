import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { packageName, executable } from '../app/src/main/assets/npx.mjs';

test('scoped and unscoped package versions resolve correctly', () => {
  assert.equal(packageName('@modelcontextprotocol/server-memory'), '@modelcontextprotocol/server-memory');
  assert.equal(packageName('@scope/name@1.2.3'), '@scope/name');
  assert.equal(packageName('package@latest'), 'package');
  assert.throws(() => packageName('../../file'));
  assert.throws(() => packageName('--global'));
  assert.throws(() => packageName('https://example.com/script.js'));
});
test('npm bin resolves to a JavaScript entry and cannot escape the package directory', () => {
  const dir = path.resolve('fixture');
  assert.equal(executable(dir, { name: 'server-memory', bin: { 'mcp-server-memory': 'dist/index.js' } }), path.join(dir, 'dist/index.js'));
  assert.equal(executable(dir, { name: 'tool', bin: 'main.js' }), path.join(dir, 'main.js'));
  assert.throws(() => executable(dir, { bin: '../escape.js' }));
  assert.throws(() => executable(dir, { bin: { a: 'a.js', b: 'b.js' } }));
});
test('npx arguments filter leading -y and --yes flags before package resolution', () => {
  const filterArgs = (rawArgs) => {
    let i = 0;
    while (rawArgs[i] === '-y' || rawArgs[i] === '--yes') i++;
    return rawArgs.slice(i);
  };
  assert.deepEqual(filterArgs(['-y', '@modelcontextprotocol/server-filesystem']), ['@modelcontextprotocol/server-filesystem']);
  assert.deepEqual(filterArgs(['--yes', 'some-pkg', '-y']), ['some-pkg', '-y']);
});
