package com.example.transactions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

@Service
public class TransactionProcessor {
    private static final Logger log = LoggerFactory.getLogger(TransactionProcessor.class);
    private static final int MAX_ATTEMPTS_PER_RUN = 3;

    private final Executor executor;
    private final Map<String, TransactionRecord> transactions = new LinkedHashMap<>();
    private final Map<String, TransactionRecord> requests = new HashMap<>();
    private final Map<String, TransactionRecord> sequenceSlots = new HashMap<>();
    private final Map<String, Long> nextSequence = new HashMap<>();
    private final Map<String, Map<String, BigDecimal>> balances = new HashMap<>();

    public TransactionProcessor(Executor transactionExecutor) {
        this.executor = transactionExecutor;
    }

    public synchronized TransactionRecord submit(TransactionRequest request) {
        request.validate();

        TransactionRecord priorRequest = requests.get(request.requestId());
        if (priorRequest != null) {
            if (!priorRequest.getRequest().equals(request)) {
                throw new RequestConflictException("requestId is already used for a different request");
            }
            return priorRequest;
        }

        TransactionRecord existing = transactions.get(request.transactionId());
        if (existing != null) {
            if (!sameBusinessTransaction(existing.getRequest(), request)) {
                throw new RequestConflictException("transactionId is already used for different transaction data");
            }
            TransactionRecord duplicate = new TransactionRecord(request);
            duplicate.update(TransactionStatus.DUPLICATE, "Business transaction was already accepted");
            requests.put(request.requestId(), duplicate);
            log.info("transaction_duplicate transactionId={} requestId={}", request.transactionId(), request.requestId());
            return duplicate;
        }

        String slot = sequenceSlot(request.accountId(), request.sequenceNumber());
        TransactionRecord occupyingRecord = sequenceSlots.get(slot);
        if (occupyingRecord != null) {
            throw new RequestConflictException("account sequence is already assigned to another transaction");
        }
        long expected = nextSequence.getOrDefault(request.accountId(), 1L);
        if (request.sequenceNumber() < expected) {
            throw new RequestConflictException("sequenceNumber is older than the account's next expected sequence");
        }

        TransactionRecord record = new TransactionRecord(request);
        transactions.put(request.transactionId(), record);
        requests.put(request.requestId(), record);
        sequenceSlots.put(slot, record);
        if (request.sequenceNumber() > expected) {
            record.update(TransactionStatus.WAITING_FOR_SEQUENCE,
                    "Waiting for sequence " + expected + " on this account");
        }
        log.info("transaction_received transactionId={} requestId={} status={}",
                request.transactionId(), request.requestId(), record.getStatus());

        if (request.sequenceNumber() == expected) {
            executor.execute(() -> processAccount(request.accountId()));
        }
        return record;
    }

    public synchronized TransactionRecord retry(String transactionId) {
        TransactionRecord record = transactions.get(transactionId);
        if (record == null) {
            throw new IllegalArgumentException("Unknown transactionId: " + transactionId);
        }
        if (record.getStatus() != TransactionStatus.FAILED) {
            throw new RequestConflictException("Only FAILED transactions can be retried");
        }
        record.update(TransactionStatus.RECEIVED, "Manually re-queued for retry");
        executor.execute(() -> processAccount(record.getAccountId()));
        return record;
    }

    public synchronized List<TransactionRecord> listTransactions() {
        return List.copyOf(requests.values());
    }

    public synchronized Map<String, Object> summary() {
        Map<TransactionStatus, Long> counts = new EnumMap<>(TransactionStatus.class);
        Map<String, Map<String, BigDecimal>> balanceSnapshot = new LinkedHashMap<>();
        for (TransactionStatus status : TransactionStatus.values()) {
            counts.put(status, 0L);
        }
        requests.values().forEach(record -> counts.merge(record.getStatus(), 1L, Long::sum));
        balances.forEach((account, currencies) -> balanceSnapshot.put(account, Map.copyOf(currencies)));
        return Map.of(
                "totalRequests", requests.size(),
                "countsByStatus", counts,
                "accountBalances", Map.copyOf(balanceSnapshot),
                "maxAttemptsPerRun", MAX_ATTEMPTS_PER_RUN
        );
    }

    public synchronized BigDecimal balance(String accountId, String currency) {
        return balances.getOrDefault(accountId, Map.of())
                .getOrDefault(currency.toUpperCase(Locale.ROOT), BigDecimal.ZERO);
    }

    synchronized void processAccount(String accountId) {
        long expected = nextSequence.getOrDefault(accountId, 1L);
        while (true) {
            TransactionRecord record = sequenceSlots.get(sequenceSlot(accountId, expected));
            if (record == null || record.getStatus() == TransactionStatus.FAILED) {
                return;
            }
            if (record.getStatus() == TransactionStatus.PROCESSED) {
                expected++;
                nextSequence.put(accountId, expected);
                continue;
            }

            record.update(TransactionStatus.PROCESSING, "Processing");
            log.info("transaction_processing transactionId={} sequence={}",
                    record.getTransactionId(), record.getSequenceNumber());
            boolean succeeded = processWithRetries(record);
            if (!succeeded) {
                return;
            }

            TransactionRequest request = record.getRequest();
            BigDecimal signedAmount = request.transactionType() == TransactionType.CREDIT
                    ? request.amount() : request.amount().negate();
            balances.computeIfAbsent(accountId, ignored -> new HashMap<>())
                    .merge(request.currency().toUpperCase(Locale.ROOT), signedAmount, BigDecimal::add);
            record.update(TransactionStatus.PROCESSED, "Processed successfully");
            log.info("transaction_processed transactionId={} attempts={}",
                    record.getTransactionId(), record.getAttempts());
            expected++;
            nextSequence.put(accountId, expected);
        }
    }

    private boolean processWithRetries(TransactionRecord record) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_RUN; attempt++) {
            record.recordAttempt();
            if (record.getAttempts() > record.getRequest().simulatedFailures()) {
                return true;
            }
            record.update(TransactionStatus.RETRY_PENDING,
                    "Simulated transient failure; retrying (attempt " + record.getAttempts() + ")");
            log.warn("transaction_retry transactionId={} attempt={}",
                    record.getTransactionId(), record.getAttempts());
        }
        record.update(TransactionStatus.FAILED,
                "Retry limit reached; use the retry endpoint after investigation");
        log.error("transaction_failed transactionId={} attempts={}",
                record.getTransactionId(), record.getAttempts());
        return false;
    }

    private boolean sameBusinessTransaction(TransactionRequest left, TransactionRequest right) {
        return Objects.equals(left.accountId(), right.accountId())
                && left.transactionType() == right.transactionType()
                && left.amount().compareTo(right.amount()) == 0
                && left.currency().equalsIgnoreCase(right.currency())
                && left.sequenceNumber() == right.sequenceNumber();
    }

    private String sequenceSlot(String accountId, long sequence) {
        return accountId + ":" + sequence;
    }
}
