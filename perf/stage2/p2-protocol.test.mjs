import test from 'node:test';
import assert from 'node:assert/strict';
import {readSchedule,writeSchedule,writeRetestSchedule,writeSpec,evaluate,fullViewportSchedule} from './p2-protocol.mjs';
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
test('write retest preserves all five AB/BA pairs and 10+30 cycles in isolated outputs',()=>{
  const runs=writeRetestSchedule();
  assert.equal(runs.length,10);
  assert.deepEqual(runs.map(r=>r.side),['a1','b','b','a1','a1','b','b','a1','a1','b']);
  assert.deepEqual(runs.map(r=>r.pair),[1,1,2,2,3,3,4,4,5,5]);
  for(const run of runs)assert.deepEqual(writeSpec(run.name,run.concurrency),{name:run.name,concurrency:1,warmupCycles:10,measuredCycles:30});
  for(const side of ['a1','b']) {
    const previous=stageSettings('p2-'+side,''),next=stageSettings('p2-'+side,'write-retest1');
    assert.equal(next.image,previous.image);assert.equal(next.stage,previous.stage);
    assert.notEqual(next.project,previous.project);assert.notEqual(next.output,previous.output);
  }
  assert.throws(()=>stageSettings('p2-b','../overwrite'));
  assert.throws(()=>stageSettings('p0','write-retest1'));
});
test('E-23 increases measured single-VU samples only, preserving both earlier sessions',()=>{
  assert.deepEqual(writeSpec('e23',1,'write-e23'),{name:'e23',concurrency:1,warmupCycles:10,measuredCycles:300});
  assert.equal(writeSpec('old',1).measuredCycles,30);
  assert.equal(writeSpec('old',1,'write-retest1').measuredCycles,30);
  assert.throws(()=>writeSpec('e23',2,'write-e23'));
  const settings=['','write-retest1','write-e23'].map(s=>stageSettings('p2-b',s));
  assert.equal(new Set(settings.map(s=>s.project)).size,3);assert.equal(new Set(settings.map(s=>s.output)).size,3);
  assert.equal(new Set(settings.map(s=>s.image)).size,1);
});
test('full viewport follows the approved 16 req/s three-run contract, not the 1% load',()=>{
  assert.equal(fullViewportSchedule().length,3);
  assert.ok(fullViewportSchedule().every(r=>r.rate===16&&r.viewport===100));
});
const readings=()=>readSchedule().map(r=>({run:r.name+'-1',validity:{valid:true},warmupValidity:{valid:true},http:{'p(95)':r.viewport===1?(r.side==='b'?10.9:10):(r.side==='b'?79:100),'p(99)':r.viewport===1?(r.side==='b'?10.9:10):110},checks:{fails:0},dropped:0,status:{200:100},collectorErrors:[]}));
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

test('detected interference blocks adoption without discarding the prescribed sample',()=>{const runs=readings();runs[0].validity={valid:false};assert.equal(evaluate(runs)[1].valid,false);assert.equal(evaluate(runs)[1].stats['p(95)'].a.length,5);});
