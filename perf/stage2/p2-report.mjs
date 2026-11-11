import fs from 'node:fs';
import readline from 'node:readline';
import {createGunzip} from 'node:zlib';
import assert from 'node:assert/strict';

// Run after collection and summarize.mjs; never alongside timed workloads.
const base='apps/server-v3/build/perf-stage2-p2';
const read=file=>JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));
const median=xs=>{const s=[...xs].sort((a,b)=>a-b),i=Math.floor(s.length/2);return s.length%2?s[i]:(s[i-1]+s[i])/2;};
const range=xs=>(Math.max(...xs)-Math.min(...xs))/median(xs)*100;
const mean=xs=>xs.reduce((a,b)=>a+b,0)/xs.length;
const rows=['a0','a1','b'].flatMap(side=>read(`${base}-${side}/summary.json`).map(r=>({...r,side})));
assert.equal(rows.length,114,'Preserve every prescribed warmup and measured/diagnostic interval');
const valid=r=>r.validity?.valid===true&&(!r.warmupValidity||r.warmupValidity.valid)&&r.checks.fails===0&&r.dropped===0;
function compact(r) {
  const count=Object.values(r.status).reduce((a,b)=>a+b,0);
  return {side:r.side,run:r.run,valid:valid(r),p50:r.http.med,p95:r.http['p(95)'],p99:r.http['p(99)'],count,status:r.status,checksFailed:r.checks.fails,dropped:r.dropped,
    throughputPerSecond:read(`${base}-${r.side}/${r.run}/summary.json`).metrics.http_reqs.values.rate,throughputSource:'k6 http_reqs rate; excludes collector preparation and Docker CLI startup',endpoints:r.endpoints,
    resources:Object.fromEntries(Object.entries(r.resources).filter(([k])=>k!=='background').map(([k,v])=>[k.split('-').slice(-2).join('-'),{cpuMean:v.cpuCores.mean,cpuP95:v.cpuCores.p95,workingSetMiBMean:v.workingSetBytes.mean/2**20,workingSetMiBMax:v.workingSetBytes.max/2**20,throttleDelta:v.throttleAfter&&v.throttleBefore?{periods:v.throttleAfter.throttled_periods-v.throttleBefore.throttled_periods,nanoseconds:v.throttleAfter.throttled_time-v.throttleBefore.throttled_time}:null}]))};
}
const result={notice:'Laptop relative comparison, not production capacity. Invalid runs remain; no replacement or relaxed gates.',readEvaluation:read(base+'/read-evaluation.json'),
  intervals:rows.length,invalid:rows.filter(r=>!r.validity.valid).map(r=>({side:r.side,run:r.run,reasons:r.validity.reasons})),
  totals:{requests:rows.reduce((n,r)=>n+Object.values(r.status).reduce((a,b)=>a+b,0),0),failedChecks:rows.reduce((n,r)=>n+r.checks.fails,0),dropped:rows.reduce((n,r)=>n+r.dropped,0)},
  reads:rows.filter(r=>/^pair-.*-1$/.test(r.run)).map(compact),fullViewport:rows.filter(r=>/^full-viewport-\d-1$/.test(r.run)).map(compact),
  writes:rows.filter(r=>/^write-/.test(r.run)&&!r.run.endsWith('warmup')).map(compact),exploration:rows.filter(r=>/^explore-\d-1$/.test(r.run)).map(compact),sql:[],memory:[]};
assert.equal(result.reads.length,20);assert.equal(result.fullViewport.length,3);assert.equal(result.writes.length,18);assert.equal(result.exploration.length,8);
result.writeSingle={valid:result.writes.filter(r=>/-c1-/.test(r.run)).every(r=>r.valid),note:'All five original values, including the invalid run, shown descriptively. No adoption from this sample.',endpoints:{}};
for(const endpoint of ['place-create','place-update','place-delete']) {
  const a=result.writes.filter(r=>r.side==='a1'&&/-c1-/.test(r.run)).map(r=>r.endpoints[endpoint]);
  const b=result.writes.filter(r=>r.side==='b'&&/-c1-/.test(r.run)).map(r=>r.endpoints[endpoint]);
  result.writeSingle.endpoints[endpoint]={};
  for(const metric of ['p50','p95','p99'])result.writeSingle.endpoints[endpoint][metric]={a:median(a.map(r=>r[metric])),b:median(b.map(r=>r[metric])),changePercent:(median(b.map(r=>r[metric]))/median(a.map(r=>r[metric]))-1)*100};
  result.writeSingle.endpoints[endpoint].p95RangePercent={a:range(a.map(r=>r.p95)),b:range(b.map(r=>r.p95))};
}
for(const side of ['a1','b'])for(const n of [1,2,4,8,16]) {
  const statements=read(`${base}-${side}/sql-c${n}-sql.json`).statements,cycles=5*n;
  const cells=statements.filter(s=>/^(SELECT ROUND|INSERT INTO place_marker_cells|DELETE FROM place_marker_cells|WITH keys)|pg_advisory_xact_lock/.test(s.query));
  const lock=statements.find(s=>s.query.includes('pg_advisory_xact_lock'));
  const control=statements.filter(s=>/^(BEGIN|COMMIT|ROLLBACK|SHOW )/.test(s.query));
  const metadata=statements.filter(s=>/pg_catalog|pg_type|SELECT c\.oid|SELECT n\.nspname|SELECT typinput/.test(s.query));
  const application=statements.filter(s=>!control.includes(s)&&!metadata.includes(s)&&!s.query.startsWith('DELETE FROM ONLY'));
  const total=(list,field)=>list.reduce((a,b)=>a+Number(b[field]),0);
  result.sql.push({side,concurrency:n,cycles,httpRequests:cycles*3,applicationSqlPerCycle:total(application,'calls')/cycles,cellSqlPerCycle:total(cells,'calls')/cycles,
    cellSqlMsPerCycle:total(cells,'total_exec_time')/cycles,cellWalBytesPerCycle:total(cells,'wal_bytes')/cycles,
    transactionBeginPerCycle:total(statements.filter(s=>s.query.startsWith('BEGIN')),'calls')/cycles,
    isolationShowPerCycle:total(statements.filter(s=>s.query.startsWith('SHOW TRANSACTION')),'calls')/cycles,
    lock:lock?{calls:lock.calls,meanMsIncludingWait:lock.mean_exec_time,maxMsIncludingWait:lock.max_exec_time,totalMsIncludingWait:lock.total_exec_time}:null});
}
for(const viewport of [1,10,100]) {
  const dir=base+'-b',name='memory-v'+viewport;
  const events=read(`${dir}/${name}-memory-events.json`).recording.events;
  const recording=events.find(e=>e.type==='jdk.ActiveRecording').values;
  assert.equal(recording.recordingDuration,'PT1M');
  const start=Date.parse(recording.recordingStart),end=start+60000;
  let requests=0,hotRequests=0;
  for(const phase of ['warmup','diagnostic'])for await(const line of readline.createInterface({input:fs.createReadStream(`${dir}/${name}-${phase}/raw.json.gz`).pipe(createGunzip()),crlfDelay:Infinity})) {
    const r=JSON.parse(line);if(r.type!=='Point'||r.metric!=='http_req_duration')continue;
    const t=Date.parse(r.data.time);if(t>=start&&t<end){requests++;if(r.data.tags.name==='markers-hot')hotRequests++;}
  }
  assert.ok(requests>0);
  const allocation=events.filter(e=>e.type==='jdk.ObjectAllocationSample'),gc=events.filter(e=>e.type==='jdk.GarbageCollection');
  const seconds=s=>{const m=/^PT(?:(\d+)M)?([\d.]+)S$/.exec(s);assert.ok(m,'Unexpected JFR duration '+s);return Number(m[1]||0)*60+Number(m[2]);};
  const after=events.filter(e=>e.type==='jdk.GCHeapSummary'&&e.values.when==='After GC').map(e=>e.values.heapUsed);
  const config=read(`${dir}/${name}-diagnostic/config.json`);
  const weight=list=>list.reduce((s,e)=>s+e.values.weight,0);
  const httpAlloc=allocation.filter(e=>e.values.eventThread?.javaName?.startsWith('http-nio-'));
  result.memory.push({viewport,windowStart:recording.recordingStart,windowSeconds:60,requests,hotRequests,hotCase:config.cases[0],
    allocationSamples:allocation.length,estimatedAllThreadMiBPerRequest:weight(allocation)/requests/2**20,estimatedHttpThreadMiBPerRequest:weight(httpAlloc)/requests/2**20,
    afterGcMeanMiB:mean(after)/2**20,afterGcMaxMiB:Math.max(...after)/2**20,gcCount:gc.length,oldGc:gc.filter(e=>e.values.name==='MarkSweepCompact').length,
    gcPauseMs:gc.reduce((s,e)=>s+seconds(e.values.sumOfPauses)*1000,0),diagnostic:compact(rows.find(r=>r.run===name+'-diagnostic')),
    note:'JFR sample weights are estimates, not exact allocation counters. HTTP completions from warmup+diagnostic aligned to the 60s recording; boundary requests may straddle the window.'});
  delete result.memory.at(-1).hotCase.overflow;
}
result.fullViewportOom=result.fullViewport.map(r=>{
  const name=r.run.replace(/-1$/,'');const snapshot=read(`${base}-b/${name}-after.json`),log=fs.readFileSync(`${base}-b/${name}-app.log`,'utf8');
  return {run:r.run,containerOom:snapshot.containers.filter(c=>c.oomKilled).length,javaOom:/OutOfMemoryError|Java heap space|GC overhead limit/.test(log)};
});
result.restoration={read:read(base+'/restoration-read-verification.json'),final:read(base+'/restoration-final-verification.json')};
result.execution={read:read(base+'/session-execution.json'),remaining:read(base+'/remaining-execution.json')};
result.execution.wallSeconds=(Date.parse(result.execution.remaining.finished)-Date.parse(result.execution.read.started))/1000;
result.adoption='BLOCKED: mandatory write sample contains interference; no replacement run or retrospective gate change. Read gates pass separately.';
fs.writeFileSync(base+'/analysis.json',JSON.stringify(result,null,2));
console.log(JSON.stringify({intervals:result.intervals,totals:result.totals,invalid:result.invalid,memory:result.memory.map(({diagnostic,hotCase,...r})=>({...r,hotCase:{returned:hotCase.returned,selectedPlaces:hotCase.selectedPlaces}})),sql:result.sql,writeSingle:result.writeSingle},null,2));
