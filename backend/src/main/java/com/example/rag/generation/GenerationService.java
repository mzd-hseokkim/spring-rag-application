package com.example.rag.generation;

import com.example.rag.common.RagException;
import com.example.rag.auth.AppUser;
import com.example.rag.auth.AppUserRepository;
import com.example.rag.generation.dto.GenerationRequest;
import com.example.rag.generation.dto.GenerationResponse;
import com.example.rag.document.Document;
import com.example.rag.document.DocumentRepository;
import com.example.rag.generation.dto.OutlineNode;
import com.example.rag.generation.renderer.MarkdownRenderer;
import com.example.rag.generation.template.DocumentTemplate;
import com.example.rag.generation.template.DocumentTemplateRepository;
import com.example.rag.generation.workflow.DocumentOutline;
import com.example.rag.generation.workflow.GenerationWorkflowService;
import com.example.rag.generation.workflow.SectionContent;
import com.example.rag.generation.workflow.SectionPlan;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class GenerationService {

    private static final String JOB_NOT_FOUND = "Generation job not found: ";
    private static final String STEP_STATUS_PROCESSING = "PROCESSING";

    private final GenerationJobRepository jobRepository;
    private final GenerationJobDocumentRepository jobDocumentRepository;
    private final DocumentTemplateRepository templateRepository;
    private final DocumentRepository documentRepository;
    private final AppUserRepository userRepository;
    private final GenerationWorkflowService workflowService;
    private final MarkdownRenderer markdownRenderer;
    private final ObjectMapper objectMapper;

    public GenerationService(GenerationJobRepository jobRepository,
                             GenerationJobDocumentRepository jobDocumentRepository,
                             DocumentTemplateRepository templateRepository,
                             DocumentRepository documentRepository,
                             AppUserRepository userRepository,
                             GenerationWorkflowService workflowService,
                             MarkdownRenderer markdownRenderer,
                             ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.jobDocumentRepository = jobDocumentRepository;
        this.templateRepository = templateRepository;
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.workflowService = workflowService;
        this.markdownRenderer = markdownRenderer;
        this.objectMapper = objectMapper;
    }

    /**
     * Step 1: 위자드 작업 생성 (DRAFT 상태)
     */
    @Transactional
    public GenerationResponse createWizardJob(GenerationRequest request, UUID userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        DocumentTemplate template = templateRepository.findById(request.templateId())
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + request.templateId()));

        GenerationJob job = new GenerationJob(template, request.userInput(), user);
        if (request.conversationId() != null) {
            job.setConversationId(request.conversationId());
        }

        // RFP 문서명 기반 제목 생성
        String title = buildTitle(request.customerDocumentIds());
        job.setTitle(title);
        job.setIncludeWebSearch(request.includeWebSearch());
        job.setCurrentStep(1);
        job.setStepStatus("COMPLETE");
        job = jobRepository.save(job);

        // 문서 연결 저장
        UUID savedJobId = job.getId();
        if (request.customerDocumentIds() != null) {
            for (UUID docId : request.customerDocumentIds()) {
                jobDocumentRepository.save(new GenerationJobDocument(savedJobId, docId, "CUSTOMER"));
            }
        }
        if (request.referenceDocumentIds() != null) {
            for (UUID docId : request.referenceDocumentIds()) {
                jobDocumentRepository.save(new GenerationJobDocument(savedJobId, docId, "REFERENCE"));
            }
        }

        return toResponse(job);
    }

    /**
     * Step 2: 목차 추출 시작 (async)
     */
    @Transactional
    public void startOutlineExtraction(UUID jobId, List<UUID> customerDocIds) {
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException(JOB_NOT_FOUND + jobId));
        job.setStatus(GenerationStatus.ANALYZING);
        job.setCurrentStep(2);
        job.setStepStatus(STEP_STATUS_PROCESSING);
        job.setErrorMessage(null);
        jobRepository.save(job);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                workflowService.extractOutline(jobId, customerDocIds);
            }
        });
    }

    /**
     * Step 2: 사용자가 수정한 목차 저장
     */
    @Transactional
    public GenerationResponse saveOutline(UUID jobId, String outlineJson) {
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException(JOB_NOT_FOUND + jobId));
        job.setOutline(outlineJson);
        jobRepository.save(job);
        return toResponse(job);
    }

    /**
     * Step 3: 요구사항 매핑 시작 (async)
     */
    @Transactional
    public void startRequirementMapping(UUID jobId, List<UUID> customerDocIds) {
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException(JOB_NOT_FOUND + jobId));
        job.setStatus(GenerationStatus.MAPPING);
        job.setCurrentStep(3);
        job.setStepStatus(STEP_STATUS_PROCESSING);
        job.setErrorMessage(null);
        jobRepository.save(job);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                workflowService.mapRequirements(jobId, customerDocIds);
            }
        });
    }

    /**
     * Step 3: 사용자가 수정한 요구사항 매핑 저장
     */
    @Transactional
    public GenerationResponse saveRequirementMapping(UUID jobId, String mappingJson) {
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException(JOB_NOT_FOUND + jobId));
        job.setRequirementMapping(mappingJson);
        jobRepository.save(job);
        return toResponse(job);
    }

    /**
     * Step 3: 미배치 요구사항을 위한 새 목차 섹션 생성
     */
    @Transactional
    public GenerationResponse generateSectionsForUnmapped(UUID jobId) {
        workflowService.generateSectionsForUnmapped(jobId);
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException(JOB_NOT_FOUND + jobId));
        return toResponse(job);
    }

    /**
     * Step 4: 섹션 생성 시작 (async)
     */
    @Transactional
    public void startSectionGeneration(UUID jobId, List<UUID> refDocIds, boolean includeWebSearch,
                                        List<String> sectionKeys, boolean forceRegenerate) {
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException(JOB_NOT_FOUND + jobId));
        if (includeWebSearch) {
            job.setIncludeWebSearch(true);
        }
        job.setStatus(GenerationStatus.GENERATING);
        job.setCurrentStep(4);
        job.setStepStatus(STEP_STATUS_PROCESSING);
        job.setErrorMessage(null);
        jobRepository.save(job);

        boolean webSearch = job.isIncludeWebSearch();
        List<String> keys = sectionKeys != null ? sectionKeys : List.of();
        boolean force = forceRegenerate;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                workflowService.generateWizardSections(jobId, refDocIds, webSearch, keys, force);
            }
        });
    }

    /**
     * Step 4: 개별 섹션 수정 저장
     */
    @Transactional
    public GenerationResponse saveSection(UUID jobId, String sectionKey, String updatedSectionJson) {
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException(JOB_NOT_FOUND + jobId));
        // generatedSections JSON 내에서 해당 key의 섹션을 교체
        if (job.getGeneratedSections() != null) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var sections = mapper.readTree(job.getGeneratedSections());
                var updated = mapper.readTree(updatedSectionJson);
                if (sections.isArray()) {
                    var arr = (com.fasterxml.jackson.databind.node.ArrayNode) sections;
                    for (int i = 0; i < arr.size(); i++) {
                        if (sectionKey.equals(arr.get(i).path("key").asText())) {
                            arr.set(i, updated);
                            break;
                        }
                    }
                    job.setGeneratedSections(mapper.writeValueAsString(arr));
                }
            } catch (Exception e) {
                throw new RagException("Failed to update section", e);
            }
        }
        jobRepository.save(job);
        return toResponse(job);
    }

    /**
     * Step 4: 기존 섹션 전체 초기화 (전체 재생성 전 호출)
     */
    @Transactional
    public void clearSections(UUID jobId) {
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException(JOB_NOT_FOUND + jobId));
        job.setGeneratedSections(null);
        job.setCurrentSection(0);
        jobRepository.save(job);
    }

    /**
     * Step 4: 단일 섹션 재생성 (async)
     */
    @Transactional
    public void startSingleSectionRegeneration(UUID jobId, String sectionKey,
                                                List<UUID> refDocIds, boolean includeWebSearch, String userInstruction) {
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException(JOB_NOT_FOUND + jobId));
        job.setErrorMessage(null);
        jobRepository.save(job);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                workflowService.regenerateSection(jobId, sectionKey, refDocIds, includeWebSearch, userInstruction);
            }
        });
    }

    /**
     * Step 5: HTML 렌더링 시작 (async)
     */
    @Transactional
    public void startRendering(UUID jobId) {
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException(JOB_NOT_FOUND + jobId));
        job.setStatus(GenerationStatus.RENDERING);
        job.setCurrentStep(5);
        job.setStepStatus(STEP_STATUS_PROCESSING);
        job.setErrorMessage(null);
        jobRepository.save(job);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                workflowService.renderWizardDocument(jobId);
            }
        });
    }

    /**
     * 기존 단일 플로우 (하위호환)
     */
    @Transactional
    public GenerationResponse startGeneration(GenerationRequest request, UUID userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        DocumentTemplate template = templateRepository.findById(request.templateId())
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + request.templateId()));

        GenerationJob job = new GenerationJob(template, request.userInput(), user);
        job.setStatus(GenerationStatus.PLANNING);
        if (request.conversationId() != null) {
            job.setConversationId(request.conversationId());
        }
        job = jobRepository.save(job);

        List<UUID> documentIds = (request.options() != null && request.options().documentIds() != null)
                ? request.options().documentIds()
                : List.of();

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
    public GenerationResponse updateTitle(UUID id, String title) {
        GenerationJob job = jobRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + id));
        job.setTitle(title);
        return toResponse(jobRepository.save(job));
    }

    @Transactional
    public void deleteJob(UUID id) {
        jobRepository.deleteById(id);
    }

    public record MarkdownResult(String title, String markdown) {}

    public MarkdownResult getMarkdownWithTitle(UUID jobId) {
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException(JOB_NOT_FOUND + jobId));
        if (job.getOutline() == null || job.getGeneratedSections() == null) {
            throw new IllegalStateException("Outline or sections not yet generated for job: " + jobId);
        }
        try {
            List<OutlineNode> outlineNodes = objectMapper.readValue(
                    job.getOutline(), new TypeReference<List<OutlineNode>>() {});
            String docTitle = job.getTitle() != null && !job.getTitle().isBlank()
                    ? job.getTitle() : "제안서";
            List<SectionPlan> allPlans = new ArrayList<>();
            flattenOutlineNodes(outlineNodes, allPlans);
            DocumentOutline outline = new DocumentOutline(docTitle, "", allPlans);

            List<SectionContent> sections = objectMapper.readValue(
                    job.getGeneratedSections(), new TypeReference<List<SectionContent>>() {});
            sections = sections.stream()
                    .sorted(java.util.Comparator.comparing(SectionContent::key, GenerationService::compareKeys))
                    .toList();
            return new MarkdownResult(docTitle, markdownRenderer.render(outline, sections));
        } catch (Exception e) {
            throw new RagException("Failed to generate markdown", e);
        }
    }

    private void flattenOutlineNodes(List<OutlineNode> nodes, List<SectionPlan> result) {
        for (OutlineNode node : nodes) {
            result.add(new SectionPlan(node.key(), node.title(), node.description(), List.of(), 0));
            if (!node.children().isEmpty()) {
                flattenOutlineNodes(node.children(), result);
            }
        }
    }

    private static int compareKeys(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.min(pa.length, pb.length); i++) {
            int cmp = Integer.compare(Integer.parseInt(pa[i]), Integer.parseInt(pb[i]));
            if (cmp != 0) return cmp;
        }
        return Integer.compare(pa.length, pb.length);
    }

    private String buildTitle(List<UUID> customerDocIds) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        if (customerDocIds == null || customerDocIds.isEmpty()) {
            return "제안서 " + date;
        }
        String docNames = documentRepository.findAllById(customerDocIds).stream()
                .map(Document::getFilename)
                .map(name -> name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name)
                .collect(Collectors.joining(", "));
        if (docNames.length() > 80) {
            docNames = docNames.substring(0, 80) + "...";
        }
        return docNames + " — 제안서 " + date;
    }

    private GenerationResponse toResponse(GenerationJob job) {
        List<GenerationJobDocument> jobDocs = jobDocumentRepository.findByJobId(job.getId());

        // 문서 ID → Document 조회
        List<UUID> allDocIds = jobDocs.stream().map(GenerationJobDocument::getDocumentId).toList();
        var docMap = documentRepository.findAllById(allDocIds).stream()
                .collect(Collectors.toMap(Document::getId, d -> d));

        List<GenerationResponse.DocItem> customerDocs = jobDocs.stream()
                .filter(jd -> "CUSTOMER".equals(jd.getDocumentRole()))
                .map(jd -> {
                    Document doc = docMap.get(jd.getDocumentId());
                    return doc != null
                            ? new GenerationResponse.DocItem(doc.getId(), doc.getFilename(), doc.getChunkCount())
                            : new GenerationResponse.DocItem(jd.getDocumentId(), "(삭제된 문서)", 0);
                })
                .toList();

        List<GenerationResponse.DocItem> refDocs = jobDocs.stream()
                .filter(jd -> "REFERENCE".equals(jd.getDocumentRole()))
                .map(jd -> {
                    Document doc = docMap.get(jd.getDocumentId());
                    return doc != null
                            ? new GenerationResponse.DocItem(doc.getId(), doc.getFilename(), doc.getChunkCount())
                            : new GenerationResponse.DocItem(jd.getDocumentId(), "(삭제된 문서)", 0);
                })
                .toList();

        return new GenerationResponse(
                job.getId(),
                job.getStatus(),
                job.getTemplate().getId(),
                job.getTemplate().getName(),
                job.getTitle(),
                job.getUserInput(),
                job.getCurrentSection(),
                job.getTotalSections(),
                job.getCurrentStep(),
                job.getStepStatus(),
                job.getOutline(),
                job.getRequirementMapping(),
                job.getGeneratedSections(),
                job.isIncludeWebSearch(),
                job.getOutputFilePath(),
                job.getErrorMessage(),
                customerDocs,
                refDocs,
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
