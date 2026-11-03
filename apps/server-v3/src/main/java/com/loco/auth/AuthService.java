package com.loco.auth;

import java.util.Map;

import com.loco.users.User;
import com.loco.users.UserRepository;
import com.loco.web.ApiProblem;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserRepository users;
    private final V2PasswordEncoder passwords;
    private final TokenIssuer tokens;
    private final RefreshTokenRepository refreshTokens;
    private final RefreshTokenRotation rotation;

    public AuthService(UserRepository users, V2PasswordEncoder passwords, TokenIssuer tokens,
                       RefreshTokenRepository refreshTokens, RefreshTokenRotation rotation) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.rotation = rotation;
    }
    public TokenIssuer.Pair register(AuthInputs.Register input) {
        if (users.findByEmail(input.email()).isPresent()) throw ApiProblem.conflict("이미 사용 중인 이메일입니다.");
        var user = users.saveAndFlush(User.local(input.email(), passwords.encode(input.password()), input.nickname()));
        // v2도 회원 생성과 토큰 저장을 별도 작업으로 수행한다.
        return tokens.issue(user);
    }
    public TokenIssuer.Pair login(AuthInputs.Login input) {
        var user = users.findByEmail(input.email()).orElse(null);
        if (user == null || user.getPassword() == null || user.getPassword().isEmpty()) throw invalidCredentials();
        if (user.getDeletedAt() != null) throw ApiProblem.unauthorized("탈퇴한 계정입니다.");
        if (!passwords.matches(input.password(), user.getPassword())) throw invalidCredentials();
        return tokens.issue(user);
    }
    private ApiProblem invalidCredentials() { return ApiProblem.unauthorized("이메일 또는 비밀번호가 올바르지 않습니다."); }

    public TokenIssuer.Pair refresh(String raw) {
        if (raw.isEmpty()) throw ApiProblem.unauthorized("Refresh token is required.");
        var stored = refreshTokens.findByTokenHash(TokenIssuer.hash(raw)).orElse(null);
        var user = stored == null ? null : users.findById(stored.getUserId()).orElse(null);
        if (stored == null || stored.getRevokedAt() != null || !stored.getExpiresAt().isAfter(tokens.now())
                || user == null || user.getDeletedAt() != null) {
            throw ApiProblem.unauthorized("Invalid refresh token.");
        }
        String next = tokens.newRefreshToken();
        rotation.rotate(stored.getId(), stored.getUserId(), TokenIssuer.hash(next), tokens.refreshExpiry());
        return tokens.pair(user, next);
    }

    public Map<String, String> logout(String raw) {
        if (raw != null && !raw.isEmpty()) refreshTokens.revokeByHash(TokenIssuer.hash(raw), tokens.now());
        return Map.of("message", "Logged out.");
    }
}
