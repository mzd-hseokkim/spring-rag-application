package kr.co.mz.ragservice.search;

import kr.co.mz.ragservice.model.ModelClientProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class VectorSearchService {

    private final ModelClientProvider modelProvider;
    private final JdbcTemplate jdbcTemplate;

    public VectorSearchService(ModelClientProvider modelProvider, JdbcTemplate jdbcTemplate) {
        this.modelProvider = modelProvider;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ChunkSearchResult> search(String query, int limit, List<UUID> documentIds) {
        float[] queryEmbedding = modelProvider.getEmbeddingModel().embed(query);
        String vectorStr = toVectorString(queryEmbedding);

        String docFilter = buildDocumentFilter(documentIds);
        List<Object> params = new ArrayList<>();
        params.add(vectorStr);
        params.addAll(documentIds);
        params.add(vectorStr);
        params.add(limit);

        return jdbcTemplate.query("""
                SELECT c.id, c.document_id, c.content, c.chunk_index,
                       d.filename,
                       p.content AS parent_content,
                       1 - (c.embedding <=> cast(? AS vector)) AS similarity
                FROM document_chunk c
                JOIN document d ON d.id = c.document_id
                LEFT JOIN document_chunk p ON p.id = c.parent_chunk_id
                WHERE d.status = 'COMPLETED'
                  AND c.embedding IS NOT NULL
                """ + docFilter + """
                ORDER BY c.embedding <=> cast(? AS vector)
                LIMIT ?
                """,
                (rs, rowNum) -> new ChunkSearchResult(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("document_id")),
                        rs.getString("filename"),
                        rs.getString("content"),
                        rs.getString("parent_content"),
                        rs.getInt("chunk_index"),
                        rs.getDouble("similarity")
                ),
                params.toArray());
    }

    private String buildDocumentFilter(List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) return "";
        String placeholders = String.join(",", documentIds.stream().map(id -> "?").toList());
        return "  AND d.id IN (" + placeholders + ")\n";
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
