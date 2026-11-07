package com.loco.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.springframework.http.MediaType;

/** Express body-parser runs before security, CORS, authentication and request quotas. */
final class RequestBodyParser {
  private static final int LIMIT = 10 * 1024 * 1024;
  private final ObjectMapper json;

  RequestBodyParser(ObjectMapper json) {
    this.json = json;
  }

  HttpServletRequest parse(HttpServletRequest request) throws IOException {
    String type = request.getContentType();
    if (type == null) return request;
    String mime = type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    boolean form = mime.equals("application/x-www-form-urlencoded");
    if (!form && !mime.equals("application/json")) return request;
    if (request.getHeader("Content-Length") == null
        && request.getHeader("Transfer-Encoding") == null) return request;
    String charset = "utf-8";
    for (String part : type.split(";")) {
      if (part.trim().toLowerCase(Locale.ROOT).startsWith("charset="))
        charset = part.trim().substring(8).replace("\"", "").toLowerCase(Locale.ROOT);
    }
    if ((form && !charset.equals("utf-8")) || (!form && !charset.startsWith("utf-")))
      throw failure(415, "unsupported charset \"" + charset.toUpperCase(Locale.ROOT) + "\"");
    Charset decoder;
    try {
      decoder = Charset.forName(charset);
    } catch (IllegalArgumentException e) {
      throw failure(415, "unsupported charset \"" + charset.toUpperCase(Locale.ROOT) + "\"");
    }
    String encoding = request.getHeader("Content-Encoding");
    encoding = encoding == null ? "identity" : encoding.toLowerCase(Locale.ROOT);
    if (!java.util.Set.of("identity", "gzip", "deflate").contains(encoding))
      throw failure(415, "unsupported content encoding \"" + encoding + "\"");
    if (encoding.equals("identity") && request.getContentLengthLong() > LIMIT)
      throw failure(413, "request entity too large");
    byte[] bytes;
    try {
      InputStream input = request.getInputStream();
      input =
          switch (encoding) {
            case "gzip" -> new GZIPInputStream(input);
            case "deflate" -> new InflaterInputStream(input);
            default -> input;
          };
      bytes = input.readNBytes(LIMIT + 1);
    } catch (IOException error) {
      throw failure(400, "Malformed request body");
    }
    if (bytes.length > LIMIT) throw failure(413, "request entity too large");
    String text = new String(bytes, decoder);
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    byte[] body;
    if (form) {
      body = json.writeValueAsBytes(ExtendedForm.parse(text, json));
    } else {
      if (text.isEmpty()) text = "{}";
      try {
        var parsed =
            json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
        if (parsed == null || !parsed.isContainerNode()) throw failure(400, "Malformed JSON");
      } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
        throw failure(400, "Malformed JSON");
      }
      body = text.getBytes(StandardCharsets.UTF_8);
    }
    return new ParsedRequest(request, body);
  }

  static Failure failure(int status, String message) {
    return new Failure(status, message);
  }

  static final class Failure extends RuntimeException {
    final int status;

    Failure(int status, String message) {
      super(message);
      this.status = status;
    }

    Map<String, Object> body() {
      return status == 400
          ? Map.of("statusCode", status, "message", getMessage(), "error", "Bad Request")
          : Map.of("statusCode", status, "message", getMessage());
    }
  }

  private static final class ParsedRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    ParsedRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public String getContentType() {
      return MediaType.APPLICATION_JSON_VALUE;
    }

    @Override
    public String getCharacterEncoding() {
      return "UTF-8";
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }

    @Override
    public String getHeader(String name) {
      if (name.equalsIgnoreCase("Content-Type")) return getContentType();
      if (name.equalsIgnoreCase("Content-Length")) return Integer.toString(body.length);
      if (name.equalsIgnoreCase("Content-Encoding") || name.equalsIgnoreCase("Transfer-Encoding"))
        return null;
      return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      if (java.util.Set.of(
              "content-type", "content-length", "content-encoding", "transfer-encoding")
          .contains(name.toLowerCase(Locale.ROOT))) {
        String value = getHeader(name);
        return Collections.enumeration(
            value == null ? java.util.List.of() : java.util.List.of(value));
      }
      return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      var names = new java.util.ArrayList<>(Collections.list(super.getHeaderNames()));
      names.removeIf(
          n -> n.equalsIgnoreCase("Content-Encoding") || n.equalsIgnoreCase("Transfer-Encoding"));
      return Collections.enumeration(names);
    }

    @Override
    public ServletInputStream getInputStream() {
      var bytes = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return bytes.read();
        }

        @Override
        public int read(byte[] target, int offset, int length) {
          return bytes.read(target, offset, length);
        }

        @Override
        public boolean isFinished() {
          return bytes.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new IllegalStateException("Synchronous parsed body");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }
}
