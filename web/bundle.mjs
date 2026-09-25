import { copyFileSync, mkdirSync } from 'node:fs';
const destination = '../app/src/main/assets/terminal';
mkdirSync(destination, { recursive: true });
for (const [source, name] of [
  ['node_modules/@xterm/xterm/lib/xterm.js', 'xterm.js'],
  ['node_modules/@xterm/xterm/css/xterm.css', 'xterm.css'],
  ['node_modules/@xterm/addon-fit/lib/addon-fit.js', 'addon-fit.js'],
  ['node_modules/@xterm/xterm/LICENSE', 'LICENSE-xterm.txt'],
  ['node_modules/@xterm/addon-fit/LICENSE', 'LICENSE-addon-fit.txt'],
  ['terminal.js', 'terminal.js'], ['protocol.js', 'protocol.js'], ['index.html', 'index.html']
]) copyFileSync(source, `${destination}/${name}`);
