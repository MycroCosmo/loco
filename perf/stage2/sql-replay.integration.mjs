// Functional diagnostic-path check, never a performance run. Own temporary DB only.
import fs from 'node:fs';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {bindTemplate,javaSqlTemplate} from './sql-replay.mjs';
import {sqlString} from './data.mjs';

const started=performance.now(),name='loco-p3-replay-check-'+randomUUID();
const image='postgis/postgis:17-3.6@sha256:1ac10b2551ba84ae05db8e7da477afcd0a3fede99bcb7705da2ee157390cb095';
const docker=(args,input)=>{const r=spawnSync('docker',args,{input,encoding:'utf8',windowsHide:true,timeout:120000,maxBuffer:8*1024*1024});assert.equal(r.status,0,r.error?.message||r.stderr);return r.stdout.trim();};
const sql=text=>docker(['exec','-i',name,'psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','postgres','-d','postgres'],text);
const source=fs.readFileSync('apps/server-v3/src/main/java/com/loco/places/MarkerQueryRepository.java','utf8');
const parameters={map:'map',precision:3,minLat:37,maxLat:38,minLng:127,maxLng:129};
const rows=q=>JSON.parse(sql(`SELECT COALESCE(json_agg(r),'[]'::json) FROM (${q}) r;`));
const results=[];let created=false;
try {
  docker(['run','-d','--name',name,'--network','none','--cpus','2','--memory','1g','--tmpfs','/var/lib/postgresql/data','-e','POSTGRES_HOST_AUTH_METHOD=trust',image,'-c','shared_preload_libraries=pg_stat_statements']);created=true;
  let ready=false;
  for(let i=0;i<60;i++){try{sql('SELECT 1;');ready=true;break;}catch{await new Promise(r=>setTimeout(r,1000));}}
  assert.ok(ready,'Temporary PostgreSQL did not become ready');
  sql('CREATE EXTENSION IF NOT EXISTS postgis; CREATE EXTENSION pg_stat_statements;');
  sql(fs.readFileSync('apps/server-v3/src/main/resources/db/migration/V1__baseline.sql','utf8'));
  sql(fs.readFileSync('apps/server-v3/src/main/resources/db/migration/V10__index_marker_cell_bbox.sql','utf8'));
  sql(`INSERT INTO users(id,email,nickname,"updatedAt") VALUES('user','synthetic@example.invalid','synthetic',now());
    INSERT INTO maps(id,"ownerId",title,"updatedAt") VALUES('map','user','synthetic',now());`);
  const predicate="SELECT ST_MakeEnvelope(0,0,1,1,4326) && ST_MakeEnvelope(1,1,2,2,4326) AS touching, ST_MakeEnvelope(0,0,1,1,4326) && ST_MakeEnvelope(2,2,3,3,4326) AS separate";
  assert.deepEqual(rows(bindTemplate(predicate,predicate,{})),[{touching:true,separate:false}]);results.push({check:'bbox touching and disjoint behavior',passed:true});
  for(const count of [2,701]) {
    sql(`TRUNCATE place_marker_cells; DELETE FROM places;
      INSERT INTO places(id,"mapId","createdById",name,address,latitude,longitude,"updatedAt")
      SELECT 'place-'||g,'map','user','name-'||g,'address',37.5+g/1000000.0,127.5,now() FROM generate_series(1,${count}) g;
      INSERT INTO place_marker_cells("mapId",precision,"cellLatKey","cellLngKey",latitude,longitude,"minLat","maxLat","minLng","maxLng",count,"exactKeyCount","placeId","placeName","updatedAt")
      SELECT 'map',3,g::text,'127.5',37.5,127.5,37.5,37.5,127.5,127.5,1,1,'place-'||g,'name-'||g,now() FROM generate_series(1,${count}) g;`);
    for(const key of ['CANDIDATES','BOUNDED_CELLS']) {
      const template=javaSqlTemplate(source,key);
      const direct=template.replace(/(?<!:):([a-zA-Z_]\w*)/g,(_,k)=>{assert.ok(Object.hasOwn(parameters,k));return sqlString(parameters[k]);});
      sql('SELECT pg_stat_statements_reset();');sql(direct);
      const prefix=key==='CANDIDATES'?'WITH marker_candidates AS':'WITH matching AS';
      const observed=rows(`SELECT query FROM pg_stat_statements WHERE query LIKE ${sqlString(prefix+'%')}`);
      assert.equal(observed.length,1);assert.match(observed[0].query,/\$\d+/);
      const replay=bindTemplate(observed[0].query,template,parameters);
      const plan=JSON.parse(sql('EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) '+replay));assert.ok(plan[0].Plan);
      const actual=rows(replay),expected=rows(direct);
      assert.deepEqual(actual.map(JSON.stringify).sort(),expected.map(JSON.stringify).sort());
      assert.equal(actual.length,key==='CANDIDATES'?Math.min(count,301):Math.min(count,700));
      if(key==='BOUNDED_CELLS') {const overflow=actual.filter(r=>r.isOverflow);assert.equal(overflow.length,count>699?1:0);if(count>699)assert.equal(overflow[0].count,count-699);}
      results.push({check:key,seedCount:count,rows:actual.length,normalizedSql:observed[0].query,replayedSql:replay,passed:true});
    }
  }
} finally {
  if(created)docker(['rm','-f',name]); // Only this test's UUID-named, tmpfs-backed container.
}
const result={purpose:'Functional B diagnostic replay verification; no load/adoption',image,checks:results.length,passed:results.length,failed:0,wallSeconds:(performance.now()-started)/1000,results};
if(process.argv[2])fs.writeFileSync(process.argv[2],JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify({checks:result.checks,passed:result.passed,wallSeconds:result.wallSeconds}));
