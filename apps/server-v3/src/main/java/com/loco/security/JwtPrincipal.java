package com.loco.security;

import java.security.Principal;

public record JwtPrincipal(String userId, String email) implements Principal {
    @Override
    public String getName() {
        return userId;
    }
}
