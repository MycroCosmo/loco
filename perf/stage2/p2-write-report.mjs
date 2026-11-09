import fs from 'node:fs';
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
import {distribution} from './paired-analysis.mjs';
import {protocol,writeRetestSchedule} from './p2-protocol.mjs';

export function evaluateWriteRetest(rows,measuredCycles=30) {
  assert.ok([30,300].includes(measuredCycles),'Only approved sample counts');
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
  const valid=rows.every(r=>r.validity?.valid===true&&r.checks.fails===0&&r.dropped===0&&r.collectorErrors.length===0&&r.execution.exitCode===0&&r.execution.hostObservationBracketsWorkload===true&&Object.keys(r.status).every(s=>s==='200'||s==='201'));
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
    comparisons[endpoint]={metrics,withinRegression:metrics.p95.changePercent<=protocol.maxRegressionPercent,
      stable:metrics.p95.a.rangePercent<=protocol.maxRangePercent&&metrics.p95.b.rangePercent<=protocol.maxRangePercent};
  }
  return {valid,comparisons,passed:valid&&Object.values(comparisons).every(c=>c.withinRegression&&c.stable),
    invalid:rows.filter(r=>!r.validity?.valid).map(r=>({run:r.run,reasons:r.validity?.reasons})),
    totals:{intervals:rows.length,requests:rows.reduce((n,r)=>n+Object.values(r.status).reduce((a,b)=>a+b,0),0),
      mainRequests:mains.reduce((n,r)=>n+Object.values(r.status).reduce((a,b)=>a+b,0),0),
      failedChecks:rows.reduce((n,r)=>n+r.checks.fails,0),dropped:rows.reduce((n,r)=>n+r.dropped,0)},
    measuredCycles,note:`No sample exclusions or replacement. p99 is descriptive: ${measuredCycles} samples per endpoint per main run. Existing p95 regression/range gates remain unchanged.`};
}

if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href) {
  const mode=process.argv[2]||'write-retest';assert.ok(['write-retest','write-e23'].includes(mode));
  const suffix=mode==='write-e23'?'write-e23':'write-retest1';
  const base='apps/server-v3/build/perf-stage2-p2-'+suffix;
  const read=p=>JSON.parse(fs.readFileSync(p));
  const session=read(base+'/'+mode+'-session.json'),background=read(base+'/background-manifest.json'),verification=read(base+'/final-verification.json');
  assert.ok(session.finishedAt&&background.restored&&verification.allRunning&&verification.originalsUnchanged);
  assert.deepEqual(session.schedule,writeRetestSchedule());assert.equal(session.runs.length,10);
  assert.deepEqual(session.sourcePlaces,read('apps/server-v3/build/perf-stage2-p2/writes-session.json').sourcePlaces);
  const rows=['a1','b'].flatMap(side=>read(`apps/server-v3/build/perf-stage2-p2-${side}-${suffix}/summary.json`));
  const evaluation=evaluateWriteRetest(rows,mode==='write-e23'?300:30),runs=[];
  for(const item of session.runs) {
    const dir=`apps/server-v3/build/perf-stage2-p2-${item.side}-${suffix}`,row=rows.find(r=>r.run===item.name);
    const snapshot=read(dir+'/'+item.name+'-after.json');
    assert.equal(snapshot.jarSha256,session.expectedJars[item.side]);assert.equal(snapshot.runtime.maxHeapBytes,protocol.heapBytes);
    assert.equal(snapshot.sqlTrackingAtSnapshot,'none');assert.ok(snapshot.containers.every(c=>!c.oomKilled));
    assert.ok(read(dir+'/'+item.name+'-db-check.json').unchanged);
    runs.push({...item,p50:row.http.med,p95:row.http['p(95)'],p99:row.http['p(99)'],endpoints:row.endpoints,
      throughputPerSecond:read(dir+'/'+item.name+'/summary.json').metrics.http_reqs.values.rate,
      resources:row.resources,host:row.host,dbUnchanged:true,containerOom:false});
  }
  const result={generatedAt:new Date().toISOString(),measurementSource:session.source,applicationSource:session.implementation,
    elapsedSeconds:verification.elapsedSeconds,acceptedPrevious:['E-16 reads','E-21 reads','full viewport'],
    evaluation,runs,adoption:evaluation.passed?'ADOPTED':'BLOCKED: fixed validity/regression/variation gate not met; no retrospective gate change',
    previousResultsPreserved:true,knownDifferences:16,applicationChanged:false};
  fs.writeFileSync(base+'/analysis.json',JSON.stringify(result,null,2));
  fs.writeFileSync(base+'/manifest.json',JSON.stringify({session,background,verification},null,2));
  console.log(JSON.stringify({elapsedSeconds:result.elapsedSeconds,evaluation,adoption:result.adoption},null,2));
}
