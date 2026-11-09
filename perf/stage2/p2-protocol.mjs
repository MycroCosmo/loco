import assert from 'node:assert/strict';

export const originalJars={
  a0:'c1a5293f1dc59a6ee0e648548e92695857a0357c57d10e45f06704a96952158f',
  a1:'da87c44168c321d1b05c5d3bbf61a459284c937fdd145c93aa6e940fff1d1a60',
};
export const protocol={warmupSeconds:120,measuredSeconds:300,pairs:5,heapBytes:389283840,
  p95BudgetMs:200,maxRegressionPercent:10,minImprovementPercent:20,maxRangePercent:10,
  expectedMinutes:[260,320],note:'Laptop relative comparison, not production capacity; do not change gates after measuring'};
export function readSchedule() {
  const result=[];
  for(let pair=1;pair<=5;pair++)for(const [viewport,rate,baseline] of [[1,50,'a0'],[10,16,'a1']]) {
    for(const side of pair%2?[baseline,'b']:['b',baseline])result.push({pair,viewport,rate,side,name:`pair-${pair}-v${viewport}-${side}`});
  }
  return result;
}
export function writeSchedule() {
  const result=[];
  for(let pair=1;pair<=5;pair++)for(const side of pair%2?['a1','b']:['b','a1'])result.push({pair,concurrency:1,side,name:`write-${pair}-c1-${side}`});
  for(const [i,concurrency] of [2,4,8,16].entries())for(const side of i%2?['b','a1']:['a1','b'])result.push({pair:1,concurrency,side,name:`write-1-c${concurrency}-${side}`,exploratory:true});
  return result;
}
export function fullViewportSchedule() {
  return Array.from({length:3},(_,i)=>({name:'full-viewport-'+(i+1),rate:16,viewport:100}));
}
export const writeRetestSchedule=()=>writeSchedule().filter(r=>r.concurrency===1);
export function writeSpec(name,concurrency) {
  assert.match(name||'',/^[a-z0-9-]+$/);concurrency=Number(concurrency);
  assert.ok([1,2,4,8,16].includes(concurrency),'Only approved write concurrency levels');
  return {name,concurrency,warmupCycles:10*concurrency,measuredCycles:30*concurrency};
}
export function evaluate(reads) {
  const median=values=>[...values].sort((a,b)=>a-b)[2];
  const result={};
  for(const viewport of [1,10]) {
    const baseline=viewport===1?'a0':'a1',select=side=>Array.from({length:5},(_,i)=>{
      const r=reads.find(r=>r.run===`pair-${i+1}-v${viewport}-${side}-1`);assert.ok(r,'Missing prescribed run');return r;
    });
    const a=select(baseline),b=select('b'),all=[...a,...b];
    const valid=all.every(r=>r.validity?.valid===true&&r.warmupValidity?.valid===true&&r.checks.fails===0&&r.dropped===0&&Object.keys(r.status).every(s=>s==='200')&&r.collectorErrors.length===0);
    const stats={};
    for(const metric of ['p(95)','p(99)']) {
      const av=a.map(r=>r.http[metric]),bv=b.map(r=>r.http[metric]);
      stats[metric]={a:av,b:bv,medianA:median(av),medianB:median(bv),regressionPercent:(median(bv)/median(av)-1)*100,
        pairedRegressionPercent:bv.map((value,i)=>(value/av[i]-1)*100),
        rangePercentA:(Math.max(...av)-Math.min(...av))/median(av)*100,rangePercentB:(Math.max(...bv)-Math.min(...bv))/median(bv)*100};
    }
    const stable=stats['p(95)'].rangePercentA<=10&&stats['p(95)'].rangePercentB<=10;
    const budget=all.every(r=>r.http['p(95)']<=200);
    const gate=viewport===1?stats['p(95)'].medianB<=stats['p(95)'].medianA*1.1&&stats['p(99)'].medianB<=stats['p(99)'].medianA*1.1:stats['p(95)'].medianB<=stats['p(95)'].medianA*.8;
    result[viewport]={baseline,valid,stable,budget,gate,passed:valid&&stable&&budget&&gate,stats};
  }
  return result;
}
