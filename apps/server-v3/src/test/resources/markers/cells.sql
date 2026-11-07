INSERT INTO place_marker_cells("mapId",precision,"cellLatKey","cellLngKey",latitude,longitude,"minLat","maxLat","minLng","maxLng",count,"exactKeyCount","groupKey","placeId","placeName","updatedAt")
SELECT "mapId",p,ROUND(latitude::numeric,p)::text,ROUND(longitude::numeric,p)::text,
AVG(latitude),AVG(longitude),MIN(latitude),MAX(latitude),MIN(longitude),MAX(longitude),COUNT(*)::int,
COUNT(DISTINCT ROUND(latitude::numeric,6)::text||':'||ROUND(longitude::numeric,6)::text)::int,
CASE WHEN COUNT(DISTINCT ROUND(latitude::numeric,6)::text||':'||ROUND(longitude::numeric,6)::text)=1 THEN MIN(ROUND(latitude::numeric,6)::text||':'||ROUND(longitude::numeric,6)::text) END,
CASE WHEN COUNT(*)=1 THEN MIN(id) END, CASE WHEN COUNT(*)=1 THEN MIN(name) END,'2026-01-01'
FROM places CROSS JOIN generate_series(1,3)p
GROUP BY "mapId",p,ROUND(latitude::numeric,p)::text,ROUND(longitude::numeric,p)::text;
