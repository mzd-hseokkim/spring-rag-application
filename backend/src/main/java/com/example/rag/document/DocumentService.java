package com.example.rag.document;

import com.example.rag.auth.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Service
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final AppUserRepository appUserRepository;

    public DocumentService(DocumentRepository documentRepository,
                           AppUserRepository appUserRepository) {
        this.documentRepository = documentRepository;
        this.appUserRepository = appUserRepository;
    }

    @Transactional
    public Document upload(MultipartFile file, UUID userId, boolean isPublic) {
        Document document = new Document(
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize()
        );
        document.setPublic(isPublic);
        appUserRepository.findById(userId).ifPresent(document::setUser);
        return documentRepository.save(document);
    }

    @Transactional(readOnly = true)
    public List<Document> findAllForUser(UUID userId) {
        return documentRepository.findByUserIdOrIsPublicTrue(userId);
    }

    @Transactional(readOnly = true)
    public Document findById(UUID id) {
        return documentRepository.findByIdWithTags(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    @Transactional
    public Document save(Document document) {
        return documentRepository.save(document);
    }
}
