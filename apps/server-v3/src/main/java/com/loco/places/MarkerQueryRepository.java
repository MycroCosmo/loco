package com.loco.places;

import com.loco.maps.*;
import com.loco.web.ApiProblem;
import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class MarkerQueryRepository {
  private final EntityManager em;
  private final MapRepository maps;
  private final MapPermissions permissions;

  public MarkerQueryRepository(EntityManager em, MapRepository maps, MapPermissions permissions) {
    this.em = em;
    this.maps = maps;
    this.permissions = permissions;
  }

  private void readable(String id, String user) {
    var map = maps.findById(id).orElseThrow(() -> ApiProblem.notFound("지도방을 찾을 수 없습니다."));
    permissions.requireReadable(id, map.getOwner().getId(), map.getVisibility().name(), user);
  }

  public List<Map<String, Object>> markers(String id, String user, MarkerInputs input, int level) {
    readable(id, user);
    double a = input.floating("swLat"),
        b = input.floating("neLat"),
        c = input.floating("swLng"),
        d = input.floating("neLng");
    if (!Double.isFinite(a) || !Double.isFinite(b) || !Double.isFinite(c) || !Double.isFinite(d))
      throw ApiProblem.badRequest("지도 영역 좌표가 올바르지 않습니다.");
    var params = new HashMap<String, Object>();
    params.put("map", id);
    params.put("minLat", PrismaFloats.input(Math.min(a, b)));
    params.put("maxLat", PrismaFloats.input(Math.max(a, b)));
    params.put("minLng", PrismaFloats.input(Math.min(c, d)));
    params.put("maxLng", PrismaFloats.input(Math.max(c, d)));
    if (level < 6) {
      var rows =
          rows(
              "SELECT id,name,latitude,longitude,ROUND(latitude::numeric,6)::text AS \"latKey\",ROUND(longitude::numeric,6)::text AS \"lngKey\" FROM places WHERE \"mapId\"=:map AND latitude>=:minLat AND latitude<=:maxLat AND longitude>=:minLng AND longitude<=:maxLng ORDER BY \"createdAt\" DESC,id DESC LIMIT 301",
              params);
      if (rows.size() <= 300) return individual(rows);
    }
    params.put("precision", level >= 11 ? 1 : level >= 9 ? 2 : 3);
    return cells(
        rows(
            "SELECT * FROM place_marker_cells WHERE \"mapId\"=:map AND precision=:precision AND \"maxLat\">=:minLat AND \"minLat\"<=:maxLat AND \"maxLng\">=:minLng AND \"minLng\"<=:maxLng ORDER BY count DESC,\"cellLatKey\" COLLATE \"C\" ASC,\"cellLngKey\" COLLATE \"C\" ASC",
            params));
  }

  public List<PlaceResponses.Read> group(String id, String user, MarkerInputs input) {
    readable(id, user);
    var keys = input.group();
    double lat = MarkerInputs.number(keys[0]), lng = MarkerInputs.number(keys[1]);
    var params = Map.<String, Object>of("map", id, "lat", lat, "lng", lng);
    return rows(
            PlaceQueryRepository.PROJECTION
                + " WHERE p.\"mapId\"=:map AND ROUND(p.latitude::numeric,6)=ROUND(CAST(:lat AS float8)::numeric,6) AND ROUND(p.longitude::numeric,6)=ROUND(CAST(:lng AS float8)::numeric,6) ORDER BY p.\"createdAt\" DESC,p.id DESC",
            params)
        .stream()
        .map(t -> PlaceResponses.read(t, "list"))
        .toList();
  }

  public Map<String, Object> cluster(String id, String user, MarkerInputs input, int precision) {
    readable(id, user);
    var keys = input.cell();
    // Old negative-zero URLs refer to the same zero cell; nonzero stale keys require refetching
    // markers.
    for (int i = 0; i < keys.length; i++)
      if (MarkerInputs.number(keys[i]) == 0)
        keys[i] = java.math.BigDecimal.ZERO.setScale(precision).toPlainString();
    var params =
        Map.<String, Object>of("map", id, "precision", precision, "lat", keys[0], "lng", keys[1]);
    int limit = input.limit();
    var cell =
        rows(
            "SELECT count FROM place_marker_cells WHERE \"mapId\"=:map AND precision=:precision AND \"cellLatKey\"=:lat AND \"cellLngKey\"=:lng",
            params);
    if (cell.isEmpty()) return Map.of("count", 0, "items", List.of(), "hasMore", false);
    // A literal precision preserves expression-index matching even with a generic prepared plan.
    String scale =
        switch (precision) {
          case 1 -> "1";
          case 2 -> "2";
          case 3 -> "3";
          default -> throw new IllegalArgumentException("Unsupported marker precision");
        };
    var ids =
        rows(
            "SELECT id FROM places WHERE \"mapId\"=:map AND ROUND(latitude::numeric,"
                + scale
                + ")::text=:lat AND ROUND(longitude::numeric,"
                + scale
                + ")::text=:lng ORDER BY \"createdAt\" DESC,id DESC LIMIT "
                + (limit + 1),
            Map.of("map", id, "lat", keys[0], "lng", keys[1]));
    boolean more = ids.size() > limit;
    var selected = ids.stream().limit(limit).map(t -> t.get("id").toString()).toList();
    List<PlaceResponses.Read> items = List.of();
    if (!selected.isEmpty()) {
      var found =
          rows(PlaceQueryRepository.PROJECTION + " WHERE p.id IN (:ids)", Map.of("ids", selected));
      var byId = new HashMap<String, PlaceResponses.Read>();
      found.forEach(t -> byId.put(t.get("id").toString(), PlaceResponses.read(t, "list")));
      items = selected.stream().map(byId::get).filter(Objects::nonNull).toList();
    }
    return Map.of("count", cell.getFirst().get("count"), "items", items, "hasMore", more);
  }

  @SuppressWarnings("unchecked")
  private List<Tuple> rows(String sql, Map<String, Object> params) {
    var q = em.createNativeQuery(sql, Tuple.class);
    params.forEach(q::setParameter);
    return q.getResultList();
  }

  private static double number(Tuple t, String key) {
    return PrismaFloats.input(((Number) t.get(key)).doubleValue());
  }

  private static String text(Tuple t, String key) {
    return PlaceResponses.text(t, key);
  }

  private static Map<String, Object> marker(
      String type, String id, String name, double lat, double lng, long count) {
    var m = new LinkedHashMap<String, Object>();
    m.put("type", type);
    m.put("id", id);
    m.put("name", name);
    m.put("latitude", PlaceResponses.jsonNumber(lat));
    m.put("longitude", PlaceResponses.jsonNumber(lng));
    m.put("count", count);
    return m;
  }

  private static List<Map<String, Object>> individual(List<Tuple> rows) {
    var groups = new LinkedHashMap<String, List<Tuple>>();
    for (var row : rows) {
      String key = text(row, "latKey") + ":" + text(row, "lngKey");
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
    }
    var result = new ArrayList<Map<String, Object>>();
    for (var entry : groups.entrySet()) {
      var group = entry.getValue();
      var first = group.getFirst();
      if (group.size() == 1)
        result.add(
            marker(
                "place",
                text(first, "id"),
                text(first, "name"),
                number(first, "latitude"),
                number(first, "longitude"),
                1));
      else {
        double lat = 0, lng = 0;
        for (var row : group) {
          lat += number(row, "latitude");
          lng += number(row, "longitude");
        }
        var m =
            marker(
                "group",
                entry.getKey(),
                group.size() + "개 장소",
                lat / group.size(),
                lng / group.size(),
                group.size());
        m.put("groupKey", entry.getKey());
        result.add(m);
      }
    }
    return result;
  }

  private static List<Map<String, Object>> cells(List<Tuple> rows) {
    var result = new ArrayList<Map<String, Object>>();
    for (var row : rows.subList(0, Math.min(699, rows.size()))) {
      int count = ((Number) row.get("count")).intValue(),
          exact = ((Number) row.get("exactKeyCount")).intValue();
      String place = text(row, "placeId"),
          name = text(row, "placeName"),
          group = text(row, "groupKey"),
          latKey = text(row, "cellLatKey"),
          lngKey = text(row, "cellLngKey");
      double lat = number(row, "latitude"), lng = number(row, "longitude");
      Map<String, Object> m;
      if (count == 1 && place != null && !place.isEmpty() && name != null && !name.isEmpty())
        m = marker("place", place, name, lat, lng, count);
      else if (exact == 1 && group != null && !group.isEmpty()) {
        m = marker("group", group, count + "개 장소", lat, lng, count);
        m.put("groupKey", group);
      } else {
        String key = latKey + ":" + lngKey;
        m = marker("cluster", key, count + "개 장소", lat, lng, count);
        m.put("clusterKey", key);
        m.put("precision", row.get("precision"));
        m.put("cellLatKey", latKey);
        m.put("cellLngKey", lngKey);
      }
      result.add(m);
    }
    if (rows.size() > 699) {
      long count = 0;
      double lat = 0, lng = 0;
      for (var row : rows.subList(699, rows.size())) {
        long n = ((Number) row.get("count")).longValue();
        count += n;
        lat += number(row, "latitude") * n;
        lng += number(row, "longitude") * n;
      }
      var m =
          marker(
              "cluster",
              "overflow",
              count + "개 장소",
              count == 0 ? 0 : lat / count,
              count == 0 ? 0 : lng / count,
              count);
      m.put("clusterKey", "overflow");
      result.add(m);
    }
    return result;
  }
}
