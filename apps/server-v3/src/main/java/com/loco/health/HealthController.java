package com.loco.health;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import com.loco.security.PublicApi;
import com.loco.web.ApiController;

@ApiController
@PublicApi
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
