package com.loco.auth;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

// validator.js 13.15.35의 기본 isEmail/isFQDN 규칙을 Java로 적용했다. licenses/validatorjs-MIT.txt 참고.
final class V2Email {
  private static final String UTF8 = "\\u00A1-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF";
  private static final Pattern LOCAL =
      Pattern.compile("[a-z0-9!#$%&'*+\\-/=?^_\\x60{|}~" + UTF8 + "]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern TLD =
      Pattern.compile(
          "([a-z\\u00A1-\\u00A8\\u00AA-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF]{2,}|xn[a-z0-9-]{2,})",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern FULL_WIDTH = Pattern.compile("[\\uff01-\\uff5e]");
  private static final Pattern SPACE =
      Pattern.compile(
          "[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]");
  private static final Pattern QUOTED =
      Pattern.compile(
          "([\\s\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f\\x21\\x23-\\x5b\\x5d-\\x7e\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF]"
              + "|(\\\\[\\x01-\\x09\\x0b\\x0c\\x0d-\\x7f\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF]))*");

  private V2Email() {}

  static boolean valid(String value) {
    if (value == null || value.length() > 254) return false;
    int at = value.lastIndexOf('@');
    if (at < 0) return false;
    String local = value.substring(0, at), domain = value.substring(at + 1);
    if (local.getBytes(StandardCharsets.UTF_8).length > 64
        || domain.getBytes(StandardCharsets.UTF_8).length > 254) return false;
    String[] labels = domain.split("\\.", -1);
    if (labels.length < 2
        || !TLD.matcher(labels[labels.length - 1]).matches()
        || SPACE.matcher(labels[labels.length - 1]).find()) return false;
    for (String label : labels) {
      boolean validLabel =
          !label.isEmpty()
              && label
                  .chars()
                  .allMatch(
                      c ->
                          c >= 0xa1
                              || c >= 'a' && c <= 'z'
                              || c >= 'A' && c <= 'Z'
                              || c >= '0' && c <= '9'
                              || c == '-');
      if (label.length() > 63
          || !validLabel
          || FULL_WIDTH.matcher(label).find()
          || label.startsWith("-")
          || label.endsWith("-")) return false;
    }
    if (local.length() >= 2 && local.startsWith("\"") && local.endsWith("\"")) {
      return QUOTED.matcher(local.substring(1, local.length() - 1)).matches();
    }
    for (String part : local.split("\\.", -1)) if (!LOCAL.matcher(part).matches()) return false;
    return true;
  }
}
