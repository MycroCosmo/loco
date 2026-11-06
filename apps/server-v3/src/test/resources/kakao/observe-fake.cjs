// Observation only: never pass a real Kakao URL or key to a transport.
delete process.env.KAKAO_REST_API_KEY;
const http=require('node:http');
const nativeFetch=global.fetch;
const input=JSON.parse(process.argv[1]);
const dbUrl=new URL(process.env.DATABASE_URL);
dbUrl.username=process.env.DB_USERNAME;dbUrl.password=process.env.DB_PASSWORD;
process.env.DATABASE_URL=dbUrl.toString();
console.log=()=>{};console.warn=()=>{};
let variant='normal',seen=[],rewrites=0,blocked=0,app,fake;
const normal={meta:{total_count:2,is_end:false,pageable_count:45},documents:[{id:'p1',place_name:'Cafe',address_name:'address',road_address_name:'road',phone:'010',category_name:'food',x:'127.25',y:'37.5',place_url:'https://example.invalid/p1'},{x:'bad',y:'1.25x'}]};
const listen=server=>new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
function call(url,token){return new Promise((resolve,reject)=>{const req=http.get(url,{headers:token?{Authorization:'Bearer '+token}:{}},res=>{let text='';res.on('data',d=>text+=d);res.on('end',()=>{try{resolve({status:res.statusCode,body:JSON.parse(text)})}catch(e){reject(e)}})});req.on('error',reject);req.setTimeout(3000,()=>req.destroy(new Error('observation watchdog')));});}
(async()=>{
  fake=http.createServer((req,res)=>{
    const url=new URL(req.url,'http://localhost');
    seen.push({path:url.pathname,query:Object.fromEntries(url.searchParams),authorizationPresent:!!req.headers.authorization});
    if(variant==='connection-failure'){req.socket.destroy();return;}
    if(variant==='400'||variant==='401'||variant==='429'||variant==='500'||variant==='503'){res.writeHead(Number(variant),{'Content-Type':'text/plain'});res.end('fake upstream '+variant+'\nbody detail');return;}
    if(variant==='error-body-drop'){res.writeHead(503,{'Content-Length':'1000'});res.flushHeaders();res.write('partial');setTimeout(()=>res.destroy(),30);return;}
    if(variant==='invalid-json'){res.end('{broken');return;}
    if(variant==='204'){res.writeHead(204);res.end();return;}
    let body=variant==='missing-meta'?{documents:[]}:variant==='missing-documents'?{meta:{}}:variant==='empty-meta'?{meta:{},documents:[]}:variant==='wrong-types'?{meta:{total_count:'two',is_end:'yes'},documents:[{id:7,place_name:false,x:null,y:'Infinity'}]}:normal;
    const send=()=>{res.setHeader('Content-Type','application/json');res.end(JSON.stringify(body));};
    if(variant==='delay')setTimeout(send,250);else send();
  });
  await listen(fake);const fakeOrigin='http://127.0.0.1:'+fake.address().port;
  global.fetch=async(url,options={})=>{
    const target=new URL(typeof url==='string'?url:url.url);
    if(target.origin!=='https://dapi.kakao.com'||target.pathname!=='/v2/local/search/keyword.json'){blocked++;throw new Error('blocked non-fixture fetch');}
    rewrites++;
    // No real/dummy API key is used; original Authorization never leaves this shim.
    return nativeFetch(fakeOrigin+target.pathname+target.search,{method:'GET',redirect:'error'});
  };
  const {NestFactory}=require('@nestjs/core');const {ValidationPipe}=require('@nestjs/common');
  const {AppModule}=require('./dist/app.module');
  app=await NestFactory.create(AppModule,{logger:false});app.setGlobalPrefix('api');app.useGlobalPipes(new ValidationPipe({whitelist:true,transform:true,forbidNonWhitelisted:true}));await app.listen(0,'127.0.0.1');
  const origin=await app.getUrl();const token=require('jsonwebtoken').sign({sub:input.user,email:'ignored@example.test'},process.env.JWT_SECRET,{algorithm:'HS256',expiresIn:'7d'});
  const rows=[];
  async function observe(name,query,kind='normal',auth=true){variant=kind;seen=[];const start=Date.now();const result=await call(origin+'/api/places/search?'+query,auth?token:null);if(kind==='delay'&&Date.now()-start<200)throw new Error('delay was not observed');rows.push({name,query,variant:kind,authenticated:auth,...result,upstream:seen,...(kind==='delay'?{delayMs:250,completed:true}:{})});}
  await observe('no-token','query=cafe','normal',false);
  for(const query of ['', 'query=', 'query=%20%20', 'query=cafe', 'query=a&query=b','query%5Bx%5D=a','query=cafe&unknown=x','query=cafe&x=&y=&radius=&page=&size='])await observe('input:'+query,query);
  for(const value of ['0','-0','%2B0','1','-1','1.5','-0.5','.5','1.','1e2','0x10','NaN','Infinity','%20','%201%20','1x','1&x=2'])await observe('x:'+value,'query=cafe&x='+value);
  for(const key of ['radius','page','size'])for(const value of ['0','-1','1.5','-0.5','100000'])await observe(key+':'+value,'query=cafe&'+key+'='+value);
  await observe('all-options','query=%20coffee%20&x=127.25&y=37.5&radius=1.9&page=2.9&size=3.9');
  for(const kind of ['400','401','429','500','503','connection-failure','delay','invalid-json','204','missing-meta','missing-documents','empty-meta','wrong-types','error-body-drop'])await observe('upstream:'+kind,'query=cafe',kind);
  if(blocked!==0||rows.some(r=>r.upstream.some(u=>u.authorizationPresent)))throw new Error('unexpected outbound behavior');
  process.stdout.write(JSON.stringify({transport:{realKakaoCalls:0,apiKeyUsed:false,rewrites,blocked},rows}));
})().catch(e=>{process.stdout.write(JSON.stringify({error:String(e)}));process.exitCode=0}).finally(async()=>{if(app)await app.close();if(fake)await new Promise(r=>fake.close(r));});
