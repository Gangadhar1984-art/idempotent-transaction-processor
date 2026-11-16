package com.example.transactions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(
        title = "Idempotent Transaction Processing API",
        version = "1.0.0",
        description = "Submit, inspect, and retry sequence-aware background transactions."
))
public class TransactionApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransactionApplication.class, args);
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService transactionExecutor() {
        return Executors.newFixedThreadPool(2);
    }
}
