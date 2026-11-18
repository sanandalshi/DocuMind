package com.documind.core.document;

import com.documind.core.chat.AiServiceClient;
import com.documind.core.common.ApiException;
import com.documind.core.common.CorrelationIdFilter;
import com.documind.core.config.RabbitConfig;
import com.documind.core.ratelimit.RateLimiter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    private final DocumentRepository documents;
    private final StorageService storage;
    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    private final RateLimiter rateLimiter;
    private final AiServiceClient aiService;

    public DocumentService(DocumentRepository documents, StorageService storage, RabbitTemplate rabbit,
                           ObjectMapper mapper, RateLimiter rateLimiter, AiServiceClient aiService) {
        this.documents = documents;
        this.storage = storage;
        this.rabbit = rabbit;
        this.mapper = mapper;
        this.rateLimiter = rateLimiter;
        this.aiService = aiService;
    }

    public record UploadResult(Document document, boolean duplicate) {}

    /** FR-6, FR-7, FR-10: validate, store, record, publish; processing happens asynchronously. */
    public UploadResult upload(UUID userId, MultipartFile file) {
        byte[] content = readAndValidate(file);
        String hash = sha256(content);

        var existing = documents.findFirstByUserIdAndFileHashAndStatusNot(userId, hash, DocumentStatus.FAILED);
        if (existing.isPresent()) {
            return new UploadResult(existing.get(), true);
        }
        rateLimiter.checkUpload(userId);

        Document document = new Document(userId, cleanName(file.getOriginalFilename()), hash, content.length);
        storage.put(document.getStorageKey(), content);
        documents.save(document);

        try {
            publishUploaded(document);
        } catch (Exception e) {
            log.error("Could not publish upload event for {}", document.getId(), e);
            document.markFailed("Could not queue the document for processing");
            documents.save(document);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "QUEUE_UNAVAILABLE",
                    "Document saved but could not be queued for processing. Please try again.");
        }
        return new UploadResult(document, false);
    }

    public List<Document> list(UUID userId) {
        return documents.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /** Returns 404 (not 403) for other users' documents so their existence is not revealed (FR-4). */
    public Document get(UUID userId, UUID documentId) {
        return documents.findByIdAndUserId(documentId, userId).orElseThrow(() -> ApiException.notFound("Document"));
    }

    public InputStream openFile(UUID userId, UUID documentId) {
        return storage.get(get(userId, documentId).getStorageKey());
    }

    /** FR-9: removes the embeddings, the file and the record. */
    @Transactional
    public void delete(UUID userId, UUID documentId) {
        Document document = get(userId, documentId);
        aiService.deleteDocument(document.getId());
        try {
            storage.delete(document.getStorageKey());
        } catch (Exception e) {
            log.warn("Could not delete file {}: {}", document.getStorageKey(), e.getMessage());
        }
        documents.delete(document);
    }

    private void publishUploaded(Document document) throws JsonProcessingException {
        Map<String, Object> event = Map.of(
                "documentId", document.getId().toString(),
                "userId", document.getUserId().toString(),
                "storageKey", document.getStorageKey(),
                "fileHash", document.getFileHash(),
                "correlationId", CorrelationIdFilter.current());
        var message = MessageBuilder.withBody(mapper.writeValueAsBytes(event))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        rabbit.send(RabbitConfig.EXCHANGE, RabbitConfig.UPLOADED, message);
    }

    private static byte[] readAndValidate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "Please choose a PDF file to upload");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("UNREADABLE_FILE", "The file could not be read");
        }
        // Check the file signature, not the extension or the browser's content type (NFR-11)
        if (content.length < PDF_MAGIC.length) {
            throw ApiException.badRequest("NOT_A_PDF", "Only PDF files are supported");
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (content[i] != PDF_MAGIC[i]) {
                throw ApiException.badRequest("NOT_A_PDF", "Only PDF files are supported");
            }
        }
        return content;
    }

    private static String cleanName(String original) {
        String name = original == null ? "document.pdf" : original.replaceAll("[\\\\/]", "_").strip();
        if (name.isEmpty()) {
            name = "document.pdf";
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
