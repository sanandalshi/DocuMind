package com.documind.core;

import com.documind.core.chat.AiServiceClient;
import com.documind.core.config.RabbitConfig;
import com.documind.core.document.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end API test against real PostgreSQL, Redis and RabbitMQ (Testcontainers).
 * Object storage and the AI Service are replaced with mocks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.limits.questions-per-minute=5", "app.admin-emails=admin@example.com", "LOG_FORMAT=",
        "logging.structured.format.console="})
@Testcontainers(disabledWithoutDocker = true)
class ApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final byte[] PDF = "%PDF-1.4 fake test document".getBytes(StandardCharsets.UTF_8);

    @Autowired TestRestTemplate http;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper mapper;

    @MockitoBean StorageService storage;
    @MockitoBean AiServiceClient aiService;

    @BeforeEach
    void stubs() {
        when(storage.get(anyString())).thenReturn(new ByteArrayInputStream(PDF));
        doAnswer(inv -> {
            Consumer<JsonNode> emit = inv.getArgument(1);
            AiServiceClient.QueryRequest req = inv.getArgument(0);
            emit.accept(json(Map.of("type", "citations", "citations", List.of(Map.of("index", 1,
                    "documentId", req.documentIds().getFirst().toString(), "page", 3,
                    "excerpt", "24 days of paid leave", "score", 0.81)))));
            emit.accept(json(Map.of("type", "token", "text", "You get 24 days ")));
            emit.accept(json(Map.of("type", "token", "text", "of leave [1].")));
            emit.accept(json(Map.of("type", "done", "notFound", false)));
            return null;
        }).when(aiService).streamQuery(any(), any());
    }

    @Test
    void fullDocumentAndQuestionFlow() throws Exception {
        String token = registerAndLogin("flow");

        // FR-6/11: only real PDFs are accepted
        assertThat(upload(token, "notes.pdf", "hello".getBytes()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // FR-6/7: upload is accepted at once and an event is queued for the AI Service
        ResponseEntity<Map> uploaded = upload(token, "policy.pdf", PDF);
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String docId = (String) uploaded.getBody().get("id");
        Message queued = rabbitTemplate.receive(RabbitConfig.AI_QUEUE, 5000);
        assertThat(queued).isNotNull();
        assertThat(mapper.readTree(queued.getBody()).path("documentId").asText()).isEqualTo(docId);

        // FR-10: the same file again returns the existing document
        ResponseEntity<Map> again = upload(token, "copy.pdf", PDF);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("id")).isEqualTo(docId);

        // Asking before processing finishes is refused
        String chatId = createChat(token, docId);
        assertThat(ask(token, chatId, "How much leave?").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // AI Service reports progress, then success
        sendEvent(RabbitConfig.PROGRESS, Map.of("documentId", docId, "percent", 40));
        sendEvent(RabbitConfig.PROCESSED, Map.of("documentId", docId, "pageCount", 12, "chunkCount", 30,
                "durationMs", 4200));
        Map doc = awaitStatus(token, docId, "READY");
        assertThat(doc.get("pageCount")).isEqualTo(12);
        assertThat(doc.get("progress")).isEqualTo(100);

        // FR-17..21: answer streams over SSE with citations that carry the document name
        ResponseEntity<String> answer = ask(token, chatId, "How much leave?");
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(answer.getBody()).contains("event:citations", "\"documentName\":\"policy.pdf\"", "\"page\":3",
                "event:token", "You get 24 days ", "event:done", "\"cached\":false");

        // FR-22: history is saved
        ResponseEntity<List> history = http.exchange("/api/v1/chats/" + chatId + "/messages", HttpMethod.GET,
                new HttpEntity<>(auth(token)), List.class);
        assertThat(history.getBody()).hasSize(2);

        // FR-23: the same first question in a new chat is served from the cache
        String chat2 = createChat(token, docId);
        assertThat(ask(token, chat2, "how much leave").getBody()).contains("\"cached\":true", "You get 24 days of leave [1].");
        verify(aiService, times(1)).streamQuery(any(), any());

        // FR-9: delete removes chunks, file and record
        assertThat(http.exchange("/api/v1/documents/" + docId, HttpMethod.DELETE, new HttpEntity<>(auth(token)),
                Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(aiService).deleteDocument(UUID.fromString(docId));
        verify(storage).delete(anyString());
        assertThat(get(token, "/api/v1/documents/" + docId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void usersCannotSeeEachOthersDocumentsOrChats() {
        String alice = registerAndLogin("alice");
        String bob = registerAndLogin("bob");
        String docId = (String) upload(alice, "secret.pdf", "%PDF-1.4 alice only".getBytes()).getBody().get("id");
        String chatId = createChat(alice, docId);

        assertThat(get(bob, "/api/v1/documents/" + docId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(bob, "/api/v1/documents/" + docId + "/file").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(bob, "/api/v1/chats/" + chatId + "/messages").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ask(bob, chatId, "what is in it?").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(bob, "/api/v1/documents").getBody()).isEqualTo("[]");
    }

    @Test
    void questionsAreRateLimited() {
        String token = registerAndLogin("limit");
        String docId = (String) upload(token, "a.pdf", "%PDF-1.4 rate limit doc".getBytes()).getBody().get("id");
        sendEvent(RabbitConfig.PROCESSED, Map.of("documentId", docId, "pageCount", 1, "chunkCount", 1, "durationMs", 1));
        awaitStatus(token, docId, "READY");
        String chatId = createChat(token, docId);
        for (int i = 0; i < 5; i++) {
            assertThat(ask(token, chatId, "question " + i).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> limited = ask(token, chatId, "one too many");
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
    }

    @Test
    void authRules() {
        assertThat(http.getForEntity("/api/v1/documents", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        String user = registerAndLogin("plain");
        assertThat(get(user, "/api/v1/admin/users").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        http.postForEntity("/api/v1/auth/register",
                Map.of("name", "Admin", "email", "admin@example.com", "password", "admin-password"), Map.class);
        Map login = http.postForEntity("/api/v1/auth/login",
                Map.of("email", "admin@example.com", "password", "admin-password"), Map.class).getBody();
        assertThat(get((String) login.get("accessToken"), "/api/v1/admin/users").getStatusCode()).isEqualTo(HttpStatus.OK);

        // refresh tokens rotate: each one works once
        String refresh = (String) login.get("refreshToken");
        assertThat(http.postForEntity("/api/v1/auth/refresh", Map.of("refreshToken", refresh), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.postForEntity("/api/v1/auth/refresh", Map.of("refreshToken", refresh), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(http.postForEntity("/api/v1/auth/login",
                Map.of("email", "admin@example.com", "password", "wrong-password"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------- helpers ----------

    private String registerAndLogin(String name) {
        String email = name + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        assertThat(http.postForEntity("/api/v1/auth/register",
                Map.of("name", name, "email", email, "password", "password-123"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        return (String) http.postForEntity("/api/v1/auth/login", Map.of("email", email, "password", "password-123"),
                Map.class).getBody().get("accessToken");
    }

    private ResponseEntity<Map> upload(String token, String filename, byte[] content) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return filename; }
        });
        HttpHeaders headers = auth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.postForEntity("/api/v1/documents", new HttpEntity<>(body, headers), Map.class);
    }

    private String createChat(String token, String docId) {
        return (String) http.postForEntity("/api/v1/chats", new HttpEntity<>(Map.of("documentId", docId), auth(token)),
                Map.class).getBody().get("id");
    }

    private ResponseEntity<String> ask(String token, String chatId, String question) {
        HttpHeaders headers = auth(token);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
        return http.postForEntity("/api/v1/chats/" + chatId + "/messages",
                new HttpEntity<>(Map.of("question", question), headers), String.class);
    }

    private ResponseEntity<String> get(String token, String path) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(token)), String.class);
    }

    private Map awaitStatus(String token, String docId, String status) {
        for (int i = 0; i < 50; i++) {
            Map doc = http.exchange("/api/v1/documents/" + docId, HttpMethod.GET, new HttpEntity<>(auth(token)),
                    Map.class).getBody();
            if (status.equals(doc.get("status"))) {
                return doc;
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("Document never reached " + status);
    }

    private void sendEvent(String routingKey, Map<String, Object> event) {
        try {
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            rabbitTemplate.send(RabbitConfig.EXCHANGE, routingKey, new Message(mapper.writeValueAsBytes(event), props));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private JsonNode json(Object value) {
        return mapper.valueToTree(value);
    }
}
