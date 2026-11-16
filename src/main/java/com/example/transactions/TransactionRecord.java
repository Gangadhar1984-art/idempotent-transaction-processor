package com.example.transactions;

import java.time.Instant;

public class TransactionRecord {
    private final TransactionRequest request;
    private final Instant receivedAt = Instant.now();
    private TransactionStatus status = TransactionStatus.RECEIVED;
    private int attempts;
    private String message = "Accepted";
    private Instant updatedAt = receivedAt;

    public TransactionRecord(TransactionRequest request) {
        this.request = request;
    }

    public TransactionRequest getRequest() {
        return request;
    }

    public String getTransactionId() {
        return request.transactionId();
    }

    public String getRequestId() {
        return request.requestId();
    }

    public String getAccountId() {
        return request.accountId();
    }

    public long getSequenceNumber() {
        return request.sequenceNumber();
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getMessage() {
        return message;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void update(TransactionStatus newStatus, String newMessage) {
        status = newStatus;
        message = newMessage;
        updatedAt = Instant.now();
    }

    void recordAttempt() {
        attempts++;
        updatedAt = Instant.now();
    }
}
