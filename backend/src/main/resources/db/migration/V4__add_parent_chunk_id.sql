ALTER TABLE document_chunk ADD COLUMN parent_chunk_id UUID REFERENCES document_chunk(id) ON DELETE CASCADE;
CREATE INDEX idx_chunk_parent ON document_chunk(parent_chunk_id);
