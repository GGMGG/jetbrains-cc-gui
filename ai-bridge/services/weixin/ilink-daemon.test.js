import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const daemon = new URL('../../weixin-daemon.js', import.meta.url);

async function child(t) {
  const process = spawn(globalThis.process.execPath, [fileURLToPath(daemon)], {
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  t.after(() => process.kill());
  process.stderr.resume();
  const lines = readline.createInterface({ input: process.stdout })[Symbol.asyncIterator]();
  assert.deepEqual(JSON.parse((await lines.next()).value), { type: 'daemon', event: 'ready' });
  return { process, lines };
}

test('daemon handles status and rejects malformed params without provider access', { timeout: 5000 }, async (t) => {
  const { process, lines } = await child(t);
  process.stdin.write(`${JSON.stringify({ id: 'status', method: 'status', params: {} })}\n`);
  const status = JSON.parse((await lines.next()).value);
  assert.equal(status.id, 'status');
  assert.equal(typeof status.result, 'object');
  process.stdin.write(`${JSON.stringify({ id: 'invalid', method: 'status', params: [] })}\n`);
  const invalid = JSON.parse((await lines.next()).value);
  assert.equal(invalid.id, 'invalid');
  assert.equal(typeof invalid.error.code, 'string');
  assert.equal(invalid.error.detail, undefined);
  const exited = once(process, 'exit');
  process.stdin.end();
  assert.equal((await exited)[0], 0);
});

test('daemon rejects an oversized line before its newline arrives', { timeout: 5000 }, async (t) => {
  const { process } = await child(t);
  const exited = once(process, 'exit');
  process.stdin.on('error', () => {});
  process.stdin.write(Buffer.alloc(256 * 1024 + 1, 120));
  assert.equal((await exited)[0], 1);
});

test('daemon bounds queued requests independently of line size', { timeout: 5000 }, async (t) => {
  const { process } = await child(t);
  const exited = once(process, 'exit');
  process.stdin.on('error', () => {});
  process.stdin.write(Array.from({ length: 65 }, (_, i) => JSON.stringify({ id: `${i}`, method: 'status' })).join('\n') + '\n');
  assert.equal((await exited)[0], 1);
});

test('daemon rejects invalid UTF-8 and incomplete final frames', { timeout: 5000 }, async (t) => {
  for (const bytes of [Buffer.from([0xff, 10]), Buffer.from('{"id":')]) {
    const { process } = await child(t);
    const exited = once(process, 'exit');
    process.stdin.end(bytes);
    assert.equal((await exited)[0], 1);
  }
});
