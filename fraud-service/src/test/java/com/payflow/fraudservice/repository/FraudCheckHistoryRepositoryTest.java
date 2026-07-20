package com.payflow.fraudservice.repository;

import com.payflow.fraudservice.domain.FraudCheckHistory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Postgres + real Kafka (fraud-service's Spring context wires a
 * @KafkaListener, same "real infra" standard as PaymentApiIntegrationTest
 * and MockBankLedgerCachingIntegrationTest). Proves the time-window and
 * per-payer filtering the ML scoring feature computation depends on
 * actually works against a live query, not just that it compiles.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FraudCheckHistoryRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private FraudCheckHistoryRepository repository;

    @Test
    void findsOnlyThisPayersChecksWithinTheTimeWindow() {
        UUID payerAccount = UUID.randomUUID();
        UUID otherPayer = UUID.randomUUID();
        Instant now = Instant.now();

        repository.save(new FraudCheckHistory(UUID.randomUUID(), UUID.randomUUID(), payerAccount, 5_000L,
                now.minus(1, ChronoUnit.HOURS)));
        repository.save(new FraudCheckHistory(UUID.randomUUID(), UUID.randomUUID(), payerAccount, 6_000L,
                now.minus(30, ChronoUnit.HOURS))); // outside the 24h window
        repository.save(new FraudCheckHistory(UUID.randomUUID(), UUID.randomUUID(), otherPayer, 7_000L,
                now.minus(1, ChronoUnit.HOURS))); // different payer

        List<FraudCheckHistory> result = repository.findByPayerAccountAndCheckedAtAfter(
                payerAccount, now.minus(24, ChronoUnit.HOURS));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAmountCents()).isEqualTo(5_000L);
    }

    @Test
    void returnsEmptyForAPayerWithNoHistory() {
        List<FraudCheckHistory> result = repository.findByPayerAccountAndCheckedAtAfter(
                UUID.randomUUID(), Instant.now().minus(24, ChronoUnit.HOURS));

        assertThat(result).isEmpty();
    }
}
