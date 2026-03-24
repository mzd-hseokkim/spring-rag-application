package com.example.rag.generation;

import com.example.rag.common.RagException;
import com.example.rag.auth.AppUser;
import com.example.rag.auth.AppUserRepository;
import com.example.rag.generation.dto.GenerationRequest;
import com.example.rag.generation.dto.GenerationResponse;
import com.example.rag.generation.template.DocumentTemplate;
import com.example.rag.generation.template.DocumentTemplateRepository;
import com.example.rag.generation.workflow.GenerationWorkflowService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class GenerationService {

    private static final String JOB_NOT_FOUND = "Generation job not found: ";

    private final GenerationJobRepository jobRepository;
    private final DocumentTemplateRepository templateRepository;
    private final AppUserRepository userRepository;
    private final GenerationWorkflowService workflowService;

    public GenerationService(GenerationJobRepository jobRepository,
                             DocumentTemplateRepository templateRepository,
                             AppUserRepository userRepository,
                             GenerationWorkflowService workflowService) {
        this.jobRepository = jobRepository;
        this.templateRepository = templateRepository;
        this.userRepository = userRepository;
        this.workflowService = workflowService;
    }

    @Transactional
    public GenerationResponse startGeneration(GenerationRequest request, UUID userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        DocumentTemplate template = templateRepository.findById(request.templateId())
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + request.templateId()));

        GenerationJob job = new GenerationJob(template, request.userInput(), user);
        if (request.conversationId() != null) {
            job.setConversationId(request.conversationId());
        }
        job = jobRepository.save(job);

        List<UUID> documentIds = (request.options() != null && request.options().documentIds() != null)
                ? request.options().documentIds()
                : List.of();

        // 트랜잭션 커밋 후에 비동기 워크플로우 실행 (커밋 전 실행 시 detached entity 에러 발생)
        GenerationJob savedJob = job;
        List<UUID> docIds = documentIds;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                workflowService.execute(savedJob, docIds);
            }
        });

        return toResponse(job);
    }

    public GenerationResponse getJob(UUID id) {
        GenerationJob job = jobRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(JOB_NOT_FOUND + id));
        return toResponse(job);
    }

    public List<GenerationResponse> getJobsByUser(UUID userId) {
        return jobRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    public Resource getOutputFile(UUID jobId) {
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException(JOB_NOT_FOUND + jobId));
        if (job.getOutputFilePath() == null) {
            throw new IllegalStateException("Output file not yet generated for job: " + jobId);
        }
        return new FileSystemResource(Path.of(job.getOutputFilePath()));
    }

    public String getPreviewHtml(UUID jobId) {
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException(JOB_NOT_FOUND + jobId));
        if (job.getOutputFilePath() == null) {
            throw new IllegalStateException("Output file not yet generated for job: " + jobId);
        }
        try {
            return Files.readString(Path.of(job.getOutputFilePath()));
        } catch (IOException e) {
            throw new RagException("Failed to read generated HTML file", e);
        }
    }

    @Transactional
    public void deleteJob(UUID id) {
        jobRepository.deleteById(id);
    }

    private GenerationResponse toResponse(GenerationJob job) {
        return new GenerationResponse(
                job.getId(),
                job.getStatus(),
                job.getTemplate().getId(),
                job.getTemplate().getName(),
                job.getCurrentSection(),
                job.getTotalSections(),
                job.getOutputFilePath(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
