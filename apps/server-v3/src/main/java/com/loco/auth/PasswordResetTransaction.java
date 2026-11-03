package com.loco.auth;

import com.loco.users.UserRepository;
import com.loco.web.ApiProblem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetTransaction {
    private final PasswordResetTokenRepository codes;
    private final UserRepository users;
    private final RefreshTokenRepository refresh;
    private final TokenIssuer clock;
    public PasswordResetTransaction(PasswordResetTokenRepository codes, UserRepository users,
                                    RefreshTokenRepository refresh, TokenIssuer clock) {
        this.codes = codes; this.users = users; this.refresh = refresh; this.clock = clock;
    }
    @Transactional
    public void reset(String tokenId, String userId, String hash) {
        if (codes.consume(tokenId, clock.now()) != 1) throw invalidCode();
        var user = users.findById(userId).orElseThrow();
        user.changePassword(hash);
        users.flush();
        refresh.revokeForUser(userId, clock.now());
    }
    static ApiProblem invalidCode() { return ApiProblem.unauthorized("유효하지 않거나 만료된 코드입니다."); }
}
