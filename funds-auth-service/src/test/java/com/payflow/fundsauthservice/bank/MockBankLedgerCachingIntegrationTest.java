package com.payflow.fundsauthservice.bank;

import com.payflow.fundsauthservice.domain.Account;
import com.payflow.fundsauthservice.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Postgres + real Kafka + real Redis, not mocks -- same "real infra"
 * standard as PaymentApiIntegrationTest. Proves the cache-aside read
 * actually works against a live Redis, not just that the annotations
 * compile (they're inert without a real Spring AOP proxy, which a
 * plain-Mockito unit test never exercises). The Redis-outage/fallback case
 * is a separate test class (MockBankLedgerCacheOutageIntegrationTest) --
 * deliberately not stopping this shared container mid-test, since this
 * class's Spring context is cached and reused across its test methods, and
 * a stop/restart could leave the connection pool pointed at a stale port.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MockBankLedgerCachingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private MockBankLedger bankLedger;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void getBalanceIsServedFromCacheOnASecondCallEvenAfterTheRowIsDeleted() {
        UUID accountId = UUID.randomUUID();
        accountRepository.save(new Account(accountId, 55_000L, Instant.now()));

        long firstRead = bankLedger.getBalance(accountId);
        assertThat(firstRead).isEqualTo(55_000L);

        // Delete straight from Postgres -- if the second read still returns
        // 55_000L, it can only have come from Redis, not a fresh DB lookup
        // (which would now find nothing and fall back to the $100,000 default).
        accountRepository.deleteById(accountId);

        long secondRead = bankLedger.getBalance(accountId);
        assertThat(secondRead).isEqualTo(55_000L);
    }
}
