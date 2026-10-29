// Documentation fixture: actual bundled xterm, offline output, no SSH or remote process.
import {chromium} from 'playwright-core';
import {mkdirSync} from 'node:fs';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
const output=resolve('../app/src/androidTestVoice/assets/ui-catalog');mkdirSync(output,{recursive:true});
const browser=await chromium.launch({executablePath:process.env.CHROME_BIN||'/usr/bin/google-chrome',headless:true,args:['--no-sandbox']});
try {
 for(const dark of [true,false]) {
  const page=await browser.newPage({viewport:{width:336,height:652},deviceScaleFactor:1});
  await page.addInitScript(()=>{window.NativeTerminal=Object.fromEntries(['ready','resize','input','draftInput','notice','ack','copy','pasteFinished','binaryInput','focusInput','reading'].map(name=>[name,()=>{}]))});
  await page.goto(pathToFileURL(resolve('../app/src/main/assets/terminal/index.html')).href);
  await page.waitForFunction(()=>typeof setConnection==='function');await page.evaluate(()=>document.fonts.ready);
  await page.evaluate(dark=>{
   setTerminalAppearance(dark,{background:dark?'#13141c':'#f1f3fa',foreground:dark?'#d5d9f8':'#24283b',cursor:dark?'#7aa2f7':'#345cab',green:dark?'#9ece6a':'#176b39'});
   setConnection('catalog-offline-render');
   const body='\x1b[32m● Code Review Agent\x1b[0m\r\n\r\n已完成连接池与输入链路检查。\r\n\r\n✓ SSH 连接可复用\r\n✓ 当前任务身份已确认\r\n✓ 测试通过，等待下一条指令';
   return new Promise(resolve=>terminal.write(body+`\x1b[${terminal.rows-2};1H› 请检查最新修改并运行测试`,resolve));
  },dark);
  await page.screenshot({path:resolve(output,`terminal-${dark?'dark':'light'}.png`)});await page.close();
 }
 console.log('Rendered bundled xterm documentation fixtures, 336 × 652, dark/light');
}finally{await browser.close()}
