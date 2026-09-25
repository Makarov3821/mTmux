import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { Terminal } = require('@xterm/headless');
const protocol = require('./protocol.js');
const write = (term, bytes) => new Promise(resolve => term.write(bytes, resolve));

test('UTF-8 survives chunks split inside a Chinese character and ANSI is interpreted', async () => {
  const term = new Terminal({ cols: 40, rows: 4, allowProposedApi: true });
  const bytes = Buffer.from('\x1b[32m中文 Agent\x1b[0m');
  await write(term, protocol.decode(bytes.subarray(0, 6).toString('base64')));
  await write(term, protocol.decode(bytes.subarray(6).toString('base64')));
  assert.equal(term.buffer.active.getLine(0).translateToString(true), '中文 Agent');
  term.dispose();
});
test('alternate-screen exit restores shell output', async () => {
  const term = new Terminal({ cols: 40, rows: 4, allowProposedApi: true });
  await write(term, 'shell\x1b[?1049hAgent TUI\x1b[?1049l');
  assert.equal(term.buffer.active.getLine(0).translateToString(true), 'shell');
  term.dispose();
});
test('paste rejects terminal escape injection and unsafe multiline fallback', () => {
  assert.ok(protocol.validatePaste('\x1b[201~rm -rf', true));
  assert.ok(protocol.validatePaste('first\nsecond', false));
  assert.equal(protocol.validatePaste('first\nsecond', true), null);
  assert.equal(protocol.validatePaste('中文 $HOME; echo test', false), null);
  assert.ok(protocol.validatePaste('中'.repeat(12000), true));
});
test('scrollback remains bounded under large output and resize', async () => {
  const term = new Terminal({ cols: 80, rows: 24, scrollback: 3000, allowProposedApi: true });
  await write(term, 'progress 中文\r\n'.repeat(12000));
  term.resize(40, 12);
  assert.ok(term.buffer.active.length <= 3012);
  term.dispose();
});
