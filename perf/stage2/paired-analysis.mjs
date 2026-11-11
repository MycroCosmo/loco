import fs from 'node:fs';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
import {stageSettings} from './data.mjs';

export function distribution(values) {
  const v=[...values].sort((a,b)=>a-b);assert.ok(v.length);
  const n=v.length,median=n%2?v[Math.floor(n/2)]:(v[n/2-1]+v[n/2])/2;
  return {values,median,min:v[0],max:v.at(-1),rangePercent:(v.at(-1)-v[0])/median*100};
}
export function comparePairs(a,b) {
  assert.equal(a.length,5);assert.equal(b.length,5);
  const result={};
  for(const metric of ['p(95)','p(99)']) {
    const before=distribution(a.map(r=>r.http[metric])),after=distribution(b.map(r=>r.http[metric]));
    result[metric]={before,after,medianRegressionPercent:(after.median/before.median-1)*100,pairedRegressionPercent:b.map((r,i)=>(r.http[metric]/a[i].http[metric]-1)*100)};
  }
  result.valid=a.concat(b).every(r=>r.checks.fails===0&&r.dropped===0&&Object.keys(r.status).every(s=>s==='200')&&r.collectorErrors.length===0);
  result.withinTenPercent=result.valid&&result['p(95)'].medianRegressionPercent<=10&&result['p(99)'].medianRegressionPercent<=10;
  return result;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href) {
  const sides=Object.fromEntries(['A','B'].map(side=>[side,JSON.parse(fs.readFileSync(stageSettings('e15-'+side.toLowerCase()).output+'/summary.json'))]));
  const result={generatedAt:new Date().toISOString(),conditions:{}};
  for(const viewport of [1,10]) {
    const select=side=>Array.from({length:5},(_,i)=>{
      const r=sides[side].find(r=>r.run===`pair-${i+1}-v${viewport}-${side.toLowerCase()}-1`);assert.ok(r);return r;
    });
    const a=select('A'),b=select('B');
    result.conditions[viewport]={...comparePairs(a,b),runs:{A:a,B:b}};
  }
  fs.writeFileSync('apps/server-v3/build/perf-stage2-e15/evaluation.json',JSON.stringify(result,null,2));
  console.log(JSON.stringify(Object.fromEntries(Object.entries(result.conditions).map(([k,{runs,...v}])=>[k,v])),null,2));
}
