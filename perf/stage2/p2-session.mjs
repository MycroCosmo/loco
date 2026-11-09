import fs from 'node:fs';
import assert from 'node:assert/strict';
import {spawn,spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {withPausedBackground,restoreBackground} from './background-lease.mjs';
import {fullViewportSchedule,writeSessions} from './p2-protocol.mjs';

const original='apps/server-v3/build/perf-stage2-p2';
const mode=process.argv[2]||'all';
const phase=mode==='watchdog'?(process.argv[4]||'all'):mode==='restore'?(process.argv[3]||(fs.existsSync(original+'/background-remaining-manifest.json')?'remaining':'all')):mode;
const base=original+(writeSessions[phase]?'-'+writeSessions[phase]:'');
fs.mkdirSync(base,{recursive:true});
const file=base+'/background'+(phase==='remaining'?'-remaining':'')+'-manifest.json';
const ids=JSON.parse(fs.readFileSync(original+'/private/approved-background.json'));
const command=(exe,args)=>{const r=spawnSync(exe,args,{encoding:'utf8',windowsHide:true});assert.equal(r.status,0,(r.stderr||'').slice(-800));return r.stdout.trim();};
const docker=(action,id)=>action==='inspect'?JSON.parse(command('docker',['inspect','--format','{{json .State}}',id])):command('docker',[action,id]);
let manifest;
function record(phase,data) {
  manifest??=fs.existsSync(file)?JSON.parse(fs.readFileSync(file)):{startedAt:new Date().toISOString(),source:command('git',['rev-parse','HEAD']),history:[]};
  manifest.history.push({at:new Date().toISOString(),phase,...data});
  if(phase==='restored')manifest.restored=data.restored;
  fs.writeFileSync(file,JSON.stringify(manifest,null,2));
  if(phase==='before'&&['all','remaining',...Object.keys(writeSessions)].includes(mode)) {
    const monitor=spawn(process.execPath,[fileURLToPath(import.meta.url),'watchdog',String(process.pid),mode],{detached:true,stdio:'ignore',windowsHide:true});monitor.unref();
  }
}
function stopOwnWorkloads() {
  const rows=command('docker',['ps','--no-trunc','--format','json']).split('\n').filter(Boolean).map(JSON.parse);
  const own=rows.filter(r=>/^loco-perf-p2-(a0|a1|b)-/.test(r.Names)).map(r=>r.ID);
  if(own.length)command('docker',['stop',...own]);
}
if(mode==='watchdog') {
  const owner=Number(process.argv[3]);
  const timer=setInterval(()=>{
    if(fs.existsSync(file)&&JSON.parse(fs.readFileSync(file)).restored)process.exit(0);
    try {process.kill(owner,0);return;} catch {}
    try {stopOwnWorkloads();restoreBackground(ids,docker,record);clearInterval(timer);process.exit(0);}
    catch(e){record('recovery-retry',{error:e.message});}
  },5000);
} else if(mode==='restore') {
  try {stopOwnWorkloads();} finally {restoreBackground(ids,docker,record);}
} else {
  assert.ok(['all','remaining',...Object.keys(writeSessions)].includes(mode));assert.ok(!fs.existsSync(file),'Never overwrite a previous pause/restore manifest');
  assert.equal(command('git',['status','--porcelain']),'','Commit the protocol before timing');
  if(mode==='remaining')assert.equal(JSON.parse(fs.readFileSync(base+'/reads-session.json')).runs.length,20,'Preserve and complete all prescribed reads before resuming later phases');
  let child;
  async function run(script,args=[],side) {
    console.log(new Date().toISOString(),side||'session',script,...args);
    child=spawn(process.execPath,['perf/stage2/'+script+'.mjs',...args],{env:{...process.env,...(side?{PERF_STAGE:'p2-'+side}:{})},stdio:'inherit',windowsHide:true});
    const code=await new Promise((resolve,reject)=>{child.once('exit',resolve);child.once('error',reject);});child=null;
    assert.equal(code,0,'Measurement step failed; original evidence preserved');
  }
  for(const signal of ['SIGINT','SIGTERM'])process.once(signal,()=>{
    child?.kill();if(manifest?.history.some(r=>r.phase==='before')) {
      try {stopOwnWorkloads();} finally {restoreBackground(ids,docker,record);}
    }
    process.exit(130);
  });
  try {
    await withPausedBackground(ids,docker,record,async()=>{
      try {
        if(writeSessions[mode]) {
          await run('p2-paired',[mode]);
          record('measurement-complete',{completedAt:new Date().toISOString()});
          return;
        }
        if(mode==='all'){await run('p2-paired',['reads']);await run('p2-paired',['evaluate']);}
        for(const {name,rate,viewport} of fullViewportSchedule()) {
          await run('run',['up'],'b');
          await run('run',['measure',name,String(rate),String(viewport),'hot80','1'],'b');
          await run('run',['snapshot',name+'-after.json'],'b');
          await run('run',['stop'],'b');
        }
        await run('p2-paired',['writes']);
        for(const side of ['a1','b']) {
          await run('run',['up'],side);
          for(const n of [1,2,4,8,16])await run('run',['p2-write-sql','sql-c'+n,String(n)],side);
          await run('run',['stop'],side);
        }
        for(const viewport of [1,10,100]) {
          await run('run',['up'],'b');
          await run('run',['profile','memory-v'+viewport,'50',String(viewport),'1'],'b');
          await run('run',['stop'],'b');
        }
        const exploration=[['S','skew','hot80',1],['S','uniform','uniform',1],['M','skew','hot80',.01],['M','skew','hot80',100],['M','uniform','hot80',1],['M','uniform','hot80',10],['M','skew','uniform',1],['M','skew','uniform',10]];
        for(const [i,[size,distribution,mix,viewport]] of exploration.entries()) {
          await run('run',['up'],'b');await run('run',['seed',size,distribution],'b');await run('run',['verify-seed'],'b');
          await run('run',['explore','explore-'+(i+1),viewport>=10?'16':'50',String(viewport),mix,'1'],'b');
          await run('run',['stop'],'b');
        }
        record('measurement-complete',{completedAt:new Date().toISOString()});
      } finally {stopOwnWorkloads();}
    });
  } catch(e) {record('measurement-failed',{error:e.message});process.exitCode=1;}
}
