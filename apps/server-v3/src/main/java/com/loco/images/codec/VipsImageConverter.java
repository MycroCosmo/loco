package com.loco.images.codec;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class VipsImageConverter implements ImageConverter {
    private final String executable;
    private final String headerExecutable;
    public VipsImageConverter(@Value("${loco.vips.executable:vips}") String executable) {
        this.executable = executable.isBlank() ? "vips" : executable;
        Path path = Path.of(this.executable);
        String header = path.getFileName().toString().endsWith(".exe") ? "vipsheader.exe" : "vipsheader";
        headerExecutable = path.getParent() == null ? header : path.resolveSibling(header).toString();
    }
    @Override public Metadata metadata(byte[] input) throws IOException {
        try (var work = new Work()) {
            Path source = work.directory.resolve("input"); Files.write(source, input);
            String output = run(work, headerExecutable, "-a", source.toString());
            var fields = new HashMap<String, String>();
            output.lines().forEach(line -> { int colon = line.indexOf(": "); if (colon > 0) fields.put(line.substring(0, colon), line.substring(colon + 2).trim()); });
            String loader = fields.getOrDefault("vips-loader", "");
            String format = switch (loader) {
                case "jpegload" -> "jpeg";
                case "pngload", "spngload" -> "png";
                case "webpload" -> "webp";
                case "gifload", "nsgifload" -> "gif";
                case "heifload" -> "av1".equals(fields.get("heif-compression")) ? "avif" : "heic";
                default -> loader;
            };
            try { return new Metadata(format, Integer.parseInt(fields.get("width")), Integer.parseInt(fields.get("height"))); }
            catch (RuntimeException error) { throw new IOException("Invalid libvips metadata", error); }
        } catch (CommandFailed error) { throw new UnsupportedImage(error.getMessage()); }
    }
    @Override public BufferedImage read(byte[] input) throws IOException {
        try (var work = new Work()) {
            Path source = work.directory.resolve("input"), output = work.directory.resolve("decoded.png");
            Files.write(source, input);
            try { run(work, executable, "copy", source + "[fail-on=warning]", output.toString()); }
            catch (CommandFailed error) {
                if (metadata(input).format().equals("heic")) throw new UnsupportedImage("libvips HEIC decoding failed in this native build");
                throw error;
            }
            BufferedImage image = ImageIO.read(output.toFile());
            if (image == null) throw new IOException("libvips produced unreadable PNG");
            return image;
        }
    }
    @Override public byte[] writeWebP(BufferedImage image) throws IOException {
        try (var work = new Work()) {
            Path source = work.directory.resolve("input.png"), output = work.directory.resolve("output.webp");
            if (!ImageIO.write(image, "png", source.toFile())) throw new IOException("No PNG writer");
            run(work, executable, "copy", source.toString(), output + "[Q=80,strip=true]");
            return Files.readAllBytes(output);
        }
    }
    private String run(Work work, String command, String... arguments) throws IOException {
        var args = new ArrayList<String>(); args.add(command); args.addAll(List.of(arguments));
        Path log = work.directory.resolve("command.log");
        var builder = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("VIPS_CONCURRENCY", "2");
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly(); process.waitFor(); throw new IOException("libvips timed out after 30 seconds");
            }
            String output;
            try (var stream = Files.newInputStream(log)) { output = new String(stream.readNBytes(65536), java.nio.charset.StandardCharsets.UTF_8); }
            if (process.exitValue() != 0 || output.contains("VIPS-WARNING")) throw new CommandFailed(output);
            return output;
        } catch (InterruptedException error) {
            process.destroyForcibly(); Thread.currentThread().interrupt(); throw new IOException("libvips interrupted", error);
        }
    }
    private static final class CommandFailed extends IOException {
        CommandFailed(String output) { super(output.length() > 1024 ? output.substring(0,1024) : output); }
    }
    private static final class Work implements AutoCloseable {
        final Path directory = Files.createTempDirectory("loco-vips-");
        Work() throws IOException { }
        @Override public void close() throws IOException {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
