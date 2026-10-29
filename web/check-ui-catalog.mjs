// Run from web/: node check-ui-catalog.mjs. Uses the existing Playwright dependency.
import {chromium} from 'playwright-core';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';

const browser=await chromium.launch({executablePath:process.env.CHROME_BIN||'/usr/bin/google-chrome',headless:true,args:['--no-sandbox']});
try {
 const page=await browser.newPage();const errors=[];
 page.on('pageerror',error=>errors.push(error.message));
 await page.goto(pathToFileURL(resolve('../docs/screenshots/current/index.html')).href);
 const count=await page.locator('.card').count();assert.ok(count>0);
 const images=await page.evaluate(async()=>Promise.all(
  JSON.parse(document.getElementById('catalog-data').textContent).screens.map(row=>new Promise(resolve=>{
   const img=new Image();img.onload=()=>resolve({id:row.id,ok:img.naturalWidth===row.width&&img.naturalHeight===row.height});
   img.onerror=()=>resolve({id:row.id,ok:false});img.src=row.file;
  }))));
 assert.deepEqual(images.filter(row=>!row.ok),[]);
 await page.locator('#group').selectOption('server');assert.ok(await page.locator('.card').count()>0);
 await page.locator('#group').selectOption('');
 await page.locator('#kind').selectOption('component-fixture');assert.ok(await page.locator('.card').count()>0);
 await page.locator('#kind').selectOption('');
 await page.locator('.image-button').first().click();assert.ok(await page.locator('#viewer').evaluate(node=>node.open));
 const first=await page.locator('#large').getAttribute('src');await page.keyboard.press('ArrowRight');
 if(count>1) assert.notEqual(await page.locator('#large').getAttribute('src'),first);
 await page.locator('#close').click();
 await page.locator('#search').fill('不存在的界面ABC');assert.equal(await page.locator('.card').count(),0);
 await page.locator('#search').fill('');assert.equal(await page.locator('.card').count(),count);
 assert.deepEqual(errors,[]);
 console.log(`PASS gallery: ${count} images, offline loading, filters, zoom, navigation and search`);
} finally {await browser.close()}
