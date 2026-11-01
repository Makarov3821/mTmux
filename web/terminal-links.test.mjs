import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const {candidates}=require('./terminal-links.js');
test('terminal links distinguish quoted paths, line suffixes and Markdown URL wrappers',()=>{
  const source='见 "docs/中文 a.md:12:3" 和 [官网](https://example.com/a(b)), /tmp/image.png';
  const links=candidates(source);
  assert.deepEqual(links.map(x=>x.text),['docs/中文 a.md:12:3','https://example.com/a(b)','/tmp/image.png']);
  for(const item of links) assert.equal(source.slice(item.start,item.end),item.text);
});
test('only recognizable files and URLs become candidates',()=>{
  assert.deepEqual(candidates('a.js javascript:alert(1) 普通彩色标题'),[]);
  assert.deepEqual(candidates('README.md ../doc/a.html file:///home/u/a.webp').map(x=>x.text),['README.md','../doc/a.html','file:///home/u/a.webp']);
});
