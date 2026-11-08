import test from 'node:test';
import assert from 'node:assert/strict';
import {key,datasetSql,markerPath,canonical,hash,bindObservedSql,measurementSpec} from './data.mjs';
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
