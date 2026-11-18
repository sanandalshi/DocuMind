package com.documind.core.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exchange and queues shared with the AI Service. Both services declare the same topology
 * (declarations are idempotent), so no upload event is lost whichever service starts first.
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "documind.events";
    public static final String DLX = "documind.dlx";
    public static final String AI_QUEUE = "ai.document.uploaded";
    public static final String AI_DLQ = "ai.document.uploaded.dlq";
    public static final String STATUS_QUEUE = "core.document.status";

    public static final String UPLOADED = "document.uploaded";
    public static final String PROGRESS = "document.progress";
    public static final String PROCESSED = "document.processed";
    public static final String FAILED = "document.failed";

    @Bean
    Declarables topology() {
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(DLX, true, false);
        Queue aiQueue = QueueBuilder.durable(AI_QUEUE).deadLetterExchange(DLX).build();
        Queue aiDlq = QueueBuilder.durable(AI_DLQ).build();
        Queue status = QueueBuilder.durable(STATUS_QUEUE).build();
        return new Declarables(exchange, dlx, aiQueue, aiDlq, status,
                bind(aiQueue, exchange, UPLOADED),
                BindingBuilder.bind(aiDlq).to(dlx).with("#"),
                bind(status, exchange, PROGRESS),
                bind(status, exchange, PROCESSED),
                bind(status, exchange, FAILED));
    }

    private static Binding bind(Queue queue, TopicExchange exchange, String key) {
        return BindingBuilder.bind(queue).to(exchange).with(key);
    }
}
