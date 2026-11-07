package com.loco.images;

import com.loco.web.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

public final class ImageUploadRequest {
  private ImageUploadRequest() {}

  public static MultipartFile file(HttpServletRequest request) {
    MultipartFile file = null;
    if (request instanceof MultipartHttpServletRequest multipart) {
      for (var entry : multipart.getMultiFileMap().entrySet()) {
        for (var part : entry.getValue()) {
          if (part.getOriginalFilename() == null || part.getOriginalFilename().isEmpty()) continue;
          if (!entry.getKey().equals("file") || file != null)
            throw ApiProblem.badRequest("Unexpected field");
          LocalImageStorage.validateTransport(part);
          file = part;
        }
      }
    }
    return file;
  }
}
