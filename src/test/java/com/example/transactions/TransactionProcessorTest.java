package com.example.transactions;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionProcessorTest {
    private final Executor directExecutor = Runnable::run;

    @Test
    void processesOutOfOrderRequestsOnceAndMarksReplayAsDuplicate() {
        TransactionProcessor processor = new TransactionProcessor(directExecutor);

        TransactionRecord second = processor.submit(request("TX-2", "REQ-2", "A", 2, "DEBIT", "40", 0));
        assertEquals(TransactionStatus.WAITING_FOR_SEQUENCE, second.getStatus());

        TransactionRecord first = processor.submit(request("TX-1", "REQ-1", "A", 1, "CREDIT", "100", 0));
        TransactionRecord duplicate = processor.submit(new TransactionRequest(
                "TX-2", "REQ-2-DUP", "A", TransactionType.DEBIT, new BigDecimal("40.00"), "usd", 2, 0));

        assertEquals(TransactionStatus.PROCESSED, first.getStatus());
        assertEquals(TransactionStatus.PROCESSED, second.getStatus());
        assertEquals(TransactionStatus.DUPLICATE, duplicate.getStatus());
        assertEquals(new BigDecimal("60"), processor.balance("A", "USD"));
    }

    @Test
    void retriesTransientFailureWithoutApplyingTheEffectMoreThanOnce() {
        TransactionProcessor processor = new TransactionProcessor(directExecutor);

        TransactionRecord record = processor.submit(request("TX-1", "REQ-1", "A", 1, "CREDIT", "12.50", 1));

        assertEquals(TransactionStatus.PROCESSED, record.getStatus());
        assertEquals(2, record.getAttempts());
        assertEquals(new BigDecimal("12.50"), processor.balance("A", "USD"));
    }

    @Test
    void isolatesInvalidInputAndExhaustsRetryLimitWithoutCreditingBalance() {
        TransactionProcessor processor = new TransactionProcessor(directExecutor);
        TransactionRequest invalid = request("BAD", "BAD-REQ", "A", 1, "DEBIT", "-1", 0);

        assertThrows(IllegalArgumentException.class, () -> processor.submit(invalid));
        TransactionRecord failed = processor.submit(request("TX-1", "REQ-1", "A", 1, "CREDIT", "30", 5));

        assertEquals(TransactionStatus.FAILED, failed.getStatus());
        assertEquals(3, failed.getAttempts());
        assertEquals(BigDecimal.ZERO, processor.balance("A", "USD"));
        Map<?, ?> counts = (Map<?, ?>) processor.summary().get("countsByStatus");
        assertEquals(1L, counts.get(TransactionStatus.FAILED));
    }

    @Test
    void manualRetryResumesAfterTheConfiguredTransientFailures() {
        TransactionProcessor processor = new TransactionProcessor(directExecutor);
        TransactionRecord failed = processor.submit(request("TX-1", "REQ-1", "A", 1, "CREDIT", "30", 3));
        assertEquals(TransactionStatus.FAILED, failed.getStatus());

        TransactionRecord retried = processor.retry("TX-1");

        assertEquals(TransactionStatus.PROCESSED, retried.getStatus());
        assertEquals(new BigDecimal("30"), processor.balance("A", "USD"));
    }

    @Test
    void rejectsConflictingTransactionAndSequenceReuse() {
        TransactionProcessor processor = new TransactionProcessor(directExecutor);
        processor.submit(request("TX-1", "REQ-1", "A", 1, "CREDIT", "10", 0));

        assertThrows(RequestConflictException.class,
                () -> processor.submit(request("TX-1", "REQ-2", "A", 1, "CREDIT", "11", 0)));
        assertThrows(RequestConflictException.class,
                () -> processor.submit(request("TX-2", "REQ-3", "A", 1, "CREDIT", "10", 0)));
    }

    @Test
    void keepsBalancesSeparateByCurrency() {
        TransactionProcessor processor = new TransactionProcessor(directExecutor);
        processor.submit(request("TX-1", "REQ-1", "A", 1, "CREDIT", "10", 0));
        processor.submit(new TransactionRequest("TX-2", "REQ-2", "A", TransactionType.CREDIT,
                new BigDecimal("20"), "EUR", 2, 0));

        assertEquals(new BigDecimal("10"), processor.balance("A", "USD"));
        assertEquals(new BigDecimal("20"), processor.balance("A", "EUR"));
    }

    @Test
    void reportsCountsByStatus() {
        TransactionProcessor processor = new TransactionProcessor(directExecutor);
        processor.submit(request("TX-2", "REQ-2", "A", 2, "CREDIT", "10", 0));

        Map<?, ?> counts = (Map<?, ?>) processor.summary().get("countsByStatus");
        assertEquals(1L, counts.get(TransactionStatus.WAITING_FOR_SEQUENCE));
    }

    private TransactionRequest request(String id, String requestId, String account, long sequence,
                                       String type, String amount, int failures) {
        return new TransactionRequest(id, requestId, account, TransactionType.valueOf(type),
                new BigDecimal(amount), "USD", sequence, failures);
    }
}
