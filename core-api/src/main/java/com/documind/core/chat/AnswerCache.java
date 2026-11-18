package com.documind.core.chat;

import com.documind.core.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Caches answers in Redis (FR-23). The key covers the user, the exact set of documents searched and
 * the normalised question; documents never change after processing, so a hit is always current.
 */
@Component
public class AnswerCache {

    private static final Logger log = LoggerFactory.getLogger(AnswerCache.class);

    public record CachedAnswer(String answer, List<Citation> citations) {}

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public AnswerCache(StringRedisTemplate redis, ObjectMapper mapper, AppProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = props.limits().answerCacheTtl();
    }

    public Optional<CachedAnswer> get(UUID userId, List<UUID> documentIds, String question) {
        try {
            String json = redis.opsForValue().get(key(userId, documentIds, question));
            return json == null ? Optional.empty() : Optional.of(mapper.readValue(json, CachedAnswer.class));
        } catch (Exception e) {
            log.warn("Answer cache read failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void put(UUID userId, List<UUID> documentIds, String question, CachedAnswer answer) {
        try {
            redis.opsForValue().set(key(userId, documentIds, question), mapper.writeValueAsString(answer), ttl);
        } catch (Exception e) {
            log.warn("Answer cache write failed: {}", e.getMessage());
        }
    }

    static String key(UUID userId, List<UUID> documentIds, String question) {
        String normalised = question.strip().toLowerCase().replaceAll("\\s+", " ").replaceAll("[?.!]+$", "");
        String docs = documentIds.stream().map(UUID::toString).sorted().reduce("", (a, b) -> a + "," + b);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((userId + "|" + docs + "|" + normalised).getBytes(StandardCharsets.UTF_8));
            return "answer:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
