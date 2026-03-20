package com.example.rag.document.pipeline;

import com.example.rag.document.*;
import com.example.rag.document.parser.DocumentParser;
import com.example.rag.model.ModelClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Component
public class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

    private final List<DocumentParser> parsers;
    private final ChunkingStrategy chunkingStrategy;
    private final FixedSizeChunkingStrategy childChunker;
    private final ModelClientProvider modelProvider;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final IngestionEventPublisher eventPublisher;
    private final TransactionTemplate tx;

    public IngestionPipeline(List<DocumentParser> parsers,
                             ChunkingStrategy chunkingStrategy,
                             ModelClientProvider modelProvider,
                             DocumentRepository documentRepository,
                             DocumentChunkRepository chunkRepository,
                             IngestionEventPublisher eventPublisher,
                             PlatformTransactionManager txManager) {
        this.parsers = parsers;
        this.chunkingStrategy = chunkingStrategy;
        this.childChunker = new FixedSizeChunkingStrategy(500, 100);
        this.modelProvider = modelProvider;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.eventPublisher = eventPublisher;
        this.tx = new TransactionTemplate(txManager);
    }

    @Async("ingestionExecutor")
    public void process(UUID documentId, String contentType, byte[] fileBytes) {
        try {
            // 상태: PROCESSING
            tx.executeWithoutResult(status -> {
                Document doc = documentRepository.findById(documentId).orElseThrow();
                doc.markProcessing();
                documentRepository.save(doc);
            });
            eventPublisher.publish(documentId, "PROCESSING", "파싱 중...");

            // 1. 파싱
            String text = parse(contentType, fileBytes);
            log.info("Document {} parsed: {} characters", documentId, text.length());

            // 2. 시맨틱 청킹 → parent 청크
            List<String> parentChunks = chunkingStrategy.chunk(text);
            log.info("Document {} semantic chunked: {} parents", documentId, parentChunks.size());

            // 3. 각 parent → child 분할
            List<String> allChildContents = new java.util.ArrayList<>();
            List<Integer> childToParentIndex = new java.util.ArrayList<>();
            for (int p = 0; p < parentChunks.size(); p++) {
                List<String> children = childChunker.chunk(parentChunks.get(p));
                for (String child : children) {
                    allChildContents.add(child);
                    childToParentIndex.add(p);
                }
            }
            log.info("Document {} child chunks: {}", documentId, allChildContents.size());
            eventPublisher.publish(documentId, "PROCESSING",
                    "임베딩 생성 중... (" + allChildContents.size() + "개 청크)");

            // 4. child 임베딩 생성
            float[][] embeddings = embedBatch(allChildContents);
            log.info("Document {} embeddings generated", documentId);

            // 5. parent + child 저장
            tx.executeWithoutResult(status -> {
                Document doc = documentRepository.findById(documentId).orElseThrow();

                // parent 저장 (임베딩 없음)
                java.util.UUID[] parentIds = new java.util.UUID[parentChunks.size()];
                for (int p = 0; p < parentChunks.size(); p++) {
                    DocumentChunk parent = new DocumentChunk(doc, parentChunks.get(p), p);
                    parent = chunkRepository.save(parent);
                    parentIds[p] = parent.getId();
                }

                // child 저장 (임베딩 + tsvector + parent 참조)
                for (int c = 0; c < allChildContents.size(); c++) {
                    int parentIdx = childToParentIndex.get(c);
                    DocumentChunk child = new DocumentChunk(
                            doc, allChildContents.get(c), c, parentIds[parentIdx]);
                    child = chunkRepository.save(child);
                    chunkRepository.updateEmbeddingAndTsvector(
                            child.getId(), toVectorString(embeddings[c]), allChildContents.get(c));
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

    private String parse(String contentType, byte[] fileBytes) {
        return parsers.stream()
                .filter(p -> p.supports(contentType))
                .findFirst()
                .map(p -> p.parse(fileBytes))
                .orElseThrow(() -> new RuntimeException("Unsupported content type: " + contentType));
    }

    private float[][] embedBatch(List<String> texts) {
        float[][] result = new float[texts.size()][];
        for (int i = 0; i < texts.size(); i++) {
            result[i] = modelProvider.getEmbeddingModel().embed(texts.get(i));
        }
        return result;
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
}
