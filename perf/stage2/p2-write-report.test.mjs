import test from 'node:test';
import assert from 'node:assert/strict';
import {writeRetestSchedule} from './p2-protocol.mjs';
import {evaluateWriteRetest} from './p2-write-report.mjs';

const fixture=(cycles=30)=>writeRetestSchedule().flatMap(item=>[false,true].map(warmup=>({
  run:item.name+(warmup?'-warmup':''),writeCycles:warmup?10:cycles,writeConcurrency:1,
  validity:{valid:true},checks:{fails:0},dropped:0,collectorErrors:[],execution:{exitCode:0,hostObservationBracketsWorkload:true},
  status:{200:warmup?20:cycles*2,201:warmup?10:cycles},http:{med:10,'p(95)':20,'p(99)':25},
  endpoints:Object.fromEntries(['place-create','place-update','place-delete'].map(k=>[k,{n:warmup?10:cycles,p50:10,p95:20,p99:25}]))
})));

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
