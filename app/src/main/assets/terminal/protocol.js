/* Shared by the bundled terminal and Node regression tests. */
(function (root) {
  const api = {
    decode(base64) { return Uint8Array.from(atob(base64), c => c.charCodeAt(0)); },
    /** Returns a stable code (localized by the Android host) or null when the paste is allowed. */
    validatePaste(text, bracketed) {
      if (new TextEncoder().encode(text).length > 32768) return 'PASTE_TOO_LONG';
      if (/[\x00-\x08\x0b-\x1f\x7f]/.test(text)) return 'PASTE_CONTROL';
      if (/[\r\n]/.test(text) && !bracketed) return 'PASTE_MULTILINE_UNSUPPORTED';
      return null;
    }
  };
  root.TerminalProtocol = api;
  if (typeof module !== 'undefined') module.exports = api;
})(globalThis);
