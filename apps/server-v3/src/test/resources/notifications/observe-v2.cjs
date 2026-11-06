// Observation only. No service-account fields, ADC, Firebase transport or Google OAuth.
const assert = require('node:assert/strict');
const input = JSON.parse(process.argv[1]);
for (const name of Object.keys(process.env))
  if (/^(FIREBASE_|GOOGLE_|GCLOUD_)/.test(name)) delete process.env[name];
const dbUrl = new URL(process.env.DATABASE_URL);
dbUrl.username = process.env.DB_USERNAME; dbUrl.password = process.env.DB_PASSWORD;
process.env.DATABASE_URL = dbUrl.toString();
const http = require('node:http'), https = require('node:https'), net = require('node:net');
let blocked = 0;
function deny() { blocked++; throw new Error('observation blocked external transport'); }
const originalConnect = net.Socket.prototype.connect;
net.Socket.prototype.connect = function (...args) {
  const a = Array.isArray(args[0]) ? args[0] : args;
  const options = typeof a[0] === 'object' ? a[0] : {port: a[0], host: a[1]};
  const host = options.host || 'localhost';
  if (!['127.0.0.1', '::1', 'localhost'].includes(host)
      && !(host === dbUrl.hostname && Number(options.port) === Number(dbUrl.port || 5432))) return deny();
  return originalConnect.apply(this, args);
};
https.request = deny; https.get = deny;
require('node:http2').connect = deny;
global.fetch = async () => deny();
console.log = () => {}; console.warn = () => {}; console.error = () => {};
const realAdmin = require('firebase-admin');
require('@nestjs/common').Logger.overrideLogger(false);
let mode = 'enabled', sent = [], pending = [], behavior = 'success', initialized = 0;
const appMarker = {name: 'offline-observation'};
const fakeAdmin = {
  apps: [],
  credential: {cert: value => mode === 'enabled' ? {} : realAdmin.credential.cert(value)},
  initializeApp: () => { initialized++; return appMarker; },
  messaging: () => ({send: async message => {
    sent.push(JSON.parse(JSON.stringify(message)));
    if (behavior === 'hold') return new Promise((resolve, reject) => pending.push({resolve, reject}));
    if (behavior !== 'success') throw Object.assign(new Error('fake send failure'), {code: behavior});
    return 'offline-message';
  }})
};
const Module = require('node:module'), originalLoad = Module._load;
Module._load = function (name, ...rest) {
  return name === 'firebase-admin' ? fakeAdmin : originalLoad.call(this, name, ...rest);
};
const {NotificationsService} = require('./dist/notifications/notifications.service');
const {TokenCleanupService} = require('./dist/tasks/token-cleanup.service');
const {PrismaClient} = require('@prisma/client');
const db = new PrismaClient({log: [{emit: 'event', level: 'query'}]});
const reader = new PrismaClient();
let queries = [];
db.$on('query', e => queries.push(e.query));
const user = n => n.toString(16).repeat(8)+'-'+n.toString(16).repeat(4)+'-4'+n.toString(16).repeat(3)+'-8'+n.toString(16).repeat(3)+'-'+n.toString(16).repeat(12);
const map = n => 'aaaaaaaa-aaaa-4aaa-8aaa-'+String(n).padStart(12,'0');
const payload = {title: '관측 제목', body: '관측 내용', data: {type: 'probe', mapId: map(1)}};
const rows = [];
const pause = () => new Promise(r => setTimeout(r, 5));
async function until(test) { const end = Date.now()+4000; while (!test()) {if(Date.now()>end)throw Error('observation watchdog');await pause();} }
function enabled(client = db) { mode='enabled';process.env.FIREBASE_SERVICE_ACCOUNT='{}';return new NotificationsService(client); }
async function token(n, value) { await db.user.update({where:{id:user(n)},data:{fcmToken:value}}); }
const selectCount = () => queries.filter(q => /^SELECT\b/.test(q)).length;
function reset() { sent=[];pending=[];queries=[];behavior='success'; }
async function serviceCases() {
  for (const raw of [undefined, '', '{broken', '{}', 'null']) {
    mode='actual-invalid';
    if(raw===undefined)delete process.env.FIREBASE_SERVICE_ACCOUNT;else process.env.FIREBASE_SERVICE_ACCOUNT=raw;
    reset();const before=initialized;const service=new NotificationsService(db);
    await service.sendToUser(user(1),payload);await service.sendToMap(map(1),user(2),payload);
    assert.equal(selectCount(),0);assert.equal(sent.length,0);assert.equal(initialized,before);
    rows.push({name:'configuration:'+String(raw),selects:0,sends:0,disabled:true});
  }
  const service=enabled();
  for(const [name,id,value] of [['missing-user',user(9),null],['null-token',user(1),null],['empty-token',user(1),''],['token',user(1),'offline-token'],['deleted-user-token',user(4),'deleted-token']]) {
    if(id!==user(9))await db.user.update({where:{id},data:{fcmToken:value}});
    reset();await service.sendToUser(id,payload);
    assert.equal(selectCount(),1);assert.equal(sent.length,value?1:0);
    rows.push({name,selects:selectCount(),messages:sent});
  }
  for(const failure of ['messaging/internal-error','messaging/invalid-registration-token','messaging/registration-token-not-registered']) {
    await token(1,'old-token');reset();behavior=failure;
    await service.sendToUser(user(1),payload);
    const left=(await reader.user.findUnique({where:{id:user(1)}})).fcmToken;
    assert.equal(left,failure.endsWith('/registration-token-not-registered')?null:'old-token');
    rows.push({name:failure,sends:sent.length,remainingToken:left});
  }
  await token(1,'old-token');reset();behavior='hold';
  const delivery=service.sendToUser(user(1),payload);await until(()=>pending.length===1);
  await token(1,'new-token');pending[0].reject({code:'messaging/registration-token-not-registered',message:'fake'});
  await delivery;const overwritten=(await reader.user.findUnique({where:{id:user(1)}})).fcmToken;
  assert.equal(overwritten,null);rows.push({name:'replacement-token-race',sentToken:sent[0].token,remainingToken:overwritten});
  for(const n of [2,3,4])await db.mapMember.upsert({where:{mapId_userId:{mapId:map(1),userId:user(n)}},create:{mapId:map(1),userId:user(n)},update:{}});
  await token(2,'shared-token');await token(3,'shared-token');await token(4,'');
  reset();await service.sendToMap(map(1),user(1),payload);
  assert.equal(selectCount(),2);assert.equal(sent.length,2);
  rows.push({name:'map-3-recipients-2-duplicate-tokens',logicalUserReads:3,selects:selectCount(),messages:sent,sql:queries.filter(q=>/^SELECT\b/.test(q))});
  reset();behavior='hold';const parallel=service.sendToMap(map(1),user(1),payload);
  await until(()=>pending.length===2);rows.push({name:'map-concurrent-sends',pendingBeforeRelease:pending.length});
  pending.forEach(p=>p.resolve('offline'));await parallel;
  for(let n=5;n<=11;n++){
    await db.user.create({data:{id:user(n),email:'probe-'+n+'@example.test',nickname:'probe',provider:'LOCAL',fcmToken:'token-'+n}});
    await db.mapMember.create({data:{mapId:map(1),userId:user(n)}});
  }
  reset();await service.sendToMap(map(1),user(1),payload);assert.equal(selectCount(),2);assert.equal(sent.length,9);
  rows.push({name:'map-10-recipients',logicalUserReads:10,selects:selectCount(),sends:sent.length});
  reset();await service.sendToMap(map(4),user(1),payload);assert.equal(selectCount(),1);assert.equal(sent.length,0);
  rows.push({name:'empty-map',selects:selectCount(),sends:sent.length});
  for(const fail of ['user-read','token-clear','member-read']) {
    const rejected=async()=>{throw Error('injected db failure');};
    const stub={user:{findUnique:fail==='user-read'?rejected:async()=>({fcmToken:'offline'}),update:rejected},mapMember:{findMany:fail==='member-read'?rejected:async()=>[{userId:user(2)}]}};
    const s=enabled(stub);reset();if(fail==='token-clear')behavior='messaging/registration-token-not-registered';
    let direct='resolved',fanout='resolved';
    try{await s.sendToUser(user(2),payload);}catch{direct='rejected';}
    try{await s.sendToMap(map(1),user(1),payload);}catch{fanout='rejected';}
    assert.equal(fanout,fail==='member-read'?'rejected':'resolved');
    rows.push({name:'db-failure:'+fail,direct,fanout});
  }
}
function request(origin,path,body,jwt) {
  return new Promise((resolve,reject)=>{
    const req=http.request(origin+path,{method:'POST',headers:{Authorization:'Bearer '+jwt,'Content-Type':'application/json'}},res=>{
      let text='';res.on('data',d=>text+=d);res.on('end',()=>resolve({status:res.statusCode,body:JSON.parse(text)}));
    });req.on('error',reject);req.setTimeout(4000,()=>req.destroy(Error('HTTP waited for pending delivery')));req.end(JSON.stringify(body||{}));
  });
}
let nest;
async function callSites() {
  enabled();await token(1,'self-token');await token(2,'owner-token');
  const {NestFactory}=require('@nestjs/core'),{ValidationPipe}=require('@nestjs/common');
  nest=await NestFactory.create(require('./dist/app.module').AppModule,{logger:false});
  nest.setGlobalPrefix('api');nest.useGlobalPipes(new ValidationPipe({whitelist:true,transform:true,forbidNonWhitelisted:true}));await nest.listen(0,'127.0.0.1');
  const origin=await nest.getUrl(),jwt=require('jsonwebtoken').sign({sub:user(1),email:'ignored@example.test'},process.env.JWT_SECRET,{algorithm:'HS256',expiresIn:'7d'});
  const cases=[['public-join','/api/maps/'+map(4)+'/join',{},map(4)],['repeat-public-join','/api/maps/'+map(4)+'/join',{},map(4)],['private-code','/api/maps/join-by-code',{inviteCode:' invite03 '},map(3)],['owner-joins-self','/api/maps/'+map(1)+'/join',{},map(1)]];
  for(const [name,path,body,mapId] of cases){
    reset();behavior='hold';const response=await request(origin,path,body,jwt);assert.equal(response.status,201);
    await until(()=>pending.length===1);
    const committed=await reader.mapMember.count({where:{mapId,userId:user(1)}});assert.equal(committed,1);
    rows.push({name,status:response.status,deliveryPendingAtResponse:true,committedMembers:committed,messages:sent});
    pending.forEach(p=>p.resolve('offline'));await pause();
  }
  await db.mapMember.upsert({where:{mapId_userId:{mapId:map(1),userId:user(2)}},create:{mapId:map(1),userId:user(2)},update:{}});
  reset();behavior='hold';const response=await request(origin,'/api/maps/'+map(1)+'/places',{name:'notification-place',address:'Seoul',latitude:37.4444,longitude:127.8888},jwt);
  assert.equal(response.status,201);await until(()=>pending.length===1);
  assert.equal(await reader.place.count({where:{id:response.body.id}}),1);
  const cells=await reader.placeMarkerCell.count({where:{mapId:map(1)}});assert(cells>0);
  assert.equal(sent[0].data.placeId,response.body.id);sent[0].data.placeId='$createdPlace';
  rows.push({name:'place-created',status:201,deliveryPendingAtResponse:true,committedPlace:true,committedCells:cells,messages:sent});
  pending.forEach(p=>p.resolve('offline'));await pause();
  await db.$executeRawUnsafe("CREATE FUNCTION notification_probe_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END; $$");
  await db.$executeRawUnsafe('CREATE TRIGGER notification_probe_fail BEFORE INSERT ON place_marker_cells FOR EACH ROW EXECUTE FUNCTION notification_probe_fail()');
  try {
    reset();const failed=await request(origin,'/api/maps/'+map(1)+'/places',{name:'partial-place',address:'Seoul',latitude:38,longitude:128},jwt);
    assert.equal(failed.status,500);assert.equal(sent.length,0);
    const committed=await reader.place.count({where:{name:'partial-place'}});assert.equal(committed,1);
    rows.push({name:'marker-failure-after-place-commit',status:500,committedPlaces:committed,sends:0});
  } finally {
    await db.$executeRawUnsafe('DROP TRIGGER notification_probe_fail ON place_marker_cells');await db.$executeRawUnsafe('DROP FUNCTION notification_probe_fail()');
  }
}
async function cleanupCases() {
  const RealDate=Date,now=RealDate.parse('2026-11-06T12:00:00.000Z'),day=86400000;
  const at=offset=>new RealDate(now+offset);
  const refresh=[['old-minus',-30*day-1,null],['old-equal',-30*day,null],['old-plus',-30*day+1,null],['expired-recent',-1,null],['future',day,null],['revoked-future',day,at(0)]];
  const reset=[['expired-minus',-1,null],['expired-equal',0,null],['future',1,null],['used-future',day,at(0)]];
  async function seed(){
    await db.refreshToken.deleteMany();await db.passwordResetToken.deleteMany();
    for(const [id,offset,revokedAt]of refresh)await db.refreshToken.create({data:{id:'probe-'+id,userId:user(1),tokenHash:'refresh-'+id,expiresAt:at(offset),revokedAt}});
    for(const [id,offset,usedAt]of reset)await db.passwordResetToken.create({data:{id:'probe-'+id,userId:user(1),tokenHash:'reset-'+id,expiresAt:at(offset),usedAt}});
  }
  const fixed=async action=>{global.Date=class extends RealDate{constructor(...args){super(...(args.length?args:[now]));}static now(){return now;}};try{return await action();}finally{global.Date=RealDate;}};
  await seed();const cleanup=new TokenCleanupService(db);await fixed(()=>cleanup.cleanupExpiredTokens());
  const remainingRefresh=(await db.refreshToken.findMany({orderBy:{id:'asc'},select:{id:true}})).map(r=>r.id);
  const remainingReset=(await db.passwordResetToken.findMany({orderBy:{id:'asc'},select:{id:true}})).map(r=>r.id);
  assert.deepEqual(remainingRefresh,['probe-expired-recent','probe-future','probe-old-equal','probe-old-plus']);
  assert.deepEqual(remainingReset,['probe-expired-equal','probe-future']);
  rows.push({name:'cleanup-boundaries',now:new RealDate(now).toISOString(),refreshBefore:6,refreshAfter:remainingRefresh,resetBefore:4,resetAfter:remainingReset});
  await fixed(()=>cleanup.cleanupExpiredTokens());assert.equal(await db.refreshToken.count(),4);assert.equal(await db.passwordResetToken.count(),2);
  rows.push({name:'repeated-cleanup',refresh:4,reset:2});
  for(const failedTable of ['refresh_tokens','password_reset_tokens']){
    await seed();await db.$executeRawUnsafe("CREATE FUNCTION token_probe_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END; $$");
    await db.$executeRawUnsafe('CREATE TRIGGER token_probe_fail BEFORE DELETE ON '+failedTable+' FOR EACH ROW EXECUTE FUNCTION token_probe_fail()');
    try{
      await assert.rejects(fixed(()=>cleanup.cleanupExpiredTokens()));
      const survivor=failedTable==='refresh_tokens'?db.passwordResetToken:db.refreshToken;
      const target=failedTable==='refresh_tokens'?2:4;
      const end=Date.now()+4000;while(await survivor.count()!==target){if(Date.now()>end)throw Error('parallel cleanup not committed');await pause();}
      rows.push({name:'delete-failure:'+failedTable,rejected:true,refresh:await db.refreshToken.count(),reset:await db.passwordResetToken.count()});
    }finally{await db.$executeRawUnsafe('DROP TRIGGER token_probe_fail ON '+failedTable);await db.$executeRawUnsafe('DROP FUNCTION token_probe_fail()');}
  }
  const method=TokenCleanupService.prototype.cleanupExpiredTokens;
  const metadata=Reflect.getMetadata('SCHEDULE_CRON_OPTIONS',method);assert.equal(metadata.cronTime,'0 3 * * *');assert.equal(metadata.timeZone,undefined);
  const {CronTime}=require('cron');
  for(const tz of ['UTC','Asia/Seoul']){
    process.env.TZ=tz;const cron=new CronTime(metadata.cronTime);
    const next=cron.getNextDateFrom(new RealDate('2026-11-06T00:00:00Z')).toUTC().toISO();
    assert.equal(next,tz==='UTC'?'2026-11-06T03:00:00.000Z':'2026-11-06T18:00:00.000Z');
    rows.push({name:'schedule:'+tz,cron:metadata.cronTime,explicitTimeZone:false,nextUtc:next});
  }
}
(async()=>{
  await assert.rejects(global.fetch('https://example.invalid'));
  assert.throws(()=>https.get('https://example.invalid'));
  assert.throws(()=>net.connect({host:'example.invalid',port:443}));
  assert.equal(blocked,3);
  await db.$connect();await reader.$connect();
  if(input.mode==='service')await serviceCases();else if(input.mode==='call-sites')await callSites();else if(input.mode==='cleanup')await cleanupCases();else throw Error('unknown observation mode');
  assert.equal(blocked,3);
  process.stdout.write(JSON.stringify({transport:{blockedProbes:3,unexpectedAttempts:blocked-3,credentialMode:'no service-account values; empty object only in SDK stub',firebaseNetwork:false},rows}));
})().catch(e=>process.stdout.write(JSON.stringify({error:String(e),stack:e.stack}))).finally(async()=>{pending.forEach(p=>p.resolve('offline'));if(nest)await nest.close();await db.$disconnect();await reader.$disconnect();});
