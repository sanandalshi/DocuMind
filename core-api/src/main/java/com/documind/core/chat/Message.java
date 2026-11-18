package com.documind.core.chat;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "messages")
public class Message {

    @Id
    private UUID id;
    private UUID chatId;
    private String role;
    private String content;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Citation> citations;
    private Long latencyMs;
    private boolean cached;
    private Instant createdAt;

    protected Message() {}

    private Message(UUID chatId, String role, String content, List<Citation> citations, Long latencyMs,
                    boolean cached) {
        this.id = UUID.randomUUID();
        this.chatId = chatId;
        this.role = role;
        this.content = content;
        this.citations = citations;
        this.latencyMs = latencyMs;
        this.cached = cached;
        this.createdAt = Instant.now();
    }

    public static Message user(UUID chatId, String question) {
        return new Message(chatId, "user", question, null, null, false);
    }

    public static Message assistant(UUID chatId, String answer, List<Citation> citations, long latencyMs,
                                    boolean cached) {
        return new Message(chatId, "assistant", answer, citations, latencyMs, cached);
    }

    public UUID getId() { return id; }
    public UUID getChatId() { return chatId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public List<Citation> getCitations() { return citations; }
    public Long getLatencyMs() { return latencyMs; }
    public boolean isCached() { return cached; }
    public Instant getCreatedAt() { return createdAt; }
}
