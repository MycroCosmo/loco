// E-33: fresh buffer-only screen; never starts HTTP load or uses earlier plans.
import fs from 'node:fs';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {stageSettings} from './data.mjs';
import {screeningPlans,evaluateBufferScreen} from './p3-protocol.mjs';
import {withPausedBackground} from './background-lease.mjs';

const base='apps/server-v3/build/perf-stage2-p3-e33';
const read=p=>JSON.parse(fs.readFileSync(p));
const write=(file,value)=>fs.writeFileSync(base+'/'+file,JSON.stringify(value,null,2)+'\n');
const command=(exe,args,env=process.env)=>{const r=spawnSync(exe,args,{env,encoding:'utf8',windowsHide:true,maxBuffer:8*1024*1024});assert.equal(r.status,0,(r.error?.message||r.stderr||r.stdout||'').slice(-1200));return r.stdout.trim();};
const settings=side=>stageSettings('p3-'+side,'screen-retest2');
const run=(side,...args)=>{console.log(side,args.join(' '));const r=spawnSync(process.execPath,['perf/stage2/run.mjs',...args],{env:{...process.env,PERF_STAGE:'p3-'+side,PERF_SESSION:'screen-retest2'},encoding:'utf8',windowsHide:true,maxBuffer:8*1024*1024});fs.appendFileSync(base+'/driver.log',`${side} ${args.join(' ')}\n${r.stdout||''}\n${r.stderr||''}\n`);assert.equal(r.status,0,'E-33 step failed; retain records; no retry: '+side+' '+args.join(' '));};
assert.equal(command('git',['status','--porcelain']),'','Commit E-33 before execution');
assert.ok(!fs.existsSync(base),'Preserve every E-33 attempt');
for(const side of ['a','b'])assert.ok(!fs.existsSync(settings(side).output),'Fresh DB/output only');
fs.mkdirSync(base,{recursive:true});
const session={decision:'E-33',source:command('git',['rev-parse','HEAD']),startedAt:new Date().toISOString(),expectedMinutes:[5,8],order:screeningPlans(),criterion:'median(B root Shared Hit+Read) / median(A) <= 0.80',appsMayRemainOpen:true,sqlTimeUsed:false,oldPlansUsed:false,httpStarted:false,plans:[]};
write('session.json',session);
const artifacts=read('perf/stage2/p3-artifacts.json');
const ids=read('apps/server-v3/build/perf-stage2-p2/private/approved-background.json');
const docker=(action,id)=>action==='inspect'?JSON.parse(command('docker',['inspect','--format','{{json .State}}',id])):command('docker',[action,id]);
const history=[];
const record=(phase,data)=>{history.push({at:new Date().toISOString(),phase,...data});write('background-manifest.json',{history});};
try {
  const result=await withPausedBackground(ids,docker,record,async()=>{
    try {
      for(const side of ['a','b']) {
        run(side,'up');
        const runtime=read(settings(side).output+'/environment-initial.json');
        assert.equal(runtime.jarSha256,artifacts[side+'Jar']);
        assert.equal(runtime.runtime.maxHeapBytes,389283840);assert.equal(runtime.runtime.shared_buffers,'1GB');
        assert.equal(runtime.runtime.work_mem,'4MB');assert.equal(runtime.runtime.hikariMaximum,10);
        run(side,'seed','M','skew');run(side,'verify-seed');run(side,'stop');
      }
      const datasets=['a','b'].map(side=>read(settings(side).output+'/dataset.json'));
      assert.equal(datasets[0].sqlSha256,datasets[1].sqlSha256);
      for(const table of Object.keys(datasets[0].data).filter(k=>k.startsWith('public.')))
        assert.deepEqual(datasets[0].data[table],datasets[1].data[table],table);
      for(const item of screeningPlans()) {
        run(item.side,'p3-db-start');run(item.side,'p3-e33-cell-plan',item.name);run(item.side,'stop');
        const plan=read(settings(item.side).output+'/'+item.name+'.json');
        assert.equal(plan.databaseUnchanged,true);session.plans.push({...item,rootBuffers:plan.rootBuffers});write('session.json',session);
      }
      const a=session.plans.filter(p=>p.side==='a').map(p=>p.rootBuffers),b=session.plans.filter(p=>p.side==='b').map(p=>p.rootBuffers);
      return {a,b,...evaluateBufferScreen(a,b)};
    } finally {
      const errors=[];for(const side of ['a','b'])if(fs.existsSync(settings(side).output+'/private/secrets.json'))try{run(side,'stop');}catch(e){errors.push(e.message);}
      assert.deepEqual(errors,[],'Own workloads must stop');
    }
  });
  write('result.json',result);session.result=result;session.ok=true;
} catch(e) {session.ok=false;session.error=e.message;process.exitCode=1;}
finally {session.finishedAt=new Date().toISOString();session.wallSeconds=(Date.parse(session.finishedAt)-Date.parse(session.startedAt))/1000;write('session.json',session);console.log(JSON.stringify(session));}
