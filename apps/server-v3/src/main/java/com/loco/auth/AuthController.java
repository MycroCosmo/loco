package com.loco.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.loco.security.PublicApi;
import com.loco.web.ApiController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;

@ApiController
@PublicApi
public class AuthController {
    private final AuthService service;
    public AuthController(AuthService service) { this.service = service; }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenIssuer.Pair register(@RequestBody(required = false) JsonNode body) {
        return service.register(AuthInputs.register(body));
    }
    @PostMapping("/auth/login")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenIssuer.Pair login(@RequestBody(required = false) JsonNode body) {
        return service.login(AuthInputs.login(body));
    }
}
