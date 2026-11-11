import {test} from 'node:test';
import assert from 'node:assert/strict';
import {overflowMatches} from './overflow-contract.js';
import {stageSettings} from './data.mjs';
const places=Object.fromEntries(Array.from({length:701},(_,i)=>[String(i),{name:'place'+i,latitude:37,longitude:127}]));
const oracle={places,count:701,latSum:37*701,lngSum:127*701};
function body(offset=0){return [...Array.from({length:699},(_,i)=>{const id=String((i+offset)%701);return {id,type:'place',count:1,...places[id]};}),{id:'overflow',clusterKey:'overflow',type:'cluster',count:2,name:'2개 장소',latitude:37,longitude:127}];}
test('equal-count membership may vary but every visible value and aggregate stays checked',()=>{
  assert.ok(overflowMatches(body(),oracle));assert.ok(overflowMatches(body(1),oracle));
  for(const mutate of [b=>b.pop(),b=>b[1]=b[0],b=>b[0].latitude++,b=>b[0].name='wrong',b=>b[699].count++,b=>b[699].longitude+=.01,b=>b[0].unexpected=1]) {
    const b=body();mutate(b);assert.equal(overflowMatches(b,oracle),false);
  }
});
test('paired environments preserve P0 image and use fresh databases and output',()=>{
  const a=stageSettings('e15-a'),b=stageSettings('e15-b');
  assert.equal(a.image,'loco-perf-p0:local');assert.equal(a.stage,'p0');assert.equal(b.stage,'p1');
  assert.notEqual(a.project,b.project);assert.notEqual(a.output,b.output);
  assert.notEqual(a.output,stageSettings('p0').output);assert.notEqual(b.output,stageSettings('p1').output);
});
