package com.example.rag.document;

import com.example.rag.document.collection.DocumentCollection;
import com.example.rag.document.collection.DocumentCollectionRepository;
import com.example.rag.document.pipeline.IngestionEventPublisher;
import com.example.rag.document.pipeline.IngestionPipeline;
import com.example.rag.document.tag.DocumentTag;
import com.example.rag.document.tag.DocumentTagRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final IngestionPipeline ingestionPipeline;
    private final IngestionEventPublisher eventPublisher;
    private final DocumentTagRepository tagRepository;
    private final DocumentCollectionRepository collectionRepository;

    public DocumentController(DocumentService documentService,
                              IngestionPipeline ingestionPipeline,
                              IngestionEventPublisher eventPublisher,
                              DocumentTagRepository tagRepository,
                              DocumentCollectionRepository collectionRepository) {
        this.documentService = documentService;
        this.ingestionPipeline = ingestionPipeline;
        this.eventPublisher = eventPublisher;
        this.tagRepository = tagRepository;
        this.collectionRepository = collectionRepository;
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
            throw new com.example.rag.common.RagException("Failed to read file", e);
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

    @PutMapping("/{id}/tags")
    public DocumentResponse setTags(@PathVariable UUID id, @RequestBody TagsRequest request) {
        Document doc = documentService.findById(id);
        Set<DocumentTag> tags = new HashSet<>(tagRepository.findAllById(
                request.tagIds().stream().map(UUID::fromString).toList()));
        doc.setTags(tags);
        return DocumentResponse.from(documentService.save(doc));
    }

    @PutMapping("/{id}/collections")
    public DocumentResponse setCollections(@PathVariable UUID id, @RequestBody CollectionsRequest request) {
        Document doc = documentService.findById(id);
        Set<DocumentCollection> collections = new HashSet<>(collectionRepository.findAllById(
                request.collectionIds().stream().map(UUID::fromString).toList()));
        doc.setCollections(collections);
        return DocumentResponse.from(documentService.save(doc));
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
    record TagsRequest(List<String> tagIds) {}
    record CollectionsRequest(List<String> collectionIds) {}
}
