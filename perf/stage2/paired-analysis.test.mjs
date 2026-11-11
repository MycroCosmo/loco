import {test} from 'node:test';
import assert from 'node:assert/strict';
import {comparePairs} from './paired-analysis.mjs';
import {pairedThresholdFailure} from './data.mjs';
const rows=n=>Array.from({length:5},()=>({http:{'p(95)':n,'p(99)':n*2},checks:{fails:0},dropped:0,status:{200:1000},collectorErrors:[]}));
test('either tail regression over ten percent fails, without averaging percentiles',()=>{
  const a=rows(10),b=rows(10.9);assert.ok(comparePairs(a,b).withinTenPercent);
  b.forEach(r=>r.http['p(99)']=22.1);assert.equal(comparePairs(a,b).withinTenPercent,false);
});
test('missing repetitions or failed/dropped/mismatched work cannot pass the gate',()=>{
  assert.throws(()=>comparePairs(rows(10).slice(1),rows(10)));
  for(const mutate of [r=>r.dropped=1,r=>r.checks.fails=1,r=>r.status[500]=1,r=>r.collectorErrors.push('failed')]) {
    const b=rows(1);mutate(b[0]);assert.equal(comparePairs(rows(10),b).valid,false);
  }
});
test('a successful HTTP response with wrong business values stops the paired session',()=>{
  const summary={metrics:{business_failure:{values:{count:2}},http_req_failed:{values:{passes:2,fails:1000}}}};
  assert.ok(pairedThresholdFailure(99,summary));
  summary.metrics.business_failure.values.count=3;
  assert.equal(pairedThresholdFailure(99,summary),false);
  assert.equal(pairedThresholdFailure(1,summary),false);
});
