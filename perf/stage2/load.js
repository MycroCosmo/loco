import http from 'k6/http';
import crypto from 'k6/crypto';
import exec from 'k6/execution';
import {check} from 'k6';
import {Trend,Counter} from 'k6/metrics';
import {overflowMatches} from './overflow-contract.js';

const config=JSON.parse(open(__ENV.CONFIG));
const credentials=JSON.parse(open('/results/private/credentials.json'));
const wall=new Trend('request_wall_ms',true), success=new Counter('business_success'), failures=new Counter('business_failure');
export const options={
  scenarios:{load:config.writeCycles?(config.writeConcurrency?{executor:'per-vu-iterations',vus:config.writeConcurrency,iterations:config.writeCycles/config.writeConcurrency,maxDuration:'10m'}:{executor:'shared-iterations',vus:1,iterations:config.writeCycles,maxDuration:'10m'}):config.fixedConcurrency?{executor:'constant-vus',vus:config.fixedConcurrency,duration:`${config.seconds}s`,gracefulStop:'10s'}:{executor:'constant-arrival-rate',rate:config.rate,timeUnit:'1s',duration:`${config.seconds}s`,preAllocatedVUs:100,maxVUs:100,gracefulStop:'10s'}},
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
  if(config.writeCycles) {
    const headers={Authorization:'Bearer '+credentials.jwt,'Content-Type':'application/json'};
    const base='http://perf-app:8081';
    // P2 VUs use disjoint cells in one hot map: isolates map-wide serialization
    // from A1's known overlapping-cell stale-snapshot bug. Historical E14 is unchanged.
    const offset=config.writeConcurrency?(exec.vu.idInTest-1)*0.4:0;
    const body={name:'e14-write-cost',address:'synthetic',latitude:-70.1234567+offset,longitude:-140.1234567};
    function send(method,url,data,status,name) {
      const r=http.request(method,base+url,data?JSON.stringify(data):null,{headers,tags:{name},timeout:'30s',redirects:0});
      const ok=r.status===status;check(r,{[name+' status']:()=>ok});(ok?success:failures).add(1,{endpoint:name,status:r.status});
      if(!ok)exec.test.abort('Write response failed');return r;
    }
    const created=send('POST',`/api/maps/${config.map}/places`,body,201,'place-create').json();
    if(!created.id||created.name!==body.name)exec.test.abort('Created business data mismatch');
    const latitude=-70.2234567+offset;
    const updated=send('PUT','/api/places/'+created.id,{latitude,longitude:-140.2234567},200,'place-update').json();
    if(updated.id!==created.id||Math.abs(updated.latitude-latitude)>1e-12)exec.test.abort('Updated business data mismatch');
    send('DELETE','/api/places/'+created.id,null,200,'place-delete');
    return;
  }
  const i=exec.scenario.iterationInTest;
  const list=config.cases;
  const c=config.hot80 && i%10<8 ? list[0] : list[1+(Math.floor(i/10)*2+i%2)%(list.length-1)];
  const selected=config.hot80 ? c : list[i%list.length];
  const started=Date.now();
  const r=http.get('http://perf-app:8081'+selected.path,{headers:config.cms?{Cookie:credentials.cookie}:{Authorization:'Bearer '+credentials.jwt},tags:{name:selected.name},timeout:'10s',redirects:0});
  wall.add(Date.now()-started,{endpoint:selected.name,status:r.status});
  let matched=false;
  try {const body=r.json(); matched=r.status===200 && (selected.overflow ? overflowMatches(body,selected.overflow) && (!selected.hash || crypto.sha256(canonical(body),'hex')===selected.hash) : crypto.sha256(canonical(body),'hex')===selected.hash);} catch (_) {}
  check(r,{'status and complete business response match':()=>matched});
  (matched?success:failures).add(1,{endpoint:selected.name,status:r.status});
}
export function handleSummary(data) {return {[`/results/${config.run}/summary.json`]:JSON.stringify(data,null,2)};}
