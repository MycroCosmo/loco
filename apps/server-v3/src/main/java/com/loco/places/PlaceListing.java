package com.loco.places;

import com.loco.web.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;

public record PlaceListing(String cursor, int limit, String sort, String search, String tag) {
  public static PlaceListing parse(HttpServletRequest request) {
    String sort = parameter(request, "sort");
    String[] sorts = request.getParameterValues("sort");
    if (sorts != null && sorts.length > 1
        || sort != null
            && !sort.isEmpty()
            && !java.util.Set.of("latest", "name", "reviews").contains(sort))
      throw ApiProblem.badRequest("sort는 latest, name, reviews만 가능합니다.");
    int size = 20;
    String raw = strip(parameter(request, "limit"));
    if (raw != null) {
      var m = java.util.regex.Pattern.compile("^[\\s]*([+-]?\\d+)").matcher(raw);
      if (m.find()) {
        var n = new java.math.BigInteger(m.group(1));
        if (n.signum() != 0)
          size = n.max(java.math.BigInteger.ONE).min(java.math.BigInteger.valueOf(50)).intValue();
      }
    }
    return new PlaceListing(
        parameter(request, "cursor"),
        size,
        sort == null || sort.isEmpty() ? "latest" : sort,
        strip(parameter(request, "search")),
        strip(parameter(request, "tag")));
  }

  private static String parameter(HttpServletRequest r, String key) {
    var values = r.getParameterValues(key);
    if (values != null) return String.join(",", values);
    values = r.getParameterValues(key + "[]");
    if (values != null) return String.join(",", values);
    return r.getParameterMap().keySet().stream().anyMatch(k -> k.startsWith(key + "["))
        ? "[object Object]"
        : null;
  }

  private static String strip(String s) {
    // ECMAScript trim에는 Java strip이 남기는 NBSP와 BOM도 포함된다.
    return s == null
        ? null
        : s.replaceAll(
            "^[\\s\\p{Zs}\\u2028\\u2029\\uFEFF]+|[\\s\\p{Zs}\\u2028\\u2029\\uFEFF]+$", "");
  }
}
