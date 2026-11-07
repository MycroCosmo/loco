package com.loco.places;

import com.loco.web.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/** DTO가 없는 v2 쿼리의 Number/parseFloat/trim 경계를 유지한다. */
final class MarkerInputs {
  private static final Pattern FLOAT =
      Pattern.compile("^[+-]?(?:Infinity|(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");
  private final HttpServletRequest request;

  MarkerInputs(HttpServletRequest request) {
    this.request = request;
  }

  String raw(String key) {
    var v = request.getParameterValues(key);
    if (v == null) v = request.getParameterValues(key + "[]");
    if (v != null) return String.join(",", v);
    return request.getParameterMap().keySet().stream().anyMatch(k -> k.startsWith(key + "["))
        ? "[object Object]"
        : null;
  }

  static String trim(String value) {
    return value == null
        ? null
        : value.replaceAll(
            "^[\\s\\p{Zs}\\u2028\\u2029\\uFEFF]+|[\\s\\p{Zs}\\u2028\\u2029\\uFEFF]+$", "");
  }

  static double number(String value) {
    if (value == null) return Double.NaN;
    value = trim(value);
    if (value.isEmpty()) return 0;
    try {
      if (value.matches("0[xX][0-9a-fA-F]+"))
        return new java.math.BigInteger(value.substring(2), 16).doubleValue();
      if (value.matches("0[bB][01]+"))
        return new java.math.BigInteger(value.substring(2), 2).doubleValue();
      if (value.matches("0[oO][0-7]+"))
        return new java.math.BigInteger(value.substring(2), 8).doubleValue();
      if (FLOAT.matcher(value).matches()) return Double.parseDouble(value);
    } catch (NumberFormatException ignored) {
    }
    return Double.NaN;
  }

  double floating(String key) {
    String s = raw(key);
    if (s == null) return Double.NaN;
    var m = FLOAT.matcher(trim(s));
    return m.find() ? number(m.group()) : Double.NaN;
  }

  int level() {
    String s = raw("level");
    if (s == null || s.isEmpty()) return 5;
    double d = number(s);
    if (!Double.isFinite(d) || d != Math.rint(d) || d < 1 || d > 14)
      throw ApiProblem.badRequest("level은 숫자여야 합니다.");
    return (int) d;
  }

  int precision() {
    double p = number(raw("precision"));
    if (p != 1 && p != 2 && p != 3) throw ApiProblem.badRequest("precision은 1, 2, 3 중 하나여야 합니다.");
    return (int) p;
  }

  int limit() {
    String s = trim(raw("limit"));
    if (s == null) return 20;
    var m = Pattern.compile("^[+-]?[0-9]+").matcher(s);
    if (!m.find()) return 20;
    var n = new java.math.BigInteger(m.group());
    return n.signum() == 0
        ? 20
        : n.max(java.math.BigInteger.ONE).min(java.math.BigInteger.valueOf(50)).intValue();
  }

  String[] group() {
    String key = raw("groupKey");
    String[] parts;
    if (key != null && !key.isEmpty()) {

      parts = key.split(":", -1);
    } else parts = new String[] {fixed(floating("lat")), fixed(floating("lng"))};
    return coordinates(parts);
  }

  String[] cell() {

    return coordinates(new String[] {raw("cellLatKey"), raw("cellLngKey")});
  }

  private static String fixed(double v) {
    return Double.isFinite(v) && Math.abs(v) < 1e21
        ? CoordinateKeys.fixed(v, 6)
        : Double.toString(v);
  }

  private static String[] coordinates(String[] parts) {
    if (parts.length != 2) throw ApiProblem.badRequest("좌표가 올바르지 않습니다.");
    String lat = trim(parts[0]), lng = trim(parts[1]);
    double a = number(lat), b = number(lng);
    if (lat == null
        || lng == null
        || lat.isEmpty()
        || lng.isEmpty()
        || !Double.isFinite(a)
        || !Double.isFinite(b)
        || Math.abs(a) > 90
        || Math.abs(b) > 180) throw ApiProblem.badRequest("좌표가 올바르지 않습니다.");
    return new String[] {lat, lng};
  }
}
