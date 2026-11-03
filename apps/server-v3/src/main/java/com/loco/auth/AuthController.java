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
    private final PasswordRecoveryService recovery;
    public AuthController(AuthService service, PasswordRecoveryService recovery) { this.service = service; this.recovery = recovery; }

    @PostMapping("/auth/forgot-password")
    @ResponseStatus(HttpStatus.CREATED)
    public java.util.Map<String, String> forgot(@RequestBody(required = false) JsonNode body) {
        return recovery.forgot(AuthInputs.forgot(AuthInputs.httpBody(body)).email());
    }
    @PostMapping("/auth/reset-password")
    @ResponseStatus(HttpStatus.CREATED)
    public java.util.Map<String, String> reset(@RequestBody(required = false) JsonNode body) {
        return recovery.reset(AuthInputs.reset(AuthInputs.httpBody(body)));
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenIssuer.Pair register(@RequestBody(required = false) JsonNode body) {
        return service.register(AuthInputs.register(AuthInputs.httpBody(body)));
    }
    @PostMapping("/auth/login")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenIssuer.Pair login(@RequestBody(required = false) JsonNode body) {
        return service.login(AuthInputs.login(AuthInputs.httpBody(body)));
    }

    @PostMapping("/auth/refresh")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenIssuer.Pair refresh(@RequestBody(required = false) JsonNode body) {
        return service.refresh(AuthInputs.refresh(AuthInputs.httpBody(body)).refreshToken());
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.CREATED)
    public java.util.Map<String, String> logout(@RequestBody(required = false) JsonNode body) {
        return service.logout(AuthInputs.logout(AuthInputs.httpBody(body)).refreshToken());
    }
}
