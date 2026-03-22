package com.example.rag.document;

import com.example.rag.auth.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documentRepository;
    private final AppUserRepository appUserRepository;
    private final Path documentDir;

    public DocumentService(DocumentRepository documentRepository,
                           AppUserRepository appUserRepository,
                           @Value("${app.upload.document-dir}") String documentDirPath) {
        this.documentRepository = documentRepository;
        this.appUserRepository = appUserRepository;
        this.documentDir = Paths.get(documentDirPath).toAbsolutePath();
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
        document = documentRepository.save(document);

        // 원본 파일을 디스크에 저장
        try {
            Path userDir = documentDir.resolve(userId.toString());
            Files.createDirectories(userDir);
            String storedFilename = document.getId() + "_" + file.getOriginalFilename();
            Path filePath = userDir.resolve(storedFilename);
            file.transferTo(filePath.toFile());
            document.setStoredPath(filePath.toString());
            document = documentRepository.save(document);
        } catch (IOException e) {
            log.warn("Failed to store original file for document {}: {}", document.getId(), e.getMessage());
        }

        return document;
    }

    public Resource loadFile(Document document) {
        if (document.getStoredPath() == null) {
            throw new IllegalStateException("원본 파일이 저장되어 있지 않습니다.");
        }
        Path path = Paths.get(document.getStoredPath());
        if (!Files.exists(path)) {
            throw new IllegalStateException("원본 파일을 찾을 수 없습니다.");
        }
        return new FileSystemResource(path);
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
