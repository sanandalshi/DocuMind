package com.documind.core.document;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(UUID id, String name, long sizeBytes, Integer pageCount, Integer chunkCount,
                               DocumentStatus status, int progress, String failureReason, Long processingMs,
                               Instant createdAt) {

    public static DocumentResponse of(Document d) {
        return new DocumentResponse(d.getId(), d.getName(), d.getSizeBytes(), d.getPageCount(), d.getChunkCount(),
                d.getStatus(), d.getProgress(), d.getFailureReason(), d.getProcessingMs(), d.getCreatedAt());
    }
}
