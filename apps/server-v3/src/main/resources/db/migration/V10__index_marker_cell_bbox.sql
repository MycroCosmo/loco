-- E-26 B: index cell bounds, not their centroid; source coordinates stay unchanged.
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE INDEX place_marker_cells_bbox_gist_idx ON place_marker_cells USING gist
    (ST_MakeEnvelope("minLng", "minLat", "maxLng", "maxLat", 4326));
