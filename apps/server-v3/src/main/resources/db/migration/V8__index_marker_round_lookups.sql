-- Match the group numeric equality and cluster text equality exactly; keep source coordinates intact.
CREATE INDEX places_map_round6_idx ON places
    ("mapId", ROUND(latitude::numeric, 6), ROUND(longitude::numeric, 6));
CREATE INDEX places_map_round1_text_idx ON places
    ("mapId", (ROUND(latitude::numeric, 1)::text), (ROUND(longitude::numeric, 1)::text));
CREATE INDEX places_map_round2_text_idx ON places
    ("mapId", (ROUND(latitude::numeric, 2)::text), (ROUND(longitude::numeric, 2)::text));
CREATE INDEX places_map_round3_text_idx ON places
    ("mapId", (ROUND(latitude::numeric, 3)::text), (ROUND(longitude::numeric, 3)::text));
