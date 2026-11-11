-- E-20: replace centers produced by the former Java/Prisma round trip with direct PG aggregates.
-- Rebuild only derived cells; original places and the E-5 coordinate-key rule stay unchanged.
-- Pause writers during cutover; old v2 writers must not rebuild cells after this migration.
LOCK TABLE places IN SHARE MODE;
LOCK TABLE place_marker_cells IN EXCLUSIVE MODE;
DELETE FROM place_marker_cells;
INSERT INTO place_marker_cells("mapId",precision,"cellLatKey","cellLngKey",latitude,longitude,"minLat","maxLat","minLng","maxLng",count,"exactKeyCount","groupKey","placeId","placeName","updatedAt")
SELECT "mapId",p,ROUND(latitude::numeric,p)::text,ROUND(longitude::numeric,p)::text,
AVG(latitude),AVG(longitude),MIN(latitude),MAX(latitude),MIN(longitude),MAX(longitude),COUNT(*)::int,
COUNT(DISTINCT ROUND(latitude::numeric,6)::text||':'||ROUND(longitude::numeric,6)::text)::int,
CASE WHEN COUNT(DISTINCT ROUND(latitude::numeric,6)::text||':'||ROUND(longitude::numeric,6)::text)=1 THEN MIN(ROUND(latitude::numeric,6)::text||':'||ROUND(longitude::numeric,6)::text) END,
CASE WHEN COUNT(*)=1 THEN MIN(id) END,CASE WHEN COUNT(*)=1 THEN MIN(name) END,CURRENT_TIMESTAMP
FROM places CROSS JOIN generate_series(1,3) p
GROUP BY "mapId",p,ROUND(latitude::numeric,p)::text,ROUND(longitude::numeric,p)::text;
