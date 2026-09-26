/* No remote URLs, injected HTML, analytics, link handlers or clipboard escape support. */
const terminal = new Terminal({
  cursorBlink: true, fontSize: 14, scrollback: 3000,
  theme: { background: '#111916', foreground: '#e2eee8' },
  allowProposedApi: false
});
const fit = new FitAddon.FitAddon();
terminal.loadAddon(fit);
terminal.open(document.getElementById('terminal'));
let connected = false;
let connectionToken = '';
let draftData = null;
let writes = Promise.resolve();
let gesture = null;
let remoteTouch = true;
let scrollSensitivity = 1;
let scrollFrame = 0;
function cancelGesture() {
  gesture = null;
  if (scrollFrame) cancelAnimationFrame(scrollFrame);
  scrollFrame = 0;
}
terminal.textarea?.setAttribute("inputmode", "none");
terminal.onData(data => {
  if (draftData !== null) { draftData.push(data); return; }
  if (connected) NativeTerminal.input(connectionToken, data);
});
terminal.onBinary(data => {
  if (connected) NativeTerminal.binaryInput(connectionToken, btoa(data));
});
terminal.onResize(({cols, rows}) => NativeTerminal.resize(cols, rows));
new ResizeObserver(() => fit.fit()).observe(document.getElementById('terminal'));
window.receive = (base64, id, token = connectionToken) => {
  writes = writes.then(() => new Promise(resolve => {
    if (token !== connectionToken) { NativeTerminal.ack(id); resolve(); return; }
    terminal.write(TerminalProtocol.decode(base64), () => { NativeTerminal.ack(id); resolve(); });
  }));
};
window.setConnection = token => {
  cancelGesture();
  connected = token !== '';
  revealBottom = false;
  updateVisibleArea();
  connectionToken = token;
  terminal.options.disableStdin = !connected;
};
window.pasteDraft = (text, token) => {
  try {
    if (!connected || token !== connectionToken) return NativeTerminal.notice('CONNECTION_CHANGED');
    const error = TerminalProtocol.validatePaste(text, terminal.modes.bracketedPasteMode);
    if (error) return NativeTerminal.notice(error);
    terminal.paste(text);
  } finally {
    // Native UI enables Enter only after the paste's input callback has been queued.
    NativeTerminal.pasteFinished(token);
  }
};
window.sendDraft = (text, token, id, enter) => {
  if (!connected || token !== connectionToken) {
    NativeTerminal.notice('CONNECTION_CHANGED');
    return NativeTerminal.pasteFinished(token);
  }
  const error = TerminalProtocol.validatePaste(text, terminal.modes.bracketedPasteMode);
  if (error) {
    NativeTerminal.notice(error);
    return NativeTerminal.pasteFinished(token);
  }
  // Encode with xterm's actual bracketed-paste mode, then write text + Enter
  // as one ordered native operation. No command or shell quoting is involved.
  draftData = [];
  let data;
  try { terminal.paste(text); data = draftData.join(''); }
  finally { draftData = null; }
  NativeTerminal.draftInput(token, id, data + (enter ? '\r' : ''));
};
window.setFont = size => { terminal.options.fontSize = size; fit.fit(); };
window.copySelection = () => NativeTerminal.copy(terminal.getSelection());
let visibleHeight = window.innerHeight;
let revealBottom = false;
function updateVisibleArea() {
  const offset = revealBottom ? Math.max(0, window.innerHeight - visibleHeight) : 0;
  document.getElementById('terminal').style.transform = `translateY(-${offset}px)`;
}
window.setVisibleHeight = height => {
  visibleHeight = Math.max(1, Number(height) || window.innerHeight);
  if (visibleHeight >= window.innerHeight) revealBottom = false;
  updateVisibleArea();
};
window.scrollLatest = () => { terminal.scrollToBottom(); revealBottom = true; updateVisibleArea(); };
window.resetTerminal = () => { writes = writes.then(() => terminal.reset()); };
// Let xterm encode the currently negotiated mouse protocol (SGR, legacy, etc.).
// Never substitute arrow keys when the application has not requested a mouse.
const container = document.getElementById('terminal');
const screen = terminal.element.querySelector('.xterm-screen');
// Touch targets must outlive xterm's replaceChildren() on every text redraw.
// Otherwise a finger starting on a glyph remains targeted at a detached span
// and subsequent touchmove events never reach our container.
const touchSurface = document.createElement('div');
touchSurface.className = 'terminal-touch-surface';
touchSurface.setAttribute('aria-hidden', 'true');
Object.assign(touchSurface.style, {position: 'absolute', inset: '0', zIndex: '5', touchAction: 'none'});
terminal.element.appendChild(touchSurface);
window.setRemoteTouch = enabled => { remoteTouch = enabled; cancelGesture(); };
window.setScrollSensitivity = value => {
  scrollSensitivity = Math.max(0.5, Math.min(2, Number(value) || 1));
  cancelGesture();
};
function hasRemoteMouse() { return connected && remoteTouch && terminal.modes.mouseTrackingMode !== 'none'; }
function mouse(type, x, y, button = 0) {
  screen.dispatchEvent(new MouseEvent(type, {bubbles: true, cancelable: true,
    clientX: x, clientY: y, button, buttons: type === 'mousedown' ? (button === 2 ? 2 : 1) : 0}));
}
// Keep fractional movement, and pace wheel notches over frames rather than
// sending a burst for every coalesced move. No momentum after release.
function scrollThreshold(g) {
  return g.remote ? g.cell * (g.firstWheel ? 1 : 3) / scrollSensitivity : g.cell;
}
function queueScroll() {
  if (!scrollFrame) scrollFrame = requestAnimationFrame(drainScroll);
}
function drainScroll() {
  scrollFrame = 0;
  const g = gesture;
  if (!g || g.token !== connectionToken || !g.moved) return;
  const threshold = scrollThreshold(g);
  if (Math.abs(g.pending) < threshold) return;
  if (g.remote) {
    // A redraw may briefly switch protocols. Retain distance until the next
    // move, but never substitute local scrolling or arrow keys for that input.
    if (!hasRemoteMouse()) return;
    screen.dispatchEvent(new WheelEvent('wheel', {bubbles: true, cancelable: true,
      clientX: g.x, clientY: g.y, deltaY: Math.sign(g.pending), deltaMode: 1}));
    g.pending -= Math.sign(g.pending) * threshold;
    g.firstWheel = false;
  } else {
    const lines = Math.max(-2, Math.min(2, Math.trunc(g.pending / g.cell)));
    terminal.scrollLines(lines);
    g.pending -= lines * g.cell;
  }
  if (Math.abs(g.pending) >= scrollThreshold(g)) queueScroll();
}
container.addEventListener('touchstart', event => {
  cancelGesture();
  gesture = event.touches.length === 1 ? {x: event.touches[0].clientX,
    y: event.touches[0].clientY, lastY: event.touches[0].clientY, pending: 0, direction: 0,
    cell: Math.max(1, screen.getBoundingClientRect().height / terminal.rows),
    remote: hasRemoteMouse(), firstWheel: true,
    started: performance.now(), moved: false, token: connectionToken} : null;
  event.preventDefault();
  event.stopImmediatePropagation();
}, {capture: true, passive: false});
container.addEventListener('touchmove', event => {
  if (gesture && event.touches.length === 1 && gesture.token === connectionToken) {
    const current = event.touches[0];
    if (Math.hypot(current.clientX - gesture.x, current.clientY - gesture.y) > 8) gesture.moved = true;
    if (gesture.moved) {
      const delta = gesture.lastY - current.clientY;
      gesture.lastY = current.clientY;
      if (delta) {
        if (gesture.direction && Math.sign(delta) !== gesture.direction) {
          gesture.pending = 0; // reversing must not fight an old backlog
          gesture.firstWheel = true;
        }
        gesture.direction = Math.sign(delta);
        gesture.pending += delta;
        // Bound latency if the remote temporarily disables mouse reporting.
        gesture.pending = Math.max(-gesture.cell * 24, Math.min(gesture.cell * 24, gesture.pending));
        queueScroll();
      }
    }
  } else { cancelGesture(); }
  event.preventDefault();
  event.stopImmediatePropagation();
}, {capture: true, passive: false});
container.addEventListener('touchend', event => {
  // A quick stroke can finish before the browser's next animation frame.
  // Commit one pending notch now, then discard any backlog on release.
  if (gesture?.moved) {
    if (scrollFrame) cancelAnimationFrame(scrollFrame);
    scrollFrame = 0;
    drainScroll();
  }
  if (gesture && !gesture.moved && gesture.token === connectionToken && hasRemoteMouse()) {
    const button = performance.now() - gesture.started > 550 ? 2 : 0;
    mouse('mousedown', gesture.x, gesture.y, button);
    mouse('mouseup', gesture.x, gesture.y, button);
  }
  cancelGesture();
  event.preventDefault();
  event.stopImmediatePropagation();
}, {capture: true, passive: false});
container.addEventListener('touchcancel', cancelGesture, {capture: true});
// Prevent WebView from opening its context menu or generating a second click.
container.addEventListener('contextmenu', event => event.preventDefault());
terminal.onScroll(() => NativeTerminal.reading?.(terminal.buffer.active.viewportY < terminal.buffer.active.baseY));
fit.fit();
setConnection('');
NativeTerminal.ready(terminal.cols, terminal.rows);

// A bounded, immutable text export; never requests remote history or emits input.
window.terminalSnapshot = () => {
  const buffer = terminal.buffer.active;
  let text = '', viewportOffset = 0, clipped = false;
  for (let i = 0; i < buffer.length; i++) {
    const line = buffer.getLine(i);
    if (i > 0 && !line.isWrapped) text += '\n';
    if (i === buffer.viewportY) viewportOffset = text.length;
    const nextWrapped = buffer.getLine(i + 1)?.isWrapped;
    text += line.translateToString(!nextWrapped);
    if (text.length > 200000) {
      let removed = text.length - 200000;
      // Do not split a surrogate pair at the retained boundary.
      if (text.charCodeAt(removed) >= 0xDC00 && text.charCodeAt(removed) <= 0xDFFF) removed++;
      text = text.slice(removed);
      viewportOffset = Math.max(0, viewportOffset - removed);
      clipped = true;
    }
  }
  text = text.replace(/\n+$/, '');
  return {text, clipped, viewportOffset: Math.min(viewportOffset, text.length)};
};

window.setTerminalAppearance = dark => {
  const background = dark ? '#111916' : '#f7faf8';
  document.documentElement.style.background = background;
  document.body.style.background = background;
  terminal.options.minimumContrastRatio = 4.5;
  terminal.options.theme = dark ? {
    background, foreground:'#e2eee8',cursor:'#79d5b0',cursorAccent:background,selectionBackground:'#426b59',
    black:'#26352c',red:'#ed7777',green:'#79d5a0',yellow:'#e6ce75',blue:'#83adf2',magenta:'#ce9aed',cyan:'#75cdd5',white:'#dce8e0',
    brightBlack:'#8b9e90',brightRed:'#ff9b9b',brightGreen:'#a0efb6',brightYellow:'#f4e09b',brightBlue:'#aecaff',brightMagenta:'#e2b7f7',brightCyan:'#9ae6ee',brightWhite:'#ffffff'
  } : {
    background, foreground:'#18251e',cursor:'#176b4d',cursorAccent:background,selectionBackground:'#badbca',
    black:'#18251e',red:'#af2525',green:'#176b39',yellow:'#795900',blue:'#245caf',magenta:'#823f9b',cyan:'#086b76',white:'#586b60',
    brightBlack:'#63766a',brightRed:'#a82d35',brightGreen:'#206d37',brightYellow:'#806000',brightBlue:'#355eb0',brightMagenta:'#8c459a',brightCyan:'#176875',brightWhite:'#374c40'
  };
};
