package com.example.rag.common.guard;

public class InputValidationException extends RuntimeException {

    public InputValidationException(String message) {
        super(message);
    }
}
