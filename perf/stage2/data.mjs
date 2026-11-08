import {createHash} from 'node:crypto';

export const seed = 'loco-perf-20261108';
export const key = (kind, i) => {
  const h = createHash('md5').update(`${seed}/${kind}/${i}`).digest('hex');
  return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;
};
export const hash = value => createHash('sha256').update(value).digest('hex');
export const sqlKey = (kind, expr) => `md5('${seed}/${kind}/'||(${expr})::text)::uuid::text`;
export const sqlString = value => `'${String(value).replaceAll("'", "''")}'`;
export function pairedThresholdFailure(exitCode, summary) {
  // k6 Rate "passes" counts true values: for http_req_failed those are failed requests.
  return exitCode===99 && (summary.metrics.business_failure?.values.count||0)<=(summary.metrics.http_req_failed?.values.passes||0);
}
export function stageSettings(stage = process.env.PERF_STAGE || 'p0') {
  if (!['p0','p1','e15-a','e15-b'].includes(stage)) throw Error('Unknown performance stage');
  return {stage:stage==='e15-a'?'p0':stage==='e15-b'?'p1':stage, project:`loco-perf-${stage}`, output:`apps/server-v3/build/perf-stage2${stage==='p0'?'':'-'+stage}`,
    image:stage==='e15-a'?'loco-perf-p0:local':`loco-perf-${stage}:local`, paired:stage.startsWith('e15-')};
}
export function measurementSpec(name,rate,viewport,mix,repeats) {
  if(!/^[a-z0-9-]+$/.test(name||''))throw Error('Invalid run name');
  rate=Number(rate);viewport=Number(viewport);repeats=Number(repeats);
  if(!Number.isInteger(rate)||rate<1||rate>10000)throw Error('Invalid request rate');
  if(!Number.isInteger(repeats)||repeats<1||repeats>10)throw Error('Invalid repeat count');
  if(![0.01,1,10,100].includes(viewport)||!['hot80','uniform'].includes(mix))throw Error('Invalid measurement condition');
  return {name,rate,viewport,mix,repeats};
}
export function bindObservedSql(query, parameters) {
  const values=[...parameters];
  // pg_stat_statements normalizes the projection's literal COALESCE(...,0) too.
  const zero=query.match(/,\$(\d+)\)::float8 AS "averageRating"/);
  if(zero)values[Number(zero[1])-1]=0;
  // P1's coordinate precision is a literal, not a JDBC parameter.
  for(const match of query.matchAll(/ROUND\((?:[a-z.]+::numeric|CAST\(\$\d+ AS float8\)::numeric),\$(\d+)\)/g)) {
    const index=Number(match[1])-1;
    if(values[index]===undefined)values[index]=6;
  }
  const limit=query.match(/LIMIT \$(\d+)\s*$/);
  if(limit && query.startsWith('SELECT id,name,latitude,longitude,'))values[Number(limit[1])-1]=301;
  return query.replace(/\$(\d+)/g,(_,i)=>{
    const value=values[Number(i)-1];
    if(value===undefined)throw Error('Missing observed SQL binding $'+i);
    return sqlString(value);
  });
}

export function datasetSql(size, distribution, processing='half') {
  if (!['S','M','CMS','CMS-S'].includes(size) || !['skew','uniform'].includes(distribution) || !['half','none'].includes(processing)) throw Error('Unknown dataset');
  const n = ['S','CMS-S'].includes(size) ? 10000 : 100000;
  const cms=size.startsWith('CMS');
  const maps = cms ? n : n/100;
  const map = cms ? 'g' : distribution === 'uniform' ? `1+(g-1)%${maps}` : `CASE WHEN g<=${n/2} THEN 1 ELSE 2+(g-${n/2}-1)%${maps-1} END`;
  return `
DO $$ BEGIN IF current_database()<>'loco_perf' THEN RAISE EXCEPTION 'Isolated database required'; END IF; END $$;
BEGIN;
TRUNCATE public.users, public.maps, public.places, public.place_marker_cells, public.map_members,
public.place_reviews, public.favorites, public.feedbacks, public.reports, public.refresh_tokens,
public.password_reset_tokens CASCADE;
TRUNCATE cms.loco_report_processing;
INSERT INTO users(id,email,nickname,"updatedAt","createdAt")
SELECT ${sqlKey('user','g')}, 'perf-'||g||'@example.invalid',CASE WHEN g%997=0 THEN 'needle' ELSE 'common' END||g,
timestamp '2026-01-01'+g*interval '1 millisecond',timestamp '2026-01-01'+g*interval '1 millisecond' FROM generate_series(1,${n}) g;
INSERT INTO maps(id,"ownerId",title,tags,"updatedAt","createdAt")
SELECT ${sqlKey('map','g')},${sqlKey('user',1)},CASE WHEN g%997=0 THEN 'needle' ELSE 'common' END||g,ARRAY['perf'],
timestamp '2026-01-01'+g*interval '1 millisecond',timestamp '2026-01-01'+g*interval '1 millisecond' FROM generate_series(1,${maps}) g;
INSERT INTO places(id,"mapId","createdById",name,address,latitude,longitude,tags,"imageUrls","updatedAt","createdAt")
SELECT ${sqlKey('place','g')},${sqlKey('map',map)},${sqlKey('user',1)},CASE WHEN g%997=0 THEN 'needle' ELSE 'common' END||g,'synthetic',
37+((g::bigint*7919)%1000000)/1000000.0+0.000000123,
127+((g::bigint*104729)%1000000)/1000000.0+0.000000123,ARRAY['perf'],ARRAY[]::text[],
timestamp '2026-01-01'+g*interval '1 millisecond',timestamp '2026-01-01'+g*interval '1 millisecond' FROM generate_series(1,${n}) g;
INSERT INTO place_reviews(id,"placeId","userId",rating,content,"createdAt","updatedAt")
SELECT ${sqlKey('review','g')},${sqlKey('place','g')},${sqlKey('user',1)},1+(g%5),'synthetic',timestamp '2026-01-01',timestamp '2026-01-01' FROM generate_series(1,2000) g;
INSERT INTO reports(id,"reporterId","targetType","targetId",reason,"createdAt")
SELECT ${sqlKey('report','g')},${sqlKey('user',1)},'PLACE',${sqlKey('place','g')},'synthetic',timestamp '2026-01-01'+g*interval '1 millisecond' FROM generate_series(1,${n}) g;
INSERT INTO cms.loco_report_processing(report_id,status,handled_by,handled_at)
SELECT ${sqlKey('report','g')},'IN_REVIEW',(SELECT id FROM cms.admin_account LIMIT 1),timestamp '2026-01-01' FROM generate_series(1,${n/2}) g WHERE ${processing==='half'?'true':'false'} AND EXISTS(SELECT 1 FROM cms.admin_account);
INSERT INTO place_marker_cells("mapId",precision,"cellLatKey","cellLngKey",latitude,longitude,"minLat","maxLat","minLng","maxLng",count,"exactKeyCount","groupKey","placeId","placeName","updatedAt")
SELECT "mapId",p,ROUND(latitude::numeric,p)::text,ROUND(longitude::numeric,p)::text,
AVG(latitude),AVG(longitude),MIN(latitude),MAX(latitude),MIN(longitude),MAX(longitude),COUNT(*)::int,
COUNT(DISTINCT ROUND(latitude::numeric,6)::text||':'||ROUND(longitude::numeric,6)::text)::int,
CASE WHEN COUNT(DISTINCT ROUND(latitude::numeric,6)::text||':'||ROUND(longitude::numeric,6)::text)=1 THEN MIN(ROUND(latitude::numeric,6)::text||':'||ROUND(longitude::numeric,6)::text) END,
CASE WHEN COUNT(*)=1 THEN MIN(id) END,CASE WHEN COUNT(*)=1 THEN MIN(name) END,timestamp '2026-01-01'
FROM places CROSS JOIN generate_series(1,3) p GROUP BY "mapId",p,ROUND(latitude::numeric,p),ROUND(longitude::numeric,p);
COMMIT;
ANALYZE;
`;
}

export function markerPath(map, viewport=1, level=5) {
  if (![0.01,1,10,100].includes(viewport)) throw Error('Unknown viewport');
  const width=Math.sqrt(viewport/100), a=37.5-width/2,b=37.5+width/2,c=127.5-width/2,d=127.5+width/2;
  return `/api/maps/${map}/places/markers?level=${level}&swLat=${a}&neLat=${b}&swLng=${c}&neLng=${d}`;
}

// Marker ordering has count ties; compare all returned business values, not arbitrary tie order.
export function canonical(value) {
  if (Array.isArray(value)) return '['+value.map(canonical).sort().join(',')+']';
  if (value && typeof value==='object') return '{'+Object.keys(value).sort().map(k=>JSON.stringify(k)+':'+canonical(value[k])).join(',')+'}';
  return JSON.stringify(value);
}
