import test from 'node:test';
import assert from 'node:assert/strict';
import {assessInterference} from './interference.mjs';
const samples=()=>Array.from({length:15},(_,i)=>({at:new Date(i*1000).toISOString(),processCount:100,ac:1,overlay:'ded574b5-45a0-4f42-8737-46345c09c238',nonWorkloadCpuCores:0,availablePhysicalBytes:8*1024**3}));
test('brief setup spikes are retained but sustained unrelated CPU invalidates the run',()=>{
  const rows=samples();rows[3].nonWorkloadCpuCores=8;assert.equal(assessInterference(rows).valid,true);
  rows.forEach(r=>r.nonWorkloadCpuCores=3);assert.deepEqual(assessInterference(rows).reasons,['sustained-background-cpu']);
});
test('power, memory, background containers and missing observation cannot silently pass',()=>{
  let rows=samples();rows[5].ac=0;assert.equal(assessInterference(rows).valid,false);
  rows=samples();rows.forEach(r=>r.availablePhysicalBytes=1024);assert.ok(assessInterference(rows).reasons.includes('host-memory-pressure'));
  assert.equal(assessInterference(samples(),[{stats:[{name:'background-123'}]}]).valid,false);
  assert.equal(assessInterference([]).valid,false);
  rows=samples();rows[4].processCount=3;assert.equal(assessInterference(rows).valid,false);
});
test('a collector gap is an invalid observation, not a clean interval',()=>{
  const rows=samples().filter((_,i)=>i<2||i>12);assert.ok(assessInterference(rows).reasons.includes('host-collector-gap'));
});
