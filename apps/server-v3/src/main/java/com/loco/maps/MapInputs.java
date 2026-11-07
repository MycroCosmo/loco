package com.loco.maps;

import com.loco.web.ApiProblem;

public final class MapInputs {
  private MapInputs() {}

  public record Listing(String cursor, int limit, String visibility, String sort, String search) {}

  public static Listing listing(
      String cursor, String limit, String visibility, String sort, String search) {
    if (visibility != null
        && !visibility.isEmpty()
        && !visibility.equals("PUBLIC")
        && !visibility.equals("PRIVATE")) throw ApiProblem.badRequest("지원하지 않는 공개 설정입니다.");
    if (sort != null
        && !sort.isEmpty()
        && !java.util.Set.of("latest", "popular", "places").contains(sort))
      throw ApiProblem.badRequest("sort는 latest, popular, places만 가능합니다.");
    int size = 20;
    if (limit != null) {
      var parsed = java.util.regex.Pattern.compile("^[\\s]*([+-]?\\d+)").matcher(limit);
      if (parsed.find()) {
        var number = new java.math.BigInteger(parsed.group(1));
        if (number.signum() != 0)
          size =
              number.max(java.math.BigInteger.ONE).min(java.math.BigInteger.valueOf(50)).intValue();
      }
    }
    return new Listing(
        cursor,
        size,
        visibility,
        sort == null || sort.isEmpty() ? "latest" : sort,
        search == null ? null : search.strip());
  }
}
