package com.loco.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** body-parser extended=true: repeated keys, bracket objects/arrays, depth and parameter limits. */
final class ExtendedForm {
  private static final Pattern CHILD = Pattern.compile("\\[([^\\[\\]]*)]");

  private ExtendedForm() {}

  static ObjectNode parse(String body, ObjectMapper json) {
    long separators = body.chars().filter(c -> c == '&').count();
    if (separators >= 1000) throw RequestBodyParser.failure(413, "too many parameters");
    int arrayLimit = (int) Math.max(100, separators);
    var values = new LinkedHashMap<String, JsonNode>();
    for (String part : body.split("&", -1)) {
      if (part.isEmpty()) continue;
      int equals = part.indexOf('=');
      String key = decode(equals < 0 ? part : part.substring(0, equals));
      var value = TextNode.valueOf(decode(equals < 0 ? "" : part.substring(equals + 1)));
      values.merge(key, value, (left, right) -> append(left, right, json));
    }
    ObjectNode result = json.createObjectNode();
    for (var entry : values.entrySet()) {
      String key = entry.getKey();
      if (key.isEmpty()) continue;
      var matcher = CHILD.matcher(key);
      var segments = new ArrayList<String>();
      boolean bracket = matcher.find();
      String parent = bracket ? key.substring(0, matcher.start()) : key;
      boolean hasParent = !parent.isEmpty();
      if (hasParent) segments.add(parent);
      int depth = 0;
      if (bracket)
        do {
          segments.add(matcher.group(1));
          depth++;
        } while (matcher.find());
      if (depth > 32) throw RequestBodyParser.failure(400, "The input exceeded the depth");
      JsonNode node = entry.getValue();
      for (int i = segments.size() - 1; i >= 0; i--) {
        String segment = segments.get(i);
        if (segment.equals("__proto__")) {
          node = json.createObjectNode();
        } else if ((i > 0 || !hasParent) && segment.isEmpty()) {
          if (!node.isArray()) node = json.createArrayNode().add(node);
        } else if ((i > 0 || !hasParent)
            && segment.matches("0|[1-9][0-9]{0,3}")
            && Integer.parseInt(segment) <= arrayLimit) {
          var array = json.createArrayNode();
          int index = Integer.parseInt(segment);
          for (int n = 0; n < index; n++) array.addNull();
          node = array.add(node);
        } else {
          node = json.createObjectNode().set(segment, node);
        }
      }
      merge(result, node, json);
    }
    return (ObjectNode) compact(result, json);
  }

  private static ArrayNode append(JsonNode left, JsonNode right, ObjectMapper json) {
    var array = json.createArrayNode();
    if (left.isArray()) array.addAll((ArrayNode) left);
    else array.add(left);
    if (right.isArray()) array.addAll((ArrayNode) right);
    else array.add(right);
    return array;
  }

  private static JsonNode merge(JsonNode target, JsonNode source, ObjectMapper json) {
    if (!source.isContainerNode()) {
      if (target.isArray()) return ((ArrayNode) target).add(source);
      if (target.isObject()) {
        ((ObjectNode) target).put(source.asText(), true);
        return target;
      }
      return append(target, source, json);
    }
    if (!target.isContainerNode()) return append(target, source, json);
    if (target.isArray() && source.isObject()) {
      var object = json.createObjectNode();
      for (int i = 0; i < target.size(); i++)
        if (!target.get(i).isNull()) object.set(Integer.toString(i), target.get(i));
      target = object;
    }
    if (target.isArray() && source.isArray()) {
      var array = (ArrayNode) target;
      for (int i = 0; i < source.size(); i++) {
        var next = source.get(i);
        if (next.isNull()) continue;
        if (i >= array.size() || array.get(i).isNull()) {
          while (array.size() <= i) array.addNull();
          array.set(i, next);
        } else if (array.get(i).isContainerNode() && next.isContainerNode()) {
          array.set(i, merge(array.get(i), next, json));
        } else array.add(next);
      }
      return array;
    }
    var object = (ObjectNode) target;
    if (source.isArray()) {
      for (int i = 0; i < source.size(); i++) {
        var next = source.get(i);
        String key = Integer.toString(i);
        if (!next.isNull())
          object.set(key, object.has(key) ? merge(object.get(key), next, json) : next);
      }
    } else {
      source
          .properties()
          .forEach(
              e ->
                  object.set(
                      e.getKey(),
                      object.has(e.getKey())
                          ? merge(object.get(e.getKey()), e.getValue(), json)
                          : e.getValue()));
    }
    return object;
  }

  private static JsonNode compact(JsonNode node, ObjectMapper json) {
    if (node.isArray()) {
      var result = json.createArrayNode();
      node.forEach(
          v -> {
            if (!v.isNull()) result.add(compact(v, json));
          });
      return result;
    }
    if (node.isObject())
      node.properties()
          .forEach(e -> ((ObjectNode) node).set(e.getKey(), compact(e.getValue(), json)));
    return node;
  }

  private static String decode(String text) {
    String value = text.replace('+', ' ');
    try {
      var bytes = new ByteArrayOutputStream();
      for (int i = 0; i < value.length(); ) {
        if (value.charAt(i) == '%') {
          if (i + 2 >= value.length()) return value;
          int high = Character.digit(value.charAt(i + 1), 16),
              low = Character.digit(value.charAt(i + 2), 16);
          if (high < 0 || low < 0) return value;
          bytes.write(high * 16 + low);
          i += 3;
        } else {
          int next = value.indexOf('%', i);
          if (next < 0) next = value.length();
          bytes.writeBytes(value.substring(i, next).getBytes(StandardCharsets.UTF_8));
          i = next;
        }
      }
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes.toByteArray()))
          .toString();
    } catch (java.nio.charset.CharacterCodingException error) {
      return value;
    }
  }
}
