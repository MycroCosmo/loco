package com.loco.images.codec;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class CodecProbe {
    public static final List<String> FORMATS = List.of("jpeg", "png", "webp", "heic", "avif");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int[][] COLORS = {{240,32,32}, {32,224,32}, {32,32,240}, {240,224,32}};
    private static final int[][] POINTS = {{8,8}, {48,8}, {8,36}, {48,36}};
    private final ImageConverter converter;
    private final Path directory;
    public CodecProbe(ImageConverter converter, Path directory) { this.converter = converter; this.directory = directory; }
    public record Result(String format, String status, boolean v2Read, String sha256, String detail) { }

    public Result run(String format) throws Exception {
        if (Runtime.version().feature() != 21) throw new IllegalStateException("Java 21 required");
        byte[] input = resource("quadrants." + format);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        JsonNode baseline = JSON.readTree(resource("v2-observation.json")).path("cases");
        JsonNode expected = null;
        for (var row : baseline) if (row.path("format").asText().equals(format)) expected = row;
        if (expected == null || !hash.equals(expected.path("sha256").asText())) throw new IllegalStateException("Fixture differs from v2 observation: " + format);
        boolean v2Read = expected.path("read").asBoolean();
        try {
            var decoded = converter.read(input);
            verifyPixels(decoded);
            byte[] output = converter.writeWebP(decoded);
            if (output.length < 12 || !new String(output, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                    || !new String(output, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) throw new AssertionError("Output is not WebP");
            verifyPixels(converter.read(output));
            Files.createDirectories(directory.resolve("outputs"));
            Files.write(directory.resolve("outputs/" + format + ".webp"), output);
            return new Result(format, "PASS", v2Read, hash, "64x48 input and WebP output; quadrant RGB error <= 24");
        } catch (ImageConverter.UnsupportedImage error) {
            boolean optional = format.equals("heic") || format.equals("avif");
            return new Result(format, optional ? (v2Read ? "KNOWN_DIFFERENCE" : "SHARED_LIMITATION") : "FAIL", v2Read, hash, error.getMessage());
        } catch (Exception | LinkageError | AssertionError error) {
            return new Result(format, "FAIL", v2Read, hash, error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private static byte[] resource(String name) throws Exception {
        try (var input = CodecProbe.class.getResourceAsStream("/codec/" + name)) {
            if (input == null) throw new IllegalStateException("Missing fixture: " + name);
            return input.readAllBytes();
        }
    }
    private static void verifyPixels(BufferedImage image) {
        if (image == null || image.getWidth() != 64 || image.getHeight() != 48) throw new AssertionError("Expected 64x48 image");
        for (int i = 0; i < POINTS.length; i++) {
            int rgb = image.getRGB(POINTS[i][0], POINTS[i][1]);
            for (int channel = 0; channel < 3; channel++) {
                if (Math.abs(((rgb >> (16 - channel * 8)) & 255) - COLORS[i][channel]) > 24) throw new AssertionError("Incorrect quadrant pixel");
            }
        }
    }

    public void report(List<Result> results, long elapsedMillis) throws Exception {
        Files.createDirectories(directory);
        long passed = results.stream().filter(r -> r.status().equals("PASS")).count();
        long failed = results.stream().filter(r -> r.status().equals("FAIL")).count();
        var report = Map.of("java", System.getProperty("java.version"), "os", System.getProperty("os.name"),
                "arch", System.getProperty("os.arch"), "library", "org.sejda.imageio:webp-imageio:0.1.6",
                "total", results.size(), "passed", passed, "failed", failed,
                "knownLimitations", results.size() - passed - failed, "elapsedMillis", elapsedMillis, "cases", results);
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("summary.json").toFile(), report);
        var markdown = new StringBuilder("# 이미지 코덱 검증\n\n").append(System.getProperty("os.name")).append(" / Java ")
                .append(System.getProperty("java.version")).append("\n\n| 형식 | v2 읽기 | Java 읽기·WebP 쓰기 |\n|---|---|---|\n");
        for (var result : results) markdown.append("| ").append(result.format()).append(" | ").append(result.v2Read()).append(" | ").append(result.status()).append(" |\n");
        markdown.append("\nKNOWN_DIFFERENCE: 알려진 차이. SHARED_LIMITATION: v2도 실패하는 알려진 지원 제약. FAIL은 승인된 예외가 아니며 진행을 중단한다.\n");
        Files.writeString(directory.resolve("summary.md"), markdown);
        System.out.printf("Codec: total=%d passed=%d failed=%d 알려진 차이/제약=%d; %s%n", results.size(), passed, failed, results.size()-passed-failed, directory);
    }

    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        var probe = new CodecProbe(new ImageIoConverter(), Path.of(args[0]));
        var results = new ArrayList<Result>();
        for (String format : FORMATS) results.add(probe.run(format));
        probe.report(results, (System.nanoTime() - start) / 1_000_000);
        if (results.stream().anyMatch(r -> r.status().equals("FAIL"))) System.exit(1);
    }
}
