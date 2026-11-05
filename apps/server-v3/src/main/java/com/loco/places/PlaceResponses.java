package com.loco.places;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.loco.maps.MapResponses;
import com.loco.users.UserResponses;
import jakarta.persistence.Tuple;
import java.util.List;

public final class PlaceResponses {
  private PlaceResponses() {}

  public record Core(
      String id,
      String mapId,
      String createdById,
      String name,
      String address,
      String roadAddress,
      Number latitude,
      Number longitude,
      String phone,
      String category,
      String memo,
      String[] tags,
      String[] imageUrls,
      String createdAt,
      String updatedAt,
      MapResponses.Owner createdBy) {}

  public record Read(
      @JsonUnwrapped Core place,
      Number averageRating,
      long reviewCount,
      @JsonInclude(JsonInclude.Include.NON_NULL) Object map) {}

  public record Page(List<Read> items, String nextCursor, boolean hasMore) {}

  public static Core core(Tuple t) {
    return new Core(
        text(t, "id"),
        text(t, "mapId"),
        text(t, "createdById"),
        text(t, "name"),
        text(t, "address"),
        text(t, "roadAddress"),
        jsonNumber(PrismaFloats.input(number(t, "latitude").doubleValue())),
        jsonNumber(PrismaFloats.input(number(t, "longitude").doubleValue())),
        text(t, "phone"),
        text(t, "category"),
        text(t, "memo"),
        array(t, "tags"),
        array(t, "imageUrls"),
        time(t, "createdAt"),
        time(t, "updatedAt"),
        new MapResponses.Owner(
            text(t, "createdById"), text(t, "authorNickname"), text(t, "authorImage")));
  }

  static Read read(Tuple t, String shape) {
    Object map = null;
    if (shape.equals("detail"))
      map =
          new MapResponses.Core(
              text(t, "mapId"),
              text(t, "mapOwner"),
              text(t, "mapTitle"),
              text(t, "mapDescription"),
              array(t, "mapTags"),
              text(t, "mapCover"),
              text(t, "mapVisibility"),
              text(t, "mapCode"),
              time(t, "mapCreated"),
              time(t, "mapUpdated"));
    if (shape.equals("mine"))
      map = java.util.Map.of("id", text(t, "mapId"), "title", text(t, "mapTitle"));
    return new Read(
        core(t), jsonNumber(number(t, "averageRating")), number(t, "reviewCount").longValue(), map);
  }

  private static Number jsonNumber(Number value) {
    double n = value.doubleValue();
    if (n == Math.rint(n)) return Long.valueOf((long) n);
    return Double.valueOf(n);
  }

  static String text(Tuple t, String k) {
    Object v = t.get(k);
    return v == null ? null : v.toString();
  }

  static Number number(Tuple t, String k) {
    return (Number) t.get(k);
  }

  static String[] array(Tuple t, String k) {
    return t.get(k) == null ? new String[0] : (String[]) t.get(k);
  }

  static String time(Tuple t, String k) {
    Object v = t.get(k);
    return UserResponses.timestamp(
        v instanceof java.sql.Timestamp s ? s.toLocalDateTime() : (java.time.LocalDateTime) v);
  }
}
