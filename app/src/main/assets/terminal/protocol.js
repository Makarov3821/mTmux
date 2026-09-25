/* Shared by the bundled terminal and Node regression tests. */
(function (root) {
  const api = {
    decode(base64) { return Uint8Array.from(atob(base64), c => c.charCodeAt(0)); },
    validatePaste(text, bracketed) {
      if (new TextEncoder().encode(text).length > 32768) return '文本过长（最多 32 KiB）';
      if (/[\x00-\x08\x0b-\x1f\x7f]/.test(text)) return '文本含控制字符，请删除后再粘贴';
      if (/[\r\n]/.test(text) && !bracketed) return '当前程序未启用 bracketed paste，P0 不发送多行文本';
      return null;
    }
  };
  root.TerminalProtocol = api;
  if (typeof module !== 'undefined') module.exports = api;
})(globalThis);
