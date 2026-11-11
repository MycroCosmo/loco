import test from 'node:test';import assert from 'node:assert/strict';
import {evaluateBufferScreen,evaluateScreening,screeningPlans,screeningLifecycle,backgroundRecoveryRequired,recoverExitedOwner} from './p3-protocol.mjs';
import {restoreBackground} from './background-lease.mjs';
import {stageSettings,markerCellCoordinate} from './data.mjs';
import fs from 'node:fs';import os from 'node:os';import path from 'node:path';import {spawn,spawnSync} from 'node:child_process';

const side=(p95=100,buffers=[100,100,100])=>({p95,buffers,valid:true});
test('E-33 uses three fresh buffer totals and only qualifies load at or below 0.80',()=>{
  assert.equal(evaluateBufferScreen([100,99,101],[80,79,81]).loadEligible,true);
  const rejected=evaluateBufferScreen([100,100,100],[81,82,80]);
  assert.equal(rejected.loadEligible,false);assert.equal(rejected.adoption,false);
  assert.throws(()=>evaluateBufferScreen([100,100],[80,80,80]));
  assert.throws(()=>evaluateBufferScreen([100,100,100],[80,NaN,80]));
});
test('readiness and retest2 use four distinct explicit subnets without changing old projects',()=>{
  const networks=new Set(),projects=new Set();
  for(const session of ['readiness','screen-retest2'])for(const side of ['p3-a','p3-b']){
    const next=stageSettings(side,session),old=stageSettings(side,'');
    assert.match(next.networkSubnet,/^10\.253\.2[0-3]\.0\/24$/);
    networks.add(next.networkSubnet);projects.add(next.project);
    assert.equal(next.image,old.image);assert.equal(old.networkSubnet,null);
  }
  assert.equal(networks.size,4);assert.equal(projects.size,4);
  assert.throws(()=>stageSettings('p2-b','readiness'));
});
test('authorized P3 retest keeps old output and volumes separate with identical artifacts',()=>{
  for(const side of ['p3-a','p3-b']) {
    const old=stageSettings(side,''),next=stageSettings(side,'screen-retest1');
    assert.notEqual(next.project,old.project);assert.notEqual(next.output,old.output);
    for(const field of ['image','stage','markerSourceRevision'])assert.equal(next[field],old[field]);
    assert.throws(()=>stageSettings(side,'write-e24'));
  }
  assert.throws(()=>stageSettings('p2-b','screen-retest1'));
  assert.throws(()=>stageSettings('p3-b','../escape'));
});
test('killed owner triggers seven-container recovery before watchdog completion',async()=>{
  const child=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore',windowsHide:true});
  await new Promise((resolve,reject)=>{child.once('spawn',resolve);child.once('error',reject);});
  const ids=Array.from({length:7},(_,i)=>'test-'+i),paused=new Set(ids),events=[];
  const docker=(action,id)=>{if(action==='inspect')return {Running:true,Paused:paused.has(id),Status:paused.has(id)?'paused':'running'};assert.equal(action,'unpause');paused.delete(id);};
  const callbacks={ownerAlive:()=>{try{process.kill(child.pid,0);return true;}catch{return false;}},recover:()=>restoreBackground(ids,docker,(phase,data)=>{assert.equal(data.restored,true);events.push(phase);}),complete:()=>{assert.equal(paused.size,0);events.push('complete');}};
  try {assert.equal(recoverExitedOwner(callbacks),false);}
  finally {const done=new Promise(resolve=>child.once('exit',resolve));child.kill();await done;}
  assert.equal(recoverExitedOwner(callbacks),true);assert.deepEqual(events,['restored','complete']);
});
test('watchdog does not write completion after a failed recovery',()=>{
  assert.throws(()=>recoverExitedOwner({ownerAlive:()=>false,recover:()=>{throw Error('recovery failed');},complete:()=>assert.fail('premature completion')}),/recovery failed/);
});
test('P3 A and B both preserve PG cell coordinates without reintroducing legacy rounding',()=>{
  const coordinate=37.123456789012344;
  assert.notEqual(coordinate,Number(coordinate.toPrecision(16)));
  for(const side of ['p3-a','p3-b'])assert.equal(markerCellCoordinate(coordinate,stageSettings(side).stage),coordinate);
  assert.equal(markerCellCoordinate(coordinate,'p1'),Number(coordinate.toPrecision(16)));
});
test('preflight failures leave unrelated containers untouched; partial pause still requires recovery',()=>{
  assert.equal(backgroundRecoveryRequired(undefined),false);
  assert.equal(backgroundRecoveryRequired({history:[{phase:'recovery-failed'}]}),false);
  assert.equal(backgroundRecoveryRequired({history:[{phase:'before'}]}),true);
});
test('screen requires both buffer median and p95, including exact 0.80 boundary',()=>{
  assert.equal(evaluateScreening({a:side(),b:side(80,[80,79,81])}).proceed,true);
  for(const b of [side(80.0001,[70,70,70]),side(70,[81,81,79])])assert.equal(evaluateScreening({a:side(),b}).proceed,false);
});
test('invalid screen cannot become a negative or positive performance conclusion',()=>{
  const result=evaluateScreening({a:side(),b:{...side(1,[1,1,1]),valid:false}});
  assert.equal(result.proceed,false);assert.match(result.outcome,/INVALID/);assert.equal(result.adoption,false);
});
test('three fixed plans cannot be replaced by a favorable subset',()=>{
  assert.throws(()=>evaluateScreening({a:side(),b:side(1,[1,1])}));
  assert.deepEqual(screeningPlans().map(r=>r.side+r.name),['aplan-1','bplan-1','aplan-2','bplan-2','aplan-3','bplan-3']);
});
test('P3 A retains original P2 image and separate projects and historical SQL',()=>{
  const a=stageSettings('p3-a'),b=stageSettings('p3-b'),p2=stageSettings('p2-b');
  assert.equal(a.image,p2.image);assert.notEqual(a.project,p2.project);assert.notEqual(b.project,a.project);
  assert.ok(a.markerSourceRevision);assert.equal(b.markerSourceRevision,null);
});
for(const fail of [false,true])test('completion follows recovery on '+(fail?'failure':'success'),async()=>{
  const events=[];const run=()=>screeningLifecycle({work:async()=>{events.push('work');if(fail)throw Error('load invalid');return 1;},recover:async()=>{events.push('restore');},complete:async r=>{events.push('complete');assert.equal(r.ok,!fail);}});
  if(fail)await assert.rejects(run,/load invalid/);else await run();assert.deepEqual(events,['work','restore','complete']);
});
test('failed restoration never reports successful completion',async()=>{
  await assert.rejects(()=>screeningLifecycle({work:async()=>1,recover:async()=>{throw Error('unpause failed');},complete:async r=>{assert.equal(r.ok,false);assert.equal(r.result,undefined);}}),/unpause failed/);
});
test('detached completion writer survives its launcher exiting',async()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'loco-p3-detach-')),file=path.join(dir,'completed.json');
  const child=path.join(dir,'child.cjs'),parent=path.join(dir,'parent.cjs');
  fs.writeFileSync(child,"setTimeout(()=>require('node:fs').writeFileSync(process.argv[2],JSON.stringify({done:true})),200);");
  fs.writeFileSync(parent,"const {spawn}=require('node:child_process');const c=spawn(process.execPath,[process.argv[2],process.argv[3]],{detached:true,stdio:'ignore',windowsHide:true});c.unref();");
  const r=spawnSync(process.execPath,[parent,child,file],{encoding:'utf8',windowsHide:true});assert.equal(r.status,0);
  for(let i=0;!fs.existsSync(file)&&i<100;i++)await new Promise(r=>setTimeout(r,20));
  assert.deepEqual(JSON.parse(fs.readFileSync(file)),{done:true});
  // Delete only these explicitly created test files, never recurse through computed paths.
  for(const p of [file,child,parent])fs.unlinkSync(p);fs.rmdirSync(dir);
});
