package com.documind.core.security;

import com.documind.core.config.AppProperties;
import com.documind.core.user.Role;
import com.documind.core.user.User;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static AppProperties props(String secret, Duration ttl) {
        return new AppProperties(new AppProperties.Jwt(secret, ttl, Duration.ofDays(7)), null, List.of(), null,
                null, null);
    }

    private final JwtService jwt = new JwtService(props("a-test-secret-that-is-long-enough-123456", Duration.ofMinutes(15)));

    @Test
    void roundTripsUserClaims() {
        User user = new User("Asha", "asha@example.com", "hash", Role.ADMIN);
        AuthUser parsed = jwt.parse(jwt.createAccessToken(user)).orElseThrow();
        assertThat(parsed.id()).isEqualTo(user.getId());
        assertThat(parsed.email()).isEqualTo("asha@example.com");
        assertThat(parsed.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void rejectsTamperedAndExpiredTokens() {
        User user = new User("Asha", "asha@example.com", "hash", Role.USER);
        String token = jwt.createAccessToken(user);
        assertThat(jwt.parse(token.substring(0, token.length() - 2) + "xx")).isEmpty();

        JwtService expired = new JwtService(props("a-test-secret-that-is-long-enough-123456", Duration.ofSeconds(-1)));
        assertThat(jwt.parse(expired.createAccessToken(user))).isEmpty();
    }

    @Test
    void refusesShortSecrets() {
        assertThatThrownBy(() -> new JwtService(props("too-short", Duration.ofMinutes(15))))
                .isInstanceOf(IllegalStateException.class);
    }
}
