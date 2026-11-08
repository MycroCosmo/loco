package com.cms.core.web;

import com.cms.core.account.*;
import com.cms.core.file.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;

@RestController
@RequestMapping("/admin/api/core/files")
public class FileController {
  private final FileService files;

  public FileController(FileService files) {
    this.files = files;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public StoredFile.View upload(
      @AuthenticationPrincipal AdminIdentity actor, HttpServletRequest request) throws IOException {
    if (!(request instanceof MultipartHttpServletRequest multipart))
      throw new AdminProblem(400, "Multipart request required");
    var parts = multipart.getMultiFileMap();
    if (parts.size() != 1 || !parts.containsKey("file") || parts.get("file").size() != 1)
      throw new AdminProblem(400, "Exactly one file required");
    var file = parts.getFirst("file");
    if (file.getSize() > FileService.MAX_BYTES) throw new AdminProblem(413, "File too large");
    return files.upload(actor, file.getOriginalFilename(), file.getContentType(), file.getBytes());
  }

  @GetMapping
  public List<StoredFile.View> list(
      @AuthenticationPrincipal AdminIdentity actor,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return files.list(actor, page, size);
  }

  @GetMapping("/{id}")
  public StoredFile.View detail(
      @AuthenticationPrincipal AdminIdentity actor, @PathVariable String id) {
    return files.detail(actor, id);
  }

  @GetMapping("/{id}/content")
  public ResponseEntity<Resource> content(
      @AuthenticationPrincipal AdminIdentity actor, @PathVariable String id) {
    var download = files.download(actor, id);
    var info = download.metadata();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(info.mediaType()))
        .contentLength(info.byteSize())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(info.originalName(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(new InputStreamResource(download.content()));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal AdminIdentity actor, @PathVariable String id) {
    files.delete(actor, id);
  }
}
