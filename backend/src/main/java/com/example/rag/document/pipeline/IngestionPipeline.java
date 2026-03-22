package com.example.rag.document.pipeline;

import com.example.rag.document.*;
import com.example.rag.document.parser.DocumentParser;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.settings.ChunkingSettings;
import com.example.rag.settings.EmbeddingSettings;
import com.example.rag.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);
    private static final Pattern SECTION_HEADER = Pattern.compile(
            "^(?:#{1,6}\\s+.+|[A-Z가-힣].{0,80}\\n[=\\-]{3,})$", Pattern.MULTILINE);

    private final List<DocumentParser> parsers;
    private final ModelClientProvider modelProvider;
    private final SettingsService settingsService;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final IngestionEventPublisher eventPublisher;
    private final TransactionTemplate tx;

    public IngestionPipeline(List<DocumentParser> parsers,
                             ModelClientProvider modelProvider,
                             SettingsService settingsService,
                             DocumentRepository documentRepository,
                             DocumentChunkRepository chunkRepository,
                             IngestionEventPublisher eventPublisher,
                             PlatformTransactionManager txManager) {
        this.parsers = parsers;
        this.modelProvider = modelProvider;
        this.settingsService = settingsService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.eventPublisher = eventPublisher;
        this.tx = new TransactionTemplate(txManager);
    }

    @Async("ingestionExecutor")
    public void process(UUID documentId, String contentType, byte[] fileBytes) {
        doProcess(documentId, contentType, fileBytes);
    }

    /**
     * 동기 처리 — 재인덱싱에서도 호출 가능
     */
    public void doProcess(UUID documentId, String contentType, byte[] fileBytes) {
        try {
            String filename = tx.execute(status -> {
                Document doc = documentRepository.findById(documentId).orElseThrow();
                doc.markProcessing();
                documentRepository.save(doc);
                return doc.getFilename();
            });
            eventPublisher.publish(documentId, "PROCESSING", "파싱 중...");

            ChunkingSettings chunkSettings = settingsService.getChunkingSettings();
            EmbeddingSettings embedSettings = settingsService.getEmbeddingSettings();

            // 1. 파싱
            String text = parse(contentType, fileBytes);
            log.info("Document {} parsed: {} characters", documentId, text.length());

            // 2. 시맨틱 청킹 → parent 청크
            ChunkingStrategy strategy = buildChunkingStrategy(chunkSettings);
            List<String> parentChunks = strategy.chunk(text);
            log.info("Document {} chunked: {} parents", documentId, parentChunks.size());

            // 3. 각 parent → child 분할 (설정 가능한 파라미터)
            FixedSizeChunkingStrategy childChunker =
                    new FixedSizeChunkingStrategy(chunkSettings.childChunkSize(), chunkSettings.childOverlap());

            List<String> allChildContents = new ArrayList<>();
            List<Integer> childToParentIndex = new ArrayList<>();
            List<Map<String, Object>> childMetadata = new ArrayList<>();

            for (int p = 0; p < parentChunks.size(); p++) {
                List<String> children = childChunker.chunk(parentChunks.get(p));
                String sectionHeader = detectSectionHeader(parentChunks.get(p));
                for (String child : children) {
                    allChildContents.add(child);
                    childToParentIndex.add(p);
                    // 메타데이터: 문서 제목 + 섹션 헤더
                    Map<String, Object> meta = new LinkedHashMap<>();
                    if (filename != null) meta.put("documentTitle", filename);
                    if (sectionHeader != null) meta.put("sectionHeader", sectionHeader);
                    meta.put("parentIndex", p);
                    childMetadata.add(meta);
                }
            }
            log.info("Document {} child chunks: {}", documentId, allChildContents.size());
            eventPublisher.publish(documentId, "PROCESSING",
                    "임베딩 생성 중... (" + allChildContents.size() + "개 청크)");

            // 4. 배치 + 병렬 임베딩
            float[][] embeddings = embedBatchParallel(
                    allChildContents, embedSettings, documentId);
            log.info("Document {} embeddings generated", documentId);

            // 5. parent + child 저장
            tx.executeWithoutResult(status -> {
                Document doc = documentRepository.findById(documentId).orElseThrow();

                UUID[] parentIds = new UUID[parentChunks.size()];
                for (int p = 0; p < parentChunks.size(); p++) {
                    DocumentChunk parent = new DocumentChunk(doc, parentChunks.get(p), p);
                    parent = chunkRepository.save(parent);
                    parentIds[p] = parent.getId();
                }

                for (int c = 0; c < allChildContents.size(); c++) {
                    int parentIdx = childToParentIndex.get(c);
                    DocumentChunk child = new DocumentChunk(
                            doc, allChildContents.get(c), c, parentIds[parentIdx]);
                    child = chunkRepository.save(child);
                    chunkRepository.updateEmbeddingTsvectorAndMetadata(
                            child.getId(),
                            toVectorString(embeddings[c]),
                            allChildContents.get(c),
                            toJsonString(childMetadata.get(c)));
                }

                doc.markCompleted(allChildContents.size());
                documentRepository.save(doc);
            });

            eventPublisher.publish(documentId, "COMPLETED",
                    parentChunks.size() + "개 parent, " + allChildContents.size() + "개 child 처리 완료");
            log.info("Document {} completed: {} parents, {} children",
                    documentId, parentChunks.size(), allChildContents.size());

        } catch (Exception e) {
            log.error("Document {} processing failed", documentId, e);
            try {
                tx.executeWithoutResult(status -> {
                    Document doc = documentRepository.findById(documentId).orElseThrow();
                    doc.markFailed(e.getMessage());
                    documentRepository.save(doc);
                });
            } catch (Exception ex) {
                log.error("Failed to update document status to FAILED", ex);
            }
            eventPublisher.publish(documentId, "FAILED", e.getMessage());
        }
    }

    private ChunkingStrategy buildChunkingStrategy(ChunkingSettings s) {
        if ("semantic".equalsIgnoreCase(s.mode())) {
            return new SemanticChunkingStrategy(
                    modelProvider.getEmbeddingModel(),
                    s.semanticBufferSize(),
                    s.semanticBreakpointPercentile(),
                    s.semanticMinChunkSize(),
                    s.semanticMaxChunkSize());
        }
        return new FixedSizeChunkingStrategy(s.fixedChunkSize(), s.fixedOverlap());
    }

    private String parse(String contentType, byte[] fileBytes) {
        return parsers.stream()
                .filter(p -> p.supports(contentType))
                .findFirst()
                .map(p -> p.parse(fileBytes))
                .orElseThrow(() -> new RuntimeException("Unsupported content type: " + contentType));
    }

    /**
     * 배치 단위 + 병렬 임베딩 생성.
     * batchSize개씩 묶어 concurrency개 스레드로 병렬 처리하고, 진행률을 SSE로 보고한다.
     */
    private float[][] embedBatchParallel(List<String> texts, EmbeddingSettings settings, UUID documentId) {
        int batchSize = settings.batchSize();
        int concurrency = settings.concurrency();
        float[][] result = new float[texts.size()][];
        AtomicInteger completed = new AtomicInteger(0);
        int total = texts.size();

        EmbeddingModel embeddingModel = modelProvider.getEmbeddingModel();

        // 배치 분할
        List<int[]> batches = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += batchSize) {
            batches.add(new int[]{i, Math.min(i + batchSize, texts.size())});
        }

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(concurrency, batches.size()));
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int[] range : batches) {
                futures.add(CompletableFuture.runAsync(() -> {
                    for (int j = range[0]; j < range[1]; j++) {
                        result[j] = embeddingModel.embed(texts.get(j));
                        int done = completed.incrementAndGet();
                        // 10% 단위로 진행률 보고
                        if (total >= 10 && done % Math.max(1, total / 10) == 0) {
                            eventPublisher.publish(documentId, "PROCESSING",
                                    "임베딩 생성 중... (" + done + "/" + total + ")");
                        }
                    }
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }
        return result;
    }

    /**
     * parent 청크의 첫 번째 섹션 헤더를 감지한다 (Markdown 헤더 또는 밑줄 스타일).
     */
    private String detectSectionHeader(String chunkText) {
        Matcher m = SECTION_HEADER.matcher(chunkText);
        if (m.find()) {
            String header = m.group().trim();
            // Markdown # 제거
            if (header.startsWith("#")) {
                header = header.replaceFirst("^#+\\s*", "");
            }
            // 밑줄 스타일 (Title\n===) → 첫 줄만
            if (header.contains("\n")) {
                header = header.substring(0, header.indexOf('\n')).trim();
            }
            return header;
        }
        return null;
    }

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private String toJsonString(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":");
            if (entry.getValue() instanceof String s) {
                sb.append("\"").append(s.replace("\"", "\\\"")).append("\"");
            } else {
                sb.append(entry.getValue());
            }
        }
        sb.append("}");
        return sb.toString();
    }
}
