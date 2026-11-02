// Actual bundled renderer in Chromium. Android/SFTP is separately covered by instrumentation.
import {chromium} from 'playwright-core';
import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const assets=resolve('../app/src/main/assets/preview');
const origin='https://preview.mtmux.invalid',prefix='/session/document',documentUrl=origin+prefix+'/remote/project/readme.md';
const svg=Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="160" height="180"><rect width="160" height="180" fill="green"/></svg>');
const browser=await chromium.launch({executablePath:process.env.CHROME_BIN||'/usr/bin/google-chrome',headless:true,args:['--no-sandbox']});
try {
  for(const kind of ['markdown','html']) {
    const page=await browser.newPage({viewport:{width:420,height:600}});
    const errors=[],downloads=[],cache=new Map(),finished=[],cancelled=[],finishedIds=[],cancelledIds=[];
    let allowed=0,sequence=0,failures=0;
    page.on('pageerror',e=>errors.push(e.message));
    const sections=Array.from({length:40},(_,i)=>kind==='html'
      ?`<section><h2 id="image${i}">Image ${i}</h2><img alt="image${i}" src="image${i}.svg" width="160" height="180"></section>`
      :`## Image ${i}\n\n<img alt="image${i}" src="image${i}.svg" width="160" height="180">\n\n`+'More readable text.\n\n'.repeat(8));
    const source=kind==='html'
      ?'<title>Inert HTML</title><link rel="stylesheet" href="styles/theme.css"><style>section{height:520px}</style><body class="report" style="color:rgb(12,34,56)"><h1>Full document</h1><details><summary>Hidden slow image</summary><img alt="slow" src="slow.svg" width="160" height="180"></details><img alt="retry" src="failure.svg" width="160" height="180"><div class="background" style="width:180px;height:180px"></div>'+sections.join('')+'<script>window.pwned=true</script><img src="https://example.com/evil.png" onerror="window.pwned=true">'+'<span class="flat">Text </span>'.repeat(130000)
      :'# Full document\n\n'+sections.join('\n');
    const context={documentUrl,kind,host:'fixture',windowUrl:origin+prefix+'/assets/window?key=test',messages:{failed:'Failed',retry:'Retry',limit:'Too large'}};
    const scripts=['marked.js','purify.js','preview-paths.js','preview-lazy.js','preview.js'];
    const html='<meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0;padding:18px;font:16px/1.7 system-ui}img{max-width:100%;height:auto}.preview-pending{min-width:80px;min-height:120px}#document{max-width:850px}</style><main id="document"></main>'+
      `<script id="preview-source" type="text/plain">${Buffer.from(source).toString('base64')}</script><script id="preview-context" type="text/plain">${Buffer.from(JSON.stringify(context)).toString('base64')}</script>`+
      scripts.map(name=>`<script nonce="fixture" src="${origin+prefix}/assets/${name}"></script>`).join('');
    await page.route('**/*',async route=>{
      const url=new URL(route.request().url());
      const headers={'Content-Security-Policy':"default-src 'none'; script-src 'nonce-fixture'; connect-src 'self'; img-src 'self' blob:; style-src 'self' 'unsafe-inline'; base-uri 'none'; frame-src 'none'; object-src 'none'",'Cache-Control':'no-store'};
      if(url.origin!==origin) return route.abort();
      if(url.pathname===new URL(documentUrl).pathname) return route.fulfill({body:html,contentType:'text/html',headers});
      if(url.pathname===prefix+'/assets/window') {
        const next=Number(url.searchParams.get('sequence'));
        if(next>sequence) {sequence=next;allowed=Number(url.searchParams.get('id'));}
        return route.fulfill({body:'{}',contentType:'application/json',headers});
      }
      if(url.pathname.startsWith(prefix+'/assets/')) {
        const name=url.pathname.split('/').at(-1);
        if(!scripts.includes(name)) return route.abort();
        return route.fulfill({body:await readFile(resolve(assets,name)),contentType:'text/javascript',headers});
      }
      if(url.pathname.endsWith('theme.css')) return route.fulfill({body:'.background{background-image:url(bg.svg)}',contentType:'text/css',headers});
      const id=Number(url.searchParams.get('_mtmux_image'));
      if(!id || id!==allowed) return route.fulfill({status:409,body:'',headers});
      if(url.pathname.endsWith('failure.svg') && failures++===0) return route.fulfill({status:413,body:'',headers:{...headers,'X-Preview-Limit':'10485760'}});
      if(!cache.has(url.pathname)) {
        downloads.push(url.pathname);
        if(url.pathname.endsWith('image2.svg') || url.pathname.endsWith('slow.svg')) {
          await new Promise(r=>setTimeout(r,url.pathname.endsWith('slow.svg')?1500:180));
          if(id!==allowed) {cancelled.push(url.pathname);cancelledIds.push(id);return route.fulfill({status:409,body:'',headers}).catch(()=>{});}
        }
        cache.set(url.pathname,svg);finished.push(url.pathname);finishedIds.push(id);
      }
      return route.fulfill({body:cache.get(url.pathname),contentType:'image/svg+xml',headers}).catch(()=>{});
    });
    await page.goto(documentUrl);
    await page.waitForFunction(()=>document.documentElement.dataset.previewReady==='true');
    await page.waitForFunction(()=>document.querySelector('img[alt="image0"]')?.naturalWidth===160);
    assert.equal(await page.locator('h2').count(),40,'all document text must be present before scrolling');
    assert.equal(await page.evaluate(()=>typeof NativeTerminal),'undefined');
    assert.equal(await page.evaluate(()=>typeof pwned),'undefined');
    assert(!downloads.some(path=>/image(?:[5-9]|[12]\d|3\d)\.svg$/.test(path)),`${kind} eagerly loaded off-screen images: ${downloads}`);
    if(kind==='html') {
      assert(!downloads.some(path=>path.endsWith('slow.svg')),'collapsed details must not download');
      const retry=page.locator('img[alt=retry]');
      await retry.waitFor();await page.waitForFunction(()=>document.querySelector('img[alt=retry]')?.dataset.previewState==='error');
      assert((await retry.getAttribute('title')).includes('Too large'));
      await retry.click();await page.waitForFunction(()=>document.querySelector('img[alt=retry]')?.naturalWidth===160);
      await page.locator('summary').click();
      const deadline=Date.now()+5000;
      while(!downloads.some(path=>path.endsWith('slow.svg')) && Date.now()<deadline) await page.waitForTimeout(20);
      assert(downloads.some(path=>path.endsWith('slow.svg')),'expanded visible content should start loading');
    }
    const before=downloads.length;
    // Large and repeated jumps mimic a fast fling: none of the intermediate screens may queue IO.
    for(let i=4;i<=28;i+=4) {
      await page.evaluate(index=>window.scrollTo(0,document.querySelectorAll('h2')[index].offsetTop),i);
      await page.waitForTimeout(18);
    }
    await page.waitForFunction(()=>document.querySelector('img[alt="image28"]')?.naturalWidth===160);
    const after=downloads.slice(before);
    assert(!after.some(path=>/image(?:[5-9]|1\d|2[0-4])\.svg$/.test(path)),`${kind} loaded intermediate fling screens: ${after}`);
    const firstDownloads=downloads.filter(x=>x.endsWith('image0.svg')).length;
    await page.evaluate(()=>scrollTo(0,0));
    await page.waitForFunction(()=>document.querySelector('img[alt="image0"]')?.naturalWidth===160);
    assert.equal(downloads.filter(x=>x.endsWith('image0.svg')).length,firstDownloads,'returning should reuse native disk cache');
    if(kind==='html') {
      assert.equal(await page.title(),'Inert HTML');
      assert.equal(await page.locator('section > img').count(),40,'HTML image loading must preserve CSS child selectors');
      assert.equal(await page.evaluate(()=>document.body.className),'report');
      assert.equal(await page.evaluate(()=>getComputedStyle(document.body).color),'rgb(12, 34, 56)');
      assert.equal(await page.locator('.flat').count(),130000,'large flat HTML must load all text');
      await page.waitForFunction(()=>getComputedStyle(document.querySelector('.background')).backgroundImage.includes('blob:'));
      assert(downloads.some(x=>x.endsWith('/styles/bg.svg')),'CSS background should be deferred and then loaded');
    }
    // Continue through more than the retired 32-file limit.
    for(let i=0;i<40;i++) {
      await page.evaluate(index=>scrollTo(0,document.querySelectorAll('h2')[index].offsetTop),i);
      await page.waitForFunction(index=>document.querySelector(`img[alt="image${index}"]`)?.naturalWidth===160,i);
    }
    assert(finished.filter(x=>/image\d+\.svg$/.test(x)).length>=40);
    if(kind==='html') {
      assert(cancelled.some(path=>path.endsWith('slow.svg')),'fling must cancel the previous slow image');
      assert(cancelledIds.every(id=>!finishedIds.includes(id)),'cancelled image request must not complete in the background');
    }
    assert.deepEqual(errors,[]);
    console.log(`PASS ${kind}: complete text, viewport priority, fling skipping, cached return, >32 files, inert source${kind==='html'?', deferred CSS backgrounds':''}`);
    await page.close();
  }
} finally {await browser.close();}
