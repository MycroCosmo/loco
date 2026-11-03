package com.loco.security;

import com.loco.config.ApiWebConfig;
import com.loco.web.ApiController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;

class ApiDeclarationTest {
    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withBean(com.loco.users.UserRepository.class, () -> org.mockito.Mockito.mock(com.loco.users.UserRepository.class))
            .withUserConfiguration(Mvc.class, ApiWebConfig.class);

    @Test
    void missingAccessDeclarationPreventsStartup() {
        context.withBean(MissingAccess.class).run(app -> assertThat(app.getStartupFailure())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Declare exactly one"));
    }

    @Test
    void conflictingAccessDeclarationsPreventStartup() {
        context.withBean(ConflictingAccess.class).run(app -> assertThat(app.getStartupFailure())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Declare exactly one"));
    }

    @Test
    void missingApiPrefixDeclarationPreventsStartup() {
        context.withBean(MissingApi.class).run(app -> assertThat(app.getStartupFailure())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@ApiController"));
    }

    @Test
    void explicitPublicControllerStarts() {
        context.withBean(ValidPublic.class).run(app -> assertThat(app).hasNotFailed());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Mvc { }

    @TestComponent
    @ApiController
    static class MissingAccess {
        @GetMapping("/missing") public String get() { return "ok"; }
    }

    @TestComponent
    @ApiController
    @PublicApi
    @AuthenticatedApi
    static class ConflictingAccess {
        @GetMapping("/conflict") public String get() { return "ok"; }
    }

    @TestComponent
    @RestController
    @PublicApi
    static class MissingApi {
        @GetMapping("/unprefixed") public String get() { return "ok"; }
    }

    @TestComponent
    @ApiController
    @PublicApi
    static class ValidPublic {
        @GetMapping("/public") public String get() { return "ok"; }
    }
}
