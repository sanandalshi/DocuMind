package com.documind.core.auth;

import com.documind.core.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank(message = "name is required") @Size(max = 100, message = "name is too long") String name,
            @NotBlank(message = "email is required") @Email(message = "email is invalid") String email,
            @Size(min = 8, max = 72, message = "password must be 8 to 72 characters") String password) {}

    public record LoginRequest(@NotBlank(message = "email is required") String email,
                               @NotBlank(message = "password is required") String password) {}

    public record RefreshRequest(@NotBlank(message = "refreshToken is required") String refreshToken) {}

    public record UserResponse(UUID id, String name, String email, String role) {
        public static UserResponse of(User user) {
            return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole().name());
        }
    }

    public record TokenResponse(String accessToken, String refreshToken, long expiresIn, UserResponse user) {}
}
