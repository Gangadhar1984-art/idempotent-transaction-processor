package com.example.transactions;

public enum TransactionStatus {
    RECEIVED,
    PROCESSING,
    PROCESSED,
    DUPLICATE,
    WAITING_FOR_SEQUENCE,
    RETRY_PENDING,
    FAILED
}
