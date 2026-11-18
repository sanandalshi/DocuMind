package com.documind.core.auth;

import com.documind.core.auth.AuthDtos.LoginRequest;
import com.documind.core.auth.AuthDtos.RegisterRequest;
import com.documind.core.auth.AuthDtos.TokenResponse;
import com.documind.core.auth.AuthDtos.UserResponse;
import com.documind.core.common.ApiException;
import com.documind.core.config.AppProperties;
import com.documind.core.security.JwtService;
import com.documind.core.user.Role;
import com.documind.core.user.User;
import com.documind.core.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties props;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AppProperties props) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.props = props;
    }

    @Transactional
    public UserResponse register(RegisterRequest req) {
        String email = req.email().strip().toLowerCase();
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        }
        Role role = props.isAdminEmail(email) ? Role.ADMIN : Role.USER;
        User user = users.save(new User(req.name().strip(), email, passwordEncoder.encode(req.password()), role));
        return UserResponse.of(user);
    }

    @Transactional
    public TokenResponse login(LoginRequest req) {
        User user = users.findByEmailIgnoreCase(req.email().strip())
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS",
                        "Email or password is incorrect"));
        requireEnabled(user);
        return issueTokens(user);
    }

    /** Rotates the refresh token: the old one stops working once used. */
    @Transactional
    public TokenResponse refresh(String rawToken) {
        RefreshToken token = refreshTokens.findByTokenHash(sha256(rawToken))
                .filter(t -> !t.isRevoked() && t.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                        "Refresh token is invalid or expired"));
        token.revoke();
        User user = users.findById(token.getUserId()).orElseThrow(() -> ApiException.notFound("User"));
        requireEnabled(user);
        return issueTokens(user);
    }

    public UserResponse me(UUID userId) {
        return users.findById(userId).map(UserResponse::of).orElseThrow(() -> ApiException.notFound("User"));
    }

    private TokenResponse issueTokens(User user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(new RefreshToken(user.getId(), sha256(raw),
                Instant.now().plus(props.jwt().refreshTokenTtl())));
        return new TokenResponse(jwtService.createAccessToken(user), raw, jwtService.accessTtlSeconds(),
                UserResponse.of(user));
    }

    private static void requireEnabled(User user) {
        if (!user.isEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "This account has been disabled");
        }
    }

    static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
