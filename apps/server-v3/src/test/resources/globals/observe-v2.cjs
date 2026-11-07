// Actual HTTP against unmodified dist/main.js; no middleware replacement.
const http = require('node:http');
const fs = require('node:fs');
const zlib = require('node:zlib');
const { spawn } = require('node:child_process');
const jwt = require('jsonwebtoken');
const token = jwt.sign({sub:'11111111-1111-4111-8111-111111111111',email:'compat@example.test'}, process.env.JWT_SECRET, {algorithm:'HS256',expiresIn:600});
const variableHeaders = ['date','etag','last-modified','x-ratelimit-remaining','x-ratelimit-reset','x-harness-handler','x-harness-route','x-harness-image-nanos'];
const file = '/app/uploads/__global.txt';
const bytes = Buffer.from('global-fixture\n');
fs.mkdirSync('/app/uploads', {recursive:true});
fs.writeFileSync(file, bytes);
fs.utimesSync(file, new Date('2020-01-01Z'), new Date('2020-01-01Z'));
function bodyOf(r) {
  if (r.body === undefined) return undefined;
  const b=r.body;
  let text=b.text;
  if(b.kind==='json-size') text='{"padding":"'+'a'.repeat(b.bytes-14)+'"}';
  if(b.kind==='json-utf8') { const n=b.bytes-14; text='{"padding":"'+'한'.repeat(Math.floor(n/3))+'a'.repeat(n%3)+'"}'; }
  if(b.kind==='form-size') text='padding='+'a'.repeat(b.bytes-8);
  let result=Buffer.from(text, b.encoding||'utf8');
  if (b.compression==='gzip') result=zlib.gzipSync(result);
  if (b.compression==='deflate') result=zlib.deflateSync(result);
  return result;
}
async function request(r,port=3000) {
 const body=bodyOf(r), headers={...r.headers};
 if(headers.Authorization==='$valid') headers.Authorization='Bearer '+token;
 if(body!==undefined&&!r.chunked) headers['Content-Length']=body.length;
 return await new Promise((resolve,reject)=>{
  const req=http.request({host:'127.0.0.1',port,path:r.path,method:r.method,headers,agent:false},res=>{
   const data=[];res.on('data',b=>data.push(b));res.on('end',()=>{
    const text=Buffer.concat(data).toString('utf8');let value=text;try{value=JSON.parse(text);}catch{}
    const normalized={};for(const key of Object.keys(res.headers).sort())normalized[key]=variableHeaders.includes(key)?'$variable':res.headers[key];
    resolve({status:res.statusCode,headers:normalized,body:value});
   });
  });req.setTimeout(20000,()=>req.destroy(new Error('local HTTP timeout')));req.on('error',reject);if(body)req.write(body);req.end();
 });
}
async function main(){
 const cases=[];
 const origins={none:undefined,allowed:'http://localhost:8080',allowed3000:'http://localhost:3000',denied:'https://denied.example.test',null:'null'};
 const endpoints={ok:{method:'GET',path:'/api/users/me',auth:true},validation:{method:'PUT',path:'/api/users/me',auth:true,body:{text:'{"unknown":1}'}},unauthorized:{method:'GET',path:'/api/users/me'},missing:{method:'GET',path:'/api/__global-missing'},failure:{method:'POST',path:'/api/feedback',auth:true,body:{text:'{"content":"global failure fixture"}'}},upload:{method:'GET',path:'/uploads/__global.txt'},uploadMissing:{method:'GET',path:'/uploads/__global-missing.txt'}};
 for(const [originName,origin]of Object.entries(origins)){
  for(const [kind,ep]of Object.entries(endpoints)){
   const r={method:ep.method,path:ep.path,headers:{}};
   if(origin!==undefined)r.headers.Origin=origin;
   if(ep.auth)r.headers.Authorization='$valid';
   if(ep.body){r.body=ep.body;r.headers['Content-Type']='application/json';}
   cases.push({name:originName+'-'+kind,request:r,response:await request(r)});
  }
  for(const path of ['/api/users/me','/api/__global-missing','/uploads/__global.txt']){
   const r={method:'OPTIONS',path,headers:{'Access-Control-Request-Method':'PUT','Access-Control-Request-Headers':'content-type,authorization,x-extra'}};
   if(origin!==undefined)r.headers.Origin=origin;
   cases.push({name:originName+'-preflight-'+path,request:r,response:await request(r)});
  }
 }
 const extra=[
  {name:'bare-options',method:'OPTIONS',path:'/api/users/me',headers:{}},
  {name:'head-upload',method:'HEAD',path:'/uploads/__global.txt',headers:{Origin:origins.denied}},
  {name:'range-upload',method:'GET',path:'/uploads/__global.txt',headers:{Range:'bytes=0-5',Origin:origins.allowed}},
  {name:'not-modified-upload',method:'GET',path:'/uploads/__global.txt',headers:{'If-Modified-Since':'Wed, 01 Jan 2020 00:00:00 GMT',Origin:origins.allowed}},
  {name:'outside-prefix',method:'GET',path:'/__global-missing',headers:{Origin:origins.allowed}},
 ];
 for(const row of extra){const {name,...r}=row;cases.push({name,request:r,response:await request(r)});}
 for(const kind of ['json-size','json-utf8','form-size'])for(const delta of [-1,0,1]){
  const r={method:'POST',path:'/api/__global-missing',headers:{Origin:origins.allowed,'Content-Type':kind==='form-size'?'application/x-www-form-urlencoded':'application/json'},body:{kind,bytes:10*1024*1024+delta}};
  cases.push({name:kind+'-'+delta,request:r,response:await request(r)});
 }
 const bodies=[
  ['malformed-json','application/json',{text:'{"broken":'}],
  ['json-null','application/json',{text:'null'}],
  ['json-scalar','application/json',{text:'1'}],
  ['json-array','application/json',{text:'[]'}],
  ['json-empty','application/json',{text:''}],
  ['json-charset','application/json; charset=iso-8859-1',{text:'{}'}],
  ['form-empty','application/x-www-form-urlencoded',{text:''}],
  ['form-params-limit','application/x-www-form-urlencoded',{text:Array.from({length:1001},(_,i)=>'a'+i+'=x').join('&')}],
  ['gzip-limit','application/json',{kind:'json-size',bytes:10485761,compression:'gzip'}],
  ['deflate-json','application/json',{text:'{}',compression:'deflate'}],
 ];
 for(const [name,type,body]of bodies){
  const r={method:'POST',path:'/api/__global-missing',headers:{Origin:origins.allowed,'Content-Type':type},body};
  if(body.compression)r.headers['Content-Encoding']=body.compression;
  cases.push({name,request:r,response:await request(r)});
 }
 for(const kind of ['json-size','form-size']){
  const r={method:'POST',path:'/api/__global-missing',headers:{Origin:origins.allowed,'Content-Type':kind==='form-size'?'application/x-www-form-urlencoded':'application/json'},chunked:true,body:{kind,bytes:10485761}};
  cases.push({name:kind+'-chunked-over',request:r,response:await request(r)});
 }
 // Exercise configured/blank origin parsing through the original bootstrap in an isolated process.
 for(const [name,originsValue,origin]of [['configured-origin',' https://one.example.test, ,https://two.example.test ','https://two.example.test'],['configured-denied','https://one.example.test','http://localhost:8080'],['blank-origin',' , ','http://localhost:8080']]){
  const child=spawn('node',['start-v2.cjs'],{cwd:'/app',env:{...process.env,NODE_OPTIONS:'',ALLOWED_ORIGINS:originsValue,PORT:'39092',FIREBASE_SERVICE_ACCOUNT:'',KAKAO_REST_API_KEY:'',NAVER_CLIENT_ID:'',NAVER_CLIENT_SECRET:''},stdio:'ignore'});
  try{
   let ready=false;for(let n=0;n<200;n++){try{await request({method:'GET',path:'/api/__global-ready',headers:{}},39092);ready=true;break;}catch{await new Promise(r=>setTimeout(r,50));}}
   if(!ready)throw new Error('isolated original bootstrap not ready');
   const r={method:'OPTIONS',path:'/api/users/me',headers:{Origin:origin,'Access-Control-Request-Method':'GET'}};
   cases.push({name,allowedOrigins:originsValue,request:r,response:await request(r,39092)});
  }finally{const stopped=new Promise(resolve=>child.once('exit',resolve));child.kill('SIGTERM');await stopped;}
 }
 return {variableHeaders,cases};
}
main().then(v=>console.log(JSON.stringify(v))).catch(e=>{console.log(JSON.stringify({error:e.message}));process.exitCode=1;}).finally(()=>fs.rmSync(file,{force:true}));
