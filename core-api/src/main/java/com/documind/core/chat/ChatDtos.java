package com.documind.core.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ChatDtos {

    private ChatDtos() {}

    public record CreateChatRequest(UUID documentId) {}

    public record AskRequest(
            @NotBlank(message = "question is required")
            @Size(max = 2000, message = "question must be at most 2000 characters") String question) {}

    public record ChatResponse(UUID id, UUID documentId, String title, Instant createdAt) {
        public static ChatResponse of(Chat chat) {
            return new ChatResponse(chat.getId(), chat.getDocumentId(), chat.getTitle(), chat.getCreatedAt());
        }
    }

    public record MessageResponse(UUID id, String role, String content, List<Citation> citations, Long latencyMs,
                                  boolean cached, Instant createdAt) {
        public static MessageResponse of(Message m) {
            return new MessageResponse(m.getId(), m.getRole(), m.getContent(),
                    m.getCitations() == null ? List.of() : m.getCitations(), m.getLatencyMs(), m.isCached(),
                    m.getCreatedAt());
        }
    }
}
