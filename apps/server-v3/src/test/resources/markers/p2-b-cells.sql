-- P2 B (91d653a): frozen pre-PostGIS response-set oracle.
WITH matching AS NOT MATERIALIZED (
        SELECT precision,"cellLatKey","cellLngKey",latitude,longitude,count,"exactKeyCount","groupKey","placeId","placeName"
        FROM place_marker_cells WHERE "mapId"=:map AND precision=:precision
          AND "maxLat">=:minLat AND "minLat"<=:maxLat AND "maxLng">=:minLng AND "minLng"<=:maxLng
      ), probe AS MATERIALIZED (SELECT * FROM matching LIMIT 700),
      top_cells AS MATERIALIZED (
        SELECT * FROM matching WHERE (SELECT count(*) FROM probe)=700
        ORDER BY count DESC,"cellLatKey" COLLATE "C","cellLngKey" COLLATE "C" LIMIT 699
      ), visible AS MATERIALIZED (
        SELECT * FROM probe WHERE (SELECT count(*) FROM probe)<700
        UNION ALL SELECT * FROM top_cells
      )
      SELECT visible.*,false AS "isOverflow" FROM visible
      UNION ALL
      SELECT NULL,NULL,NULL,
        COALESCE(SUM(m.latitude::numeric*m.count)/NULLIF(SUM(m.count),0),0)::float8,
        COALESCE(SUM(m.longitude::numeric*m.count)/NULLIF(SUM(m.count),0),0)::float8,
        SUM(m.count)::bigint,NULL,NULL,NULL,NULL,true
      FROM matching m WHERE (SELECT count(*) FROM probe)=700
        AND (m."cellLatKey",m."cellLngKey") NOT IN (SELECT v."cellLatKey",v."cellLngKey" FROM visible v)
      HAVING count(*)>0
