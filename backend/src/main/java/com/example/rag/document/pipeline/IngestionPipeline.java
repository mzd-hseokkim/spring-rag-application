package com.example.rag.document.pipeline;

import com.example.rag.document.*;
import com.example.rag.document.parser.DocumentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
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
    private final EmbeddingModel embeddingModel;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final IngestionEventPublisher eventPublisher;
    private final TransactionTemplate tx;

    public IngestionPipeline(List<DocumentParser> parsers,
                             ChunkingStrategy chunkingStrategy,
                             EmbeddingModel embeddingModel,
                             DocumentRepository documentRepository,
                             DocumentChunkRepository chunkRepository,
                             IngestionEventPublisher eventPublisher,
                             PlatformTransactionManager txManager) {
        this.parsers = parsers;
        this.chunkingStrategy = chunkingStrategy;
        this.embeddingModel = embeddingModel;
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

            // 2. 청킹
            List<String> chunks = chunkingStrategy.chunk(text);
            log.info("Document {} chunked: {} chunks", documentId, chunks.size());
            eventPublisher.publish(documentId, "PROCESSING", "임베딩 생성 중... (" + chunks.size() + "개 청크)");

            // 3. 임베딩 생성 (배치)
            float[][] embeddings = embedBatch(chunks);
            log.info("Document {} embeddings generated", documentId);

            // 4. 청크 저장 + 임베딩/tsvector 업데이트
            tx.executeWithoutResult(status -> {
                Document doc = documentRepository.findById(documentId).orElseThrow();
                for (int i = 0; i < chunks.size(); i++) {
                    DocumentChunk chunk = new DocumentChunk(doc, chunks.get(i), i);
                    chunk = chunkRepository.save(chunk);
                    chunkRepository.updateEmbeddingAndTsvector(
                            chunk.getId(), toVectorString(embeddings[i]), chunks.get(i));
                }
                doc.markCompleted(chunks.size());
                documentRepository.save(doc);
            });

            eventPublisher.publish(documentId, "COMPLETED", chunks.size() + "개 청크 처리 완료");
            log.info("Document {} processing completed: {} chunks", documentId, chunks.size());

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
            result[i] = embeddingModel.embed(texts.get(i));
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
