package com.documind.core.auth;

import com.documind.core.auth.AuthDtos.LoginRequest;
import com.documind.core.auth.AuthDtos.RefreshRequest;
import com.documind.core.auth.AuthDtos.RegisterRequest;
import com.documind.core.auth.AuthDtos.TokenResponse;
import com.documind.core.auth.AuthDtos.UserResponse;
import com.documind.core.security.AuthUser;
import com.documind.core.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return authService.refresh(req.refreshToken());
    }

    @GetMapping("/me")
    public UserResponse me(@CurrentUser AuthUser user) {
        return authService.me(user.id());
    }
}
