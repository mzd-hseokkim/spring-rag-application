package com.example.rag.questionnaire;

import com.example.rag.auth.AppUser;
import com.example.rag.auth.AppUserRepository;
import com.example.rag.questionnaire.dto.QuestionnaireRequest;
import com.example.rag.questionnaire.dto.QuestionnaireResponse;
import com.example.rag.questionnaire.persona.Persona;
import com.example.rag.questionnaire.persona.PersonaRepository;
import com.example.rag.document.Document;
import com.example.rag.document.DocumentRepository;
import com.example.rag.questionnaire.workflow.QuestionnaireWorkflowService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.rag.common.RagException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class QuestionnaireService {

    private static final String JOB_NOT_FOUND = "Questionnaire job not found: ";

    private final QuestionnaireJobRepository jobRepository;
    private final PersonaRepository personaRepository;
    private final AppUserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final QuestionnaireWorkflowService workflowService;

    public QuestionnaireService(QuestionnaireJobRepository jobRepository,
                                PersonaRepository personaRepository,
                                AppUserRepository userRepository,
                                DocumentRepository documentRepository,
                                QuestionnaireWorkflowService workflowService) {
        this.jobRepository = jobRepository;
        this.personaRepository = personaRepository;
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
        this.workflowService = workflowService;
    }

    @Transactional
    public QuestionnaireResponse startGeneration(QuestionnaireRequest request, UUID userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        List<Persona> personas = personaRepository.findAllById(request.personaIds());
        if (personas.isEmpty()) {
            throw new IllegalArgumentException("선택된 페르소나가 없습니다.");
        }

        List<UUID> customerDocIds = request.customerDocumentIds() != null ? request.customerDocumentIds() : List.of();
        List<UUID> proposalDocIds = request.proposalDocumentIds() != null ? request.proposalDocumentIds() : List.of();

        // 제목 자동 생성: 고객문서(RFP) 기준 "문서명 — 예상질의 (날짜)"
        String title = buildTitle(customerDocIds);

        QuestionnaireJob job = new QuestionnaireJob(request.userInput(), user);
        job.setTitle(title);
        job.setTotalPersonas(personas.size());
        job = jobRepository.save(job);
        List<UUID> refDocIds = request.referenceDocumentIds() != null ? request.referenceDocumentIds() : List.of();
        List<UUID> personaIds = personas.stream().map(Persona::getId).toList();
        int questionCount = request.questionCount();

        QuestionnaireJob savedJob = job;
        boolean webSearch = request.includeWebSearch();
        String analysisMode = request.analysisMode();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                workflowService.execute(savedJob, customerDocIds, proposalDocIds, refDocIds, personaIds, questionCount, webSearch, analysisMode);
            }
        });

        return toResponse(job);
    }

    public QuestionnaireResponse getJob(UUID id) {
        QuestionnaireJob job = jobRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(JOB_NOT_FOUND + id));
        return toResponse(job);
    }

    public List<QuestionnaireResponse> getJobsByUser(UUID userId) {
        return jobRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    public Resource getOutputFile(UUID jobId) {
        QuestionnaireJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException(JOB_NOT_FOUND + jobId));
        if (job.getOutputFilePath() == null) {
            throw new IllegalStateException("Output file not yet generated for job: " + jobId);
        }
        return new FileSystemResource(Path.of(job.getOutputFilePath()));
    }

    public String getPreviewHtml(UUID jobId) {
        QuestionnaireJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException(JOB_NOT_FOUND + jobId));
        if (job.getOutputFilePath() == null) {
            throw new IllegalStateException("Output file not yet generated for job: " + jobId);
        }
        // outputFilePath는 .xlsx이므로, HTML 미리보기 파일은 같은 경로에 .html 확장자로 존재
        String htmlPath = job.getOutputFilePath().replace(".xlsx", ".html");
        try {
            return Files.readString(Path.of(htmlPath));
        } catch (IOException e) {
            throw new RagException("Failed to read questionnaire preview HTML file", e);
        }
    }

    @Transactional
    public QuestionnaireResponse updateTitle(UUID id, String title) {
        QuestionnaireJob job = jobRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + id));
        job.setTitle(title);
        return toResponse(jobRepository.save(job));
    }

    @Transactional
    public void deleteJob(UUID id) {
        jobRepository.deleteById(id);
    }

    private String buildTitle(List<UUID> customerDocIds) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        if (customerDocIds.isEmpty()) {
            return "예상질의 " + date;
        }
        String docNames = documentRepository.findAllById(customerDocIds).stream()
                .map(Document::getFilename)
                .map(name -> name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name)
                .collect(Collectors.joining(", "));
        if (docNames.length() > 100) {
            docNames = docNames.substring(0, 100) + "...";
        }
        return docNames + " — 예상질의 " + date;
    }

    private QuestionnaireResponse toResponse(QuestionnaireJob job) {
        return new QuestionnaireResponse(
                job.getId(),
                job.getTitle(),
                job.getStatus(),
                job.getUserInput(),
                job.getCurrentPersona(),
                job.getTotalPersonas(),
                job.getOutputFilePath(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
