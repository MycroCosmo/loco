package com.loco.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.security.AuthenticatedApi;
import com.loco.web.ApiController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;

@ApiController
@AuthenticatedApi
public class KakaoSearchController {
  private final KakaoSearchClient client;

  public KakaoSearchController(KakaoSearchClient client) {
    this.client = client;
  }

  @GetMapping("/places/search")
  public JsonNode search(HttpServletRequest request) {
    return client.search(KakaoSearchInputs.query(request));
  }
}
