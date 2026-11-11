import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {spawnSync,spawn} from 'node:child_process';
import {hash,stageSettings} from './data.mjs';

const output='apps/server-v3/build/perf-stage2-e15';
fs.mkdirSync(output,{recursive:true});
const command=(exe,args)=>{const r=spawnSync(exe,args,{encoding:'utf8',windowsHide:true});assert.equal(r.status,0,r.stderr);return r.stdout.trim();};
async function run(side,...args) {
  console.log(`${new Date().toISOString()} ${side} ${args.join(' ')}`);
  const child=spawn(process.execPath,['perf/stage2/run.mjs',...args],{env:{...process.env,PERF_STAGE:'e15-'+side.toLowerCase()},stdio:'inherit',windowsHide:true});
  const code=await new Promise((resolve,reject)=>{child.on('error',reject);child.on('exit',resolve);});assert.equal(code,0,`${side} ${args.join(' ')}`);
}
const schedule=[];
for(let pair=1;pair<=5;pair++)for(const [viewport,rate] of [[1,50],[10,16]])for(const side of pair%2?['A','B']:['B','A'])schedule.push({pair,viewport,rate,side,name:`pair-${pair}-v${viewport}-${side.toLowerCase()}`});
const startFile=path.join(output,'session.json');
assert.ok(!fs.existsSync(startFile),'A paired session cannot silently restart or overwrite evidence');
assert.ok(['closed-user-confirmed','not-closed-user-confirmed'].includes(process.env.PERF_HEAVY_APPS_STATUS),'Record the current user-confirmed heavy-app state before starting');
const session={startedAt:new Date().toISOString(),source:command('git',['rev-parse','HEAD']),dirty:command('git',['status','--porcelain']),
  userDeclaration:process.env.PERF_HEAVY_APPS_STATUS,
  protocol:'Each side has its own fresh M-skew database. Stop the inactive project, start active JVM from scratch; identical preflight then 120s target warmup + 300s measured. AB/BA alternate by pair. No favorable retry. P0 original image and default heap unchanged.',
  expectedLoadSeconds:20*(120+300),expectedTotalMinutes:[180,210],schedule,runs:[]};
assert.equal(session.dirty,'','Commit the measured source before starting');
fs.writeFileSync(startFile,JSON.stringify(session,null,2));
for(const item of schedule) {
  const other=item.side==='A'?'B':'A';
  await run(other,'stop');
  await run(item.side,'up');
  const state=JSON.parse(fs.readFileSync(path.join(stageSettings('e15-'+item.side.toLowerCase()).output,'environment-initial.json')));
  if(item.side==='A')assert.equal(state.jarSha256,'c1a5293f1dc59a6ee0e648548e92695857a0357c57d10e45f06704a96952158f','Must use the original c7320e6 measurement artifact');
  else assert.equal(state.jarSha256,hash(fs.readFileSync('apps/server-v3/build/libs/loco-server-v3-0.0.1-SNAPSHOT-perf-local-only.jar')));
  const start=Date.now();await run(item.side,'measure',item.name,String(item.rate),String(item.viewport),'hot80','1');
  session.runs.push({...item,startedAt:new Date(start).toISOString(),elapsedSeconds:(Date.now()-start)/1000,jarSha256:state.jarSha256,environment:state.environment});
  fs.writeFileSync(startFile,JSON.stringify(session,null,2));
  await run(item.side,'stop');
}
session.finishedAt=new Date().toISOString();fs.writeFileSync(startFile,JSON.stringify(session,null,2));
console.log('20 paired measurements completed; evaluate historical and paired regression separately.');
