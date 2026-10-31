// 渲染模式图：node docs/plan-ui/shared/render.mjs
// 依赖 web/node_modules 中的 playwright-core 和本机 Chrome（CHROME_BIN 可覆盖路径）。
import { createRequire } from 'node:module';
import { mkdirSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const planDir = resolve(here, '..');
const repo = resolve(planDir, '../..');
const require = createRequire(join(repo, 'web/package.json'));
const { chromium } = require('playwright-core');

const schemes = (process.argv.slice(2).length ? process.argv.slice(2) : ['a-refined', 'b-tui', 'c-paper', 'a2-refined', 'a2-doctor']);
const browser = await chromium.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', args: ['--font-render-hinting=none'] });
for (const name of schemes) {
  const file = join(planDir, `${name}.html`);
  if (!existsSync(file)) { console.error('missing', file); continue; }
  const out = join(planDir, 'png', name);
  mkdirSync(out, { recursive: true });
  const page = await browser.newPage({ viewport: { width: 2480, height: 1200 }, deviceScaleFactor: 2 });
  await page.goto(pathToFileURL(file).href);
  await page.evaluate(() => document.fonts.ready);
  const shots = await page.$$('figure.shot');
  for (const shot of shots) {
    const id = await shot.getAttribute('data-id');
    await (await shot.$('.phone')).screenshot({ path: join(out, `${id}.png`), omitBackground: true });
  }
  const board = await browser.newPage({ viewport: { width: 2480, height: 1200 }, deviceScaleFactor: 1 });
  await board.goto(pathToFileURL(file).href);
  await board.evaluate(() => document.fonts.ready);
  await board.screenshot({ path: join(planDir, 'png', `${name}-board.png`), fullPage: true });
  console.log(name, shots.length, 'screens');
  await page.close(); await board.close();
}
await browser.close();
