package com.loco.images;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import com.loco.images.codec.ImageConverter;
import com.loco.web.ApiProblem;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ProfileImageStorage {
    private static final Set<String> MIMES = Set.of("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif");
    private final Path root;
    private final ImageConverter converter;
    private final LocalImageFiles files;
    public ProfileImageStorage(LocalImageFiles files, ImageConverter converter) {
        root = files.root(); this.files=files; this.converter = converter;
    }
    public Path root() { return root; }
    public String save(MultipartFile file) {
        if (file == null) throw ApiProblem.badRequest("이미지 파일을 첨부해주세요.");
        if (!MIMES.contains(file.getContentType() == null ? "" : file.getContentType())) throw ApiProblem.badRequest("지원하지 않는 이미지 형식입니다.");
        if (file.getSize() >= 10 * 1024 * 1024) throw ApiProblem.payloadTooLarge("File too large");
        if (file.isEmpty()) throw ApiProblem.badRequest("빈 파일입니다.");
        try {
            byte[] bytes = file.getBytes();
            ImageConverter.Metadata metadata;
            try { metadata = converter.metadata(bytes); }
            catch (ImageConverter.UnsupportedImage exception) { throw ApiProblem.badRequest("지원하지 않는 이미지 파일입니다."); }
            if (metadata.format().equals("heic")) {
                // 플랫폼의 HEVC 지원 여부와 관계없이 관측한 v2 HTTP·파일 동작을 유지한다.
                newFile(); throw new IllegalStateException("HEIC upload follows v2 decoder limitation");
            }
            if (!Set.of("jpeg", "png", "webp", "avif").contains(metadata.format())) throw ApiProblem.badRequest("지원하지 않는 이미지 형식입니다.");
            Path target = newFile();
            var decoded = converter.read(bytes);
            byte[] webp = converter.writeWebP(crop(decoded));
            Files.write(target, webp);
            return "/uploads/profiles/" + target.getFileName();
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }
    private Path newFile() throws IOException {
        Path directory = root.resolve("profiles"); Files.createDirectories(directory);
        return Files.createFile(directory.resolve(UUID.randomUUID() + ".webp"));
    }
    private BufferedImage crop(BufferedImage source) {
        double scale = Math.min(1, Math.max(512.0 / source.getWidth(), 512.0 / source.getHeight()));
        int width = Math.min(512, (int) Math.round(source.getWidth() * scale));
        int height = Math.min(512, (int) Math.round(source.getHeight() * scale));
        var image = new BufferedImage(width, height, source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.translate((width - source.getWidth() * scale) / 2, (height - source.getHeight() * scale) / 2);
            graphics.scale(scale, scale); graphics.drawImage(source, 0, 0, null);
        } finally { graphics.dispose(); }
        return image;
    }
    public void delete(String url) {
        if (url == null || !url.startsWith("/uploads/profiles/")) return;
        files.delete(url);
    }
}
