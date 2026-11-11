import fs from 'node:fs';import path from 'node:path';import assert from 'node:assert/strict';
import {spawn,spawnSync} from 'node:child_process';import {fileURLToPath} from 'node:url';
import {stageSettings,hash} from './data.mjs';
import {withPausedBackground,restoreBackground} from './background-lease.mjs';
import {screeningProtocol,p2Jar,screeningPlans,evaluateScreening,screeningLifecycle,backgroundRecoveryRequired,recoverExitedOwner} from './p3-protocol.mjs';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');process.chdir(root);
const sessionName=process.env.PERF_SESSION||'';
stageSettings('p3-a',sessionName); // Validate before creating paths; old sessions remain immutable.
assert.notEqual(sessionName,'readiness','Readiness is not a screening session');
const base='apps/server-v3/build/perf-stage2-p3-screen'+(sessionName?'-'+sessionName:''),script=fileURLToPath(import.meta.url),mode=process.argv[2];
assert.ok(['check','launch','run','watchdog','restore'].includes(mode));
fs.mkdirSync(base,{recursive:true});
const read=p=>JSON.parse(fs.readFileSync(p));
const atomic=(name,data)=>{const p=base+'/'+name;fs.writeFileSync(p+'.tmp',JSON.stringify(data,null,2));fs.renameSync(p+'.tmp',p);};
const command=(exe,args)=>{const r=spawnSync(exe,args,{encoding:'utf8',windowsHide:true,maxBuffer:16*1024*1024,timeout:60000});assert.equal(r.status,0,(r.error?.message||r.stderr||'').slice(-1000));return r.stdout.trim();};
const ids=()=>read('apps/server-v3/build/perf-stage2-p2/private/approved-background.json');
const docker=(action,id)=>action==='inspect'?JSON.parse(command('docker',['inspect','--format','{{json .State}}',id])):command('docker',[action,id]);
function record(phase,data){const p=base+'/background-manifest.json',m=fs.existsSync(p)?read(p):{startedAt:new Date().toISOString(),history:[]};m.history.push({at:new Date().toISOString(),phase,...data});if(phase==='restored')m.restored=data.restored;atomic('background-manifest.json',m);}
// Compose interpolation needs the project's private credentials, even for stop.
function stopProjects(){for(const side of ['a','b']){const settings=stageSettings('p3-'+side),p=settings.output+'/private/secrets.json';if(!fs.existsSync(p))continue;const sec=read(p);const r=spawnSync('docker',['compose','-p',settings.project,'-f','perf/stage2/compose.yml',...(settings.networkSubnet?['-f','perf/stage2/compose.p3-network.yml']:[]),'stop'],{env:{...process.env,PERF_NETWORK_SUBNET:settings.networkSubnet||'',PERF_DB_PASSWORD:sec.db,PERF_JWT_SECRET:sec.jwt},encoding:'utf8',windowsHide:true});assert.equal(r.status,0,'Own workload stop failed');}}
const lease=()=>fs.existsSync(base+'/background-manifest.json')?read(base+'/background-manifest.json'):undefined;
const backgroundsSafe=()=>!backgroundRecoveryRequired(lease())||lease().restored===true;
function restore(){if(!backgroundRecoveryRequired(lease()))return;try{stopProjects();}finally{restoreBackground(ids(),docker,record);}}
function watch(owner){return setInterval(()=>{
  if(fs.existsSync(base+'/completed.json')&&read(base+'/completed.json').backgroundRestored===true)process.exit(0);
  try{if(recoverExitedOwner({ownerAlive:()=>{try{process.kill(owner,0);return true;}catch{return false;}},recover:restore,complete:()=>atomic('completed.json',{at:new Date().toISOString(),ok:false,error:'Detached owner exited; watchdog restored backgrounds',backgroundRestored:true,backgroundLeaseAcquired:backgroundRecoveryRequired(lease())})}))process.exit(0);}catch(e){record('recovery-failed',{error:e.message});}
},5000);}
if(mode==='restore'){restore();process.exit(0);}
if(mode==='watchdog'){watch(Number(process.argv[3]));}
else if(mode==='launch'||mode==='check'){
  if(mode==='launch') {
    assert.equal(process.env.PERF_HEAVY_APPS_STATUS,'closed-user-confirmed');
    assert.equal(process.env.PERF_APP_UPDATES_STATUS,'completed-or-paused-user-confirmed');
  }
  assert.equal(command('git',['status','--porcelain']),'','Commit all implementation and evidence first');
  assert.ok(!fs.existsSync(base+'/launch.json'),'Single session only; never silently retry');
  for(const side of ['a','b'])assert.ok(!fs.existsSync(stageSettings('p3-'+side).output),'Use untouched P3 output projects');
  const artifact=read('perf/stage2/p3-artifacts.json');assert.equal(artifact.aJar,p2Jar);assert.equal(artifact.bJar,hash(fs.readFileSync('apps/server-v3/build/libs/loco-server-v3-0.0.1-SNAPSHOT-perf-local-only.jar')));
  if(sessionName==='screen-retest2')for(const side of ['a','b']) {
    const ready=stageSettings('p3-'+side,'readiness'),check=read(ready.output+'/readiness-result.json');
    assert.equal(check.mapsChecked,1000);assert.equal(check.databaseUnchanged,true);assert.equal(check.k6ExitCode,0);
    assert.equal(read(ready.output+'/environment-initial.json').jarSha256,artifact[side+'Jar']);
    const settings=stageSettings('p3-'+side),net=JSON.parse(command('docker',['network','inspect',settings.project+'_default']))[0];
    assert.equal(net.Internal,true);assert.equal(net.IPAM.Config[0].Subnet,settings.networkSubnet);
    assert.equal(net.Labels['com.docker.compose.project'],settings.project);
    assert.equal(Object.keys(net.Containers).length,0,'Reserved measurement network must be unused');
    assert.equal(command('docker',['ps','-q','--filter','label=com.docker.compose.project='+ready.project]),'','Stop readiness workloads before screening');
  }
  if(mode==='check'){console.log('Launch prerequisites passed; no driver spawned, no lease acquired');process.exit(0);}
  atomic('launch.json',{at:new Date().toISOString(),source:command('git',['rev-parse','HEAD']),protocol:screeningProtocol,sessionName,artifact,countdownSeconds:90,userConfirmation:{heavyApps:process.env.PERF_HEAVY_APPS_STATUS,updates:process.env.PERF_APP_UPDATES_STATUS},note:'Close Codex/ChatGPT/Chrome completely during countdown; agent does not poll until completion.'});
  const log=fs.openSync(base+'/driver.log','wx');const child=spawn(process.execPath,[script,'run'],{detached:true,stdio:['ignore',log,log],windowsHide:true});child.unref();fs.closeSync(log);
  console.log(JSON.stringify({pid:child.pid,completion:base+'/completed.json',countdownSeconds:90,note:'Detached. Close apps; no Codex polling.'}));
}else if(mode==='run'){
  assert.ok(fs.existsSync(base+'/launch.json'));assert.ok(!fs.existsSync(base+'/session.json'));
  const launch=read(base+'/launch.json');assert.equal(command('git',['rev-parse','HEAD']),launch.source);
  const session={startedAt:new Date().toISOString(),source:launch.source,protocol:screeningProtocol,runs:[],plans:[]};atomic('session.json',session);
  const watchdog=spawn(process.execPath,[script,'watchdog',String(process.pid)],{detached:true,stdio:'ignore',windowsHide:true});watchdog.unref();
  async function run(side,...args){console.log(new Date().toISOString(),side,args.join(' '));const child=spawn(process.execPath,['perf/stage2/run.mjs',...args],{env:{...process.env,PERF_STAGE:'p3-'+side,PERF_SESSION:sessionName},stdio:'inherit',windowsHide:true});assert.equal(await new Promise((resolve,reject)=>{child.once('exit',resolve);child.once('error',reject);}),0,'Screen step failed; retain original, no retry');}
  const summarize=side=>{const r=spawnSync(process.execPath,['perf/stage2/summarize.mjs'],{env:{...process.env,PERF_STAGE:'p3-'+side,PERF_SESSION:sessionName},encoding:'utf8',windowsHide:true});assert.equal(r.status,0,r.stderr);};
  const sideResult=side=>{const dir=stageSettings('p3-'+side).output,rows=read(dir+'/summary.json');assert.equal(rows.length,2);const main=rows.find(x=>x.run==='screen-1');assert.ok(main);return {buffers:[1,2,3].map(n=>read(dir+'/plan-'+n+'.json').rootBuffers),p95:main.http['p(95)'],valid:read(dir+'/screen-1-db-check.json').unchanged&&rows.every(r=>r.validity?.valid===true&&r.execution.exitCode===0&&r.checks.fails===0&&r.dropped===0&&r.collectorErrors.length===0&&Object.keys(r.status).every(s=>s==='200'))};};
  try{await screeningLifecycle({
    work:async()=>{
      await new Promise(r=>setTimeout(r,launch.countdownSeconds*1000));
      assert.equal(command('git',['status','--porcelain']),'');
      const apps=JSON.parse(command('pwsh',['-NoProfile','-Command',"@((Get-Process | Where-Object { $_.ProcessName -match '^(ChatGPT|chrome|codex|codex-computer-use|Installer|Docker Desktop)$' } | Select-Object ProcessName,Id,WorkingSet64)) | ConvertTo-Json -AsArray"]));
      atomic('app-preflight.json',{at:new Date().toISOString(),processes:apps});
      assert.ok(!apps.some(p=>/^(ChatGPT|chrome|codex|codex-computer-use|Installer)$/i.test(p.ProcessName)),'Close apps and finish/pause application updates before screening; no automatic retry');
      return withPausedBackground(ids(),docker,record,async()=>{
        // Runtime environment and process snapshots are collected by run.mjs, never by Codex.
        for(const side of ['a','b']){await run(side,'up');const state=read(stageSettings('p3-'+side).output+'/environment-initial.json');assert.equal(state.jarSha256,launch.artifact[side+'Jar']);assert.equal(state.runtime.maxHeapBytes,389283840);assert.equal(state.runtime.hikariMaximum,10);assert.equal(state.runtime.shared_buffers,'1GB');assert.equal(state.runtime.work_mem,'4MB');assert.deepEqual(state.runtime.gc,['Copy','MarkSweepCompact']);assert.equal(state.environment.background.length,0);await run(side,'seed','M','skew');await run(side,'verify-seed');await run(side,'stop');}
        const datasets=['a','b'].map(side=>read(stageSettings('p3-'+side).output+'/dataset.json'));for(const table of Object.keys(datasets[0].data).filter(k=>k.startsWith('public.')))assert.deepEqual(datasets[0].data[table],datasets[1].data[table],table);
        for(const item of screeningPlans()){await run(item.side,'p3-db-start');await run(item.side,'p3-cell-plan',item.name);await run(item.side,'stop');session.plans.push(item);atomic('session.json',session);}
        for(const side of ['a','b']){await run(side,'up');await run(side,'measure','screen','16','10','hot80','1');await run(side,'snapshot','screen-after.json');const after=read(stageSettings('p3-'+side).output+'/screen-after.json');assert.ok(after.containers.every(x=>!x.oomKilled));await run(side,'stop');summarize(side);session.runs.push({side,finishedAt:new Date().toISOString()});atomic('session.json',session);}
        await run('a','p3-db-start');await run('a','p3-cell-plan','a-ext');await run('a','stop');
        const inputs={a:sideResult('a'),b:sideResult('b')};const result={inputs,...evaluateScreening(inputs)};atomic('screening-result.json',result);return result;
      });
    },recover:async()=>restore(),complete:async result=>{session.finishedAt=new Date().toISOString();atomic('session.json',session);atomic('completed.json',{...result,at:session.finishedAt,backgroundRestored:backgroundsSafe(),backgroundLeaseAcquired:backgroundRecoveryRequired(lease()),formalStarted:false});}
  });}catch(e){console.error(e.message);process.exitCode=1;}
}
