package com.example.rag.document;

import com.example.rag.document.pipeline.IngestionEventPublisher;
import com.example.rag.document.pipeline.IngestionPipeline;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final IngestionPipeline ingestionPipeline;
    private final IngestionEventPublisher eventPublisher;

    public DocumentController(DocumentService documentService,
                              IngestionPipeline ingestionPipeline,
                              IngestionEventPublisher eventPublisher) {
        this.documentService = documentService;
        this.ingestionPipeline = ingestionPipeline;
        this.eventPublisher = eventPublisher;
    }

    @PostMapping
    public ResponseEntity<DocumentResponse> upload(@RequestParam("file") MultipartFile file,
                                                    @RequestParam(defaultValue = "false") boolean isPublic,
                                                    Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        // ADMIN이 아니면 공용 등록 불가
        boolean hasAdminRole = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        boolean effectivePublic = hasAdminRole && isPublic;
        Document document = documentService.upload(file, userId, effectivePublic);

        try {
            byte[] fileBytes = file.getBytes();
            ingestionPipeline.process(document.getId(), file.getContentType(), fileBytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file", e);
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(DocumentResponse.from(document));
    }

    @GetMapping
    public List<DocumentResponse> findAll(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return documentService.findAllForUser(userId).stream()
                .map(DocumentResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public DocumentResponse findById(@PathVariable UUID id) {
        return DocumentResponse.from(documentService.findById(id));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id) {
        return eventPublisher.subscribe(id);
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(DocumentNotFoundException e) {
        return new ErrorResponse(e.getMessage());
    }

    record ErrorResponse(String message) {}
}
