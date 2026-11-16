package com.example.transactions;

import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Transaction request accepted by the background processor.")
public record TransactionRequest(
        @Schema(description = "Unique business transaction identifier.", example = "TX-1001")
        String transactionId,
        @Schema(description = "Unique identifier for this submission or retry.", example = "REQ-1001")
        String requestId,
        @Schema(description = "Account whose sequence and balance are affected.", example = "ACC-42")
        String accountId,
        @Schema(description = "Ledger operation.", example = "CREDIT")
        TransactionType transactionType,
        @Schema(description = "Positive transaction amount.", example = "125.50", minimum = "0.01")
        BigDecimal amount,
        @Schema(description = "Three-letter currency code.", example = "USD", minLength = 3, maxLength = 3)
        String currency,
        @Schema(description = "Sequence number within the account, starting at 1.", example = "1", minimum = "1")
        long sequenceNumber,
        @Schema(description = "Demo-only transient failures to simulate before success.", example = "0", minimum = "0")
        int simulatedFailures
) {
    public void validate() {
        if (isBlank(transactionId) || isBlank(requestId) || isBlank(accountId) || isBlank(currency)) {
            throw new IllegalArgumentException("transactionId, requestId, accountId, and currency are required");
        }
        if (transactionType == null) {
            throw new IllegalArgumentException("transactionType must be CREDIT or DEBIT");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("sequenceNumber must be at least 1");
        }
        if (simulatedFailures < 0) {
            throw new IllegalArgumentException("simulatedFailures cannot be negative");
        }
        if (!currency.matches("[A-Za-z]{3}")) {
            throw new IllegalArgumentException("currency must be a three-letter code");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
