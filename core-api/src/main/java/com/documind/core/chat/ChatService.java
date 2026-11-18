package com.documind.core.chat;

import com.documind.core.chat.AiServiceClient.QueryRequest;
import com.documind.core.chat.AiServiceClient.Turn;
import com.documind.core.chat.AnswerCache.CachedAnswer;
import com.documind.core.common.ApiException;
import com.documind.core.common.CorrelationIdFilter;
import com.documind.core.document.Document;
import com.documind.core.document.DocumentRepository;
import com.documind.core.document.DocumentStatus;
import com.documind.core.ratelimit.RateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final long STREAM_TIMEOUT_MS = 120_000;

    private final ChatRepository chats;
    private final MessageRepository messages;
    private final DocumentRepository documents;
    private final AiServiceClient aiService;
    private final AnswerCache cache;
    private final RateLimiter rateLimiter;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatService(ChatRepository chats, MessageRepository messages, DocumentRepository documents,
                       AiServiceClient aiService, AnswerCache cache, RateLimiter rateLimiter) {
        this.chats = chats;
        this.messages = messages;
        this.documents = documents;
        this.aiService = aiService;
        this.cache = cache;
        this.rateLimiter = rateLimiter;
    }

    public Chat create(UUID userId, UUID documentId) {
        String title = "All documents";
        if (documentId != null) {
            title = documents.findByIdAndUserId(documentId, userId)
                    .map(Document::getName)
                    .orElseThrow(() -> ApiException.notFound("Document"));
        }
        return chats.save(new Chat(userId, documentId, title));
    }

    public List<Chat> list(UUID userId) {
        return chats.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<Message> history(UUID userId, UUID chatId) {
        return messages.findByChatIdOrderByCreatedAtAsc(get(userId, chatId).getId());
    }

    /**
     * FR-17..FR-23: checks run synchronously (so errors are plain JSON); the answer then streams over SSE
     * as events: citations, token*, done | error.
     */
    public SseEmitter ask(UUID userId, UUID chatId, String question) {
        Chat chat = get(userId, chatId);
        rateLimiter.checkQuestion(userId);

        List<Document> scope = chat.getDocumentId() != null
                ? documents.findByIdAndUserId(chat.getDocumentId(), userId).stream().toList()
                : documents.findByUserIdAndStatus(userId, DocumentStatus.READY);
        List<Document> ready = scope.stream().filter(d -> d.getStatus() == DocumentStatus.READY).toList();
        if (ready.isEmpty()) {
            throw ApiException.conflict("NO_READY_DOCUMENTS", chat.getDocumentId() != null
                    ? "This document is not ready yet"
                    : "Upload a document and wait for it to finish processing first");
        }
        List<UUID> documentIds = ready.stream().map(Document::getId).toList();
        Map<UUID, String> names = ready.stream().collect(Collectors.toMap(Document::getId, Document::getName));

        List<Message> previous = new ArrayList<>(messages.findTop5ByChatIdOrderByCreatedAtDesc(chatId));
        Collections.reverse(previous);
        List<Turn> history = previous.stream().map(m -> new Turn(m.getRole(), m.getContent())).toList();
        messages.save(Message.user(chatId, question.strip()));

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        String correlationId = CorrelationIdFilter.current();
        long start = System.currentTimeMillis();

        executor.submit(() -> {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
            try {
                // Follow-up questions depend on the conversation, so only first questions use the cache
                Optional<CachedAnswer> cached = history.isEmpty()
                        ? cache.get(userId, documentIds, question) : Optional.empty();
                if (cached.isPresent()) {
                    replayCached(emitter, chatId, cached.get(), start);
                } else {
                    streamFromAi(emitter, chatId, new QueryRequest(question.strip(), userId, documentIds, history),
                            names, history.isEmpty(), start);
                }
            } catch (ClientGoneException e) {
                log.info("Client closed the answer stream for chat {}", chatId);
            } catch (ApiException e) {
                sendQuietly(emitter, "error", Map.of("code", e.getCode(), "message", e.getMessage()));
                emitter.complete();
            } catch (Exception e) {
                log.error("Answer stream failed for chat {}", chatId, e);
                sendQuietly(emitter, "error", Map.of("code", "INTERNAL_ERROR", "message", "Something went wrong"));
                emitter.complete();
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        });
        return emitter;
    }

    private void streamFromAi(SseEmitter emitter, UUID chatId, QueryRequest request, Map<UUID, String> names,
                              boolean cacheable, long start) {
        StringBuilder answer = new StringBuilder();
        List<Citation> citations = new ArrayList<>();
        boolean[] finished = {false};

        aiService.streamQuery(request, event -> {
            switch (event.path("type").asText()) {
                case "citations" -> {
                    for (JsonNode c : event.path("citations")) {
                        UUID docId = UUID.fromString(c.path("documentId").asText());
                        citations.add(new Citation(c.path("index").asInt(), docId, names.getOrDefault(docId, "Document"),
                                c.path("page").asInt(), c.path("excerpt").asText(), c.path("score").asDouble()));
                    }
                    send(emitter, "citations", Map.of("citations", citations));
                }
                case "token" -> {
                    String text = event.path("text").asText();
                    answer.append(text);
                    send(emitter, "token", Map.of("text", text));
                }
                case "done" -> {
                    long latency = System.currentTimeMillis() - start;
                    Message saved = messages.save(Message.assistant(chatId, answer.toString(), citations, latency, false));
                    boolean notFound = event.path("notFound").asBoolean();
                    if (cacheable && !notFound) {
                        cache.put(request.userId(), request.documentIds(), request.question(),
                                new CachedAnswer(answer.toString(), citations));
                    }
                    send(emitter, "done", Map.of("messageId", saved.getId(), "latencyMs", latency, "cached", false,
                            "notFound", notFound));
                    finished[0] = true;
                }
                case "error" -> {
                    send(emitter, "error", Map.of("code", "AI_SERVICE_ERROR",
                            "message", event.path("message").asText("The AI service failed")));
                    finished[0] = true;
                }
                default -> log.debug("Ignoring AI event {}", event);
            }
        });
        if (!finished[0]) {
            send(emitter, "error", Map.of("code", "AI_SERVICE_ERROR", "message", "The answer stream ended early"));
        }
        emitter.complete();
    }

    private void replayCached(SseEmitter emitter, UUID chatId, CachedAnswer cached, long start) {
        send(emitter, "citations", Map.of("citations", cached.citations()));
        send(emitter, "token", Map.of("text", cached.answer()));
        long latency = System.currentTimeMillis() - start;
        Message saved = messages.save(Message.assistant(chatId, cached.answer(), cached.citations(), latency, true));
        send(emitter, "done", Map.of("messageId", saved.getId(), "latencyMs", latency, "cached", true,
                "notFound", false));
        emitter.complete();
    }

    private Chat get(UUID userId, UUID chatId) {
        return chats.findByIdAndUserId(chatId, userId).orElseThrow(() -> ApiException.notFound("Chat"));
    }

    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new ClientGoneException();
        }
    }

    private static void sendQuietly(SseEmitter emitter, String name, Object data) {
        try {
            send(emitter, name, data);
        } catch (ClientGoneException ignored) {
            // nothing to do
        }
    }

    private static final class ClientGoneException extends RuntimeException {
        ClientGoneException() {
            super(null, null, false, false);
        }
    }
}
