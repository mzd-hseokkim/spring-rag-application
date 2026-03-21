ALTER TABLE conversation ADD COLUMN user_id UUID REFERENCES app_user(id);
ALTER TABLE document ADD COLUMN user_id UUID REFERENCES app_user(id);

CREATE INDEX idx_conversation_user ON conversation (user_id, updated_at DESC);
CREATE INDEX idx_document_user ON document (user_id);
