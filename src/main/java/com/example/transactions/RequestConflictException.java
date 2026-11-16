package com.example.transactions;

public class RequestConflictException extends RuntimeException {
    public RequestConflictException(String message) {
        super(message);
    }
}
