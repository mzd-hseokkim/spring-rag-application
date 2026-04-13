package kr.co.mz.ragservice.document;

import java.util.UUID;

public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(UUID id) {
        super("Document not found: " + id);
    }
}
