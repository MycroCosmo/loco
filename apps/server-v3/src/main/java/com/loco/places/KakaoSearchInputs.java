package com.loco.places;

import com.loco.web.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import java.math.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

final class KakaoSearchInputs {
  private static final List<String> NUMBERS = List.of("x", "y", "radius", "page", "size");
  private static final Pattern NUMERIC = Pattern.compile("^[+-]?(?:[0-9]*\\.)?[0-9]+$");

  static String query(HttpServletRequest request) {
    var errors = new ArrayList<String>();
    var roots = new LinkedHashSet<String>();
    request.getParameterMap().keySet().forEach(k -> roots.add(k.split("\\[", 2)[0]));
    for (String k : roots)
      if (!k.equals("query")
          && !NUMBERS.contains(k)
          && !Set.of("__proto__", "constructor").contains(k))
        errors.add("property " + k + " should not exist");
    String query = scalar(request, "query");
    if (!roots.contains("query") || "".equals(query)) errors.add("query should not be empty");
    if (query == null) errors.add("query must be a string");
    for (String k : NUMBERS) {
      String value = scalar(request, k);
      if (roots.contains(k) && (value == null || !NUMERIC.matcher(value).matches()))
        errors.add(k + " must be a number string");
    }
    if (!errors.isEmpty()) throw ApiProblem.invalid(errors);
    var values = new LinkedHashMap<String, String>();
    values.put("query", query);
    values.put("size", roots.contains("size") ? number(integer(scalar(request, "size"))) : "15");
    for (String k : List.of("x", "y", "radius", "page")) {
      String raw = scalar(request, k);
      if (raw == null) continue;
      double value = k.equals("x") || k.equals("y") ? Double.parseDouble(raw) : integer(raw);
      if (value != 0 && !Double.isNaN(value)) values.put(k, number(value));
    }
    return values.entrySet().stream()
        .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(java.util.stream.Collectors.joining("&"));
  }

  private static String scalar(HttpServletRequest r, String key) {
    String[] values = r.getParameterValues(key);
    if (r.getParameterMap().keySet().stream().anyMatch(k -> k.startsWith(key + "["))) return null;
    return values != null && values.length == 1 ? values[0] : null;
  }

  private static double integer(String value) {
    var m = Pattern.compile("^[+-]?[0-9]+").matcher(value);
    return m.find() ? new BigInteger(m.group()).doubleValue() : Double.NaN;
  }

  static String number(double value) {
    if (Double.isNaN(value)) return "NaN";
    if (Double.isInfinite(value)) return value < 0 ? "-Infinity" : "Infinity";
    if (value == 0) return "0";
    // JS는 같은 double로 돌아오는 가장 짧은 십진수를 선택한다. Java의 표기 임계값과 다르다.
    var exact = new BigDecimal(value);
    BigDecimal shortest = exact;
    for (int digits = 1; digits <= 17; digits++) {
      shortest = exact.round(new MathContext(digits, RoundingMode.HALF_EVEN)).stripTrailingZeros();
      if (shortest.doubleValue() == value) break;
    }
    double magnitude = Math.abs(value);
    if (magnitude >= 1e-6 && magnitude < 1e21) return shortest.toPlainString();
    int exponent = shortest.precision() - shortest.scale() - 1;
    return shortest.movePointLeft(exponent).toPlainString()
        + "e"
        + (exponent >= 0 ? "+" : "")
        + exponent;
  }
}
