package com.loco.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import com.loco.compat.CompatibilityTestBase;
import com.loco.compat.SharedHarness;
import com.loco.users.Provider;
import com.loco.users.User;
import com.loco.users.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import static org.assertj.core.api.Assertions.assertThat;

@Import(EntityPersistenceTest.ControlledTime.class)
class EntityPersistenceTest extends CompatibilityTestBase {
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired PasswordResetTokenRepository resetTokens;
    @Autowired MutableClock clock;

    @BeforeEach void reset() throws Exception {
        environment().reset("compat/seed.sql");
        clock.now = Instant.parse("2026-01-01T00:00:00.123Z");
    }

    @Test
    void hibernateGeneratesStringUuidsOnPersistAgainstTheUnchangedTextSchema() throws Exception {
        var user = User.local("entity@example.test", "mapping-fixture", "entity");
        assertThat(user.getId()).isNull();
        assertThat(users.saveAndFlush(user)).isSameAs(user);
        var expiry = LocalDateTime.now(clock).plusDays(30);
        var refresh = refreshTokens.saveAndFlush(RefreshToken.issue(user.getId(), "a".repeat(64), expiry));
        var reset = resetTokens.saveAndFlush(PasswordResetToken.issue(user.getId(), "b".repeat(64), expiry));
        for (String id : List.of(user.getId(), refresh.getId(), reset.getId())) {
            assertThat(UUID.fromString(id).version()).isEqualTo(4);
            assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        }
        assertThat(refreshTokens.findByTokenHash(refresh.getTokenHash()).orElseThrow().getUserId()).isEqualTo(user.getId());
        assertThat(resetTokens.findByTokenHash(reset.getTokenHash()).orElseThrow().getUserId()).isEqualTo(user.getId());
        assertThat(refresh.getRevokedAt()).isNull();
        assertThat(reset.getUsedAt()).isNull();
        try (var connection = environment().connect(SharedHarness.Side.V3)) {
            for (String table : List.of("users", "refresh_tokens", "password_reset_tokens")) {
                try (var result = connection.createStatement().executeQuery("SELECT pg_typeof(id)::text FROM " + table + " LIMIT 1")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("text");
                }
            }
        }
    }

    @Test
    void auditingUsesTheInjectedClockAndPreservesCreationTimeOnUpdate() {
        var user = users.saveAndFlush(User.local("audit@example.test", "before", "audit"));
        var created = LocalDateTime.now(clock);
        assertThat(user.getCreatedAt()).isEqualTo(created);
        assertThat(user.getUpdatedAt()).isEqualTo(created);
        clock.now = clock.now.plusSeconds(30);
        user.changePassword("after");
        users.saveAndFlush(user);
        var reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isEqualTo(created);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(LocalDateTime.now(clock));
        assertThat(reloaded.getPassword()).isEqualTo("after");
    }

    @Test
    void existingPrismaRowsAndAllNamedProviderValuesCanBeRead() throws Exception {
        try (var connection = environment().connect(SharedHarness.Side.V3);
             var statement = connection.prepareStatement("UPDATE users SET provider=CAST(? AS \"Provider\") WHERE id=?")) {
            for (var provider : Provider.values()) {
                statement.setString(1, provider.name());
                statement.setString(2, SharedHarness.Environment.USER_ID);
                statement.executeUpdate();
                var user = users.findById(SharedHarness.Environment.USER_ID).orElseThrow();
                assertThat(user.getProvider()).isEqualTo(provider);
                assertThat(user.getEmail()).isEqualTo(SharedHarness.Environment.EMAIL);
                assertThat(user.getDeletedAt()).isNull();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledTime {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
