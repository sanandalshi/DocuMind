package com.documind.core.document;

import com.documind.core.security.AuthUser;
import com.documind.core.security.CurrentUser;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final ProgressBroadcaster broadcaster;

    public DocumentController(DocumentService documentService, ProgressBroadcaster broadcaster) {
        this.documentService = documentService;
        this.broadcaster = broadcaster;
    }

    /** 202 for a new upload (processing continues in the background), 200 for a duplicate. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(@CurrentUser AuthUser user, @RequestParam("file") MultipartFile file) {
        var result = documentService.upload(user.id(), file);
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(DocumentResponse.of(result.document()));
    }

    @GetMapping
    public List<DocumentResponse> list(@CurrentUser AuthUser user) {
        return documentService.list(user.id()).stream().map(DocumentResponse::of).toList();
    }

    @GetMapping("/{id}")
    public DocumentResponse get(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return DocumentResponse.of(documentService.get(user.id(), id));
    }

    /** Streams the PDF through the API, so storage is never exposed to the browser. */
    @GetMapping("/{id}/file")
    public ResponseEntity<InputStreamResource> file(@CurrentUser AuthUser user, @PathVariable UUID id) {
        Document document = documentService.get(user.id(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(document.getName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(new InputStreamResource(documentService.openFile(user.id(), id)));
    }

    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return broadcaster.subscribe(documentService.get(user.id(), id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthUser user, @PathVariable UUID id) {
        documentService.delete(user.id(), id);
    }
}
