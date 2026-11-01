import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const {rewrite}=require('./preview-paths.js');
const context={documentUrl:'https://preview.mtmux.invalid/session/document/remote/home/u/readme.md',host:'dev.example'};
test('absolute paths and file URIs map to the same remote image',()=>{
  const expected='https://preview.mtmux.invalid/session/document/remote/home/u/a%20b.png';
  for(const path of ['/home/u/a b.png','file:///home/u/a%20b.png','file://localhost/home/u/a%20b.png','file://DEV.EXAMPLE/home/u/a%20b.png'])
    assert.equal(rewrite(path,context),expected,path);
  assert.equal(rewrite('relative.png',context),'relative.png');
  assert.equal(rewrite('https://example.com/a.png',context),'https://example.com/a.png');
  assert.equal(rewrite('//other.example/a.png',context),'//other.example/a.png');
});
test('foreign authorities and control characters never become local file reads',()=>{
  for(const path of ['file://other.example/a.png','file://user@dev.example/a.png','file:///a%00.png','file:///a\nb.png','file:relative.png'])
    assert.equal(rewrite(path,context),null,path);
  assert.equal(rewrite('file:///home/u/%2520.png',context),'https://preview.mtmux.invalid/session/document/remote/home/u/%2520.png');
  assert.equal(rewrite('/home/u/../../a.png',context),'https://preview.mtmux.invalid/session/document/remote/a.png');
});
