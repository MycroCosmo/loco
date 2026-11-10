import test from 'node:test';import assert from 'node:assert/strict';
import {evaluateScreening,screeningPlans,screeningLifecycle,backgroundRecoveryRequired} from './p3-protocol.mjs';
import {stageSettings} from './data.mjs';
import fs from 'node:fs';import os from 'node:os';import path from 'node:path';import {spawnSync} from 'node:child_process';

const side=(p95=100,buffers=[100,100,100])=>({p95,buffers,valid:true});
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
