import assert from 'node:assert/strict';

export const screeningProtocol=Object.freeze({decision:'E-32',viewport:10,rate:16,warmupSeconds:120,measuredSeconds:300,planRepeats:3,httpRepeats:1,maxRatio:.8,expectedMinutes:[24,31],formalAutomaticallyStarts:false});
export const p2Jar='639c3956667040fde1072d922d614c4b8a96c7c78c554957d9042c89175cef5e';
export const screeningPlans=()=>Array.from({length:3},(_,i)=>['a','b'].map(side=>({side,name:'plan-'+(i+1)}))).flat();
export function evaluateBufferScreen(a,b) {
  for(const values of [a,b]) {
    assert.equal(values.length,3,'E-33 requires all three new plans per side');
    assert.ok(values.every(x=>Number.isFinite(x)&&x>0));
  }
  const median=v=>[...v].sort((x,y)=>x-y)[1],ratio=median(b)/median(a);
  return {aMedian:median(a),bMedian:median(b),ratio,loadEligible:ratio<=.8,adoption:false,
    outcome:ratio>.8?'CLOSED: 이 데이터·경로에서 공간 인덱스 이점 작음, 기존 B-tree로 충분':'WAIT: confirm app closure before p95 screening'};
}
// A preflight failure has not acquired the seven-container lease.
export const backgroundRecoveryRequired=manifest=>manifest?.history?.some(entry=>entry.phase==='before')===true;
export function recoverExitedOwner({ownerAlive,recover,complete}) {
  if(ownerAlive())return false;
  recover();
  complete();
  return true;
}
export function evaluateScreening({a,b}) {
  for(const side of [a,b]) {
    assert.equal(side.buffers.length,3,'Keep all three prescribed plans');
    assert.ok(side.buffers.every(x=>Number.isFinite(x)&&x>0));
    assert.ok(Number.isFinite(side.p95)&&side.p95>0);
  }
  const median=v=>[...v].sort((a,b)=>a-b)[1];
  const valid=a.valid===true&&b.valid===true;
  const ratios={buffers:median(b.buffers)/median(a.buffers),p95:b.p95/a.p95};
  return {valid,ratios,proceed:valid&&ratios.buffers<=.8&&ratios.p95<=.8,
    outcome:!valid?'INVALID: stop; reconsider dedicated VM':ratios.buffers<=.8&&ratios.p95<=.8?'SCREEN PASSED: eligible for formal measurement, not adoption':'CLOSED: 이 데이터·경로에서 공간 인덱스 이점 작음, 기존 B-tree로 충분',adoption:false};
}

// Shared by the detached runner and failure-path tests; recovery precedes completion.
export async function screeningLifecycle({work,recover,complete}) {
  let failure,result;
  try {result=await work();}catch(e){failure=e;}
  try {await recover();}catch(e){failure??=e;result=undefined;}
  await complete({result,error:failure?.message,ok:!failure});
  if(failure)throw failure;
  return result;
}
