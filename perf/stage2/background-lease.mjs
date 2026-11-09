import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';

export const alias=id=>'background-'+createHash('sha256').update(id).digest('hex').slice(0,8);
export function stateOf(ids,docker) {
  return ids.map(id=>{const s=docker('inspect',id);return {alias:alias(id),running:s.Running,paused:s.Paused,status:s.Status};});
}
export function restoreBackground(ids,docker,record) {
  const failures=[];
  for(const id of ids)try {if(docker('inspect',id).Paused)docker('unpause',id);}catch(e){failures.push(alias(id)+': '+e.message);}
  const states=stateOf(ids,docker),restored=failures.length===0&&states.every(s=>s.running&&!s.paused&&s.status==='running');
  record('restored',{states,restored,failures});
  assert.ok(restored,'Background recovery incomplete; inspect manifest and recover before ending');
}
export async function withPausedBackground(ids,docker,record,work) {
  assert.equal(new Set(ids).size,7,'Exactly the seven approved containers, no duplicates');
  const before=stateOf(ids,docker);
  assert.ok(before.every(s=>s.running&&!s.paused&&s.status==='running'),'Approved containers must initially be running and unpaused');
  record('before',{states:before});
  try {
    for(const id of ids)docker('pause',id);
    const paused=stateOf(ids,docker);record('paused',{states:paused});
    assert.ok(paused.every(s=>s.running&&s.paused),'Every approved container must be paused');
    return await work();
  } finally {restoreBackground(ids,docker,record);}
}
