package com.loco.images;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalImageFiles {
  private final Path root;

  public LocalImageFiles(@Value("${loco.uploads.directory:uploads}") String directory) {
    root = Path.of(directory.isBlank() ? "uploads" : directory).toAbsolutePath().normalize();
  }

  public Path root() {
    return root;
  }

  public void delete(String url) {
    if (url == null || !url.startsWith("/uploads/")) return;
    Path target = root.resolve(url.substring("/uploads/".length())).normalize();
    if (!target.startsWith(root)
        || target.equals(root)
        || Files.isDirectory(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
    try {
      Files.deleteIfExists(target);
    } catch (IOException ignored) {
      /* v2: 정리 실패는 커밋한 API 성공을 취소하지 않는다. */
    }
  }
}
