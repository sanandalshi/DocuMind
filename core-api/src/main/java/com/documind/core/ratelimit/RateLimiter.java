package com.documind.core.ratelimit;

import com.documind.core.config.AppProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/** Fixed-window counters in Redis, shared by every Core API instance (FR-24). */
@Service
public class RateLimiter {

    private final StringRedisTemplate redis;
    private final AppProperties.Limits limits;

    public RateLimiter(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.limits = props.limits();
    }

    public void checkQuestion(UUID userId) {
        check("rl:q:" + userId, limits.questionsPerMinute(), Duration.ofMinutes(1),
                "You can ask " + limits.questionsPerMinute() + " questions per minute. Please wait a moment.");
    }

    public void checkUpload(UUID userId) {
        check("rl:u:" + userId, limits.uploadsPerDay(), Duration.ofDays(1),
                "You can upload " + limits.uploadsPerDay() + " documents per day.");
    }

    private void check(String key, int limit, Duration window, String message) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, window);
        }
        if (count != null && count > limit) {
            Long ttl = redis.getExpire(key);
            throw new RateLimitExceededException(message, ttl != null && ttl > 0 ? ttl : window.toSeconds());
        }
    }
}
