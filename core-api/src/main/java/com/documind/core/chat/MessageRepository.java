package com.documind.core.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByChatIdOrderByCreatedAtAsc(UUID chatId);

    List<Message> findTop5ByChatIdOrderByCreatedAtDesc(UUID chatId);

    @Query("select c.userId, count(m) from Message m, Chat c where m.chatId = c.id and m.role = 'user' "
            + "group by c.userId")
    List<Object[]> countQuestionsPerUser();
}
