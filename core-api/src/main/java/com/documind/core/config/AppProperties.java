package com.documind.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        Cors cors,
        List<String> adminEmails,
        Storage storage,
        AiService aiService,
        Limits limits) {

    public record Jwt(String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {}

    public record Cors(List<String> allowedOrigins) {}

    public record Storage(String endpoint, String accessKey, String secretKey, String bucket, String region) {}

    public record AiService(String url, String token, Duration timeout) {}

    public record Limits(int questionsPerMinute, int uploadsPerDay, Duration answerCacheTtl) {}

    public boolean isAdminEmail(String email) {
        return adminEmails != null && adminEmails.stream()
                .anyMatch(admin -> admin.strip().equalsIgnoreCase(email));
    }
}
