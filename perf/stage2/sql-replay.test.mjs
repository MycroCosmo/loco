import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {bindTemplate,javaSqlTemplate} from './sql-replay.mjs';

test('replays request parameters and normalized branch/limit literals without guessing positions',()=>{
  const template='SELECT CASE WHEN count(*)<=300 THEN ROUND(latitude::numeric,6) END FROM places WHERE "mapId"=:map LIMIT 301';
  const query='SELECT CASE WHEN count(*)<=$2 THEN ROUND(latitude::numeric,$3) END FROM places WHERE "mapId"=$1 LIMIT $4';
  const replay=bindTemplate(query,template,{map:"test'quote"});
  assert.match(replay,/<= 300/);assert.match(replay,/numeric , 6/);
  assert.match(replay,/'test''quote'/);assert.match(replay,/LIMIT 301$/);
});
test('changed SQL, missing values and inconsistent bindings fail closed',()=>{
  assert.throws(()=>bindTemplate('SELECT $1 FROM users','SELECT :id FROM places',{id:1}));
  assert.throws(()=>bindTemplate('SELECT $1','SELECT :id',{}));
  assert.throws(()=>bindTemplate('SELECT $1,$1','SELECT 1,2',{}));
});
test('replays the real captured HTTP cell SQL against its source template',()=>{
  const source='String BOUNDED_CELLS = \"\"\"'+fs.readFileSync('apps/server-v3/src/test/resources/markers/p2-b-cells.sql','utf8').replace(/^--[^\n]*\n/,'')+'\"\"\";';
  const capture=JSON.parse(fs.readFileSync('perf/stage2/results/p2-implementation/bounded-50000.json'));
  let n=0;const observed=capture.sql.replaceAll('?',()=>'$'+(++n));assert.equal(n,6);
  const replay=bindTemplate(observed,javaSqlTemplate(source,'BOUNDED_CELLS'),{map:'example',precision:3,minLat:-90,maxLat:90,minLng:-180,maxLng:180});
  assert.match(replay,/"mapId" = 'example'/);assert.match(replay,/precision = '3'/);
  assert.match(replay,/"maxLat" >= '-90'/);assert.doesNotMatch(replay,/\$\d/);
});
