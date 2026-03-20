package com.example.rag.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class KeywordSearchService {

    private final JdbcTemplate jdbcTemplate;

    public KeywordSearchService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ChunkSearchResult> search(String query, int limit) {
        return jdbcTemplate.query("""
                SELECT c.id, c.document_id, c.content, c.chunk_index,
                       d.filename,
                       ts_rank(c.content_tsv, q) AS rank
                FROM document_chunk c
                JOIN document d ON d.id = c.document_id,
                     plainto_tsquery('simple', ?) q
                WHERE d.status = 'COMPLETED'
                  AND c.content_tsv @@ q
                ORDER BY rank DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new ChunkSearchResult(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("document_id")),
                        rs.getString("filename"),
                        rs.getString("content"),
                        rs.getInt("chunk_index"),
                        rs.getDouble("rank")
                ),
                query, limit);
    }
}
