package com.example.transactions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Transactions", description = "Submit, process, and inspect transaction requests.")
public class TransactionController {
    private final TransactionProcessor processor;

    public TransactionController(TransactionProcessor processor) {
        this.processor = processor;
    }

    @PostMapping("/transactions")
    @Operation(summary = "Submit a transaction", description = "Accepts a valid transaction for background processing.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Transaction accepted or identified as a duplicate"),
            @ApiResponse(responseCode = "400", description = "Request validation failed"),
            @ApiResponse(responseCode = "409", description = "Transaction or sequence conflicts with existing state")
    })
    public ResponseEntity<TransactionRecord> submit(@RequestBody TransactionRequest request) {
        return ResponseEntity.accepted().body(processor.submit(request));
    }

    @PostMapping("/transactions/batch")
    @Operation(
            summary = "Submit a transaction batch",
            description = "Accepts each record independently. Invalid or conflicting records are reported in their result without stopping the rest of the batch.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(name = "Out-of-order batch", value = """
                                    [
                                      {"transactionId":"TX-2","requestId":"REQ-2","accountId":"ACC-1","transactionType":"DEBIT","amount":40,"currency":"USD","sequenceNumber":2,"simulatedFailures":0},
                                      {"transactionId":"TX-1","requestId":"REQ-1","accountId":"ACC-1","transactionType":"CREDIT","amount":100,"currency":"USD","sequenceNumber":1,"simulatedFailures":0}
                                    ]
                                    """)
                    )
            )
    )
    @ApiResponse(responseCode = "202", description = "Per-record acceptance and validation results")
    public ResponseEntity<List<Map<String, Object>>> submitBatch(@RequestBody List<TransactionRequest> batch) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (TransactionRequest request : batch) {
            if (request == null) {
                results.add(Map.of("accepted", false, "transactionId", "", "error", "record must not be null"));
                continue;
            }
            try {
                results.add(Map.of("accepted", true, "record", processor.submit(request)));
            } catch (IllegalArgumentException | RequestConflictException exception) {
                results.add(Map.of(
                        "accepted", false,
                        "transactionId", String.valueOf(request.transactionId()),
                        "error", exception.getMessage()
                ));
            }
        }
        return ResponseEntity.accepted().body(results);
    }

    @GetMapping("/transactions")
    @Operation(summary = "List transactions", description = "Returns all accepted submissions and their current processing statuses.")
    public List<TransactionRecord> transactions() {
        return processor.listTransactions();
    }

    @PostMapping("/transactions/{transactionId}/retry")
    @Operation(summary = "Retry a failed transaction", description = "Re-queues a FAILED transaction and resumes account sequence processing.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Transaction re-queued"),
            @ApiResponse(responseCode = "400", description = "Transaction does not exist"),
            @ApiResponse(responseCode = "409", description = "Transaction is not in FAILED status")
    })
    public ResponseEntity<TransactionRecord> retry(
            @Parameter(description = "Business transaction identifier.", example = "TX-1001")
            @PathVariable String transactionId) {
        return ResponseEntity.accepted().body(processor.retry(transactionId));
    }

    @GetMapping("/summary")
    @Operation(summary = "Get processing summary", description = "Returns request counts by status and current account/currency balances.")
    public Map<String, Object> summary() {
        return processor.summary();
    }

    @GetMapping("/accounts/{accountId}/balance")
    @Operation(summary = "Get account balance", description = "Returns the in-memory balance for an account and currency.")
    public Map<String, Object> balance(
            @Parameter(description = "Account identifier.", example = "ACC-42")
            @PathVariable String accountId,
            @Parameter(description = "Three-letter currency code.", example = "USD")
            @RequestParam String currency) {
        BigDecimal balance = processor.balance(accountId, currency);
        return Map.of("accountId", accountId, "currency", currency.toUpperCase(Locale.ROOT), "balance", balance);
    }
}

@RestControllerAdvice
class TransactionExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(RequestConflictException.class)
    ResponseEntity<Map<String, String>> conflict(RequestConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
    }
}
