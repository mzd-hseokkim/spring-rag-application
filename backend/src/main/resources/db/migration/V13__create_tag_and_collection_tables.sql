CREATE TABLE document_tag (
    id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE document_tag_mapping (
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    tag_id      UUID NOT NULL REFERENCES document_tag(id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, tag_id)
);

CREATE TABLE document_collection (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    user_id     UUID REFERENCES app_user(id) ON DELETE CASCADE,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE document_collection_mapping (
    document_id   UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    collection_id UUID NOT NULL REFERENCES document_collection(id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, collection_id)
);
