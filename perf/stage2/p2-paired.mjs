import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {spawn,spawnSync} from 'node:child_process';
import {stageSettings,hash} from './data.mjs';
import {protocol,originalJars,readSchedule,writeSchedule,writeRetestSchedule,writeSessions,writeSpec,writeE24Policy,evaluate} from './p2-protocol.mjs';

const mode=process.argv[2];assert.ok(['reads','writes',...Object.keys(writeSessions),'evaluate'].includes(mode),'Unknown paired mode');
const writeSession=writeSessions[mode];
if(writeSession)process.env.PERF_SESSION=writeSession;
const output='apps/server-v3/build/perf-stage2-p2'+(writeSession?'-'+writeSession:'');fs.mkdirSync(output,{recursive:true});
const cmd=(exe,args)=>{const r=spawnSync(exe,args,{encoding:'utf8',windowsHide:true});assert.equal(r.status,0,r.stderr);return r.stdout.trim();};
async function run(side,script,...args) {
  console.log(`${new Date().toISOString()} ${side} ${args.join(' ')}`);
  const p=spawn(process.execPath,[`perf/stage2/${script}.mjs`,...args],{env:{...process.env,PERF_STAGE:'p2-'+side},stdio:'inherit',windowsHide:true});
  assert.equal(await new Promise((resolve,reject)=>{p.on('error',reject);p.on('exit',resolve);}),0,'Run failed; retain artifacts, no automatic retry');
}
if(mode==='evaluate') {
  const readings=[];
  for(const side of ['a0','a1','b']) {await run(side,'summarize');readings.push(...JSON.parse(fs.readFileSync(stageSettings('p2-'+side).output+'/summary.json')));}
  const result=evaluate(readings);fs.writeFileSync(path.join(output,'read-evaluation.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
  if(fs.existsSync(path.join(output,'read-handoff.json'))) {
    console.log('Read phase complete. Controlled handoff restores backgrounds before the corrected, not-yet-run full viewport phase.');
    process.exitCode=78;
  }
} else {
  const file=path.join(output,mode+'-session.json');assert.ok(!fs.existsSync(file),'Do not overwrite or silently resume a session');
  assert.equal(process.env.PERF_HEAVY_APPS_STATUS,'closed-user-confirmed','Obtain a fresh user environment confirmation after tests and push');
  assert.equal(cmd('git',['status','--porcelain']),'','Commit measured code first');
  const bJar=hash(fs.readFileSync('apps/server-v3/build/libs/loco-server-v3-0.0.1-SNAPSHOT-perf-local-only.jar'));
  const schedule=mode==='reads'?readSchedule():writeSession?writeRetestSchedule():writeSchedule();
  const session={startedAt:new Date().toISOString(),source:cmd('git',['rev-parse','HEAD']),implementation:'91d653a2b69a5f403f9969bd754f3c46eac76735',mode,protocol,userDeclaration:process.env.PERF_HEAVY_APPS_STATUS,confirmedConditions:'AC connected, Windows best performance, sleep disabled, Windows Update paused, no heavy applications until measurement ends',expectedJars:{...originalJars,b:bJar},schedule,runs:[]};
  fs.writeFileSync(file,JSON.stringify(session,null,2));
  if(writeSession) {
    session.confirmedConditions+=', including browsers closed and unused';
    session.preservedOriginalSession='apps/server-v3/build/perf-stage2-p2/writes-session.json';
    session.writeSpec=writeSpec('protocol',1,writeSession);
    if(mode==='write-e23')session.preservedRetestSession='apps/server-v3/build/perf-stage2-p2-write-retest1/write-retest-session.json';
    if(mode==='write-e24') {
      session.adoptionPolicy=writeE24Policy;
      session.preservedEarlierSessions=['apps/server-v3/build/perf-stage2-p2-write-retest1/write-retest-session.json','apps/server-v3/build/perf-stage2-p2-write-e23/write-e23-session.json'];
    }
    assert.equal(bJar,'639c3956667040fde1072d922d614c4b8a96c7c78c554957d9042c89175cef5e','Retest the unchanged P2 JAR');
    fs.writeFileSync(file,JSON.stringify(session,null,2));
  }
  for(const item of schedule) {
    for(const other of ['a0','a1','b'].filter(s=>s!==item.side))await run(other,'run','stop');
    await run(item.side,'run','up');
    const dir=stageSettings('p2-'+item.side).output,state=JSON.parse(fs.readFileSync(dir+'/environment-initial.json'));
    assert.equal(state.jarSha256,session.expectedJars[item.side],'Never rebuild the historical A0/A1 artifacts');
    assert.equal(state.runtime.maxHeapBytes,protocol.heapBytes);assert.equal(state.runtime.hikariMaximum,10);
    assert.deepEqual(state.runtime.gc,['Copy','MarkSweepCompact']);assert.equal(state.runtime.work_mem,'4MB');
    assert.equal(state.runtime.shared_buffers,'1GB');assert.equal(state.environment.background.length,0,'Stop unrelated containers before timing');
    if(!fs.existsSync(dir+'/dataset.json')) {await run(item.side,'run','seed','M','skew');await run(item.side,'run','verify-seed');}
    const data=JSON.parse(fs.readFileSync(dir+'/dataset.json'));assert.equal(data.size,'M');assert.equal(data.distribution,'skew');
    session.sourcePlaces??=data.data['public.places'];assert.deepEqual(data.data['public.places'],session.sourcePlaces);
    const start=Date.now();
    if(mode==='reads')await run(item.side,'run','measure',item.name,String(item.rate),String(item.viewport),'hot80','1');
    else await run(item.side,'run','p2-write',item.name,String(item.concurrency));
    await run(item.side,'run','snapshot',item.name+'-after.json');
    const after=JSON.parse(fs.readFileSync(dir+'/'+item.name+'-after.json'));
    assert.ok(after.containers.every(c=>!c.oomKilled),'OOM is a failed run, not an excluded sample');
    session.runs.push({...item,elapsedSeconds:(Date.now()-start)/1000,jar:state.jarSha256,environment:state.environment});
    fs.writeFileSync(file,JSON.stringify(session,null,2));
    await run(item.side,'run','stop');
  }
  session.finishedAt=new Date().toISOString();fs.writeFileSync(file,JSON.stringify(session,null,2));
}
