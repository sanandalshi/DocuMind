package com.documind.core.document;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes document progress to open browser streams (FR-27).
 * Streams are held in memory, so with several Core API instances each browser sees updates
 * from the instance it is connected to; all instances receive every event from RabbitMQ only
 * if each has its own queue (see README, "Scaling").
 */
@Component
public class ProgressBroadcaster {

    private static final long TIMEOUT_MS = 10 * 60 * 1000L;

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Document document) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        send(emitter, document);
        if (document.isFinished()) {
            emitter.complete();
            return emitter;
        }
        List<SseEmitter> list = emitters.computeIfAbsent(document.getId(), id -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    public void publish(Document document) {
        List<SseEmitter> list = emitters.getOrDefault(document.getId(), List.of());
        for (SseEmitter emitter : list) {
            if (send(emitter, document) && document.isFinished()) {
                emitter.complete();
            }
        }
        if (document.isFinished()) {
            emitters.remove(document.getId());
        }
    }

    private boolean send(SseEmitter emitter, Document document) {
        try {
            emitter.send(SseEmitter.event().name("progress").data(DocumentResponse.of(document)));
            return true;
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
            return false;
        }
    }
}
