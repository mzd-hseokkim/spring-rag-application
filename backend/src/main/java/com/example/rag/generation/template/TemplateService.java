package com.example.rag.generation.template;

import com.example.rag.auth.AppUser;
import com.example.rag.auth.AppUserRepository;
import com.example.rag.generation.dto.TemplateRequest;
import com.example.rag.generation.dto.TemplateResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class TemplateService {

    private final DocumentTemplateRepository templateRepository;
    private final AppUserRepository userRepository;

    public TemplateService(DocumentTemplateRepository templateRepository,
                           AppUserRepository userRepository) {
        this.templateRepository = templateRepository;
        this.userRepository = userRepository;
    }

    public List<TemplateResponse> getAvailableTemplates(UUID userId) {
        return templateRepository.findAvailableTemplates(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    public TemplateResponse getTemplate(UUID id) {
        DocumentTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + id));
        return toResponse(template);
    }

    @Transactional
    public TemplateResponse createTemplate(TemplateRequest request, UUID userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        DocumentTemplate template = new DocumentTemplate(
                request.name(),
                request.description(),
                request.outputFormat(),
                request.sectionSchema(),
                request.systemPrompt()
        );
        template.setTemplatePath(request.templatePath());
        template.setPublic(request.isPublic());
        template.setUser(user);

        return toResponse(templateRepository.save(template));
    }

    @Transactional
    public TemplateResponse updateTemplate(UUID id, TemplateRequest request) {
        DocumentTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + id));

        template.setName(request.name());
        template.setDescription(request.description());
        template.setOutputFormat(request.outputFormat());
        template.setSectionSchema(request.sectionSchema());
        template.setSystemPrompt(request.systemPrompt());
        template.setTemplatePath(request.templatePath());
        template.setPublic(request.isPublic());

        return toResponse(templateRepository.save(template));
    }

    @Transactional
    public void deleteTemplate(UUID id) {
        templateRepository.deleteById(id);
    }

    private TemplateResponse toResponse(DocumentTemplate t) {
        return new TemplateResponse(
                t.getId(),
                t.getName(),
                t.getDescription(),
                t.getOutputFormat(),
                t.getSectionSchema(),
                t.getSystemPrompt(),
                t.getTemplatePath(),
                t.isPublic(),
                t.getCreatedAt(),
                t.getUpdatedAt()
        );
    }
}
