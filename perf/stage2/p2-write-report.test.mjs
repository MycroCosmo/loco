import test from 'node:test';
import assert from 'node:assert/strict';
import {writeRetestSchedule} from './p2-protocol.mjs';
import {evaluateWriteRetest} from './p2-write-report.mjs';

const fixture=()=>writeRetestSchedule().flatMap(item=>[false,true].map(warmup=>({
  run:item.name+(warmup?'-warmup':''),writeCycles:warmup?10:30,writeConcurrency:1,
  validity:{valid:true},checks:{fails:0},dropped:0,collectorErrors:[],execution:{exitCode:0,hostObservationBracketsWorkload:true},
  status:{200:warmup?20:60,201:warmup?10:30},http:{med:10,'p(95)':20,'p(99)':25},
  endpoints:Object.fromEntries(['place-create','place-update','place-delete'].map(k=>[k,{n:warmup?10:30,p50:10,p95:20,p99:25}]))
})));

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
