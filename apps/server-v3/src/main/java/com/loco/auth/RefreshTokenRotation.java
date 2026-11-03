package com.loco.auth;

import java.time.LocalDateTime;
import com.loco.web.ApiProblem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenRotation {
    private final RefreshTokenRepository tokens;
    private final TokenIssuer issuer;
    public RefreshTokenRotation(RefreshTokenRepository tokens, TokenIssuer issuer) {
        this.tokens = tokens;
        this.issuer = issuer;
    }

    // 별도 빈을 거쳐 프록시 트랜잭션을 적용한다. 새 토큰 저장이 실패하면 폐기도 롤백된다.
    @Transactional
    public void rotate(String oldId, String userId, String nextHash, LocalDateTime nextExpiry) {
        if (tokens.consume(oldId, issuer.now()) != 1) throw ApiProblem.unauthorized("Invalid refresh token.");
        tokens.saveAndFlush(RefreshToken.issue(userId, nextHash, nextExpiry));
    }
}
