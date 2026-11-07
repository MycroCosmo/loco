package com.loco.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.Map;
import java.util.regex.Pattern;

final class KakaoSearchResponse {
  private static final Map<String, String> FIELDS =
      Map.of(
          "id",
          "id",
          "placeName",
          "place_name",
          "addressName",
          "address_name",
          "roadAddressName",
          "road_address_name",
          "phone",
          "phone",
          "categoryName",
          "category_name",
          "placeUrl",
          "place_url");
  private static final Pattern FLOAT =
      Pattern.compile("^[+-]?(?:Infinity|(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");

  static ObjectNode map(JsonNode raw) {
    if (raw == null
        || raw.path("meta").isMissingNode()
        || raw.path("meta").isNull()
        || !raw.path("documents").isArray())
      throw new IllegalArgumentException("Invalid upstream JSON shape");
    var result = JsonNodeFactory.instance.objectNode();
    var meta = result.putObject("meta");
    if (raw.path("meta").has("total_count"))
      meta.set("totalCount", raw.path("meta").get("total_count"));
    if (raw.path("meta").has("is_end")) meta.set("isEnd", raw.path("meta").get("is_end"));
    var docs = result.putArray("documents");
    for (var item : raw.path("documents")) {
      if (item.isNull()) throw new IllegalArgumentException("Null upstream document");
      var mapped = docs.addObject();
      FIELDS.forEach(
          (target, source) ->
              mapped.set(
                  target,
                  item.path(source).isNull() || item.path(source).isMissingNode()
                      ? TextNode.valueOf("")
                      : item.path(source)));
      coordinate(mapped, "latitude", item.path("y"));
      coordinate(mapped, "longitude", item.path("x"));
    }
    return result;
  }

  private static void coordinate(ObjectNode output, String field, JsonNode value) {
    var m = FLOAT.matcher(MarkerInputs.trim(jsString(value)));
    double d = m.find() ? Double.parseDouble(m.group()) : Double.NaN;
    if (!Double.isFinite(d)) output.putNull(field);
    else {
      String spelling = KakaoSearchInputs.number(d);
      if (spelling.contains(".") || spelling.contains("e")) output.put(field, d);
      else output.set(field, BigIntegerNode.valueOf(new java.math.BigInteger(spelling)));
    }
  }

  private static String jsString(JsonNode value) {
    if (value.isArray()) {
      var parts = new java.util.ArrayList<String>();
      value.forEach(v -> parts.add(v.isNull() ? "" : jsString(v)));
      return String.join(",", parts);
    }
    if (value.isObject()) return "[object Object]";
    if (value.isNumber()) return KakaoSearchInputs.number(value.doubleValue());
    return value.asText();
  }
}
