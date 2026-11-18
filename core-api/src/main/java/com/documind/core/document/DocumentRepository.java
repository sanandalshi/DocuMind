package com.documind.core.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Document> findByIdAndUserId(UUID id, UUID userId);

    List<Document> findByUserIdAndStatus(UUID userId, DocumentStatus status);

    Optional<Document> findFirstByUserIdAndFileHashAndStatusNot(UUID userId, String fileHash, DocumentStatus status);

    @Query("select d.userId, count(d) from Document d group by d.userId")
    List<Object[]> countPerUser();
}
