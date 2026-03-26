package com.example.rag.questionnaire.workflow;

import com.example.rag.common.RagException;
import com.example.rag.questionnaire.QuestionnaireEmitterManager;
import com.example.rag.questionnaire.QuestionnaireJob;
import com.example.rag.questionnaire.QuestionnaireJobRepository;
import com.example.rag.questionnaire.QuestionnaireStatus;
import com.example.rag.questionnaire.dto.QuestionnaireProgressEvent;
import com.example.rag.questionnaire.persona.Persona;
import com.example.rag.questionnaire.persona.PersonaRepository;
import com.example.rag.questionnaire.renderer.QuestionnaireHtmlRenderer;
import com.example.rag.search.ChunkSearchResult;
import com.example.rag.search.SearchService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class QuestionnaireWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(QuestionnaireWorkflowService.class);
    private static final int MAX_RETRIES = 2;

    // 문서 분석용 다양한 관점의 검색 쿼리
    private static final List<String> ANALYSIS_QUERIES = List.of(
            "사업 개요 목적 배경 범위",
            "기술 아키텍처 시스템 구성 인프라",
            "수행 방안 방법론 절차 산출물",
            "프로젝트 관리 일정 위험 품질",
            "인력 조직 경험 실적",
            "유지보수 지원 교육 하자보수 보안"
    );

    private final QuestionnaireGeneratorService generator;
    private final RequirementExtractor requirementExtractor;
    private final RequirementMatcher requirementMatcher;
    private final RequirementCacheService requirementCache;
    private final QuestionnaireHtmlRenderer renderer;
    private final SearchService searchService;
    private final TavilySearchService tavilySearchService;
    private final DocumentAnalysisCacheService analysisCache;
    private final QuestionnaireJobRepository jobRepository;
    private final PersonaRepository personaRepository;
    private final QuestionnaireEmitterManager emitterManager;
    private final ObjectMapper objectMapper;

    public QuestionnaireWorkflowService(QuestionnaireGeneratorService generator,
                                        RequirementExtractor requirementExtractor,
                                        RequirementMatcher requirementMatcher,
                                        RequirementCacheService requirementCache,
                                        QuestionnaireHtmlRenderer renderer,
                                        SearchService searchService,
                                        TavilySearchService tavilySearchService,
                                        DocumentAnalysisCacheService analysisCache,
                                        QuestionnaireJobRepository jobRepository,
                                        PersonaRepository personaRepository,
                                        QuestionnaireEmitterManager emitterManager,
                                        ObjectMapper objectMapper) {
        this.generator = generator;
        this.requirementExtractor = requirementExtractor;
        this.requirementMatcher = requirementMatcher;
        this.requirementCache = requirementCache;
        this.renderer = renderer;
        this.searchService = searchService;
        this.tavilySearchService = tavilySearchService;
        this.analysisCache = analysisCache;
        this.jobRepository = jobRepository;
        this.personaRepository = personaRepository;
        this.emitterManager = emitterManager;
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("java:S107") // async workflow entry point — parameter object adds unnecessary indirection
    @Async("ingestionExecutor")
    public void execute(QuestionnaireJob detachedJob, List<UUID> customerDocIds, List<UUID> proposalDocIds,
                        List<UUID> refDocIds, List<UUID> personaIds, int questionCount,
                        boolean includeWebSearch, String analysisMode) {
        QuestionnaireJob job = jobRepository.findByIdWithUser(detachedJob.getId())
                .orElseThrow(() -> new IllegalStateException("Job not found: " + detachedJob.getId()));
        try {
            // ── Phase 1: ANALYZING — 요구사항 추출 + 갭 매트릭스 ──
            updateStatus(job, QuestionnaireStatus.ANALYZING);
            emitEvent(job, QuestionnaireProgressEvent.status(QuestionnaireStatus.ANALYZING, "문서를 분석하고 있습니다..."));

            List<Persona> personas = personaRepository.findAllById(personaIds);
            job.setTotalPersonas(personas.size());
            jobRepository.save(job);

            log.info("Questionnaire job {} - {} personas, {} customer docs, {} proposal docs, {} ref docs, mode={}, questionCount={}, webSearch={}",
                    job.getId(), personas.size(), customerDocIds.size(), proposalDocIds.size(), refDocIds.size(), analysisMode, questionCount, includeWebSearch);

            // 캐시에서 기존 분석 결과 조회
            String documentAnalysis = analysisCache.get(customerDocIds, proposalDocIds, analysisMode, job.getUserInput());

            if (documentAnalysis != null) {
                emitEvent(job, QuestionnaireProgressEvent.status(QuestionnaireStatus.ANALYZING,
                        "이전 분석 결과를 재사용합니다. 질문 생성을 준비합니다..."));
                log.info("Questionnaire job {} - using cached analysis ({} chars)", job.getId(), documentAnalysis.length());
            } else {
                // Phase 1a: 고객문서에서 요구사항 추출 (캐시 우선)
                List<Requirement> requirements = requirementCache.get(customerDocIds);
                if (!requirements.isEmpty()) {
                    emitEvent(job, QuestionnaireProgressEvent.status(QuestionnaireStatus.ANALYZING,
                            "캐시된 요구사항 " + requirements.size() + "개를 사용합니다."));
                    log.info("Questionnaire job {} - using {} cached requirements", job.getId(), requirements.size());
                } else {
                    emitEvent(job, QuestionnaireProgressEvent.status(QuestionnaireStatus.ANALYZING, "고객 문서에서 요구사항을 추출하고 있습니다..."));
                    List<String> customerChunks = collectDocumentContent(customerDocIds, job.getUserInput());
                    log.info("Questionnaire job {} - collected {} customer chunks", job.getId(), customerChunks.size());

                    requirements = requirementExtractor.extract(customerChunks, job.getUserInput(),
                            msg -> emitEvent(job, QuestionnaireProgressEvent.status(QuestionnaireStatus.ANALYZING, msg)));
                    requirementCache.put(customerDocIds, requirements);
                }
                emitEvent(job, QuestionnaireProgressEvent.status(QuestionnaireStatus.ANALYZING,
                        requirements.size() + "개 요구사항을 추출했습니다."));
                log.info("Questionnaire job {} - extracted {} requirements", job.getId(), requirements.size());

                // Phase 1b: 제안서 대응 확인 → 갭 매트릭스 생성
                GapMatrix gapMatrix;
                if (!proposalDocIds.isEmpty()) {
                    if ("LLM".equals(analysisMode)) {
                        emitEvent(job, QuestionnaireProgressEvent.status(QuestionnaireStatus.ANALYZING, "제안서 대응을 정밀 분석하고 있습니다 (LLM)..."));
                        List<String> proposalChunks = collectDocumentContent(proposalDocIds, job.getUserInput());
                        gapMatrix = requirementMatcher.matchWithLlm(requirements, proposalChunks,
                                msg -> emitEvent(job, QuestionnaireProgressEvent.status(QuestionnaireStatus.ANALYZING, msg)));
                    } else {
                        emitEvent(job, QuestionnaireProgressEvent.status(QuestionnaireStatus.ANALYZING, "제안서 대응을 확인하고 있습니다 (RAG)..."));
                        gapMatrix = requirementMatcher.matchWithRag(requirements, proposalDocIds,
                                msg -> emitEvent(job, QuestionnaireProgressEvent.status(QuestionnaireStatus.ANALYZING, msg)));
                    }
                } else {
                    gapMatrix = requirementMatcher.buildWithoutProposal(requirements);
                }

                documentAnalysis = gapMatrix.toMarkdown();
                if (log.isInfoEnabled()) {
                    log.info("Questionnaire job {} - gap matrix: {}", job.getId(), gapMatrix.summary());
                }

                // 캐시에 저장
                analysisCache.put(customerDocIds, proposalDocIds, analysisMode, job.getUserInput(), documentAnalysis);
                emitEvent(job, QuestionnaireProgressEvent.status(QuestionnaireStatus.ANALYZING,
                        "갭 분석 완료. " + gapMatrix.summary() + " 질문 생성을 준비합니다..."));
            }

            // DB에도 저장 (참조/디버깅용)
            job.setDocumentAnalysis(documentAnalysis);
            jobRepository.save(job);

            // ── Phase 2: GENERATING — 페르소나별 질문 생성 ──
            updateStatus(job, QuestionnaireStatus.GENERATING);
            List<PersonaQna> allQna = generateForAllPersonas(
                    job, personas, documentAnalysis, refDocIds, includeWebSearch, questionCount);

            job.setGeneratedQna(toJson(allQna));
            jobRepository.save(job);

            // ── Phase 3: RENDERING ──
            updateStatus(job, QuestionnaireStatus.RENDERING);
            emitEvent(job, QuestionnaireProgressEvent.status(QuestionnaireStatus.RENDERING, "질의서를 생성하고 있습니다..."));

            String outputPath = renderer.render(allQna, job.getUser().getId(), job.getId());
            job.setOutputFilePath(outputPath);

            updateStatus(job, QuestionnaireStatus.COMPLETE);
            emitEvent(job, QuestionnaireProgressEvent.complete("/api/questionnaires/" + job.getId() + "/download"));
            log.info("Questionnaire job {} - complete, output: {}", job.getId(), outputPath);

        } catch (Exception e) {
            log.error("Questionnaire job {} failed", job.getId(), e);
            job.setErrorMessage(e.getMessage());
            updateStatus(job, QuestionnaireStatus.FAILED);
            emitEvent(job, QuestionnaireProgressEvent.error(e.getMessage()));
        }
    }

    /**
     * 다양한 관점의 쿼리로 문서를 폭넓게 검색하여 전체 내용을 수집한다.
     * 중복 청크를 제거하고 순서를 유지한다.
     * 총 컨텍스트 크기를 MAX_ANALYSIS_CHARS 이내로 제한하여 토큰 초과를 방지한다.
     */
    private List<String> collectDocumentContent(List<UUID> documentIds, String userInput) {
        if (documentIds.isEmpty()) {
            return List.of();
        }

        // 모든 검색을 병렬로 실행 (쿼리 확장 없이 직접 검색)
        List<CompletableFuture<List<ChunkSearchResult>>> futures = new ArrayList<>();

        // 사용자 입력 기반 검색
        if (userInput != null && !userInput.isBlank()) {
            String query = userInput.substring(0, Math.min(userInput.length(), 500));
            futures.add(CompletableFuture.supplyAsync(() -> searchService.searchDirect(query, documentIds)));
        }

        // 평가 영역별 다양한 관점으로 검색
        for (String query : ANALYSIS_QUERIES) {
            futures.add(CompletableFuture.supplyAsync(() -> searchService.searchDirect(query, documentIds)));
        }

        // 모든 검색 완료 대기 후 중복 제거
        LinkedHashSet<String> uniqueChunks = new LinkedHashSet<>();
        for (CompletableFuture<List<ChunkSearchResult>> future : futures) {
            future.join().stream()
                    .map(ChunkSearchResult::contextContent)
                    .forEach(uniqueChunks::add);
        }

        List<String> result = new ArrayList<>(uniqueChunks);
        int totalChars = result.stream().mapToInt(String::length).sum();
        log.info("Collected {} unique chunks ({} chars)", result.size(), totalChars);
        return result;
    }

    @SuppressWarnings("java:S107")
    private List<PersonaQna> generateForAllPersonas(QuestionnaireJob job, List<Persona> personas,
                                                      String documentAnalysis, List<UUID> refDocIds,
                                                      boolean includeWebSearch, int questionCount) {
        GapMatrix gapMatrixForPersona = GapMatrix.fromMarkdown(documentAnalysis);
        List<PersonaQna> allQna = new ArrayList<>();

        for (int i = 0; i < personas.size(); i++) {
            Persona persona = personas.get(i);
            job.setCurrentPersona(i + 1);
            jobRepository.save(job);

            emitEvent(job, QuestionnaireProgressEvent.progress(i + 1, personas.size(), persona.getName()));

            String personaAnalysis = gapMatrixForPersona != null
                    ? gapMatrixForPersona.toMarkdownForPersona(persona.getFocusAreas())
                    : documentAnalysis;

            List<String> refContext = List.of();
            if (!refDocIds.isEmpty()) {
                emitEvent(job, QuestionnaireProgressEvent.status(QuestionnaireStatus.GENERATING,
                        persona.getName() + " — 참조 문서 검색 중..."));
                refContext = searchForPersona(persona, job.getUserInput(), refDocIds);
            }

            List<String> webContext = List.of();
            if (includeWebSearch) {
                emitEvent(job, QuestionnaireProgressEvent.status(QuestionnaireStatus.GENERATING,
                        persona.getName() + " — 웹 검색 중..."));
                webContext = searchWebForPersona(persona, job.getUserInput());
                log.info("Questionnaire job {} - web search for persona '{}' returned {} results",
                        job.getId(), persona.getName(), webContext.size());
            }

            emitEvent(job, QuestionnaireProgressEvent.status(QuestionnaireStatus.GENERATING,
                    persona.getName() + " — 질문/답변 생성 중..."));
            PersonaQna qna = generateWithRetry(persona, personaAnalysis, refContext, webContext,
                    job.getUserInput(), questionCount);
            allQna.add(qna);

            log.info("Questionnaire job {} - persona {}/{} complete: {} ({} questions)",
                    job.getId(), i + 1, personas.size(), persona.getName(), qna.questions().size());
        }
        return allQna;
    }

    private PersonaQna generateWithRetry(Persona persona, String documentAnalysis,
                                          List<String> refContext, List<String> webContext,
                                          String userInput, int questionCount) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return generator.generateForPersona(persona, documentAnalysis, refContext,
                        webContext, userInput, questionCount);
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    throw e;
                }
                log.warn("Questionnaire generation retry {}/{} for persona '{}': {}",
                        attempt + 1, MAX_RETRIES, persona.getName(), e.getMessage());
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    private List<String> searchForPersona(Persona persona, String userInput, List<UUID> documentIds) {
        String query = persona.getName() + " " + (persona.getFocusAreas() != null ? persona.getFocusAreas() : "");
        if (userInput != null && !userInput.isBlank()) {
            query = userInput.substring(0, Math.min(userInput.length(), 300)) + " " + query;
        }
        if (query.length() > 500) {
            query = query.substring(0, 500);
        }
        return searchService.search(query, documentIds).stream()
                .map(ChunkSearchResult::contextContent)
                .toList();
    }

    private List<String> searchWebForPersona(Persona persona, String userInput) {
        StringBuilder query = new StringBuilder();
        if (userInput != null && !userInput.isBlank()) {
            query.append(userInput, 0, Math.min(userInput.length(), 200));
        }
        if (persona.getFocusAreas() != null) {
            query.append(" ").append(persona.getFocusAreas());
        }
        query.append(" 제안평가 ").append(persona.getName());

        return tavilySearchService.search(query.toString().trim(), 5);
    }

    private void updateStatus(QuestionnaireJob job, QuestionnaireStatus status) {
        job.setStatus(status);
        jobRepository.save(job);
    }

    private void emitEvent(QuestionnaireJob job, QuestionnaireProgressEvent event) {
        SseEmitter emitter = emitterManager.get(job.getId());
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(event.eventType())
                    .data(event));
            if ("complete".equals(event.eventType()) || "error".equals(event.eventType())) {
                emitter.complete();
            }
        } catch (IOException e) {
            log.debug("Failed to send SSE event for job {}: {}", job.getId(), e.getMessage());
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RagException("Failed to serialize to JSON", e);
        }
    }
}
