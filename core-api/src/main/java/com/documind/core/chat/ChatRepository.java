package com.documind.core.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatRepository extends JpaRepository<Chat, UUID> {

    List<Chat> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Chat> findByIdAndUserId(UUID id, UUID userId);
}
