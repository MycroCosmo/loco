package com.loco.cmsadmin;

import com.cms.core.account.AdminProblem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.util.MultiValueMap;

record LocoReadFilter(Map<String, String> values, int size, Cursor cursor, String scope) {
  record Cursor(LocalDateTime time, String id) {}

  static LocoReadFilter parse(LocoReadResource resource, MultiValueMap<String, String> input) {
    var values = new TreeMap<String, String>();
    input.forEach(
        (key, list) -> {
          if ((!resource.filters.contains(key)
                  && !Set.of("from", "until", "size", "cursor").contains(key))
              || list.size() != 1) throw invalid();
          String value = list.getFirst().trim();
          if (!value.isEmpty()) values.put(key, value);
        });
    int size = 50;
    try {
      if (values.containsKey("size")) {
        if (!values.get("size").matches("[0-9]{1,3}")) throw invalid();
        size = Integer.parseInt(values.get("size"));
      }
      if (size < 1 || size > 100) throw invalid();
      for (var e : values.entrySet()) {
        String key = e.getKey(), value = e.getValue();
        if (key.equals("id") || key.endsWith("Id")) uuid(value);
        if (key.equals("search") || key.equals("tag")) {
          if (value.codePointCount(0, value.length()) > 100) throw invalid();
        }
        if (key.equals("from") || key.equals("until"))
          values.put(key, OffsetDateTime.parse(value).toInstant().toString());
        if (key.equals("provider") && !Set.of("LOCAL", "GOOGLE", "KAKAO", "NAVER").contains(value))
          throw invalid();
        if (key.equals("status") && !Set.of("ACTIVE", "DELETED", "ALL").contains(value))
          throw invalid();
        if (key.equals("visibility") && !Set.of("PUBLIC", "PRIVATE").contains(value))
          throw invalid();
        if (key.equals("targetType") && !Set.of("MAP", "PLACE", "USER").contains(value))
          throw invalid();
      }
      if (values.containsKey("from")
          && values.containsKey("until")
          && !Instant.parse(values.get("from")).isBefore(Instant.parse(values.get("until"))))
        throw invalid();
      String encoded = values.remove("cursor");
      values.remove("size");
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update(resource.name().getBytes(StandardCharsets.UTF_8));
      digest.update((byte) size);
      values.forEach(
          (k, v) -> {
            digest.update(k.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(v.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
          });
      String scope = HexFormat.of().formatHex(digest.digest());
      Cursor cursor = null;
      if (encoded != null) {
        if (encoded.length() > 512) throw invalid();
        String[] parts =
            new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
                .split("\\|", -1);
        if (parts.length != 4 || !parts[0].equals("1") || !parts[3].equals(scope)) throw invalid();
        var time = LocalDateTime.parse(parts[1]);
        if (time.getNano() % 1_000_000 != 0) throw invalid();
        cursor = new Cursor(time, uuid(parts[2]));
      }
      return new LocoReadFilter(Map.copyOf(values), size, cursor, scope);
    } catch (AdminProblem invalid) {
      throw invalid;
    } catch (Exception invalid) {
      throw invalid();
    }
  }

  String next(Map<String, Object> row) {
    var time =
        LocalDateTime.ofInstant(Instant.parse((String) row.get("createdAt")), ZoneOffset.UTC);
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            ("1|" + time + "|" + row.get("id") + "|" + scope).getBytes(StandardCharsets.UTF_8));
  }

  static String uuid(String value) {
    try {
      if (!UUID.fromString(value).toString().equals(value)) throw invalid();
      return value;
    } catch (IllegalArgumentException invalid) {
      throw invalid();
    }
  }

  static AdminProblem invalid() {
    return new AdminProblem(400, "Invalid query or cursor");
  }
}
