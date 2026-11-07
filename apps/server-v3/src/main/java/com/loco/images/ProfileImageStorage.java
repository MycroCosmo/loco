package com.loco.images;

import java.nio.file.Path;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ProfileImageStorage {
  private final LocalImageStorage storage;
  private final LocalImageFiles files;

  public ProfileImageStorage(LocalImageStorage storage, LocalImageFiles files) {
    this.storage = storage;
    this.files = files;
  }

  public Path root() {
    return files.root();
  }

  public String save(MultipartFile file) {
    return storage.save(file, LocalImageStorage.Purpose.PROFILE);
  }

  public void delete(String url) {
    if (url != null && url.startsWith("/uploads/profiles/")) files.delete(url);
  }
}
