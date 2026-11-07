package com.loco.images;

import com.loco.images.codec.ImageConverter;
import com.loco.web.ApiProblem;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class LocalImageStorage {
  private static final Set<String> MIMES =
      Set.of("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif");
  private final Path root;
  private final ImageConverter converter;

  public enum Purpose {
    PROFILE("profiles", 512, 512),
    MAP_COVER("maps", 800, 400),
    PLACE("places", 1200, 1200);
    final String folder;
    final int width;
    final int height;

    Purpose(String folder, int width, int height) {
      this.folder = folder;
      this.width = width;
      this.height = height;
    }
  }

  public LocalImageStorage(LocalImageFiles files, ImageConverter converter) {
    root = files.root();

    this.converter = converter;
  }

  public String save(MultipartFile file, Purpose purpose) {
    if (file == null) throw ApiProblem.badRequest("이미지 파일을 첨부해주세요.");
    validateTransport(file);
    if (file.isEmpty()) throw ApiProblem.badRequest("빈 파일입니다.");
    try {
      byte[] bytes = file.getBytes();
      ImageConverter.Metadata metadata;
      try {
        metadata = converter.metadata(bytes);
      } catch (ImageConverter.UnsupportedImage exception) {
        throw ApiProblem.badRequest("지원하지 않는 이미지 파일입니다.");
      }
      if (metadata.format().equals("heic")) {
        // 플랫폼의 HEVC 지원 여부와 관계없이 관측한 v2 HTTP·파일 동작을 유지한다.
        newFile(purpose);
        throw new IllegalStateException("HEIC upload follows v2 decoder limitation");
      }
      if (!Set.of("jpeg", "png", "webp", "avif").contains(metadata.format()))
        throw ApiProblem.badRequest("지원하지 않는 이미지 형식입니다.");
      Path target = newFile(purpose);
      var decoded = converter.read(bytes);
      byte[] webp = converter.writeWebP(crop(decoded, purpose));
      Files.write(target, webp);
      return "/uploads/" + purpose.folder + "/" + target.getFileName();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  public static void validateTransport(MultipartFile file) {
    if (file == null) return;
    if (!MIMES.contains(file.getContentType() == null ? "" : file.getContentType()))
      throw ApiProblem.badRequest("지원하지 않는 이미지 형식입니다.");
    if (file.getSize() >= 10 * 1024 * 1024) throw ApiProblem.payloadTooLarge("File too large");
  }

  private Path newFile(Purpose purpose) throws IOException {
    Path directory = root.resolve(purpose.folder);
    Files.createDirectories(directory);
    return Files.createFile(directory.resolve(UUID.randomUUID() + ".webp"));
  }

  private BufferedImage crop(BufferedImage source, Purpose purpose) {
    double widthScale = (double) purpose.width / source.getWidth();
    double heightScale = (double) purpose.height / source.getHeight();
    double scale =
        Math.min(
            1,
            purpose == Purpose.PLACE
                ? Math.min(widthScale, heightScale)
                : Math.max(widthScale, heightScale));
    int width = Math.max(1, Math.min(purpose.width, (int) Math.round(source.getWidth() * scale)));
    int height =
        Math.max(1, Math.min(purpose.height, (int) Math.round(source.getHeight() * scale)));
    var image =
        new BufferedImage(
            width,
            height,
            source.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB
                : BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      graphics.translate(
          (width - source.getWidth() * scale) / 2, (height - source.getHeight() * scale) / 2);
      graphics.scale(scale, scale);
      graphics.drawImage(source, 0, 0, null);
    } finally {
      graphics.dispose();
    }
    return image;
  }
}
