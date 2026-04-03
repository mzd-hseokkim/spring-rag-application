package com.example.rag.generation.workflow;

import com.example.rag.common.PromptLoader;
import com.example.rag.dashboard.GenerationTraceEntity;
import com.example.rag.dashboard.GenerationTraceService;
import com.example.rag.generation.GenerationJob;
import com.example.rag.generation.GenerationJobRepository;
import com.example.rag.generation.GenerationStatus;
import com.example.rag.generation.dto.GenerationProgressEvent;
import com.example.rag.generation.dto.OutlineNode;
import com.example.rag.model.TokenRecordingContext;
import com.example.rag.questionnaire.workflow.Requirement;
import com.example.rag.questionnaire.workflow.TavilySearchService;
import com.example.rag.search.ChunkSearchResult;
import com.example.rag.search.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WizardSectionService {

    private static final Logger log = LoggerFactory.getLogger(WizardSectionService.class);
    private static final int MAX_RETRIES = 2;
    private static final String JOB_NOT_FOUND = "Job not found: ";
    private static final String STEP_STATUS_PROCESSING = "PROCESSING";
    private static final String STEP_STATUS_COMPLETE = "COMPLETE";
    private static final String STEP_STATUS_FAILED = "FAILED";
    private static final String FIELD_REQUIREMENTS = "requirements";

    private final ContentGeneratorService contentGenerator;
    private final OutlineExtractor outlineExtractor;
    private final GenerationDataParser dataParser;
    private final WorkflowEventEmitter eventEmitter;
    private final SearchService searchService;
    private final TavilySearchService tavilySearchService;
    private final PromptLoader promptLoader;
    private final GenerationJobRepository jobRepository;
    private final GenerationTraceService traceService;

    public WizardSectionService(ContentGeneratorService contentGenerator,
                                OutlineExtractor outlineExtractor,
                                GenerationDataParser dataParser,
                                WorkflowEventEmitter eventEmitter,
                                SearchService searchService,
                                TavilySearchService tavilySearchService,
                                PromptLoader promptLoader,
                                GenerationJobRepository jobRepository,
                                GenerationTraceService traceService) {
        this.contentGenerator = contentGenerator;
        this.outlineExtractor = outlineExtractor;
        this.dataParser = dataParser;
        this.eventEmitter = eventEmitter;
        this.searchService = searchService;
        this.tavilySearchService = tavilySearchService;
        this.promptLoader = promptLoader;
        this.jobRepository = jobRepository;
        this.traceService = traceService;
    }

    @Async("ingestionExecutor")
    public void generateWizardSections(UUID jobId, List<UUID> refDocIds, boolean includeWebSearch) {
        generateWizardSections(jobId, refDocIds, includeWebSearch, List.of(), false);
    }

    @Async("ingestionExecutor")
    public void generateWizardSections(UUID jobId, List<UUID> refDocIds, boolean includeWebSearch, List<String> filterKeys, boolean forceRegenerate) {
        GenerationJob job = jobRepository.findByIdWithTemplate(jobId)
                .orElseThrow(() -> new IllegalStateException(JOB_NOT_FOUND + jobId));
        TokenRecordingContext.setUserId(job.getUser().getId());
        GenerationTraceEntity trace = traceService.start(jobId, "GENERATION", "SECTION_GENERATE");
        try {
            job.setStatus(GenerationStatus.GENERATING);
            job.setCurrentStep(4);
            job.setStepStatus(STEP_STATUS_PROCESSING);
            jobRepository.save(job);

            // 목차 파싱
            List<OutlineNode> outline = outlineExtractor.fromJson(job.getOutline());

            // 요구사항 매핑 파싱
            List<Requirement> requirements = dataParser.parseRequirementsFromMapping(job.getRequirementMapping());
            Map<String, List<String>> mapping = dataParser.parseMappingFromJson(job.getRequirementMapping());

            Map<String, Requirement> reqMap = new java.util.HashMap<>();
            for (Requirement r : requirements) reqMap.put(r.id(), r);

            // 리프 노드(최하위 목차) 수집 + 자연수 정렬
            List<LeafSection> leafSections = new ArrayList<>();
            OutlineUtils.collectLeafSections(outline, mapping, leafSections);
            leafSections.sort(java.util.Comparator.comparing(LeafSection::key, OutlineUtils::compareKeys));

            // 선택된 섹션만 필터링 (filterKeys가 비어있으면 전체)
            if (!filterKeys.isEmpty()) {
                java.util.Set<String> allowed = new java.util.HashSet<>(filterKeys);
                leafSections = leafSections.stream()
                        .filter(leaf -> allowed.contains(leaf.key()) || filterKeys.stream().anyMatch(fk -> leaf.key().startsWith(fk + ".")))
                        .toList();
                log.info("Filtered to {} leaf sections by {} keys", leafSections.size(), filterKeys.size());
            }

            job.setTotalSections(leafSections.size());
            jobRepository.save(job);

            String systemPrompt = job.getTemplate().getSystemPrompt();
            if (systemPrompt == null || systemPrompt.isBlank()) {
                systemPrompt = "당신은 전문 제안서 작성자입니다. 고객 요구사항을 정확히 반영하고, 구체적이며 설득력 있는 문서를 작성하세요.";
            }

            // 기존에 생성된 섹션이 있으면 복원 (이어서 생성)
            List<SectionContent> sections = new ArrayList<>();
            java.util.Set<String> existingKeys = new java.util.HashSet<>();
            List<SectionContent> existing = dataParser.parseSections(job.getGeneratedSections());
            if (!existing.isEmpty()) {
                // forceRegenerate이면 선택된 섹션의 기존 결과를 제거
                if (forceRegenerate && !filterKeys.isEmpty()) {
                    java.util.Set<String> forceKeys = new java.util.HashSet<>(filterKeys);
                    existing = existing.stream()
                            .filter(s -> forceKeys.stream().noneMatch(fk -> s.key().equals(fk) || s.key().startsWith(fk + ".")))
                            .toList();
                    log.info("Generation job {} - force regenerating {} top-level keys, kept {} existing sections",
                            jobId, filterKeys.size(), existing.size());
                }
                sections.addAll(existing);
                existing.forEach(s -> existingKeys.add(s.key()));
                log.info("Generation job {} - resuming with {} existing sections", jobId, existing.size());
            }

            // 웹 검색 결과 캐시 (같은 job 내 동일 쿼리 재사용)
            Map<String, List<String>> webSearchCache = new java.util.HashMap<>();

            for (int i = 0; i < leafSections.size(); i++) {
                LeafSection leaf = leafSections.get(i);
                job.setCurrentSection(i + 1);
                jobRepository.save(job);

                // 이미 생성된 섹션은 스킵
                if (existingKeys.contains(leaf.key())) {
                    eventEmitter.emitEvent(job, GenerationProgressEvent.progress(i + 1, leafSections.size(), leaf.title() + " (기존)", leaf.key()));
                    continue;
                }

                eventEmitter.emitEvent(job, GenerationProgressEvent.progress(i + 1, leafSections.size(), leaf.title(), leaf.key()));

                SectionContent content = generateLeafSection(leaf, reqMap, refDocIds, includeWebSearch, systemPrompt, sections, webSearchCache);
                sections.add(content);
                // 중간 저장 — 프론트엔드가 progress 이벤트마다 조회하여 완료된 섹션을 표시
                job.setGeneratedSections(dataParser.toJson(sections));
                jobRepository.save(job);
                log.info("Generation job {} - wizard section {}/{} complete: {}", jobId, i + 1, leafSections.size(), leaf.title());
            }

            job.setGeneratedSections(dataParser.toJson(sections));
            job.setCurrentStep(4);
            job.setStepStatus(STEP_STATUS_COMPLETE);
            job.setStatus(GenerationStatus.READY);
            jobRepository.save(job);

            traceService.complete(trace);
            eventEmitter.emitEvent(job, GenerationProgressEvent.complete("sections"));
            log.info("Generation job {} - all {} wizard sections complete", jobId, sections.size());

        } catch (Exception e) {
            log.error("Generation job {} wizard section generation failed", jobId, e);
            traceService.fail(trace, e.getMessage());
            job.setErrorMessage(e.getMessage());
            job.setStepStatus(STEP_STATUS_FAILED);
            job.setStatus(GenerationStatus.FAILED);
            jobRepository.save(job);
            eventEmitter.emitEvent(job, GenerationProgressEvent.error(e.getMessage()));
        } finally {
            TokenRecordingContext.clear();
        }
    }

    @Async("ingestionExecutor")
    public void regenerateSection(UUID jobId, String sectionKey, List<UUID> refDocIds, boolean includeWebSearch, String userInstruction) {
        GenerationJob job = jobRepository.findByIdWithTemplate(jobId)
                .orElseThrow(() -> new IllegalStateException(JOB_NOT_FOUND + jobId));
        try {
            eventEmitter.emitEvent(job, GenerationProgressEvent.status(GenerationStatus.GENERATING, sectionKey + " 섹션을 재생성하고 있습니다..."));

            // 기존 섹션 목록 파싱
            List<SectionContent> parsed = dataParser.parseSections(job.getGeneratedSections());
            if (parsed.isEmpty()) {
                throw new IllegalStateException("Failed to parse existing sections");
            }
            List<SectionContent> sections = new ArrayList<>(parsed);

            // 대상 섹션 찾기
            int targetIndex = -1;
            for (int i = 0; i < sections.size(); i++) {
                if (sectionKey.equals(sections.get(i).key())) {
                    targetIndex = i;
                    break;
                }
            }
            if (targetIndex < 0) {
                throw new IllegalArgumentException("Section not found: " + sectionKey);
            }

            // 요구사항 매핑에서 해당 섹션의 요구사항 추출
            String reqText = buildRequirementTextForSection(job.getRequirementMapping(), sectionKey);

            SectionContent old = sections.get(targetIndex);
            String systemPrompt = job.getTemplate().getSystemPrompt();
            if (systemPrompt == null || systemPrompt.isBlank()) {
                systemPrompt = "당신은 전문 제안서 작성자입니다.";
            }

            // RAG 검색
            List<String> ragContext = List.of();
            if (!refDocIds.isEmpty()) {
                String query = old.title() + " " + reqText;
                if (query.length() > 500) query = query.substring(0, 500);
                ragContext = searchService.searchDirect(query, refDocIds).stream()
                        .map(ChunkSearchResult::contextContent).toList();
            }

            // 웹 검색
            List<String> webContext = List.of();
            if (includeWebSearch) {
                webContext = tavilySearchService.search(old.title() + " 제안서", 3);
            }

            // 이전 섹션 컨텍스트 (대상 앞까지)
            List<SectionContent> previous = sections.subList(0, targetIndex);

            // 재생성
            SectionContent regenerated = contentGenerator.generateSectionWithRequirements(
                    old.title(), userInstruction, reqText, systemPrompt, ragContext, webContext, previous);

            // key 강제 덮어씌움 + sources 추가
            List<String> sources = new ArrayList<>();
            if (!refDocIds.isEmpty()) {
                searchService.searchDirect(old.title(), refDocIds).stream()
                        .map(r -> "[문서] " + r.filename() + " (청크 #" + r.chunkIndex() + ")")
                        .distinct().forEach(sources::add);
            }
            if (includeWebSearch) {
                webContext.stream()
                        .map(this::formatWebSource)
                        .forEach(sources::add);
            }
            regenerated = new SectionContent(
                    sectionKey, regenerated.title(), regenerated.content(),
                    regenerated.highlights(), regenerated.tables(), regenerated.references(),
                    regenerated.layoutType(), regenerated.layoutData(),
                    regenerated.governingMessage(), regenerated.visualGuide(), sources);

            sections.set(targetIndex, regenerated);
            job.setGeneratedSections(dataParser.toJson(sections));
            jobRepository.save(job);

            eventEmitter.emitEvent(job, GenerationProgressEvent.complete("section-regenerated"));
            log.info("Generation job {} - section '{}' regenerated", jobId, sectionKey);

        } catch (Exception e) {
            log.error("Generation job {} section '{}' regeneration failed", jobId, sectionKey, e);
            eventEmitter.emitEvent(job, GenerationProgressEvent.error(e.getMessage()));
        }
    }

    private String formatWebSource(String webResult) {
        // URL 추출
        java.util.regex.Matcher urlMatcher = java.util.regex.Pattern
                .compile("(https?://[^\\s)]+)").matcher(webResult);
        String url = urlMatcher.find() ? urlMatcher.group(1) : "";
        if (log.isDebugEnabled()) {
            log.debug("formatWebSource input ({}chars): {}", webResult.length(),
                    webResult.substring(0, Math.min(webResult.length(), 200)));
            log.debug("formatWebSource extracted URL: '{}'", url);
        }

        // 제목 추출 (첫 번째 [...] 부분)
        String title = "";
        java.util.regex.Matcher titleMatcher = java.util.regex.Pattern
                .compile("^\\[([^]]+)]").matcher(webResult);
        if (titleMatcher.find()) {
            title = titleMatcher.group(1);
        }

        if (!url.isEmpty()) {
            return "[웹] " + (!title.isEmpty() ? "[" + title + "] " : "") + url;
        }
        // URL이 없으면 텍스트 요약
        String summary = webResult.length() > 100 ? webResult.substring(0, 100) + "..." : webResult;
        return "[웹] " + summary;
    }

    private List<String> generateSearchQueries(LeafSection leaf, String reqText) {
        try {
            var client = contentGenerator.getModelClient();
            String prompt = promptLoader.load("generation-search-query.txt");

            String desc = leaf.description() != null ? leaf.description() : "";
            String reqs = reqText.isBlank() ? "없음" : reqText;

            String content = client.prompt()
                    .user(u -> u.text(prompt)
                            .param("title", leaf.title())
                            .param("description", desc)
                            .param(FIELD_REQUIREMENTS, reqs))
                    .call()
                    .content();

            if (content == null || content.isBlank()) {
                return List.of(leaf.title());
            }
            List<String> queries = java.util.Arrays.stream(content.trim().split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .limit(1)
                    .toList();
            return queries.isEmpty() ? List.of(leaf.title()) : queries;
        } catch (Exception e) {
            log.warn("Failed to generate search queries for '{}': {}", leaf.title(), e.getMessage());
            return List.of(leaf.title());
        }
    }

    private SectionContent generateLeafSection(LeafSection leaf, Map<String, Requirement> reqMap,
                                                List<UUID> refDocIds, boolean includeWebSearch,
                                                String systemPrompt, List<SectionContent> sections,
                                                Map<String, List<String>> webSearchCache) {
        String reqText = leaf.requirementIds().stream()
                .map(reqMap::get)
                .filter(java.util.Objects::nonNull)
                .map(r -> "- [" + r.importance() + "] " + r.item() + ": " + r.description())
                .collect(java.util.stream.Collectors.joining("\n"));

        List<String> searchQueries = generateSearchQueries(leaf, reqText);

        List<String> ragContext = List.of();
        List<String> sources = new ArrayList<>();
        if (!refDocIds.isEmpty()) {
            java.util.LinkedHashSet<String> uniqueChunks = new java.util.LinkedHashSet<>();
            List<String> ragSourceList = new ArrayList<>();
            for (String query : searchQueries) {
                List<ChunkSearchResult> results = searchService.searchDirect(query, refDocIds);
                results.forEach(r -> {
                    uniqueChunks.add(r.contextContent());
                    ragSourceList.add("[문서] " + r.filename() + " (청크 #" + r.chunkIndex() + ")");
                });
            }
            ragContext = new ArrayList<>(uniqueChunks);
            ragSourceList.stream().distinct().forEach(sources::add);
        }

        List<String> webContext = List.of();
        if (includeWebSearch) {
            List<String> allWebResults = new ArrayList<>();
            for (String query : searchQueries) {
                List<String> cached = webSearchCache.get(query);
                if (cached != null) {
                    log.debug("Web search cache hit for query: {}", query);
                    allWebResults.addAll(cached);
                } else {
                    List<String> fresh = tavilySearchService.search(query, 3);
                    webSearchCache.put(query, fresh);
                    allWebResults.addAll(fresh);
                }
            }
            webContext = allWebResults.stream().distinct().toList();
            webContext.stream().map(this::formatWebSource).forEach(sources::add);
        }

        SectionContent content = generateSectionWithRetry(leaf, reqText, systemPrompt, ragContext, webContext, sections);
        return new SectionContent(
                leaf.key(), content.title(), content.content(),
                content.highlights(), content.tables(), content.references(),
                content.layoutType(), content.layoutData(),
                content.governingMessage(), content.visualGuide(), sources);
    }

    private String buildRequirementTextForSection(String requirementMappingJson, String sectionKey) {
        List<Requirement> reqList = dataParser.parseRequirementsFromMapping(requirementMappingJson);
        Map<String, List<String>> mapping = dataParser.parseMappingFromJson(requirementMappingJson);
        if (reqList.isEmpty()) return "";

        Map<String, Requirement> reqMap = new java.util.HashMap<>();
        for (Requirement r : reqList) reqMap.put(r.id(), r);

        List<String> reqIds = mapping.getOrDefault(sectionKey, List.of());
        return reqIds.stream()
                .map(reqMap::get)
                .filter(java.util.Objects::nonNull)
                .map(r -> "- [" + r.importance() + "] " + r.item() + ": " + r.description())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private SectionContent generateSectionWithRetry(LeafSection leaf, String reqText, String systemPrompt,
                                                     List<String> ragContext, List<String> webContext,
                                                     List<SectionContent> sections) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                String heading = leaf.parentPath().isEmpty()
                        ? leaf.title()
                        : leaf.parentPath() + " > " + leaf.title();
                return contentGenerator.generateSectionWithRequirements(
                        heading, leaf.description(), reqText,
                        systemPrompt, ragContext, webContext, sections);
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) throw e;
                log.warn("Section generation retry {}/{} for '{}': {}", attempt + 1, MAX_RETRIES, leaf.title(), e.getMessage());
            }
        }
        throw new IllegalStateException("Unreachable");
    }
}
