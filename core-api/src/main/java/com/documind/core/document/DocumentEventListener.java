package com.documind.core.document;

import com.documind.core.config.RabbitConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.UUID;

/** Applies progress / processed / failed events from the AI Service (SRS 5.3). */
@Component
public class DocumentEventListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentEventListener.class);

    private final DocumentRepository documents;
    private final ProgressBroadcaster broadcaster;
    private final ObjectMapper mapper;

    public DocumentEventListener(DocumentRepository documents, ProgressBroadcaster broadcaster, ObjectMapper mapper) {
        this.documents = documents;
        this.broadcaster = broadcaster;
        this.mapper = mapper;
    }

    @RabbitListener(queues = RabbitConfig.STATUS_QUEUE)
    @Transactional
    public void onEvent(Message message) throws IOException {
        JsonNode event = mapper.readTree(message.getBody());
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        MDC.put("correlationId", event.path("correlationId").asText("-"));
        try {
            UUID documentId = UUID.fromString(event.path("documentId").asText());
            documents.findById(documentId).ifPresentOrElse(document -> {
                switch (routingKey) {
                    case RabbitConfig.PROGRESS -> document.markProgress(event.path("percent").asInt());
                    case RabbitConfig.PROCESSED -> document.markReady(event.path("pageCount").asInt(),
                            event.path("chunkCount").asInt(), event.path("durationMs").asLong());
                    case RabbitConfig.FAILED -> document.markFailed(event.path("reason").asText());
                    default -> log.warn("Ignoring unknown event {}", routingKey);
                }
                documents.save(document);
                broadcaster.publish(document);
                if (!RabbitConfig.PROGRESS.equals(routingKey)) {
                    log.info("Document {} is now {}", documentId, document.getStatus());
                }
            }, () -> log.info("Event {} for deleted document {}", routingKey, documentId));
        } finally {
            MDC.remove("correlationId");
        }
    }
}
