import { copyFileSync, mkdirSync } from 'node:fs';
const destination = '../app/src/main/assets/terminal';
mkdirSync(destination, { recursive: true });
for (const [source, name] of [
  ['node_modules/@xterm/xterm/lib/xterm.js', 'xterm.js'],
  ['node_modules/@xterm/xterm/css/xterm.css', 'xterm.css'],
  ['node_modules/@xterm/addon-fit/lib/addon-fit.js', 'addon-fit.js'],
  ['node_modules/@xterm/xterm/LICENSE', 'LICENSE-xterm.txt'],
  ['node_modules/@xterm/addon-fit/LICENSE', 'LICENSE-addon-fit.txt'],
  ['terminal.js', 'terminal.js'], ['terminal-links.js', 'terminal-links.js'], ['protocol.js', 'protocol.js'], ['index.html', 'index.html']
]) copyFileSync(source, `${destination}/${name}`);
const preview='../app/src/main/assets/preview';
mkdirSync(preview,{recursive:true});
for(const [source,name] of [
  ['node_modules/marked/lib/marked.umd.js','marked.js'],
  ['node_modules/marked/LICENSE','LICENSE-marked.txt'],
  ['node_modules/dompurify/dist/purify.min.js','purify.js'],
  ['node_modules/dompurify/LICENSE','LICENSE-dompurify.txt'],
  ['preview.js','preview.js'],['preview-paths.js','preview-paths.js'],['preview-lazy.js','preview-lazy.js']
]) copyFileSync(source,`${preview}/${name}`);
