package kr.co.mz.ragservice.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class KeywordSearchService {

    private final JdbcTemplate jdbcTemplate;

    public KeywordSearchService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ChunkSearchResult> search(String query, int limit, List<UUID> documentIds) {
        String docFilter = buildDocumentFilter(documentIds);
        List<Object> params = new ArrayList<>();
        params.add(query);
        params.addAll(documentIds);
        params.add(limit);

        return jdbcTemplate.query("""
                SELECT c.id, c.document_id, c.content, c.chunk_index,
                       d.filename,
                       p.content AS parent_content,
                       ts_rank(c.content_tsv, q) AS rank
                FROM document_chunk c
                JOIN document d ON d.id = c.document_id
                LEFT JOIN document_chunk p ON p.id = c.parent_chunk_id,
                     plainto_tsquery('simple', ?) q
                WHERE d.status = 'COMPLETED'
                  AND c.content_tsv @@ q
                """ + docFilter + """
                ORDER BY rank DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new ChunkSearchResult(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("document_id")),
                        rs.getString("filename"),
                        rs.getString("content"),
                        rs.getString("parent_content"),
                        rs.getInt("chunk_index"),
                        rs.getDouble("rank")
                ),
                params.toArray());
    }

    private String buildDocumentFilter(List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) return "";
        String placeholders = String.join(",", documentIds.stream().map(id -> "?").toList());
        return "  AND d.id IN (" + placeholders + ")\n";
    }
}
