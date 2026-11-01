import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const {candidates,linksForRow}=require('./terminal-links.js');
const {Terminal}=require('@xterm/headless');
const write=(term,text)=>new Promise(resolve=>term.write(text,resolve));
test('terminal links distinguish quoted paths, line suffixes and Markdown URL wrappers',()=>{
  const source='见 "docs/中文 a.md:12:3" 和 [官网](https://example.com/a(b)), /tmp/image.png';
  const links=candidates(source);
  assert.deepEqual(links.map(x=>x.text),['docs/中文 a.md:12:3','https://example.com/a(b)','/tmp/image.png']);
  for(const item of links) assert.equal(source.slice(item.start,item.end),item.text);
});
test('only recognizable files and URLs become candidates',()=>{
  assert.deepEqual(candidates('a.js javascript:alert(1) 普通彩色标题'),[]);
  assert.deepEqual(candidates('README.md ../doc/a.html file:///home/u/a.webp').map(x=>x.text),['README.md','../doc/a.html','file:///home/u/a.webp']);
  assert.deepEqual(candidates('(docs/diagram.svg) photo.jpeg image.JPG docs/图.svg:12').map(x=>x.text),
    ['docs/diagram.svg','photo.jpeg','image.JPG','docs/图.svg:12']);
});
test('parentheses around a report link are not part of its remote filename',()=>{
  const path='2nd/tackle-1/batch/reports/index.html';
  const source='可以查看处理报告 ('+path+')。';
  const found=candidates(source);
  assert.deepEqual(found.map(x=>x.text),[path]);
  assert.equal(source.slice(found[0].start,found[0].end),path);
  assert.deepEqual(candidates('[报告](docs/report.html) docs/chart(1).html (draft).html "(literal.html"').map(x=>x.text),
    ['docs/report.html','docs/chart(1).html','(draft).html','(literal.html']);
});
test('Codex parenthesized HTML links continue across an inset hard wrap without indentation',async()=>{
  const term=new Terminal({cols:48,rows:6,allowProposedApi:true});
  try {
    await write(term,'batch)，可以查看处理报告 (2nd/tackle-1/\r\nbatch/reports/index.html)。\r\n\r\n下一项 docs/other.html');
    const path='2nd/tackle-1/batch/reports/index.html';
    for(const row of [1,2]) assert.deepEqual(linksForRow(term,row).map(x=>x.text),[path]);
    assert.deepEqual(linksForRow(term,4).map(x=>x.text),['docs/other.html']);
  } finally {term.dispose();}
});
test('Agent hard wraps preserve a URL and file path from either visible row',async()=>{
  const term=new Terminal({cols:18,rows:8,allowProposedApi:true});
  try {
    await write(term,'  https://a.b.c.\r\n  d/path?q=1\r\n\r\n  docs/very-long\r\n  /folder/readme.md');
    assert.equal(term.buffer.active.getLine(1).isWrapped,false);
    for(const row of [1,2]) assert.deepEqual(linksForRow(term,row).map(x=>x.text),['https://a.b.c.d/path?q=1']);
    for(const row of [4,5]) assert.deepEqual(linksForRow(term,row).map(x=>x.text),['docs/very-long/folder/readme.md']);
  } finally {term.dispose();}
});
test('true wraps preserve CJK cell positions and adjacent independent links stay separate',async()=>{
  const term=new Terminal({cols:20,rows:8,allowProposedApi:true});
  try {
    await write(term,'  docs/'+ '文'.repeat(12)+'.md\r\n\r\nhttps://one.example\r\nhttps://two.example\r\nnotes.md\r\nimage.png');
    const wrapped=linksForRow(term,2).find(x=>x.text.endsWith('.md'));
    assert.equal(wrapped.text,'docs/'+ '文'.repeat(12)+'.md');
    assert.equal(wrapped.range.start.x,3);
    assert.deepEqual(linksForRow(term,4).map(x=>x.text),['https://one.example']);
    assert.deepEqual(linksForRow(term,5).map(x=>x.text),['https://two.example']);
    assert.deepEqual(linksForRow(term,6).map(x=>x.text),['notes.md']);
    assert.deepEqual(linksForRow(term,7).map(x=>x.text),['image.png']);
  } finally {term.dispose();}
});
