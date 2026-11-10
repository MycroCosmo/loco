import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync,spawn} from 'node:child_process';
import {randomBytes,createHmac} from 'node:crypto';
import http from 'node:http';
import assert from 'node:assert/strict';
import {key,hash,datasetSql,markerPath,canonical,sqlString,bindObservedSql,measurementSpec,stageSettings,pairedThresholdFailure} from './data.mjs';
import {overflowMatches} from './overflow-contract.js';
import {bindTemplate,javaSqlTemplate} from './sql-replay.mjs';
import {writeSpec} from './p2-protocol.mjs';
import {assessInterference,waitForHostCoverage} from './interference.mjs';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const settings=stageSettings();
const project=settings.project;
const output=path.join(root,settings.output);
const privateDir=path.join(output,'private');
fs.mkdirSync(privateDir,{recursive:true}); fs.mkdirSync(path.join(output,'runtime'),{recursive:true});
const secretsFile=path.join(privateDir,'secrets.json');
if(!fs.existsSync(secretsFile)) fs.writeFileSync(secretsFile,JSON.stringify({db:randomBytes(24).toString('hex'),jwt:randomBytes(48).toString('hex'),password:randomBytes(24).toString('hex'),nextPassword:randomBytes(24).toString('hex')}));
const secrets=JSON.parse(fs.readFileSync(secretsFile));
const env={...process.env,PERF_DB_PASSWORD:secrets.db,PERF_JWT_SECRET:secrets.jwt,PERF_OUTPUT_DIR:output.replaceAll('\\','/'),PERF_APP_IMAGE:settings.image};
const compose=['compose','-p',project,'-f','perf/stage2/compose.yml'];
const write=(name,value)=>fs.writeFileSync(path.join(output,name),JSON.stringify(value,null,2));
function command(executable,args,input) {
  const r=spawnSync(executable,args,{cwd:root,env,input,encoding:'utf8',maxBuffer:64*1024*1024,windowsHide:true});
  if(r.status!==0) throw Error(`${executable} failed (${r.status}): ${(r.stderr||r.stdout||'').slice(-2500)}`);
  return r.stdout.trim();
}
const dc=(...args)=>command('docker',[...compose,...args]);
function sql(text) { return command('docker',[...compose,'exec','-T','perf-db','psql','-U','perf','-d','loco_perf','-X','-q','-A','-t','-v','ON_ERROR_STOP=1'],text); }
function query(text) {return JSON.parse(sql(`SELECT coalesce(json_agg(t),'[]'::json) FROM (${text}) t;`));}
function token() {
  const encode=o=>Buffer.from(JSON.stringify(o)).toString('base64url');
  const content=encode({alg:'HS256',typ:'JWT'})+'.'+encode({sub:key('user',1),email:'perf-1@example.invalid',iat:Math.floor(Date.now()/1000),exp:Math.floor(Date.now()/1000)+86400});
  return content+'.'+createHmac('sha256',secrets.jwt).update(content).digest('base64url');
}
let credentials={jwt:token(),cookie:''};
if(fs.existsSync(path.join(privateDir,'credentials.json'))) credentials={...JSON.parse(fs.readFileSync(path.join(privateDir,'credentials.json'))),jwt:token()};
const saveCredentials=()=>fs.writeFileSync(path.join(privateDir,'credentials.json'),JSON.stringify(credentials));
saveCredentials();
let cookies=new Map();
let appContainer=`${project}-perf-app-1`;
async function request(url,method='GET',body,admin=false) {
  const headers=admin?{Cookie:[...cookies].map(([k,v])=>k+'='+v).join('; '),'X-XSRF-TOKEN':decodeURIComponent(cookies.get('XSRF-TOKEN')||'')}:{Authorization:'Bearer '+credentials.jwt};
  if(body) headers['Content-Type']='application/json';
  // Setup/diagnostics use docker exec, so the isolated network needs no published port.
  // Headers and passwords go over stdin, never process arguments or command logs.
  const config=[`url = ${JSON.stringify('http://localhost:8081'+url)}`,`request = ${JSON.stringify(method)}`,...Object.entries(headers).map(([k,v])=>`header = ${JSON.stringify(k+': '+v)}`),...(body?[`data = ${JSON.stringify(JSON.stringify(body))}`]:[])].join('\n');
  const raw=command('docker',['exec','-i',appContainer,'curl','--silent','--show-error','--max-time','30','--include','--write-out','\n%{http_code}','--config','-'],config);
  const last=raw.lastIndexOf('\n'), status=Number(raw.slice(last+1)), response=raw.slice(0,last), split=response.indexOf('\r\n\r\n');
  const lines=response.slice(0,split).split('\r\n');
  if(admin) for(const item of lines.filter(l=>/^set-cookie:/i.test(l)).map(l=>l.slice(l.indexOf(':')+1).trim())) {const [kv]=item.split(';');const i=kv.indexOf('=');cookies.set(kv.slice(0,i),kv.slice(i+1));}
  const text=response.slice(split+4);
  if(status>=400) throw Error(`${method} ${url}: ${status} ${text.slice(0,150)}`);
  return {status,body:text?JSON.parse(text):null};
}
async function login(password) {
  cookies=new Map();
  await request('/admin/api/core/auth/csrf','GET',null,true);
  await request('/admin/api/core/auth/login','POST',{loginId:'perf-admin',password},true);
  credentials.cookie=[...cookies].map(([k,v])=>k+'='+v).join('; ');saveCredentials();
}
async function ready() {
  for(let i=0;i<120;i++) {
    try {await request('/api/health'); return;} catch{}
    await new Promise(r=>setTimeout(r,1000));
  }
  throw Error('Perf app did not become healthy');
}
function fingerprint() {
  const result={};
  const tables=query("SELECT schemaname||'.'||tablename AS name FROM pg_tables WHERE schemaname IN ('public','cms') AND tablename NOT IN ('flyway_schema_history','spatial_ref_sys') ORDER BY 1").map(r=>r.name);
  for(const table of tables) {
    assert.match(table,/^(public|cms)\.[a-z_]+$/);
    result[table]=query(`SELECT count(*) AS rows,md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY md5(row_to_json(t)::text))) AS hash FROM ${table} t`)[0];
  }
  return result;
}
function environment() {
  const ps=`Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public class PerfEnv { [DllImport("powrprof.dll")] public static extern uint PowerGetEffectiveOverlayScheme(out Guid g); [StructLayout(LayoutKind.Sequential)] public struct Status { public byte ACLineStatus,BatteryFlag,BatteryLifePercent,SystemStatusFlag; public uint BatteryLifeTime,BatteryFullLifeTime; } [DllImport("kernel32.dll")] public static extern bool GetSystemPowerStatus(out Status s); }'; $g=[guid]::Empty; $r=[PerfEnv]::PowerGetEffectiveOverlayScheme([ref]$g); $s=New-Object PerfEnv+Status; $ok=[PerfEnv]::GetSystemPowerStatus([ref]$s); @{cpu=(Get-CimInstance Win32_Processor|Select-Object Name,NumberOfCores,NumberOfLogicalProcessors); memory=(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory; overlay=$g.ToString(); ac=$s.ACLineStatus; powerRead=$ok; wslConfigExists=(Test-Path "$env:USERPROFILE/.wslconfig")} | ConvertTo-Json -Depth 4`;
  const power=JSON.parse(command('pwsh',['-NoProfile','-Command',ps]));
  assert.equal(power.ac,1,'AC connection required'); assert.equal(power.overlay.toLowerCase(),'ded574b5-45a0-4f42-8737-46345c09c238','Best performance overlay required');
  const docker=JSON.parse(command('docker',['info','--format','{{json .}}']));
  const containers=command('docker',['ps','--no-trunc','--format','json']).split('\n').filter(Boolean).map(s=>JSON.parse(s));
  const processFile=path.join(output,'process-snapshot.jsonl');
  command('pwsh',['-NoProfile','-File','perf/stage2/host-sample.ps1','-OutputFile',processFile,'-Seconds','6']);
  const hostProcesses=JSON.parse(fs.readFileSync(processFile,'utf8').trim().split('\n').at(-1));
  assert.ok(hostProcesses.processCount>=10,'Host processes are sandbox-hidden; use the authorized host execution environment');
  const background=containers.filter(c=>!c.Names.startsWith(project));
  return {at:new Date().toISOString(),power,hostProcesses,docker:{version:docker.ServerVersion,cpus:docker.NCPU,memory:docker.MemTotal,os:docker.OperatingSystem},hybridCoreCaveat:'i9-13900HX P/E hybrid, no CPU pinning; laptop relative comparison, not production absolute figures',background:background.filter(c=>c.State!=='paused').map(c=>({alias:'background-'+hash(c.ID).slice(0,8),state:c.State})),pausedBackground:background.filter(c=>c.State==='paused').map(c=>({alias:'background-'+hash(c.ID).slice(0,8),state:c.State,note:'Memory retained; user-authorized pause only'}))};
}
function snapshot(name) {
  write(name,{environment:environment(),git:command('git',['rev-parse','HEAD']),dirty:command('git',['status','--porcelain']),jarSha256:command('docker',['exec',appContainer,'sha256sum','/app/app.jar']).split(/\s+/)[0],runtime:JSON.parse(fs.readFileSync(path.join(output,'runtime/runtime.json'))),sqlTrackingAtSnapshot:sql('SHOW pg_stat_statements.track;'),data:fingerprint(),images:query('SELECT extname FROM pg_extension'),containers:JSON.parse(command('docker',['inspect',...dc('ps','-q').split(/\s+/)])).map(c=>({id:c.Id,name:c.Name,image:c.Image,cpu:c.HostConfig.NanoCpus,memory:c.HostConfig.Memory,oomKilled:c.State.OOMKilled,network:Object.keys(c.NetworkSettings.Networks)}))});
}
const socketHost=command('docker',['context','inspect','--format','{{.Endpoints.docker.Host}}']);
const socketPath=socketHost.replace('npipe:////./pipe/','\\\\.\\pipe\\');
function dockerGet(url) {
  return new Promise((resolve,reject)=>{
    const req=http.get({socketPath,path:url,timeout:5000},res=>{let body='';res.on('data',s=>body+=s);res.on('end',()=>{try {if(res.statusCode!==200) throw Error('Docker stats '+res.statusCode);resolve(JSON.parse(body));}catch(e){reject(e);}});});
    req.on('error',reject);req.on('timeout',()=>req.destroy(Error('Docker stats timeout')));
  });
}
async function observe(stream,signal) {
  let previous=new Map();
  while(!signal.aborted) {
    const start=Date.now();
    try {
      const containers=await dockerGet('/containers/json');
      const stats=await Promise.all(containers.map(async c=>{
        if(c.State==='paused')return {name:'background-'+hash(c.Id).slice(0,8),paused:true,state:'paused'};
        const s=await dockerGet(`/containers/${c.Id}/stats?stream=false&one-shot=true`),p=previous.get(c.Id);
        previous.set(c.Id,s);
        const cpu=p?(s.cpu_stats.cpu_usage.total_usage-p.cpu_stats.cpu_usage.total_usage)/(s.cpu_stats.system_cpu_usage-p.cpu_stats.system_cpu_usage)*(s.cpu_stats.online_cpus||32):null;
        return {name:c.Names[0].startsWith('/'+project)?c.Names[0]:'background-'+hash(c.Id).slice(0,8),cpuCores:cpu,rss:s.memory_stats.stats?.anon??s.memory_stats.stats?.rss,memory:s.memory_stats.usage,workingSet:s.memory_stats.usage-(s.memory_stats.stats?.inactive_file||0),limit:s.memory_stats.limit,throttle:s.cpu_stats.throttling_data,io:s.blkio_stats,network:s.networks,pids:s.pids_stats.current};
      }));
      stream.write(JSON.stringify({at:new Date().toISOString(),stats})+'\n');
    } catch(e) {stream.write(JSON.stringify({at:new Date().toISOString(),error:e.message})+'\n');}
    await new Promise(r=>setTimeout(r,Math.max(0,1000-(Date.now()-start))));
  }
}
async function runLoad(config) {
  assert.equal(sql('SHOW pg_stat_statements.track;'),config.diagnostic?'all':'none','SQL tracing must be off except explicitly separate diagnostics');
  const folder=path.join(output,config.run);
  assert.ok(!fs.existsSync(folder),'Result directory already exists; choose a new run name');
  fs.mkdirSync(folder,{recursive:true});
  write(`${config.run}/config.json`,config);
  const log=fs.openSync(path.join(folder,'k6.log'),'w');
  const hostLog=fs.openSync(path.join(folder,'host-collector.log'),'w');
  const host=spawn('pwsh',['-NoProfile','-File','perf/stage2/host-sample.ps1','-OutputFile',path.join(folder,'host.jsonl'),'-Seconds',String(config.seconds+30)],{cwd:root,stdio:['ignore',hostLog,hostLog],windowsHide:true});
  const readHost=()=>{
    const file=path.join(folder,'host.jsonl');if(!fs.existsSync(file))return [];
    const content=fs.readFileSync(file,'utf8');
    return content.slice(0,content.lastIndexOf('\n')+1).split('\n').filter(Boolean).map(JSON.parse);
  };
  const hostAlive=()=>host.exitCode===null&&host.signalCode===null;
  const stopHost=async()=>{
    if(hostAlive()){const done=new Promise(resolve=>host.once('exit',resolve));host.kill();await done;}
    fs.closeSync(hostLog);
  };
  if(config.writeCycles) {
    try {await waitForHostCoverage(readHost,{alive:hostAlive});}
    catch(e){await stopHost();fs.closeSync(log);throw e;}
  }
  const samples=fs.createWriteStream(path.join(folder,'resources.jsonl'));
  const abort=new AbortController(), observing=observe(samples,abort.signal);
  const child=spawn('docker',[...compose,'run','--rm','-T','--name',`${project}-load`,'-e',`CONFIG=/results/${config.run}/config.json`,'load','run','--quiet','--no-usage-report','--out',`json=/results/${config.run}/raw.json.gz`,'/scripts/load.js'],{cwd:root,env,stdio:['ignore',log,log],windowsHide:true});
  const start=Date.now();
  const progress=setInterval(()=>console.log(`${config.run}: ${Math.round((Date.now()-start)/1000)}/${config.seconds}s`),60000);
  const code=await new Promise((resolve,reject)=>{child.on('exit',resolve);child.on('error',reject);});
  const workloadEndedAt=Date.now();
  clearInterval(progress);abort.abort();await observing;samples.end();fs.closeSync(log);
  try {if(config.writeCycles)await waitForHostCoverage(readHost,{after:workloadEndedAt,alive:hostAlive});}
  finally {await stopHost();}
  const readLines=file=>fs.readFileSync(path.join(folder,file),'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
  const validity=assessInterference(readLines('host.jsonl'),readLines('resources.jsonl'));
  write(config.run+'/validity.json',validity);
  if(!validity.valid)console.log('INVALID interference: '+validity.reasons.join(', ')+'; original run retained, no automatic retry');
  const result={run:config.run,exitCode:code,elapsedSeconds:(Date.now()-start)/1000,
    ...(config.writeCycles?{workloadStartedAt:new Date(start).toISOString(),workloadEndedAt:new Date(workloadEndedAt).toISOString(),workloadElapsedSeconds:(workloadEndedAt-start)/1000,hostObservationBracketsWorkload:true}:{})};write(`${config.run}/execution.json`,result);
  console.log(JSON.stringify(result));
  if(code!==0) throw Error('k6 failed; raw results retained at '+folder);
  if(settings.screening&&!validity.valid) throw Error('P3 screening invalid; stop without replacement');
}
async function observedCellPlan(name,bound) {
  const folder=path.join(output,name);assert.ok(!fs.existsSync(folder),'Keep every plan observation');fs.mkdirSync(folder);
  const hostFile=path.join(folder,'host.jsonl'),log=fs.openSync(path.join(folder,'host-collector.log'),'w');
  const host=spawn('pwsh',['-NoProfile','-File','perf/stage2/host-sample.ps1','-OutputFile',hostFile,'-Seconds','120'],{cwd:root,stdio:['ignore',log,log],windowsHide:true});
  const alive=()=>host.exitCode===null&&host.signalCode===null;
  const lines=file=>{if(!fs.existsSync(file))return [];const content=fs.readFileSync(file,'utf8');return content.slice(0,content.lastIndexOf('\n')+1).split('\n').filter(Boolean).map(JSON.parse);};
  const resources=fs.createWriteStream(path.join(folder,'resources.jsonl')),abort=new AbortController(),observing=observe(resources,abort.signal);
  let plan,failure;
  try {
    await waitForHostCoverage(()=>lines(hostFile),{alive});
    plan=JSON.parse(sql('EXPLAIN (ANALYZE,BUFFERS,VERBOSE,FORMAT JSON) '+bound));
    await waitForHostCoverage(()=>lines(hostFile),{after:Date.now(),alive});
  }catch(e){failure=e;}
  finally {
    abort.abort();await observing;await new Promise(resolve=>resources.end(resolve));
    if(alive()){const ended=new Promise(resolve=>host.once('exit',resolve));host.kill();await ended;}fs.closeSync(log);
  }
  const samples=lines(path.join(folder,'resources.jsonl'));
  const validity=assessInterference(lines(hostFile),samples);
  if(samples.length===0){validity.valid=false;validity.reasons.push('resource-samples-missing');}
  write(name+'/validity.json',validity);
  if(failure)throw failure;
  // Preserve the raw plan even if interference disqualifies it.
  write(name+'/plan.json',plan);
  assert.ok(validity.valid,'P3 EXPLAIN screening invalid; stop without replacement');
  return plan;
}
async function markerCases(viewport, maps) {
  const selected=Array.from({length:maps},(_,i)=>i+1);
  const directory=path.join(output,'runtime/preflight');fs.mkdirSync(directory,{recursive:true});
  const config=[`header = ${JSON.stringify('Authorization: Bearer '+credentials.jwt)}`,
    ...selected.flatMap(i=>[`url = ${JSON.stringify('http://localhost:8081'+markerPath(key('map',i),viewport))}`,`output = "/results/preflight/map-${i}.json"`])].join('\n');
  command('docker',['exec','-i',appContainer,'curl','--parallel','--parallel-max','8','--max-time','30','--fail','--silent','--show-error','--config','-'],config);
  const width=Math.sqrt(viewport/100),minLat=37.5-width/2,maxLat=37.5+width/2,minLng=127.5-width/2,maxLng=127.5+width/2;
  const selectedRows=new Map(query(`SELECT "mapId",count(*) AS count,array_agg(id ORDER BY id) AS ids FROM places WHERE latitude>=${minLat} AND latitude<=${maxLat} AND longitude>=${minLng} AND longitude<=${maxLng} GROUP BY "mapId"`).map(r=>[r.mapId,r]));
  const cellCounts=new Map(query(`SELECT "mapId",sum(count) AS count FROM place_marker_cells WHERE precision=3 AND "maxLat">=${minLat} AND "minLat"<=${maxLat} AND "maxLng">=${minLng} AND "minLng"<=${maxLng} GROUP BY "mapId"`).map(r=>[r.mapId,Number(r.count)]));
  const cases=[];
  for(const i of selected) {
    const id=key('map',i),p=markerPath(id,viewport),body=JSON.parse(fs.readFileSync(path.join(directory,`map-${i}.json`)));assert.ok(Array.isArray(body));
    const rows=selectedRows.get(id)||{count:0,ids:[]},total=body.reduce((s,m)=>s+m.count,0);
    assert.equal(total,Number(rows.count)<=300?Number(rows.count):cellCounts.get(id));
    if(Number(rows.count)<=300) assert.deepEqual(body.map(m=>m.id).sort(),rows.ids);
    const entry={name:i===1?'markers-hot':'markers-other',path:p,hash:hash(canonical(body)),returned:body.length,total,selectedPlaces:Number(rows.count)};
    if(settings.paired && viewport===10 && body.some(m=>m.id==='overflow')) {
      const cells=query(`SELECT "placeId" AS id,"placeName" AS name,latitude,longitude,count FROM place_marker_cells WHERE "mapId"=${sqlString(id)} AND precision=3 AND "maxLat">=${minLat} AND "minLat"<=${maxLat} AND "maxLng">=${minLng} AND "minLng"<=${maxLng}`);
      assert.ok(cells.every(c=>c.count===1),'Overflow contract only covers the approved all-singleton M seed');
      const places=Object.fromEntries(cells.map(c=>[c.id,{name:c.name,latitude:settings.stage==='p2'?c.latitude:Number(c.latitude.toPrecision(16)),longitude:settings.stage==='p2'?c.longitude:Number(c.longitude.toPrecision(16))}]));
      entry.overflow={places,count:cells.length,latSum:Object.values(places).reduce((n,c)=>n+c.latitude,0),lngSum:Object.values(places).reduce((n,c)=>n+c.longitude,0)};
      assert.ok(overflowMatches(body,entry.overflow),'Preflight must satisfy the full known-difference contract');
      if(settings.stage==='p0') delete entry.hash;
    }
    cases.push(entry);
  }
  assert.ok(cases[0].returned>0,'Primary hot map must do work');
  return cases;
}
async function diagnostic(cases,name) {
  sql("ALTER SYSTEM SET pg_stat_statements.track='all'; SELECT pg_reload_conf();");
  await new Promise(r=>setTimeout(r,1000));
  const result=[];
  try {
  assert.equal(sql('SHOW pg_stat_statements.track;'),'all','Diagnostic tracking setting must take effect');
  for(const c of cases) {
    sql('SELECT pg_stat_statements_reset();');
    const r=await request(c.path,'GET',null,c.path.startsWith('/admin/'));
    if(c.overflow)assert.ok(overflowMatches(r.body,c.overflow));
    if(c.hash)assert.equal(hash(canonical(r.body)),c.hash);
    const statements=query("SELECT query,calls,total_exec_time,rows,shared_blks_hit,shared_blks_read FROM pg_stat_statements WHERE query !~ '(pg_stat_statements|pg_reload_conf|ALTER SYSTEM)' ORDER BY query");
    const explain=[];
    const url=new URL(c.path,'http://local'),q=url.searchParams,map=url.pathname.split('/')[3];
    for(const s of statements) {
      let values;
      if(/^WITH (marker_candidates|matching) AS/.test(s.query)) {
        const sourcePath='apps/server-v3/src/main/java/com/loco/places/MarkerQueryRepository.java';
        const source=settings.markerSourceRevision?command('git',['show',settings.markerSourceRevision+':'+sourcePath]):fs.readFileSync(path.join(root,sourcePath),'utf8');
        const template=javaSqlTemplate(source,s.query.startsWith('WITH marker_candidates')?'CANDIDATES':'BOUNDED_CELLS');
        const parameters={map,precision:3,minLat:+q.get('swLat'),maxLat:+q.get('neLat'),minLng:+q.get('swLng'),maxLng:+q.get('neLng')};
        const bound=bindTemplate(s.query,template,parameters);
        explain.push({sql:s.query,parameters,plan:JSON.parse(sql('EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) '+bound))});
      }
      if(s.query.startsWith('SELECT id,name,latitude,longitude')) values=[map,+q.get('swLat'),+q.get('neLat'),+q.get('swLng'),+q.get('neLng'),...(settings.stage==='p0'?[301]:[])];
      if(s.query.startsWith('SELECT * FROM place_marker_cells WHERE')) values=[map,3,+q.get('swLat'),+q.get('neLat'),+q.get('swLng'),+q.get('neLng')];
      if(s.query.startsWith('SELECT count FROM place_marker_cells')) values=[map,3,q.get('cellLatKey'),q.get('cellLngKey')];
      if(s.query.startsWith('SELECT id FROM places WHERE')) values=settings.stage==='p0'?[map,3,q.get('cellLatKey'),3,q.get('cellLngKey'),21]:[map,q.get('cellLatKey'),q.get('cellLngKey'),3,3,21];
      if(s.query.includes('WHERE p.id IN (')) values=r.body.items.map(item=>item.id);
      if(s.query.includes('WHERE p."mapId"=') && q.has('groupKey')) {
        const [lat,lng]=q.get('groupKey').split(':').map(Number);
        values=settings.stage==='p0'?[map,lat-0.0000005,lat+0.0000005,lng-0.0000005,lng+0.0000005]:[map,lat,lng];
      }
      if(values) {
        const bound=bindObservedSql(s.query,values);
        explain.push({sql:s.query,parameters:values,plan:JSON.parse(sql('EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) '+bound))});
      }
    }
    const selects=statements.filter(s=>/^(select|WITH (marker_candidates|matching) AS)/i.test(s.query.trim()));
    const postgresSelectCount=selects.reduce((n,s)=>n+Number(s.calls),0);
    const driverMetadataQueries=selects.filter(s=>s.query.includes('pg_catalog.')).reduce((n,s)=>n+Number(s.calls),0);
    const sqlCount=postgresSelectCount-driverMetadataQueries;
    assert.ok(sqlCount>=3,'A real authenticated read must capture at least three SQL statements');
    result.push({name:c.name,path:c.path,sqlCount,postgresSelectCount,driverMetadataQueries,statements,explain});
  }
  } finally {
  sql("ALTER SYSTEM SET pg_stat_statements.track='none'; SELECT pg_reload_conf();");
  }
  write(name,result);
}
async function bootstrap() {
  if(sql('SELECT count(*) FROM cms.admin_account;')==='0') {
    command('docker',[...compose,'exec','-T','perf-app','java','-jar','app.jar','cms-bootstrap','--login-id=perf-admin','--display-name=Performance Administrator'],secrets.password+'\n');
    await login(secrets.password);
    await request('/admin/api/core/auth/password','PUT',{currentPassword:secrets.password,newPassword:secrets.nextPassword},true);
  }
  await login(secrets.nextPassword);
}

const [action,...args]=process.argv.slice(2);
if(action==='up') {
  console.log(dc('up','-d','--wait','perf-db','perf-app'));await ready();
  sql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements; ALTER SYSTEM SET pg_stat_statements.track='none'; SELECT pg_reload_conf();");await bootstrap();snapshot('environment-initial.json');
} else if(action==='p3-cell-plan') {
  assert.ok(settings.screening,'P3 isolated project required');
  assert.match(args[0]||'',/^(plan-[123]|a-ext)$/);
  assert.ok(!fs.existsSync(path.join(output,args[0]+'.json')),'Never overwrite plan evidence');
  if(args[0]==='a-ext') {assert.equal(process.env.PERF_STAGE,'p3-a');sql('CREATE EXTENSION postgis;');}
  const templatePath='apps/server-v3/src/main/java/com/loco/places/MarkerQueryRepository.java';
  const source=settings.markerSourceRevision?command('git',['show',settings.markerSourceRevision+':'+templatePath]):fs.readFileSync(path.join(root,templatePath),'utf8');
  const template=javaSqlTemplate(source,'BOUNDED_CELLS');
  const u=new URL(markerPath(key('map',1),10),'http://local');
  const parameters={map:key('map',1),precision:3,minLat:+u.searchParams.get('swLat'),maxLat:+u.searchParams.get('neLat'),minLng:+u.searchParams.get('swLng'),maxLng:+u.searchParams.get('neLng')};
  const bound=template.replace(/:(map|precision|minLat|maxLat|minLng|maxLng)\b/g,(_,name)=>sqlString(parameters[name]));
  const plan=await observedCellPlan(args[0],bound);
  write(args[0]+'.json',{at:new Date().toISOString(),parameters,sql:bound,plan,role:args[0]==='a-ext'?'extension-only diagnostic, not gate':'E-32 gate plan',rootBuffers:plan[0].Plan['Shared Hit Blocks']+plan[0].Plan['Shared Read Blocks']});
} else if(action==='p3-db-start') {
  assert.ok(settings.screening);dc('start','perf-db');
  for(let i=0;;i++){try{sql('SELECT 1');break;}catch(e){if(i>=30)throw e;await new Promise(r=>setTimeout(r,1000));}}
} else if(action==='seed') {
  const [size='M',distribution='skew',processing='half']=args,start=Date.now();
  const script=datasetSql(size,distribution,processing);sql(script);
  const descriptor={size,distribution,processing,seed:'loco-perf-20261108',sqlSha256:hash(script),preparedSeconds:(Date.now()-start)/1000,data:fingerprint(),mapDistribution:query('SELECT min(n) AS min,max(n) AS max,sum(n) AS places,count(*) AS maps FROM (SELECT "mapId",count(*) n FROM places GROUP BY "mapId") s')};
  write('dataset.json',descriptor);write(`dataset-${size}-${distribution}-${processing}.json`,descriptor);
  console.log(JSON.stringify({size,distribution,seconds:(Date.now()-start)/1000}));
} else if(action==='boundary') {
  const map=key('map',1);sql(`DELETE FROM places WHERE "mapId"=${sqlString(map)}; DELETE FROM place_marker_cells WHERE "mapId"=${sqlString(map)};`);
  const observations=[];
  for(const lat of [37.05,-0.00001]) {
    const created=await request(`/api/maps/${map}/places`,'POST',{name:'boundary',address:'synthetic',latitude:lat,longitude:0});assert.equal(created.status,201);
    const levels=[];
    for(const level of [5,6,9,11]) {const r=await request(`/api/maps/${map}/places/markers?level=${level}&swLat=${lat-0.001}&neLat=${lat+0.001}&swLng=-0.001&neLng=0.001`);levels.push({level,body:r.body});}
    assert.equal(levels[0].body.length,1);
    if(settings.stage==='p0')assert.equal(levels[3].body.length,0,'P0 preserves the observed missing-cell bug');
    else for(const result of levels)assert.equal(result.body.length,1,'P1 must preserve the place at every level');
    observations.push({latitude:lat,levels});await request('/api/places/'+created.body.id,'DELETE');
  }
  write('boundary-http.json',observations);console.log(settings.stage==='p0'?'R real HTTP: both known omission coordinates reproduced':'R real HTTP: both omission coordinates present at every level');
} else if(action==='verify-seed') {
  const rows=query(`SELECT id,latitude,longitude,ARRAY[ROUND(latitude::numeric,1)::text,ROUND(latitude::numeric,2)::text,ROUND(latitude::numeric,3)::text] AS latkeys,ARRAY[ROUND(longitude::numeric,1)::text,ROUND(longitude::numeric,2)::text,ROUND(longitude::numeric,3)::text] AS lngkeys FROM places`);
  for(const row of rows)for(let p=1;p<=3;p++) {
    assert.equal(row.latitude.toFixed(p),row.latkeys[p-1],`Bulk seed must not conceal rounding mismatch: ${row.id}`);
    assert.equal(row.longitude.toFixed(p),row.lngkeys[p-1]);
  }
  const totals=query('SELECT precision,sum(count) AS places,count(*) AS cells FROM place_marker_cells GROUP BY precision ORDER BY precision');
  assert.equal(totals.length,3);for(const row of totals)assert.equal(Number(row.places),rows.length);
  write('seed-verification.json',{places:rows.length,coordinateKeyChecks:rows.length*6,mismatches:0,totals});console.log('Seed JS/PG key checks and cell totals passed: '+rows.length+' places');
} else if(action==='pilot') {
  const cases=await markerCases(1,1000);
  for(const rate of [1,5,10,25,50,100]) await runLoad({run:`pilot-${rate}`,rate,seconds:60,hot80:true,cases});
} else if(action==='diagnostic') {
  const name=args[0]||'marker-diagnostics';
  assert.match(name,/^[a-z0-9-]+$/);
  assert.ok(!fs.existsSync(path.join(output,name+'.json')),'Do not overwrite existing diagnostics');
  const cases=await markerCases(1,1000);
  const row=query(`SELECT latitude,longitude FROM places WHERE "mapId"=${sqlString(key('map',1))} ORDER BY id LIMIT 1`)[0];
  for(const [api,param,precision] of [['marker-group','groupKey',6],['marker-cluster','cellKey',3]]) {
    const coordinates=api==='marker-group'?`${param}=${row.latitude.toFixed(precision)}:${row.longitude.toFixed(precision)}`:`cellLatKey=${row.latitude.toFixed(precision)}&cellLngKey=${row.longitude.toFixed(precision)}`;
    const path=`/api/maps/${key('map',1)}/places/${api}?${coordinates}&precision=3`;
    const r=await request(path);cases.push({name:api,path,hash:hash(canonical(r.body))});
  }
  await diagnostic([cases[0],cases[1],...cases.slice(-2)],name+'.json');
  console.log('Marker SQL and EXPLAIN captured outside measurement');
} else if(action==='measure'||action==='explore') {
  const [name,rateText='50',viewportText='1',requestMix='hot80',repeatsText='1']=args;
  const spec=measurementSpec(name,rateText,viewportText,requestMix,repeatsText);
  for(let i=1;i<=spec.repeats;i++)for(const suffix of ['', '-warmup'])
    assert.ok(!fs.existsSync(path.join(output,`${name}-${i}${suffix}`)),'Result directory already exists; choose a new run name');
  const dataset=JSON.parse(fs.readFileSync(path.join(output,'dataset.json')));
  assert.ok(['S','M'].includes(dataset.size),'Marker workload requires an S or M dataset');
  const cases=await markerCases(spec.viewport,dataset.size==='S'?100:1000);
  const before=fingerprint();snapshot(`${name}-manifest.json`);
  await diagnostic([cases[0],cases[1]],`${name}-sql.json`);
  for(let i=1;i<=spec.repeats;i++) {
    const config={exploratory:action==='explore',rate:spec.rate,hot80:spec.mix==='hot80',cases};
    for(const [suffix,seconds] of [['-warmup',120],['',action==='explore'?60:300]]) {
      try {await runLoad({...config,run:`${name}-${i}${suffix}`,seconds,...(!suffix&&(settings.stage==='p1'||settings.paired)?{budgetMs:200}:{})});}
      catch(error) {
        if(!settings.paired)throw error;
        const folder=path.join(output,`${name}-${i}${suffix}`),e=JSON.parse(fs.readFileSync(path.join(folder,'execution.json'))),s=JSON.parse(fs.readFileSync(path.join(folder,'summary.json')));
        if(!pairedThresholdFailure(e.exitCode,s))throw error;
        console.log('Paired threshold failure retained; same prescribed duration and rate, no favorable retry');
      }
    }
    assert.deepEqual(fingerprint(),before,'Read workload must not change business data');
    write(`${name}-${i}-db-check.json`,{unchanged:true});
    environment();
  }
  if(settings.paired) {
    const logs=spawnSync('docker',['logs',appContainer],{encoding:'utf8',maxBuffer:64*1024*1024,windowsHide:true});
    assert.equal(logs.status,0);fs.writeFileSync(path.join(output,name+'-app.log'),logs.stdout+'\n'+logs.stderr);
  }
} else if(action==='probe'||action==='warmup') {
  const [name,rate='1',viewport='10',seconds='60']=args;
  const spec=measurementSpec(name,rate,viewport,'hot80',1);
  assert.ok(['60','120','300'].includes(seconds));
  const cases=await markerCases(spec.viewport,1000),before=fingerprint();
  snapshot(name+'-manifest.json');
  try {await runLoad({run:name,rate:spec.rate,seconds:Number(seconds),hot80:true,cases,...(action==='probe'?{budgetMs:200}:{})});}
  finally {
    assert.deepEqual(fingerprint(),before,'Read workload must not change business data');
    write(name+'-db-check.json',{unchanged:true});
    const logs=spawnSync('docker',['logs',appContainer],{encoding:'utf8',maxBuffer:64*1024*1024,windowsHide:true});
    assert.equal(logs.status,0,'App log capture failed');
    fs.writeFileSync(path.join(output,name+'-app.log'),logs.stdout+'\n'+logs.stderr);
  }
} else if(action==='p2-write'||action==='p2-write-sql') {
  assert.match(project,/^loco-perf-p2-(a1|b)(-write-(retest1|e23|e24))?$/,'P2 writes require isolated A1/B projects');
  const {name,concurrency,warmupCycles,measuredCycles}=writeSpec(args[0],args[1],process.env.PERF_SESSION||'');
  const before=fingerprint(),diagnosis=action==='p2-write-sql';
  snapshot(name+'-manifest.json');
  try {
    if(diagnosis) {
      sql("ALTER SYSTEM SET pg_stat_statements.track='all'; SELECT pg_reload_conf();");
      await new Promise(r=>setTimeout(r,1000));sql('SELECT pg_stat_statements_reset();');
    }
    if(!diagnosis)await runLoad({run:name+'-warmup',seconds:600,writeCycles:warmupCycles,writeConcurrency:concurrency,map:key('map',1)});
    await runLoad({run:name,seconds:600,writeCycles:diagnosis?concurrency*5:measuredCycles,writeConcurrency:concurrency,map:key('map',1),diagnostic:diagnosis});
    if(diagnosis)write(name+'-sql.json',{note:'Diagnostic only; SELECT lock total_exec_time includes wait and execution, not pure wait time',concurrency,
      statements:query("SELECT query,calls,total_exec_time,mean_exec_time,max_exec_time,rows,wal_bytes,shared_blks_hit,shared_blks_read FROM pg_stat_statements WHERE query !~ '(pg_stat_statements|pg_reload_conf|ALTER SYSTEM)' ORDER BY query")});
    assert.deepEqual(fingerprint(),before,'Disjoint-cell CRUD cycles must restore every business table');
    write(name+'-db-check.json',{unchanged:true,concurrency});
  } finally {
    if(diagnosis)sql("ALTER SYSTEM SET pg_stat_statements.track='none'; SELECT pg_reload_conf();");
  }
} else if(action==='write-cost') {
  assert.equal(project,'loco-perf-e15-b','Write experiment is confined to the new disposable B database');
  const ddl=fs.readFileSync(path.join(root,'apps/server-v3/src/main/resources/db/migration/V8__index_marker_round_lookups.sql'),'utf8');
  const indexes=['places_map_round6_idx',...Array.from({length:3},(_,i)=>`places_map_round${i+1}_text_idx`)];
  const drop=()=>sql(indexes.map(i=>`DROP INDEX IF EXISTS ${i};`).join('\n'));
  const before=fingerprint();const results=[];
  try {
    for(let pair=1;pair<=5;pair++)for(const indexed of pair%2?[false,true]:[true,false]) {
      drop();const ddlStart=Date.now();if(indexed)sql(ddl);const indexBuildSeconds=(Date.now()-ddlStart)/1000;sql('ANALYZE places;');
      const name=`write-${pair}-${indexed?'indexed':'noindex'}`;
      const sizes=query(`SELECT indexrelname,pg_relation_size(indexrelid) AS bytes FROM pg_stat_user_indexes WHERE indexrelname LIKE 'places_map_round%' ORDER BY 1`);
      await runLoad({run:name+'-warmup',seconds:600,writeCycles:10,map:key('map',1)});
      await runLoad({run:name,seconds:600,writeCycles:30,map:key('map',1)});
      assert.deepEqual(fingerprint(),before,'Synthetic create/update/delete must restore business data');
      results.push({pair,indexed,indexBuildSeconds,sizes,unchanged:true});write('write-cost-progress.json',results);
    }
    // SQL instrumentation stays outside the timed HTTP samples.
    for(const indexed of [false,true]) {
      drop();if(indexed)sql(ddl);sql('ANALYZE places; ALTER SYSTEM SET pg_stat_statements.track=\'all\'; SELECT pg_reload_conf();');
      await new Promise(r=>setTimeout(r,1000));
      const entries=[];
      for(let i=0;i<5;i++) {
        sql('SELECT pg_stat_statements_reset();');
        const created=await request(`/api/maps/${key('map',1)}/places`,'POST',{name:'e14-write-cost',address:'synthetic',latitude:-70.1234567,longitude:-140.1234567});
        entries.push({operation:'create',statements:query("SELECT query,calls,total_exec_time,rows,wal_bytes FROM pg_stat_statements WHERE query !~ '(pg_stat_statements|pg_reload_conf|ALTER SYSTEM)' ORDER BY query")});
        sql('SELECT pg_stat_statements_reset();');
        await request('/api/places/'+created.body.id,'PUT',{latitude:-70.2234567,longitude:-140.2234567});
        entries.push({operation:'update',statements:query("SELECT query,calls,total_exec_time,rows,wal_bytes FROM pg_stat_statements WHERE query !~ '(pg_stat_statements|pg_reload_conf|ALTER SYSTEM)' ORDER BY query")});
        sql('SELECT pg_stat_statements_reset();');
        await request('/api/places/'+created.body.id,'DELETE');
        entries.push({operation:'delete',statements:query("SELECT query,calls,total_exec_time,rows,wal_bytes FROM pg_stat_statements WHERE query !~ '(pg_stat_statements|pg_reload_conf|ALTER SYSTEM)' ORDER BY query")});
      }
      write(`write-sql-${indexed?'indexed':'noindex'}.json`,entries);
      sql("ALTER SYSTEM SET pg_stat_statements.track='none'; SELECT pg_reload_conf();");
      assert.deepEqual(fingerprint(),before);
    }
  } finally {drop();sql(ddl);sql("ANALYZE places; ALTER SYSTEM SET pg_stat_statements.track='none'; SELECT pg_reload_conf();");}
} else if(action==='restart') {
  console.log(dc('restart','perf-app'));await ready();
} else if(action==='cms'||action==='cms-probe') {
  const dataset=JSON.parse(fs.readFileSync(path.join(output,'dataset.json')));
  const prefix=`cms-${dataset.size}-${dataset.processing}`;
  const planFile=path.join(output,'runtime/cms-plans.json');
  if(fs.existsSync(planFile))fs.unlinkSync(planFile);
  // Restart outside measured intervals to collect real generated CMS SQL/EXPLAIN.
  dc('stop','perf-app');
  dc('run','-d','--name',`${project}-plan`,'--use-aliases','perf-app','--capture-plans');
  appContainer=`${project}-plan`;
  await ready(); await bootstrap();
  let plans;
  for(let i=0;i<120;i++) {
    try {plans=JSON.parse(fs.readFileSync(planFile));break;}
    catch(error) {if(error.code!=='ENOENT'&&!(error instanceof SyntaxError))throw error;}
    await new Promise(r=>setTimeout(r,250));
  }
  assert.ok(Array.isArray(plans)&&plans.length===17,'Current dataset plans did not finish');
  for(const plan of plans.filter(p=>p.case==='deep')) {
    const cursorId=Buffer.from(plan.query.cursor,'base64url').toString().split('|')[2];
    assert.equal(cursorId,key(plan.resource.slice(0,-1),dataset.size==='CMS-S'?5000:50000),'Stale dataset cursor');
  }
  write(`${prefix}-plans.json`,plans);
  const cases=[];
  for(const p of plans) {const url=p.path+'?'+new URLSearchParams(p.query);const r=await request(url,'GET',null,true);cases.push({name:p.resource+'-'+p.case,path:url,hash:hash(canonical(r.body))});}
  snapshot(`${prefix}-manifest.json`);await diagnostic(cases,`${prefix}-sql.json`);
  if(action==='cms') {
  await runLoad({run:'cms-warmup',rate:10,seconds:120,cases,hot80:false,cms:true});
  const before=fingerprint();
  await runLoad({run:'cms',rate:10,seconds:300,cases,hot80:false,cms:true});
  assert.deepEqual(fingerprint(),before);
  }
  command('docker',['stop',`${project}-plan`]);command('docker',['rm',`${project}-plan`]);dc('start','perf-app');appContainer=`${project}-perf-app-1`;await ready();
} else if(action==='profile') {
  const [name='profile',rate='50',viewport='1']=args;
  const fixedConcurrency=args[3]===undefined?undefined:Number(args[3]);
  assert.ok(fixedConcurrency===undefined||fixedConcurrency===1,'Memory comparison uses fixed concurrency 1; primary arrival-rate runs are unchanged');
  const spec=measurementSpec(name,rate,viewport,'hot80',1);
  const recording=name==='profile'?'diagnostic.jfr':name+'.jfr';
  assert.ok(!fs.existsSync(path.join(output,'runtime',recording)),'Do not overwrite a JFR recording');
  dc('stop','perf-app');
  const jfr=`-XX:StartFlightRecording=delay=140s,duration=60s,filename=/results/${recording},settings=profile,jdk.InitialEnvironmentVariable#enabled=false,jdk.InitialSystemProperty#enabled=false`;
  dc('run','-d','--name',`${project}-jfr`,'--use-aliases','-e','JAVA_TOOL_OPTIONS='+jfr,'perf-app');
  appContainer=`${project}-jfr`;
  try {
  await ready();snapshot(`${name}-manifest.json`);
  const cases=await markerCases(spec.viewport,1000),before=fingerprint();
  for(const [suffix,seconds] of [['warmup',120],['diagnostic',90]]) {
    const run=`${name}-${suffix}`;
    try {await runLoad({run,rate:spec.rate,seconds,hot80:true,cases,...(fixedConcurrency?{fixedConcurrency}:{})});}
    catch(error) {
      const result=JSON.parse(fs.readFileSync(path.join(output,run,'execution.json')));
      if(result.exitCode!==99)throw error;
      console.log(`${run}: threshold failure retained for diagnosis, never a baseline pass`);
    }
  }
  assert.ok(fs.statSync(path.join(output,'runtime',recording)).size>0);
  assert.deepEqual(fingerprint(),before);
  write(`${name}-db-check.json`,{unchanged:true});
  } finally {
  const logs=spawnSync('docker',['logs',appContainer],{encoding:'utf8',maxBuffer:64*1024*1024,windowsHide:true});
  fs.writeFileSync(path.join(output,`${name}-app.log`),logs.stdout+'\n'+logs.stderr);
  command('docker',['stop',`${project}-jfr`]);command('docker',['rm',`${project}-jfr`]);
  dc('start','perf-app');appContainer=`${project}-perf-app-1`;await ready();
  }
} else if(action==='snapshot') snapshot(args[0]||'environment-final.json');
else if(action==='stop') console.log(dc('stop'));
else throw Error('Use up | seed S/M/CMS skew/uniform | boundary | pilot | probe/warmup NAME RATE VIEWPORT SECONDS | measure NAME RATE VIEWPORT hot80/uniform REPEATS | cms | restart | snapshot | stop');
