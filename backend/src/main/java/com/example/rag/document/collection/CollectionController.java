package com.example.rag.document.collection;

import com.example.rag.auth.AppUserRepository;
import com.example.rag.document.Document;
import com.example.rag.document.DocumentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/collections")
public class CollectionController {

    private final DocumentCollectionRepository collectionRepository;
    private final DocumentRepository documentRepository;
    private final AppUserRepository appUserRepository;

    public CollectionController(DocumentCollectionRepository collectionRepository,
                                 DocumentRepository documentRepository,
                                 AppUserRepository appUserRepository) {
        this.collectionRepository = collectionRepository;
        this.documentRepository = documentRepository;
        this.appUserRepository = appUserRepository;
    }

    @GetMapping
    public List<CollectionDto> list(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return collectionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(CollectionDto::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CollectionDto create(@RequestBody CreateRequest request, Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        var user = appUserRepository.findById(userId).orElseThrow();
        var collection = new DocumentCollection(request.name(), request.description(), user);
        collectionRepository.save(collection);
        return CollectionDto.from(collection);
    }

    @PatchMapping("/{id}")
    public CollectionDto update(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        var collection = collectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("컬렉션을 찾을 수 없습니다."));
        if (body.containsKey("name")) collection.setName(body.get("name"));
        if (body.containsKey("description")) collection.setDescription(body.get("description"));
        collectionRepository.save(collection);
        return CollectionDto.from(collection);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        collectionRepository.deleteById(id);
    }

    @PutMapping("/{id}/documents")
    public void setDocuments(@PathVariable UUID id, @RequestBody DocumentsRequest request) {
        var collection = collectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("컬렉션을 찾을 수 없습니다."));
        List<Document> docs = documentRepository.findAllById(
                request.documentIds().stream().map(UUID::fromString).toList());
        docs.forEach(doc -> doc.getCollections().add(collection));
        documentRepository.saveAll(docs);
    }

    @GetMapping("/{id}/documents")
    public List<DocumentSummary> getDocuments(@PathVariable UUID id) {
        return documentRepository.findByCollectionsId(id).stream()
                .map(d -> new DocumentSummary(d.getId(), d.getFilename(), d.getStatus().name()))
                .toList();
    }

    record CreateRequest(String name, String description) {}
    record DocumentsRequest(List<String> documentIds) {}
    record DocumentSummary(UUID id, String filename, String status) {}
    record CollectionDto(UUID id, String name, String description, java.time.LocalDateTime createdAt) {
        static CollectionDto from(DocumentCollection c) {
            return new CollectionDto(c.getId(), c.getName(), c.getDescription(), c.getCreatedAt());
        }
    }
}
