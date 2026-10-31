// 演示终端内容（与现有截图使用的演示数据一致）。<div class="term" data-box="1" data-cursor="1"></div>
// 颜色类：t-b 强调/标题、t-g 成功、t-y 警告、t-r 错误、t-m 次要、t-w 高亮正文。
function cw(s) { let n = 0; for (const ch of s) n += /[\u1100-\uffff]/.test(ch) && !/[\u2500-\u257f\u23bf\u23fa\u25cf\u2713\u2212]/.test(ch) ? 2 : 1; return n; }
function pad(s, w) { return s + ' '.repeat(Math.max(0, w - cw(s))); }
function esc(s) { return s.replace(/&/g, '&amp;').replace(/</g, '&lt;'); }
function fillTerm(el) {
  const box = +(el.dataset.box || 42);
  const cursor = el.dataset.cursor === '1';
  const prompt = el.dataset.prompt ?? '请检查最新修改并运行测试';
  const top = [
    '<span class="t-b">●</span> <span class="t-w">Code Review Agent</span>',
    '已完成连接池与输入链路检查。',
    '',
    '<span class="t-g">✓</span> SSH 连接可复用',
    '<span class="t-g">✓</span> 当前任务身份已确认',
    '<span class="t-g">✓</span> 测试通过，等待下一条指令',
    '',
    '<span class="t-m">  TerminalView.kt   </span><span class="t-g">+42</span> <span class="t-r">−7</span>',
    '<span class="t-m">  SshPool.kt        </span><span class="t-g">+18</span> <span class="t-r">−3</span>',
    '',
    '<span class="t-b">⏺</span> 运行 ./gradlew :core:test',
    '<span class="t-m">  ⎿ BUILD SUCCESSFUL in 41s · 312 tests</span>',
  ];
  // 最后一行是远端 Agent 自己的提示行；光标即直接输入的位置（应用不再有独立输入框）
  const bot = [`<span class="t-b">›</span> ${esc(prompt)}${cursor ? '<span class="t-cur"> </span>' : ''}`];
  el.innerHTML = `<div class="t-top">${top.join('\n')}</div><div class="t-bot">${bot.join('')}</div>`;
}
document.addEventListener('DOMContentLoaded', () => document.querySelectorAll('.term[data-box]').forEach(fillTerm));
