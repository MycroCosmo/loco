package com.loco.health;

import com.loco.security.PublicApi;
import com.loco.web.ApiController;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;

@ApiController
@PublicApi
public class HealthController {

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "ok");
  }
}
