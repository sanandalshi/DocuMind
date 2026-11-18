package com.documind.core.chat;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chats")
public class Chat {

    @Id
    private UUID id;
    private UUID userId;
    /** null = the chat covers all of the user's documents */
    private UUID documentId;
    private String title;
    private Instant createdAt;

    protected Chat() {}

    public Chat(UUID userId, UUID documentId, String title) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.documentId = documentId;
        this.title = title;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getDocumentId() { return documentId; }
    public String getTitle() { return title; }
    public Instant getCreatedAt() { return createdAt; }
}
