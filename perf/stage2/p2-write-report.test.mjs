import test from 'node:test';
import assert from 'node:assert/strict';
import {writeRetestSchedule} from './p2-protocol.mjs';
import {evaluateWriteRetest} from './p2-write-report.mjs';

const fixture=(cycles=30)=>writeRetestSchedule().flatMap(item=>[false,true].map(warmup=>({
  run:item.name+(warmup?'-warmup':''),writeCycles:warmup?10:cycles,writeConcurrency:1,
  validity:{valid:true},checks:{fails:0},dropped:0,collectorErrors:[],dbRestored:true,execution:{exitCode:0,hostObservationBracketsWorkload:true},
  status:{200:warmup?20:cycles*2,201:warmup?10:cycles},http:{med:10,'p(95)':20,'p(99)':25},
  endpoints:Object.fromEntries(['place-create','place-update','place-delete'].map(k=>[k,{n:warmup?10:cycles,p50:10,p95:20,p99:25}]))
})));

test('E-24 accepts paired improvement despite range warnings without changing legacy verdict',()=>{
  const rows=fixture(300);
  for(let pair=1;pair<=5;pair++)for(const side of ['a1','b']) {
    const r=rows.find(r=>r.run===`write-${pair}-c1-${side}`),value=(50-pair*5)*(side==='b'?.8:1);
    r.http['p(95)']=value;for(const v of Object.values(r.endpoints))v.p95=value;
  }
  const result=evaluateWriteRetest(rows,300,'e24-b');assert.equal(result.passed,true);assert.equal(result.repeatabilityWarnings.length,8);
  assert.equal(evaluateWriteRetest(rows,300).passed,false);
  assert.equal(evaluateWriteRetest([...rows].reverse(),300,'e24-b').passed,true);
});
test('E-24 checks each pair: one regression cannot hide behind a passing median',()=>{
  const rows=fixture(300);rows.find(r=>r.run==='write-5-c1-b').endpoints['place-create'].p95=22.001;
  const result=evaluateWriteRetest(rows,300,'e24-b');assert.equal(result.passed,false);
  assert.equal(result.comparisons['place-create'].metrics.p95.changePercent,0);
  assert.ok(result.comparisons['place-create'].pairedP95Ratios[4]>1.1);
});
test('E-24 includes the exact 1.10 boundary and independently checks mixed p95',()=>{
  const rows=fixture(300);for(const r of rows.filter(r=>r.run.endsWith('-b'))){r.http['p(95)']=22;for(const v of Object.values(r.endpoints))v.p95=22;}
  assert.equal(evaluateWriteRetest(rows,300,'e24-b').passed,true);
  rows.find(r=>r.run==='write-2-c1-b').http['p(95)']=22.001;
  assert.equal(evaluateWriteRetest(rows,300,'e24-b').passed,false);
});
test('E-24 retains interference, errors, dropped work and DB restoration as mandatory validity',()=>{
  for(const mutate of [r=>r.validity={valid:false,reasons:['sustained-background-cpu']},r=>r.status[500]=1,r=>r.checks.fails=1,r=>r.dropped=1,r=>r.dbRestored=false]){
    const rows=fixture(300);mutate(rows[1]);const result=evaluateWriteRetest(rows,300,'e24-b');
    assert.equal(result.passed,false);assert.equal(result.valid,false);assert.deepEqual(result.invalidScenarios,['write-1-c1-a1']);
  }
  assert.throws(()=>evaluateWriteRetest(fixture(),30,'e24-b'));
});

test('E-23 requires 300 samples per endpoint and keeps the same variance limit',()=>{
  const rows=fixture(300);assert.equal(evaluateWriteRetest(rows,300).totals.requests,9300);
  assert.equal(evaluateWriteRetest(rows,300).passed,true);
  assert.throws(()=>evaluateWriteRetest(fixture(),300));
  rows.find(r=>r.run==='write-3-c1-b').endpoints['place-create'].p95=23;
  assert.equal(evaluateWriteRetest(rows,300).passed,false);
});

test('complete stable five-pair writes pass; missing or duplicated intervals never do',()=>{
  const rows=fixture();assert.equal(evaluateWriteRetest(rows).passed,true);
  assert.equal(evaluateWriteRetest(rows).totals.requests,1200);
  assert.throws(()=>evaluateWriteRetest(rows.slice(1)));
  rows[1]=rows[0];assert.throws(()=>evaluateWriteRetest(rows));
});
test('a fast result cannot hide large endpoint variance or invalid warmup',()=>{
  const rows=fixture();rows.find(r=>r.run==='write-2-c1-b').endpoints['place-create'].p95=15;
  const result=evaluateWriteRetest(rows);assert.equal(result.comparisons['place-create'].withinRegression,true);
  assert.equal(result.comparisons['place-create'].stable,false);assert.equal(result.passed,false);
  const invalid=fixture();invalid[1].validity.valid=false;assert.equal(evaluateWriteRetest(invalid).passed,false);
});
test('stable slowdown, HTTP failure and lost observation cannot pass',()=>{
  const slow=fixture();for(const r of slow.filter(r=>r.run.endsWith('-b')))r.endpoints['place-delete'].p95=23;
  assert.equal(evaluateWriteRetest(slow).passed,false);
  const failed=fixture();failed[0].status[500]=1;assert.equal(evaluateWriteRetest(failed).passed,false);
  const unseen=fixture();unseen[0].execution.hostObservationBracketsWorkload=false;assert.equal(evaluateWriteRetest(unseen).passed,false);
});
