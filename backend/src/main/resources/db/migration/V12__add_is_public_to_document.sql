ALTER TABLE document ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX idx_document_public ON document (is_public, status);
