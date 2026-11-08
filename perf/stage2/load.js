import http from 'k6/http';
import crypto from 'k6/crypto';
import exec from 'k6/execution';
import {check} from 'k6';
import {Trend,Counter} from 'k6/metrics';

const config=JSON.parse(open(__ENV.CONFIG));
const credentials=JSON.parse(open('/results/private/credentials.json'));
const wall=new Trend('request_wall_ms',true), success=new Counter('business_success'), failures=new Counter('business_failure');
export const options={
  scenarios:{load:{executor:'constant-arrival-rate',rate:config.rate,timeUnit:'1s',duration:`${config.seconds}s`,preAllocatedVUs:100,maxVUs:100,gracefulStop:'10s'}},
  systemTags:['status','method','name','scenario','expected_response'],
  thresholds:{checks:['rate==1'],http_req_failed:['rate==0'],dropped_iterations:['count==0'],...(config.budgetMs?{http_req_duration:[`p(95)<=${config.budgetMs}`]}:{})},
  summaryTrendStats:['avg','min','med','p(95)','p(99)','max'],
};
function canonical(value) {
  if(Array.isArray(value)) return '['+value.map(canonical).sort().join(',')+']';
  if(value&&typeof value==='object') return '{'+Object.keys(value).sort().map(k=>JSON.stringify(k)+':'+canonical(value[k])).join(',')+'}';
  return JSON.stringify(value);
}
export default function() {
  const i=exec.scenario.iterationInTest;
  const list=config.cases;
  const c=config.hot80 && i%10<8 ? list[0] : list[1+(Math.floor(i/10)*2+i%2)%(list.length-1)];
  const selected=config.hot80 ? c : list[i%list.length];
  const started=Date.now();
  const r=http.get('http://perf-app:8081'+selected.path,{headers:config.cms?{Cookie:credentials.cookie}:{Authorization:'Bearer '+credentials.jwt},tags:{name:selected.name},timeout:'10s',redirects:0});
  wall.add(Date.now()-started,{endpoint:selected.name,status:r.status});
  let matched=false;
  try {matched=r.status===200&&crypto.sha256(canonical(r.json()),'hex')===selected.hash;} catch (_) {}
  check(r,{'status and complete business response match':()=>matched});
  (matched?success:failures).add(1,{endpoint:selected.name,status:r.status});
}
export function handleSummary(data) {return {[`/results/${config.run}/summary.json`]:JSON.stringify(data,null,2)};}
