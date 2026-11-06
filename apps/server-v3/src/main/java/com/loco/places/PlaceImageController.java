package com.loco.places;

import com.loco.images.ImageUploadRequest;
import com.loco.security.AuthenticatedApi;
import com.loco.security.JwtPrincipal;
import com.loco.web.ApiController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@ApiController
@AuthenticatedApi
public class PlaceImageController {
  private final PlaceImageService images;

  public PlaceImageController(PlaceImageService images) {
    this.images = images;
  }

  @PostMapping("/places/{id}/images")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public PlaceResponses.Core upload(
      @PathVariable String id,
      @AuthenticationPrincipal JwtPrincipal user,
      HttpServletRequest request) {
    return images.upload(id, user.userId(), ImageUploadRequest.file(request));
  }
}
