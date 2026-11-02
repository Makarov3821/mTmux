import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const {priority,Motion}=createRequire(import.meta.url)('./preview-lazy.js');
const view={top:0,left:0,width:400,height:600};
const rect=(top,bottom,left=0,right=200)=>({top,bottom,left,right});
test('images are eligible only in the visible viewport and one screen on either side',()=>{
  assert.equal(priority(rect(-900,-600),view),null);
  assert.notEqual(priority(rect(-610,-590),view),null);
  assert.notEqual(priority(rect(1190,1210),view),null);
  assert.equal(priority(rect(1200,1300),view),null);
  assert.equal(priority(rect(10,100,500,600),view),null);
});
test('visible images outrank both prefetch directions, with the direction of reading first',()=>{
  const visible=priority(rect(200,400),view,1),below=priority(rect(700,900),view,1),above=priority(rect(-500,-300),view,1);
  assert(visible<below && below<above);
  assert(priority(rect(-500,-300),view,-1)<priority(rect(700,900),view,-1));
});
test('fast scrolling and the first large jump pause downloads until the quiet interval',()=>{
  const target={},motion=new Motion();motion.sample(target,0,0,600);
  assert.equal(motion.sample(target,900,1000,600),true);
  assert.equal(motion.ready(1100),false);assert.equal(motion.ready(1180),true);
  assert.equal(motion.sample(target,1000,1016,600),true);
  assert.equal(motion.ready(1180),false);assert.equal(motion.ready(1196),true);
});
test('slow scrolling can load without waiting for the gesture to end',()=>{
  const target={},motion=new Motion();motion.sample(target,500,0,600);
  assert.equal(motion.sample(target,480,100,600),false);
  assert.equal(motion.direction,-1);assert.equal(motion.ready(100),true);
});
