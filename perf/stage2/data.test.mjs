import test from 'node:test';
import assert from 'node:assert/strict';
import {key,datasetSql,markerPath,canonical,hash,bindObservedSql,measurementSpec,stageSettings} from './data.mjs';
test('synthetic identifiers are stable, distinct UUID strings',()=>{
  assert.match(key('map',1),/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/);
  assert.equal(key('map',1),key('map',1));
  assert.notEqual(key('map',1),key('place',1));
});
test('unknown dataset cannot execute reset SQL',()=>{
  assert.throws(()=>datasetSql('production','skew'));
  assert.throws(()=>datasetSql('M','unexpected'));
  assert.match(datasetSql('M','skew'),/current_database\(\)<>'loco_perf'/);
});
test('same seed yields byte-identical SQL; distributions differ',()=>{
  assert.equal(hash(datasetSql('M','skew')),hash(datasetSql('M','skew')));
  assert.notEqual(hash(datasetSql('M','uniform')),hash(datasetSql('M','skew')));
});
test('viewport percentage means area, not side length',()=>{
  const u=new URL(markerPath(key('map',1),1),'http://local');
  assert.ok(Math.abs((+u.searchParams.get('neLat')-u.searchParams.get('swLat'))-0.1)<1e-12);
  assert.throws(()=>markerPath(key('map',1),2));
});
test('response check catches changed coordinates and counts despite tie ordering',()=>{
  const a=[{id:'1',count:1,latitude:37},{id:'2',count:2,latitude:38}];
  assert.equal(canonical(a),canonical([...a].reverse()));
  assert.notEqual(canonical(a),canonical([{...a[0],count:2},a[1]]));
  assert.notEqual(canonical(a),canonical([{...a[0],latitude:37.05},a[1]]));
});
test('EXPLAIN replay restores PostgreSQL-normalized projection zero',()=>{
  assert.equal(bindObservedSql('SELECT coalesce(avg(rating),$2)::float8 AS "averageRating" FROM reviews WHERE id=$1',['synthetic']), 'SELECT coalesce(avg(rating),\'0\')::float8 AS "averageRating" FROM reviews WHERE id=\'synthetic\'');
});
test('EXPLAIN replay rejects unrecognized bindings rather than inventing a value',()=>{
  assert.throws(()=>bindObservedSql('SELECT * FROM places WHERE id=$2',['synthetic']),/Missing observed SQL binding/);
});
test('approved measurement arguments preserve their meaning',()=>{
  assert.deepEqual(measurementSpec('primary','50','1','hot80','5'),{name:'primary',rate:50,viewport:1,mix:'hot80',repeats:5});
});
test('invalid repeat counts cannot silently produce zero measurements',()=>{
  for(const value of ['0','-1','NaN','1.5'])assert.throws(()=>measurementSpec('primary',50,1,'hot80',value));
});
test('mistyped conditions or unsafe names cannot change the workload silently',()=>{
  assert.throws(()=>measurementSpec('../old',50,1,'hot80',5));
  assert.throws(()=>measurementSpec('primary',0,1,'hot80',5));
  assert.throws(()=>measurementSpec('primary',50,1,'hot8',5));
});

test('P1 cannot reuse P0 database project or raw results',()=>{
  const p0=stageSettings('p0'),p1=stageSettings('p1');
  assert.notEqual(p0.project,p1.project);assert.notEqual(p0.output,p1.output);
  assert.equal(p0.output,'apps/server-v3/build/perf-stage2');
  assert.throws(()=>stageSettings('production'));
});

test('EXPLAIN replay restores six-digit P1 key literals without changing parameters',()=>{
  const query='SELECT id,name,latitude,longitude,ROUND(latitude::numeric,$6)::text AS "latKey",ROUND(longitude::numeric,$7)::text AS "lngKey" FROM places WHERE "mapId"=$1 AND latitude>=$2 AND latitude<=$3 AND longitude>=$4 AND longitude<=$5 LIMIT $8';
  const bound=bindObservedSql(query,['map',37,38,127,128]);
  assert.match(bound,/ROUND\(latitude::numeric,'6'\)/);assert.match(bound,/LIMIT '301'$/);
  assert.match(bound,/latitude>='37'/);assert.doesNotMatch(bound,/\$\d/);
  const group='SELECT ROUND(p.latitude::numeric,$4)=ROUND(CAST($2 AS float8)::numeric,$5) FROM places p WHERE p."mapId"=$1 AND ROUND(p.longitude::numeric,$6)=ROUND(CAST($3 AS float8)::numeric,$7)';
  const grouped=bindObservedSql(group,['map',37.05,0]);
  assert.match(grouped,/CAST\('37.05' AS float8\)::numeric,'6'/);
  assert.doesNotMatch(grouped,/\$\d/);
});

test('E14 cluster replay retains constant precision and all request bindings',()=>{
  const query='SELECT id FROM places WHERE "mapId"=$1 AND ROUND(latitude::numeric,$4)::text=$2 AND ROUND(longitude::numeric,$5)::text=$3 ORDER BY "createdAt" DESC,id DESC LIMIT $6';
  const bound=bindObservedSql(query,['map','37.444','127.889',3,3,21]);
  assert.match(bound,/latitude::numeric,'3'/);assert.match(bound,/::text='37.444'/);assert.match(bound,/LIMIT '21'/);
});
