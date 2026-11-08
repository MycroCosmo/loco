import fs from 'node:fs';
import path from 'node:path';
import {createGunzip} from 'node:zlib';
import readline from 'node:readline';
const base='apps/server-v3/build/perf-stage2';
const quantile=(values,p)=>{if(!values.length)return null;const v=[...values].sort((a,b)=>a-b),k=(v.length-1)*p,l=Math.floor(k);return v[l]+(v[Math.ceil(k)]-v[l])*(k-l);};
const stats=v=>({n:v.length,mean:v.length?v.reduce((n,x)=>n+x,0)/v.length:null,p50:quantile(v,.5),p95:quantile(v,.95),p99:quantile(v,.99),max:v.length?Math.max(...v):null});
const output=[];
for(const directory of fs.readdirSync(base,{withFileTypes:true}).filter(d=>d.isDirectory()).map(d=>d.name)) {
  const summaryFile=path.join(base,directory,'summary.json');if(!fs.existsSync(summaryFile))continue;
  const summary=JSON.parse(fs.readFileSync(summaryFile)),config=JSON.parse(fs.readFileSync(path.join(base,directory,'config.json')));
  const endpoint={},status={},resources={},gaps=[],collectorErrors=[];let previous;
  for await(const line of readline.createInterface({input:fs.createReadStream(path.join(base,directory,'raw.json.gz')).pipe(createGunzip()),crlfDelay:Infinity})) {
    const item=JSON.parse(line);if(item.type!=='Point')continue;
    if(item.metric==='http_req_duration') {const tag=item.data.tags.name;(endpoint[tag]??=[]).push(item.data.value);status[item.data.tags.status]=(status[item.data.tags.status]||0)+1;}
  }
  for(const line of fs.readFileSync(path.join(base,directory,'resources.jsonl'),'utf8').trim().split('\n').filter(Boolean)) {
    const sample=JSON.parse(line);if(previous)gaps.push((Date.parse(sample.at)-previous)/1000);previous=Date.parse(sample.at);
    if(sample.error)collectorErrors.push({at:sample.at,error:sample.error});
    const individual=(sample.stats||[]).filter(s=>s.name.startsWith('/loco-perf-p0'));
    const background=(sample.stats||[]).filter(s=>!s.name.startsWith('/loco-perf-p0'));
    if(background.length)individual.push({name:'background',cpuCores:background.reduce((n,s)=>n+(s.cpuCores||0),0),workingSet:background.reduce((n,s)=>n+(s.workingSet||0),0)});
    for(const item of individual) {
      const name=item.name.startsWith('/loco-perf-p0')?item.name:'background';
      const r=resources[name]??={cpu:[],workingSet:[],firstThrottle:null,lastThrottle:null};
      if(item.cpuCores!==null&&Number.isFinite(item.cpuCores))r.cpu.push(item.cpuCores);
      if(item.workingSet)r.workingSet.push(item.workingSet);
      r.firstThrottle??=item.throttle;r.lastThrottle=item.throttle;
    }
  }
  const metrics=summary.metrics;
  const hostFile=path.join(base,directory,'host.jsonl');
  const hostSamples=fs.existsSync(hostFile)?fs.readFileSync(hostFile,'utf8').trim().split('\n').filter(Boolean).map(l=>JSON.parse(l)):[];
  output.push({run:directory,rate:config.rate,seconds:config.seconds,hot80:config.hot80,cms:config.cms||false,http:metrics.http_req_duration?.values,wall:metrics.request_wall_ms?.values,status,checks:metrics.checks?.values,dropped:metrics.dropped_iterations?.values?.count||0,endpoints:Object.fromEntries(Object.entries(endpoint).map(([k,v])=>[k,{...stats(v),p99AdoptionEligible:v.length>=10000}])),resources:Object.fromEntries(Object.entries(resources).map(([k,v])=>[k,{cpuCores:stats(v.cpu),workingSetBytes:stats(v.workingSet),throttleBefore:v.firstThrottle,throttleAfter:v.lastThrottle}])),sampleGapSeconds:stats(gaps),collectorErrors,execution:JSON.parse(fs.readFileSync(path.join(base,directory,'execution.json')))});
  output.at(-1).host={cpuPercent:stats(hostSamples.filter(s=>s.cpuPercent!==null).map(s=>s.cpuPercent)),availablePhysicalBytes:stats(hostSamples.map(s=>s.availablePhysicalBytes))};
}
fs.writeFileSync(path.join(base,'summary.json'),JSON.stringify(output,null,2));
console.table(output.map(r=>({run:r.run,n:Object.values(r.status).reduce((a,b)=>a+b,0),p50:r.http.med,p95:r.http['p(95)'],p99:r.http['p(99)'],dropped:r.dropped,errors:Object.entries(r.status).filter(([s])=>s!=='200').reduce((n,[,v])=>n+v,0)})));
