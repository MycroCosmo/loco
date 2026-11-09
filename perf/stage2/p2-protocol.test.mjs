import test from 'node:test';
import assert from 'node:assert/strict';
import {readSchedule,writeSchedule,writeSpec,evaluate} from './p2-protocol.mjs';
import {stageSettings} from './data.mjs';

test('original P0 is compared only at 1%, A1 only at 10%, AB/BA alternates',()=>{
  const runs=readSchedule();assert.equal(runs.length,20);
  assert.deepEqual(runs.slice(0,6).map(r=>r.side),['a0','b','a1','b','b','a0']);
  assert.ok(runs.filter(r=>r.viewport===1).every(r=>r.rate===50&&r.side!=='a1'));
  assert.ok(runs.filter(r=>r.viewport===10).every(r=>r.rate===16&&r.side!=='a0'));
  assert.equal(new Set(['p2-a0','p2-a1','p2-b'].map(s=>stageSettings(s).project)).size,3);
  assert.equal(stageSettings('p2-a0').image,'loco-perf-p0:local');assert.equal(stageSettings('p2-a1').image,'loco-perf-e15-b:local');
});
test('write sampling retains five single-VU pairs and one exploratory pair per concurrency',()=>{
  assert.equal(writeSchedule().length,18);
  assert.equal(writeSchedule().filter(r=>r.concurrency===1).length,10);
  assert.equal(writeSchedule().filter(r=>r.exploratory).length,8);
  for(const c of [1,2,4,8,16])assert.equal(writeSpec('example',c).measuredCycles,30*c);
  assert.throws(()=>writeSpec('example',3));assert.throws(()=>writeSpec('../old',1));
});
const readings=()=>readSchedule().map(r=>({run:r.name+'-1',http:{'p(95)':r.viewport===1?(r.side==='b'?10.9:10):(r.side==='b'?79:100),'p(99)':r.viewport===1?(r.side==='b'?10.9:10):110},checks:{fails:0},dropped:0,status:{200:100},collectorErrors:[]}));
test('both fixed gates pass only with clean complete five-pair evidence',()=>{
  const runs=readings();assert.ok(Object.values(evaluate(runs)).every(r=>r.passed));
  assert.throws(()=>evaluate(runs.slice(1)),/Missing/);
  runs[0].dropped=1;assert.equal(evaluate(runs)[1].passed,false);
});
test('1% p99 regression, 10% improvement miss and large variance each block adoption',()=>{
  let runs=readings();for(const r of runs.filter(r=>r.run.includes('v1-b')))r.http['p(99)']=11.1;
  assert.equal(evaluate(runs)[1].passed,false);
  runs=readings();for(const r of runs.filter(r=>r.run.includes('v10-b')))r.http['p(95)']=81;
  assert.equal(evaluate(runs)[10].passed,false);
  runs=readings();runs.find(r=>r.run==='pair-1-v10-a1-1').http['p(95)']=120;
  assert.equal(evaluate(runs)[10].stable,false);
});
