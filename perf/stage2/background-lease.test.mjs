import test from 'node:test';
import assert from 'node:assert/strict';
import {withPausedBackground} from './background-lease.mjs';
const ids=Array.from({length:7},(_,i)=>'opaque-'+i);
function fixture(failPause=-1) {
  const states=new Map(ids.map(id=>[id,{Running:true,Paused:false,Status:'running'}])),calls=[],records=[];
  const docker=(action,id)=>{calls.push([action,id]);const s=states.get(id);if(action==='inspect')return {...s};if(action==='pause'){if(id===ids[failPause])throw Error('injected pause failure');s.Paused=true;s.Status='paused';}else if(action==='unpause'){s.Paused=false;s.Status='running';}else throw Error('Forbidden action');};
  return {states,calls,records,docker,record:(phase,data)=>records.push({phase,...data})};
}
for(const failure of [false,true])test('restores all seven after '+(failure?'measurement failure':'success'),async()=>{
  const f=fixture();const task=withPausedBackground(ids,f.docker,f.record,async()=>{assert.ok([...f.states.values()].every(s=>s.Paused));if(failure)throw Error('measurement failed');return 42;});
  if(failure)await assert.rejects(task,/measurement failed/);else assert.equal(await task,42);
  assert.ok([...f.states.values()].every(s=>s.Status==='running'&&!s.Paused));assert.equal(f.records.at(-1).restored,true);
  assert.ok(f.records.flatMap(r=>r.states).every(s=>s.alias.startsWith('background-')&&!JSON.stringify(s).includes('opaque-')));
});
test('partial pause failure restores the already-paused subset without touching volumes or stopping services',async()=>{
  const f=fixture(3);await assert.rejects(withPausedBackground(ids,f.docker,f.record,async()=>assert.fail('must not measure')),/pause failure/);
  assert.equal(f.calls.filter(([action])=>action==='unpause').length,3);assert.equal(f.records.at(-1).restored,true);
});
