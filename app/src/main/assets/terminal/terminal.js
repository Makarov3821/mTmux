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
// The Android IME owns composing text. Never write a local echo into the remote screen.
const composing = document.createElement('div');
Object.assign(composing.style, {position:'absolute', zIndex:'7', pointerEvents:'none',
  display:'none', whiteSpace:'pre', maxWidth:'95%', overflow:'hidden',
  borderBottom:'2px solid currentColor', background:'var(--composition-bg, #263b32)',
  color:'inherit', padding:'1px 3px', borderRadius:'3px'});
terminal.element.appendChild(composing);
window.showComposition = text => {
  composing.textContent = text;
  composing.style.display = text ? 'block' : 'none';
  positionComposition();
};
function positionComposition() {
  const screen = terminal.element.querySelector('.xterm-screen');
  if (!screen || !composing.textContent) return;
  const buffer = terminal.buffer.active;
  const cellWidth = screen.clientWidth / terminal.cols;
  const cellHeight = screen.clientHeight / terminal.rows;
  composing.style.left = `${Math.max(0, Math.min(buffer.cursorX * cellWidth, screen.clientWidth - composing.offsetWidth))}px`;
  composing.style.top = `${Math.max(0, Math.min((buffer.baseY + buffer.cursorY - buffer.viewportY) * cellHeight, screen.clientHeight - cellHeight))}px`;
  composing.style.fontSize = `${terminal.options.fontSize}px`;
  composing.style.lineHeight = `${cellHeight}px`;
  composing.style.background = terminal.options.theme.background;
  composing.style.color = terminal.options.theme.foreground;
}
terminal.onCursorMove(positionComposition);
terminal.onResize(positionComposition);
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
  window.showComposition('');
  connected = token !== '';
  inputFocused = false;
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
let inputFocused = false;
function updateVisibleArea() {
  const offset = revealBottom ? Math.max(0, window.innerHeight - visibleHeight) : 0;
  document.getElementById('terminal').style.transform = `translateY(-${offset}px)`;
}
window.setVisibleHeight = height => {
  visibleHeight = Math.max(1, Number(height) || window.innerHeight);
  if (visibleHeight >= window.innerHeight) revealBottom = false;
  updateVisibleArea();
  if (inputFocused) window.revealInputCursor();
};
// Focus can arrive before the IME's first viewport update; remember it through the animation.
window.setInputFocus = focused => {
  inputFocused = connected && Boolean(focused);
  if (inputFocused) window.revealInputCursor();
};
window.scrollLatest = () => { terminal.scrollToBottom(); revealBottom = true; updateVisibleArea(); };
window.revealInputCursor = () => {
  const buffer = terminal.buffer.active;
  if (buffer.viewportY < buffer.baseY) return;
  const screen = terminal.element.querySelector('.xterm-screen');
  if (screen && (buffer.cursorY + 1) * screen.clientHeight / terminal.rows > visibleHeight) {
    revealBottom = true; updateVisibleArea();
  }
};
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
  scrollSensitivity = TerminalProtocol.normalizeScrollSpeed(value);
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
  return g.remote ? g.cell * (g.firstWheel ? 1 : 3) : g.cell;
}
function queueScroll() {
  if (!scrollFrame) scrollFrame = requestAnimationFrame(drainScroll);
}
function drainScroll() {
  scrollFrame = 0;
  const g = gesture;
  if (!g || g.token !== connectionToken || !g.moved) return;
  if (g.remote) {
    // Convert each baseline notch to N mouse events. A bounded per-frame budget
    // prevents a 10x setting from flooding the native writer in one callback.
    if (!hasRemoteMouse()) return;
    while (Math.abs(g.pending) >= scrollThreshold(g)) {
      const direction = Math.sign(g.pending);
      g.pending -= direction * scrollThreshold(g);
      g.wheelCredits = Math.max(-80, Math.min(80, g.wheelCredits + direction * scrollSensitivity));
      g.firstWheel = false;
    }
    for (let count = 0; count < Math.min(8, scrollSensitivity) && g.wheelCredits; count++) {
      const direction = Math.sign(g.wheelCredits);
      screen.dispatchEvent(new WheelEvent('wheel', {bubbles: true, cancelable: true,
        clientX: g.x, clientY: g.y, deltaY: direction, deltaMode: 1}));
      g.wheelCredits -= direction;
    }
  } else {
    const lines = Math.max(-2, Math.min(2, Math.trunc(g.pending / g.cell)));
    terminal.scrollLines(lines);
    g.pending -= lines * g.cell;
  }
  if (Math.abs(g.pending) >= scrollThreshold(g) || (g.remote && g.wheelCredits)) queueScroll();
}
container.addEventListener('touchstart', event => {
  cancelGesture();
  gesture = event.touches.length === 1 ? {x: event.touches[0].clientX,
    y: event.touches[0].clientY, lastY: event.touches[0].clientY, pending: 0, direction: 0,
    cell: Math.max(1, screen.getBoundingClientRect().height / terminal.rows),
    remote: hasRemoteMouse(), firstWheel: true, wheelCredits: 0,
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
          gesture.pending = 0; gesture.wheelCredits = 0; // reversing must not fight an old backlog
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
  // Commit at most one bounded frame now, then discard all backlog on release.
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
  if (gesture && !gesture.moved && gesture.token === connectionToken && connected && performance.now() - gesture.started < 550)
    NativeTerminal.focusInput?.();
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

window.setTerminalAppearance = (dark, palette) => {
  const background = palette?.background || (dark ? '#111916' : '#f7faf8');
  document.documentElement.style.background = background;
  document.body.style.background = background;
  terminal.options.minimumContrastRatio = 4.5;
  terminal.options.theme = palette || (dark ? {
    background, foreground:'#e2eee8',cursor:'#79d5b0',cursorAccent:background,selectionBackground:'#426b59',
    black:'#26352c',red:'#ed7777',green:'#79d5a0',yellow:'#e6ce75',blue:'#83adf2',magenta:'#ce9aed',cyan:'#75cdd5',white:'#dce8e0',
    brightBlack:'#8b9e90',brightRed:'#ff9b9b',brightGreen:'#a0efb6',brightYellow:'#f4e09b',brightBlue:'#aecaff',brightMagenta:'#e2b7f7',brightCyan:'#9ae6ee',brightWhite:'#ffffff'
  } : {
    background, foreground:'#18251e',cursor:'#176b4d',cursorAccent:background,selectionBackground:'#badbca',
    black:'#18251e',red:'#af2525',green:'#176b39',yellow:'#795900',blue:'#245caf',magenta:'#823f9b',cyan:'#086b76',white:'#586b60',
    brightBlack:'#63766a',brightRed:'#a82d35',brightGreen:'#206d37',brightYellow:'#806000',brightBlue:'#355eb0',brightMagenta:'#8c459a',brightCyan:'#176875',brightWhite:'#374c40'
  });
};
