import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { findWindowsCommand, prepareStdioLaunch } from './process-launcher.js';
import { getStdioServerTools } from './stdio-tools-getter.js';
import { verifyStdioServerStatus } from './stdio-verifier.js';

test('PATH lookup uses one asynchronous where call with PATHEXT', async () => {
  const calls = [];
  let release;
  const lookup = findWindowsCommand('npx', { PATH: 'fixture' }, (...args) => {
    calls.push(args);
    return new Promise(resolve => { release = resolve; });
  });
  let timerRan = false;
  await new Promise(resolve => setTimeout(() => { timerRan = true; resolve(); }, 0));
  assert.equal(timerRan, true);
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0].slice(0, 2), ['where.exe', ['npx']]);
  release({ stdout: 'C:\\Program Files\\nodejs\\npx.cmd\r\n' });
  assert.deepEqual(await lookup, ['C:\\Program Files\\nodejs\\npx.cmd']);
});

test('rejects shell expansion in resolved paths and arguments', () => {
  for (const value of ['%PATH%', '!PATH!', 'a"b', 'a^b', 'a&b', 'a\nb']) {
    assert.throws(() => prepareStdioLaunch({ command: value, useShell: true }, []), /unsafe shell/);
    assert.throws(() => prepareStdioLaunch({ command: 'tool.cmd', useShell: true }, [value]), /unsafe shell/);
  }
  assert.deepEqual(prepareStdioLaunch({ command: 'node.exe', useShell: false }, ['a&b']), {
    command: 'node.exe', useShell: false, args: ['a&b']
  });
});

test('launches resolved and explicit Windows shims in paths containing spaces', {
  skip: process.platform !== 'win32'
}, async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'ccg mcp shim '));
  const shim = path.join(root, 'fixture-mcp.cmd');
  const script = path.join(root, 'fixture.mjs');
  await writeFile(shim, `@echo off\r\n"${process.execPath}" "${script}" %*\r\n`, 'utf8');
  await writeFile(script, `
import readline from 'node:readline';
readline.createInterface({ input: process.stdin }).on('line', line => {
  const request = JSON.parse(line);
  if (request.id === 1) console.log(JSON.stringify({ jsonrpc: '2.0', id: 1, result: {
    protocolVersion: '2024-11-05', capabilities: {}, serverInfo: { name: 'shim', version: '1' }
  } }));
  if (request.id === 2) console.log(JSON.stringify({ jsonrpc: '2.0', id: 2, result: {
    tools: [{ name: 'args', description: JSON.stringify(process.argv.slice(2)), inputSchema: { type: 'object' } }]
  } }));
});
`, 'utf8');
  try {
    const args = ['value with spaces', 'C:\\Program Files (x86)\\fixture', ''];
    for (const command of ['fixture-mcp', shim]) {
      const config = { command, args, cwd: root, env: { PATH: `${root}${path.delimiter}${process.env.PATH}` } };
      const tools = await getStdioServerTools('space-shim', config);
      assert.equal(tools.error, null);
      assert.deepEqual(JSON.parse(tools.tools[0].description), args);
      const status = await verifyStdioServerStatus('space-shim', config);
      assert.equal(status.status, 'connected');
    }
  } finally {
    const resolved = path.resolve(root);
    assert.equal(path.dirname(resolved), path.resolve(os.tmpdir()));
    assert.ok(path.basename(resolved).startsWith('ccg mcp shim '));
    await rm(resolved, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
  }
});
