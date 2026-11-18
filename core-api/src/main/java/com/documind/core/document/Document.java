package com.documind.core.document;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents")
public class Document {

    @Id
    private UUID id;
    private UUID userId;
    private String name;
    private String storageKey;
    private String fileHash;
    private long sizeBytes;
    private Integer pageCount;
    private Integer chunkCount;
    @Enumerated(EnumType.STRING)
    private DocumentStatus status;
    private int progress;
    private String failureReason;
    private Long processingMs;
    private Instant createdAt;

    protected Document() {}

    public Document(UUID userId, String name, String fileHash, long sizeBytes) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.name = name;
        this.fileHash = fileHash;
        this.sizeBytes = sizeBytes;
        // never derived from the user's file name (NFR-11)
        this.storageKey = "users/" + userId + "/" + id + ".pdf";
        this.status = DocumentStatus.UPLOADED;
        this.createdAt = Instant.now();
    }

    public boolean isFinished() {
        return status == DocumentStatus.READY || status == DocumentStatus.FAILED;
    }

    public void markProgress(int percent) {
        if (!isFinished()) {
            status = DocumentStatus.PROCESSING;
            progress = Math.max(progress, Math.min(percent, 99));
        }
    }

    public void markReady(int pageCount, int chunkCount, long processingMs) {
        this.status = DocumentStatus.READY;
        this.progress = 100;
        this.pageCount = pageCount;
        this.chunkCount = chunkCount;
        this.processingMs = processingMs;
        this.failureReason = null;
    }

    public void markFailed(String reason) {
        this.status = DocumentStatus.FAILED;
        this.failureReason = reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getName() { return name; }
    public String getStorageKey() { return storageKey; }
    public String getFileHash() { return fileHash; }
    public long getSizeBytes() { return sizeBytes; }
    public Integer getPageCount() { return pageCount; }
    public Integer getChunkCount() { return chunkCount; }
    public DocumentStatus getStatus() { return status; }
    public int getProgress() { return progress; }
    public String getFailureReason() { return failureReason; }
    public Long getProcessingMs() { return processingMs; }
    public Instant getCreatedAt() { return createdAt; }
}
