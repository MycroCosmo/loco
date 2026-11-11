import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import assert from 'node:assert/strict';
import {stageSettings} from './data.mjs';

// Exploratory brackets only. Five 300-second passes are still required afterward.
const {stage,output}=stageSettings();
assert.equal(stage,'p1','Explicit PERF_STAGE=p1 required');
const plan=path.join(output,'capacity-plan.json');
assert.ok(!fs.existsSync(plan),'Do not overwrite a previous capacity search');
const probes=[];
function run(args) {
  return spawnSync(process.execPath,['perf/stage2/run.mjs',...args],{stdio:'inherit',env:process.env,windowsHide:true}).status;
}
function probe(rate) {
  const name=`capacity-${rate}`;
  const status=run(['probe',name,String(rate),'10','60']);
  const execution=JSON.parse(fs.readFileSync(path.join(output,name,'execution.json')));
  assert.ok([0,99].includes(execution.exitCode),'Unexpected k6 failure');
  const summary=JSON.parse(fs.readFileSync(path.join(output,name,'summary.json'))).metrics;
  const errors=summary.http_req_failed?.values?.passes||0;
  const failures=summary.business_failure?.values?.count||0;
  assert.equal(failures,errors,'HTTP 200 business-response mismatch requires investigation');
  assert.equal(JSON.parse(fs.readFileSync(path.join(output,name+'-db-check.json'))).unchanged,true);
  const entry={rate,run:name,p95:summary.http_req_duration.values['p(95)'],errors,failures,dropped:summary.dropped_iterations?.values?.count||0,passed:status===0};
  assert.equal(entry.passed,entry.p95<=200&&errors===0&&failures===0&&entry.dropped===0);
  probes.push(entry);console.log(JSON.stringify(entry));
  if(entry.errors||entry.dropped) {
    assert.equal(run(['restart']),0,'Recovery must keep the same app configuration');
    assert.equal(run(['warmup',name+'-recovery','1','10','120']),0,'Recovery warmup failed');
  }
  return entry.passed;
}
let low=0,high=1;
assert.equal(run(['warmup','capacity-initial-warmup','1','10','120']),0,'Initial warmup failed');
while(probe(high)) {
  low=high;high*=2;
  assert.ok(high<=10000,'Search ceiling reached; do not infer an unmeasured maximum');
}
while(high-low>1) {
  const middle=Math.floor((high+low)/2);
  if(probe(middle))low=middle;else high=middle;
}
fs.writeFileSync(plan,JSON.stringify({candidate:low,firstFailingRate:high,windowSeconds:60,requiresFiveFullMeasurements:true,probes},null,2));
assert.ok(low>0,'No integer request rate meets the budget in exploration');
console.log(`Exploratory candidate ${low} req/s; verify five full runs and ${high} req/s for 300 seconds before reporting capacity.`);
