// Desktop Chromium smoke test of the actual bundled terminal, not an Android IME test.
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, extname } from 'node:path';
import assert from 'node:assert/strict';
const root = resolve('../app/src/main/assets/terminal');
const server = createServer(async (request, response) => {
  try {
    const path = resolve(root, '.' + (request.url === '/' ? '/index.html' : request.url));
    if (!path.startsWith(root + '/')) throw new Error('outside assets');
    response.setHeader('Content-Type', ({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[extname(path)] || 'text/plain');
    response.end(await readFile(path));
  } catch { response.writeHead(404); response.end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
let browser;
try {
  browser = await chromium.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 412, height: 500 } });
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.events = [];
    window.NativeTerminal = Object.fromEntries(['ready','resize','input','draftInput','notice','ack','copy','pasteFinished','binaryInput'].map(name => [name, (...args) => events.push([name, ...args])]));
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(() => events.some(e => e[0] === 'ready'));
  async function feed(text, id) {
    await page.evaluate(([data, id]) => receive(data, id), [Buffer.from(text).toString('base64'), id]);
    await page.waitForFunction(id => events.some(e => e[0] === 'ack' && e[1] === id), id);
  }
  await feed('\x1b[32m中文 Agent\x1b[0m', 'a');
  assert.equal(await page.evaluate(() => terminal.buffer.active.getLine(0).translateToString(true)), '中文 Agent');
  await page.evaluate(() => { setConnection('session-a'); pasteDraft('你好', 'session-a'); });
  assert.deepEqual(await page.evaluate(() => events.filter(e => e[0] === 'input').at(-1)), ['input', 'session-a', '你好']);
  assert.deepEqual(await page.evaluate(() => events.at(-1)), ['pasteFinished', 'session-a']);
  // A visible Send action is a single ordered native write, not a bare Enter.
  await page.evaluate(() => sendDraft('pwd', 'session-a', 'draft-1', true));
  assert.deepEqual(await page.evaluate(() => events.at(-1)), ['draftInput', 'session-a', 'draft-1', 'pwd\r']);
  await page.evaluate(() => sendDraft('ls', 'session-a', 'draft-2', false));
  assert.deepEqual(await page.evaluate(() => events.at(-1)), ['draftInput', 'session-a', 'draft-2', 'ls']);
  const count = await page.evaluate(() => events.filter(e => e[0] === 'input').length);
  await page.evaluate(() => { setConnection(''); pasteDraft('must-not-send', 'session-a'); });
  await page.evaluate(() => sendDraft('must-not-execute', 'session-a', 'offline', true));
  assert.equal(await page.evaluate(() => events.filter(e => e[0] === 'draftInput').length), 2);
  assert.equal(await page.evaluate(() => events.filter(e => e[0] === 'input').length), count);
  await page.evaluate(() => { setConnection('session-b'); pasteDraft('stale', 'session-a'); pasteDraft('one\ntwo', 'session-b'); });
  assert.equal(await page.evaluate(() => events.filter(e => e[0] === 'input').length), count);
  await feed('\x1b[?2004h', 'bracketed');
  await page.evaluate(() => pasteDraft('one\ntwo', 'session-b'));
  const pasted = await page.evaluate(() => events.filter(e => e[0] === 'input').at(-1));
  assert.equal(pasted[1], 'session-b');
  assert.equal(pasted[2], '\x1b[200~one\rtwo\x1b[201~');
  await page.evaluate(() => sendDraft('one\ntwo', 'session-b', 'multi', true));
  assert.deepEqual(await page.evaluate(() => events.at(-1)), ['draftInput', 'session-b', 'multi', '\x1b[200~one\rtwo\x1b[201~\r']);
  const before = await page.evaluate(() => terminal.rows);
  await page.setViewportSize({width: 412, height: 280});
  await page.waitForFunction(rows => terminal.rows < rows, before);
  // A parse still pending during disconnect must finish before clear, and stale
  // chunks must never repopulate the terminal after clear/reconnect.
  await page.evaluate(() => {
    receive(btoa('old-output'.repeat(5000)), 'old-pending', 'session-b');
    setConnection('');
    resetTerminal();
    receive(btoa('stale-output'), 'stale-output', 'session-b');
  });
  await page.waitForFunction(() => events.some(e => e[0] === 'ack' && e[1] === 'stale-output'));
  assert.equal(await page.evaluate(() => terminal.buffer.active.getLine(0).translateToString(true)), '');
  await page.evaluate(() => setConnection('session-c'));
  await feed('fresh-output', 'fresh');
  assert.equal(await page.evaluate(() => terminal.buffer.active.getLine(0).translateToString(true)), 'fresh-output');
  // Exercise actual xterm mouse encoding using touch events on the rendered screen.
  await feed('\x1b[?1000h\x1b[?1006h', 'mouse-sgr');
  assert.equal(await page.evaluate(() => document.elementFromPoint(100,80).className), 'terminal-touch-surface');
  await feed('\x1b[Hredrawn text under finger', 'redraw-overlay');
  assert.equal(await page.evaluate(() => document.elementFromPoint(100,80).className), 'terminal-touch-surface');
  async function touch(type, x, y) {
    await page.evaluate(([type, x, y]) => {
      const target = document.querySelector('.xterm-screen');
      const touch = new Touch({identifier: 1, target, clientX: x, clientY: y});
      target.dispatchEvent(new TouchEvent(type, {bubbles:true, cancelable:true,
        touches:type === 'touchend' || type === 'touchcancel' ? [] : [touch], changedTouches:[touch]}));
    }, [type,x,y]);
    if (type === 'touchmove') await page.waitForTimeout(80);
  }
  let mouseCount = await page.evaluate(() => events.filter(e => e[0] === 'input').length);
  await touch('touchstart', 100, 80); await touch('touchend', 100, 80);
  let sent = await page.evaluate(n => events.filter(e => e[0] === 'input').slice(n).map(e=>e[2]), mouseCount);
  assert.equal(sent.length, 2, 'one tap produces exactly one press and one release');
  assert.match(sent[0], /^\x1b\[<0;[0-9]+;[0-9]+M$/);
  assert.match(sent[1], /^\x1b\[<0;[0-9]+;[0-9]+m$/);
  mouseCount += 2;
  await touch('touchstart', 100, 80); await touch('touchmove', 100, 160); await touch('touchend', 100, 160);
  sent = await page.evaluate(n => events.filter(e => e[0] === 'input').slice(n).map(e=>e[2]), mouseCount);
  assert.ok(sent.length > 0);
  assert.ok(sent.every(e=>/^\x1b\[<64;[0-9]+;[0-9]+M$/.test(e)), 'drag down sends wheel up, never a click');
  mouseCount = await page.evaluate(() => events.filter(e => e[0] === 'input').length);
  await touch('touchstart', 100, 180); await touch('touchmove', 100, 80); await touch('touchend', 100, 80);
  sent = await page.evaluate(n => events.filter(e => e[0] === 'input').slice(n).map(e=>e[2]), mouseCount);
  assert.ok(sent.length > 0 && sent.every(e=>/^\x1b\[<65;[0-9]+;[0-9]+M$/.test(e)), 'drag up sends wheel down');
  mouseCount = await page.evaluate(() => events.filter(e => e[0] === 'input').length);
  await touch('touchstart', 100, 80); await page.waitForTimeout(600); await touch('touchend', 100, 80);
  sent = await page.evaluate(n => events.filter(e => e[0] === 'input').slice(n).map(e=>e[2]), mouseCount);
  assert.equal(sent.length, 2);
  assert.match(sent[0], /^\x1b\[<2;[0-9]+;[0-9]+M$/);
  assert.match(sent[1], /^\x1b\[<2;[0-9]+;[0-9]+m$/);
  // Smooth repeated small moves, through redraws, with a pause and a reversal.
  mouseCount = await page.evaluate(() => events.filter(e => e[0] === 'input').length);
  await touch('touchstart', 100, 70);
  const counts = [];
  for (let stage=0; stage<3; stage++) {
    for (let i=1; i<=12; i++) {
      await touch('touchmove',100,70+stage*55+i*55/12);
      await feed('\x1b[Hupdated ' + stage + ':' + i, 'move-'+stage+'-'+i);
    }
    counts.push(await page.evaluate(n=>events.filter(e=>e[0]==='input').length-n,mouseCount));
  }
  assert.ok(counts[0]>0 && counts[1]>counts[0] && counts[2]>counts[1], String(counts));
  assert.ok(counts[2] <= 5, 'default gain must not multiply a short drag into a wheel burst');
  const atRest=counts[2];
  await page.waitForTimeout(200);
  assert.equal(await page.evaluate(n=>events.filter(e=>e[0]==='input').length-n,mouseCount),atRest);
  await touch('touchmove',100,210);
  sent=await page.evaluate(n=>events.filter(e=>e[0]==='input').slice(n).map(e=>e[2]),mouseCount+atRest);
  assert.ok(sent.length>0 && sent.every(e=>/^\x1b\[<65;/.test(e)), 'reverse responds without draining old direction');
  await touch('touchend',100,210);
  const afterUp=await page.evaluate(()=>events.filter(e=>e[0]==='input').length);
  await page.waitForTimeout(200);
  assert.equal(await page.evaluate(()=>events.filter(e=>e[0]==='input').length),afterUp);
  // Android can deliver a complete fast swipe before the next animation frame.
  await page.evaluate(() => {
    const target = document.querySelector('.terminal-touch-surface');
    for (const [type,y] of [['touchstart',80],['touchmove',180],['touchend',180]]) {
      const t = new Touch({identifier:1,target,clientX:100,clientY:y});
      target.dispatchEvent(new TouchEvent(type,{bubbles:true,cancelable:true,
        touches:type==='touchend'?[]:[t],changedTouches:[t]}));
    }
  });
  sent=await page.evaluate(n=>events.filter(e=>e[0]==='input').slice(n).map(e=>e[2]),afterUp);
  assert.equal(sent.length,1,'a same-frame stroke commits one notch on release');
  assert.match(sent[0],/^\x1b\[<64;/);
  await page.waitForTimeout(200);
  assert.equal(await page.evaluate(()=>events.filter(e=>e[0]==='input').length),afterUp+1,
    'a fast stroke leaves no queued momentum after release');
  await page.setViewportSize({width:1200,height:500});
  await page.waitForFunction(()=>terminal.cols > 110);
  await feed('\x1b[?1006l', 'mouse-legacy');
  await touch('touchstart', 1000, 80); await touch('touchend', 1000, 80);
  const binary = await page.evaluate(()=>events.filter(e=>e[0]==='binaryInput').map(e=>e[2]));
  assert.ok(binary.length >= 2, 'legacy mouse is forwarded through the byte-preserving binary bridge');
  assert.ok(Buffer.from(binary[0], 'base64').some(b=>b>127));
  const beforeGesture = await page.evaluate(()=>events.filter(e=>['input','binaryInput'].includes(e[0])).length);
  await touch('touchstart', 100, 80);
  await page.evaluate(()=>setConnection('replacement'));
  await touch('touchend', 100, 80);
  assert.equal(await page.evaluate(()=>events.filter(e=>['input','binaryInput'].includes(e[0])).length), beforeGesture);
  await feed('\x1b[?1000l\x1b[?1049h', 'alt-no-mouse');
  await touch('touchstart', 100, 80); await touch('touchmove', 100, 160); await touch('touchend', 100, 160);
  assert.equal(await page.evaluate(()=>events.filter(e=>['input','binaryInput'].includes(e[0])).length), beforeGesture,
    'unnegotiated mouse must not become arrow keys or accidental Agent actions');
  await feed('\x1b[>c', 'device-attributes');
  assert.deepEqual(await page.evaluate(()=>events.filter(e=>e[0]==='input').at(-1)),
    ['input','replacement','\x1b[>0;276;0c']);
  const beforeClip = await page.evaluate(()=>[terminal.cols,terminal.rows,terminal.buffer.active.viewportY]);
  await page.evaluate(()=>setVisibleHeight(240));
  assert.deepEqual(await page.evaluate(()=>[terminal.cols,terminal.rows,terminal.buffer.active.viewportY]),beforeClip);
  assert.equal(await page.evaluate(()=>document.querySelector('.xterm-screen').getBoundingClientRect().top),0);
  await page.evaluate(()=>scrollLatest());
  assert.ok(await page.evaluate(()=>document.querySelector('.xterm-screen').getBoundingClientRect().top<0));
  await page.evaluate(()=>setVisibleHeight(window.innerHeight));
  assert.equal(await page.evaluate(()=>document.querySelector('.xterm-screen').getBoundingClientRect().top),0);
  await page.evaluate(() => { terminal.reset(); terminal.resize(12, 4); });
  await feed('中文👋 ABCDEFGHIJKLMN\r\nsecond line\r\nthird', 'copy-fixture');
  const copyEvents = await page.evaluate(() => events.filter(e=>['input','binaryInput'].includes(e[0])).length);
  const snapshot = await page.evaluate(() => terminalSnapshot());
  assert.equal(snapshot.text, '中文👋 ABCDEFGHIJKLMN\nsecond line\nthird');
  assert.equal(snapshot.clipped, false);
  await feed(' new output', 'copy-new-output');
  assert.ok(!snapshot.text.includes('new output'), 'snapshot stays immutable');
  await feed('\x1b[?1049h\x1b[Halternate only', 'copy-alternate');
  assert.equal((await page.evaluate(() => terminalSnapshot())).text, 'alternate only');
  assert.equal(await page.evaluate(() => events.filter(e=>['input','binaryInput'].includes(e[0])).length),copyEvents);
  await feed('\x1b[?1049l', 'copy-normal');
  await page.evaluate(() => terminal.resize(100,24));
  await feed(('X'.repeat(100)+'\r\n').repeat(2500), 'copy-bounded');
  const bounded = await page.evaluate(() => terminalSnapshot());
  assert.equal(bounded.clipped,true);
  assert.ok(bounded.text.length <= 200000);
  assert.ok(bounded.viewportOffset >= 0 && bounded.viewportOffset <= bounded.text.length);
  const beforeTheme=await page.evaluate(()=>({text:terminalSnapshot().text,cols:terminal.cols,rows:terminal.rows,viewport:terminal.buffer.active.viewportY,token:connectionToken,inputs:events.filter(e=>['input','binaryInput'].includes(e[0])).length}));
  await page.evaluate(()=>setTerminalAppearance(false));
  assert.equal(await page.evaluate(()=>terminal.options.theme.background),'#f7faf8');
  assert.equal(await page.evaluate(()=>terminal.options.theme.foreground),'#18251e');
  assert.equal(await page.evaluate(()=>getComputedStyle(document.body).backgroundColor),'rgb(247, 250, 248)');
  assert.equal(await page.evaluate(()=>terminal.options.minimumContrastRatio),4.5);
  await page.evaluate(()=>setTerminalAppearance(true));
  assert.equal(await page.evaluate(()=>terminal.options.theme.background),'#111916');
  assert.deepEqual(await page.evaluate(()=>({text:terminalSnapshot().text,cols:terminal.cols,rows:terminal.rows,viewport:terminal.buffer.active.viewportY,token:connectionToken,inputs:events.filter(e=>['input','binaryInput'].includes(e[0])).length})),beforeTheme);
  // Mixed CJK punctuation must occupy the same grid as the width-cache samples.
  // A pure run of one ideograph does not expose contextual punctuation trimming.
  for (const width of [336, 393, 720]) {
    await page.setViewportSize({width,height:500});
    for (const size of [13,14,17,20]) {
      await page.evaluate(size=>{terminal.reset();setFont(size);},size);
      const cols=await page.evaluate(()=>terminal.cols);
      const chinese='中文终端，右侧文字。测试：完成！'.repeat(cols).slice(0,Math.floor((cols-2)/2))
        +'A'.repeat((cols-2)%2)+'末';
      await feed(chinese+'\r\n'+'W'.repeat(cols)+'\r\n\x1b[1m'+chinese+'\x1b[0m','edge-'+width+'-'+size);
      await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
      const geometry=await page.evaluate(()=>{
        const screen=document.querySelector('.xterm-screen').getBoundingClientRect();
        return {screen:screen.right, viewport:innerWidth,
          ends:[...document.querySelectorAll('.xterm-rows > div')].slice(0,3).map(row=>{
            const range=document.createRange();range.selectNodeContents(row);
            return range.getBoundingClientRect().right;
          })};
      });
      assert.ok(geometry.screen<=geometry.viewport && geometry.ends.every(end=>end<=geometry.screen+1),
        'clipped right edge '+JSON.stringify({width,size,...geometry}));
    }
  }
  assert.deepEqual(errors, []);
  console.log('PASS: bundled xterm page, native bridge callbacks, UTF-8, paste preview protocol, stale/offline tokens, bracketed paste, resize, touch wheel/click and binary mouse protocols');
} finally {
  await browser?.close();
  await new Promise(resolve => server.close(resolve));
}
