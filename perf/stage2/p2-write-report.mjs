import fs from 'node:fs';
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
import {distribution} from './paired-analysis.mjs';
import {protocol,writeRetestSchedule,writeSessions,writeE24Policy} from './p2-protocol.mjs';

export function evaluateWriteRetest(rows,measuredCycles=30,policy='legacy') {
  assert.ok([30,300].includes(measuredCycles),'Only approved sample counts');
  assert.ok(['legacy','e24-b'].includes(policy));
  const e24=policy==='e24-b';if(e24)assert.equal(measuredCycles,300);
  const schedule=writeRetestSchedule();
  assert.equal(rows.length,20,'Keep every prescribed main and warmup interval');
  for(const item of schedule)for(const warmup of [false,true]) {
    const run=item.name+(warmup?'-warmup':'');
    const matches=rows.filter(r=>r.run===run);assert.equal(matches.length,1,'Missing or duplicate '+run);
    const r=matches[0],cycles=warmup?10:measuredCycles;
    assert.equal(r.writeConcurrency,1);assert.equal(r.writeCycles,cycles);
    for(const endpoint of ['place-create','place-update','place-delete'])assert.equal(r.endpoints[endpoint].n,cycles);
  }
  const mains=schedule.map(item=>rows.find(r=>r.run===item.name));
  const validity=rows.map(r=>({run:r.run,reasons:[...(r.validity?.valid!==true?(r.validity?.reasons?.length?r.validity.reasons:['interference-or-missing-observation']):[]),
    ...(r.checks.fails!==0?['failed-checks']:[]),...(r.dropped!==0?['dropped']:[]),...(r.collectorErrors.length?['collector-error']:[]),
    ...(r.execution.exitCode!==0?['execution-failed']:[]),...(r.execution.hostObservationBracketsWorkload!==true?['uncovered-workload']:[]),
    ...(Object.keys(r.status).some(s=>s!=='200'&&s!=='201')?['http-error']:[]),...(e24&&r.dbRestored!==true?['db-not-restored']:[])]}));
  const valid=validity.every(r=>r.reasons.length===0);
  const comparisons={};
  for(const endpoint of ['mixed','place-create','place-update','place-delete']) {
    const sides=Object.fromEntries(['a1','b'].map(side=>[side,mains.filter(r=>r.run.endsWith('-'+side))]));
    const metrics={};
    for(const metric of ['p50','p95','p99']) {
      const value=r=>endpoint==='mixed'?r.http[{p50:'med',p95:'p(95)',p99:'p(99)'}[metric]]:r.endpoints[endpoint][metric];
      const a=distribution(sides.a1.map(value)),b=distribution(sides.b.map(value));
      assert.ok([...a.values,...b.values].every(Number.isFinite));
      metrics[metric]={a,b,changePercent:(b.median/a.median-1)*100,pairedChangePercent:b.values.map((n,i)=>(n/a.values[i]-1)*100)};
    }
    const pairedP95Ratios=metrics.p95.b.values.map((n,i)=>{assert.ok(metrics.p95.a.values[i]>0);return n/metrics.p95.a.values[i];});
    comparisons[endpoint]={metrics,...(e24?{pairedP95Ratios}:{}),withinRegression:e24?pairedP95Ratios.every(r=>r<=writeE24Policy.maxPairedP95Ratio):metrics.p95.changePercent<=protocol.maxRegressionPercent,
      stable:metrics.p95.a.rangePercent<=protocol.maxRangePercent&&metrics.p95.b.rangePercent<=protocol.maxRangePercent};
  }
  const repeatabilityWarnings=Object.entries(comparisons).flatMap(([endpoint,c])=>['a','b'].filter(side=>c.metrics.p95[side].rangePercent>10).map(side=>({endpoint,side:side==='a'?'a1':'b',rangePercent:c.metrics.p95[side].rangePercent})));
  return {valid,comparisons,passed:valid&&Object.values(comparisons).every(c=>c.withinRegression&&(e24||c.stable)),
    ...(e24?{policy:writeE24Policy,repeatabilityWarnings,invalidScenarios:[...new Set(validity.filter(r=>r.reasons.length).map(r=>r.run.replace(/-warmup$/,'')))]}:{}),
    invalid:validity.filter(r=>r.reasons.length),
    totals:{intervals:rows.length,requests:rows.reduce((n,r)=>n+Object.values(r.status).reduce((a,b)=>a+b,0),0),
      mainRequests:mains.reduce((n,r)=>n+Object.values(r.status).reduce((a,b)=>a+b,0),0),
      failedChecks:rows.reduce((n,r)=>n+r.checks.fails,0),dropped:rows.reduce((n,r)=>n+r.dropped,0)},
    measuredCycles,note:`No sample exclusions or replacement. p99 is descriptive: ${measuredCycles} samples per endpoint per main run. ${e24?'E-24 B: every paired p95 ratio <=1.10 required; range/median >10% is a warning only. No bootstrap or confidence interval.':'Existing p95 regression/range gates remain unchanged.'}`};
}

if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href) {
  const mode=process.argv[2]||'write-retest';assert.ok(Object.keys(writeSessions).includes(mode));
  const suffix=writeSessions[mode],e24=mode==='write-e24';
  const base='apps/server-v3/build/perf-stage2-p2-'+suffix;
  const read=p=>JSON.parse(fs.readFileSync(p));
  const session=read(base+'/'+mode+'-session.json'),background=read(base+'/background-manifest.json'),verification=read(base+'/final-verification.json');
  assert.ok(session.finishedAt&&background.restored&&verification.allRunning&&verification.originalsUnchanged);
  assert.deepEqual(session.schedule,writeRetestSchedule());assert.equal(session.runs.length,10);
  if(e24)assert.deepEqual(session.adoptionPolicy,writeE24Policy,'Never apply E-24 retrospectively to an earlier session');
  assert.deepEqual(session.sourcePlaces,read('apps/server-v3/build/perf-stage2-p2/writes-session.json').sourcePlaces);
  const rows=['a1','b'].flatMap(side=>read(`apps/server-v3/build/perf-stage2-p2-${side}-${suffix}/summary.json`));
  if(e24)for(const r of rows) {
    const name=r.run.replace(/-warmup$/,''),side=name.endsWith('-a1')?'a1':'b';
    r.dbRestored=read(`apps/server-v3/build/perf-stage2-p2-${side}-${suffix}/${name}-db-check.json`).unchanged===true;
  }
  const evaluation=evaluateWriteRetest(rows,mode==='write-retest'?30:300,e24?'e24-b':'legacy'),runs=[];
  for(const item of session.runs) {
    const dir=`apps/server-v3/build/perf-stage2-p2-${item.side}-${suffix}`,row=rows.find(r=>r.run===item.name);
    const snapshot=read(dir+'/'+item.name+'-after.json');
    assert.equal(snapshot.jarSha256,session.expectedJars[item.side]);assert.equal(snapshot.runtime.maxHeapBytes,protocol.heapBytes);
    assert.equal(snapshot.sqlTrackingAtSnapshot,'none');assert.ok(snapshot.containers.every(c=>!c.oomKilled));
    const dbUnchanged=read(dir+'/'+item.name+'-db-check.json').unchanged===true;if(!e24)assert.ok(dbUnchanged);
    runs.push({...item,p50:row.http.med,p95:row.http['p(95)'],p99:row.http['p(99)'],endpoints:row.endpoints,
      throughputPerSecond:read(dir+'/'+item.name+'/summary.json').metrics.http_reqs.values.rate,
      resources:row.resources,host:row.host,dbUnchanged,containerOom:false});
  }
  const result={generatedAt:new Date().toISOString(),measurementSource:session.source,applicationSource:session.implementation,
    elapsedSeconds:verification.elapsedSeconds,acceptedPrevious:['E-16 reads','E-21 reads','full viewport'],
    evaluation,runs,adoption:evaluation.passed?'ADOPTED':e24&&!evaluation.valid?'CLOSED: read/full viewport adopted; write improvement direction observed, quantitative verdict withheld; no further run':e24?'BLOCKED: mandatory paired p95 regression failed; no further run':'BLOCKED: fixed validity/regression/variation gate not met; no retrospective gate change',
    previousResultsPreserved:true,knownDifferences:16,applicationChanged:false};
  fs.writeFileSync(base+'/analysis.json',JSON.stringify(result,null,2));
  fs.writeFileSync(base+'/manifest.json',JSON.stringify({session,background,verification},null,2));
  console.log(JSON.stringify({elapsedSeconds:result.elapsedSeconds,evaluation,adoption:result.adoption},null,2));
}
