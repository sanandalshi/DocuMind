package com.documind.core.chat;

import com.documind.core.common.ApiException;
import com.documind.core.common.CorrelationIdFilter;
import com.documind.core.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Internal HTTP client for the AI Service (SRS 5.2). */
@Component
public class AiServiceClient {

    public record Turn(String role, String content) {}

    public record QueryRequest(String question, UUID userId, List<UUID> documentIds, List<Turn> history) {}

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final AppProperties.AiService config;

    public AiServiceClient(ObjectMapper mapper, AppProperties props) {
        this.mapper = mapper;
        this.config = props.aiService();
        // HTTP/1.1: the default would attempt an h2c upgrade, which Uvicorn rejects
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Streams the AI Service's newline-delimited JSON events to {@code onEvent}, in order. */
    public void streamQuery(QueryRequest request, Consumer<JsonNode> onEvent) {
        try {
            HttpRequest httpRequest = base("/internal/query")
                    .header("Content-Type", "application/json")
                    .timeout(config.timeout())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(request)))
                    .build();
            HttpResponse<Stream<String>> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                response.body().close();
                throw unavailable("AI Service returned " + response.statusCode());
            }
            try (Stream<String> lines = response.body()) {
                lines.filter(line -> !line.isBlank()).forEach(line -> {
                    try {
                        onEvent.accept(mapper.readTree(line));
                    } catch (IOException e) {
                        throw new IllegalStateException("Malformed event from AI Service", e);
                    }
                });
            }
        } catch (IOException e) {
            throw unavailable("AI Service is unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("Interrupted while calling the AI Service");
        }
    }

    public void deleteDocument(UUID documentId) {
        try {
            HttpResponse<Void> response = http.send(base("/internal/documents/" + documentId)
                    .timeout(config.timeout()).DELETE().build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                throw unavailable("AI Service returned " + response.statusCode() + " deleting chunks");
            }
        } catch (IOException e) {
            throw unavailable("AI Service is unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("Interrupted while calling the AI Service");
        }
    }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create(config.url() + path))
                .header("X-Service-Token", config.token())
                .header(CorrelationIdFilter.HEADER, CorrelationIdFilter.current());
    }

    private static ApiException unavailable(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "AI_SERVICE_ERROR", message);
    }

}
