// Original SDK HTTP layer, loopback only. No FirebaseApp, credential or OAuth.
for(const k of Object.keys(process.env))if(/^(FIREBASE_|GOOGLE_|GCLOUD_)/.test(k))delete process.env[k];
const assert=require('node:assert/strict'),http=require('node:http'),net=require('node:net'),path=require('node:path');
const deny=()=>{throw Error('external transport blocked');};global.fetch=async()=>deny();require('node:https').request=deny;require('node:https').get=deny;require('node:http2').connect=deny;
const connect=net.Socket.prototype.connect;net.Socket.prototype.connect=function(...args){const a=Array.isArray(args[0])?args[0]:args;const o=typeof a[0]==='object'?a[0]:{host:a[1]};if(!['localhost','127.0.0.1','::1'].includes(o.host||'localhost'))deny();return connect.apply(this,args);};
const {HttpClient}=require(path.join(path.dirname(require.resolve('firebase-admin')),'utils/api-request.js'));
let variant,status,hits=0,times=[],header;
const server=http.createServer((req,res)=>{
  assert.equal(req.headers.authorization,undefined);hits++;times.push(performance.now());
  if(variant==='reset'){req.socket.destroy();return;}
  if(header)res.setHeader('Retry-After',header);
  const send=()=>{res.writeHead(status,{'Content-Type':'application/json'});res.end('{"ok":true}');};
  if(variant==='idle'){setTimeout(send,250);return;}
  if(variant==='drip'){res.writeHead(200,{'Content-Type':'application/json'});res.flushHeaders();const timer=setInterval(()=>res.write(' '),20);res.on('close',()=>clearInterval(timer));setTimeout(()=>{clearInterval(timer);res.end('{"ok":true}');},200);return;}
  send();
});
(async()=>{
  await new Promise(r=>server.listen(0,'127.0.0.1',r));const url='http://127.0.0.1:'+server.address().port+'/send';const rows=[];
  for(const spec of [{name:'503-real-backoff',status:503,real:true},{name:'503-retry-after-60',status:503,header:'60'},{name:'503-retry-after-61',status:503,header:'61'},{name:'429',status:429},{name:'500',status:500},{name:'reset',status:200},{name:'idle',status:200},{name:'drip',status:200}]){
    variant=spec.name;status=spec.status;header=spec.header;hits=0;times=[];const waits=[];
    class Probe extends HttpClient{waitForRetry(ms){waits.push(ms);return spec.real?super.waitForRetry(ms):Promise.resolve();}}
    let success=true;try{await new Probe().send({url,method:'POST',data:{probe:true},timeout:['idle','drip'].includes(variant)?60:15000});}catch{success=false;}
    const retry=['503-real-backoff','503-retry-after-60','reset','idle'].includes(variant);
    assert.equal(hits,retry?5:1);assert.equal(success,variant==='drip');
    assert.deepEqual(waits,retry?(header==='60'?[60000,60000,60000,60000]:[0,1000,2000,4000]):[]);
    if(spec.real){const gaps=times.slice(1).map((t,i)=>t-times[i]);for(let i=1;i<4;i++)assert(gaps[i]>=waits[i]-100);assert(times.at(-1)-times[0]>=6800);}
    rows.push({name:spec.name,httpRequests:hits,delaysMillis:waits,actualBackoffWaited:!!spec.real,success});
  }
  process.stdout.write(JSON.stringify({externalCalls:0,rows}));
})().catch(e=>process.stdout.write(JSON.stringify({error:String(e),stack:e.stack}))).finally(()=>server.close());
