package com.payflow.fundsauthservice.bank;

import com.payflow.fundsauthservice.domain.Account;
import com.payflow.fundsauthservice.repository.AccountRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Separate Spring context from MockBankLedgerCachingIntegrationTest,
 * deliberately: this one points spring.data.redis at a port nothing is
 * listening on for its entire lifetime, rather than stopping/restarting a
 * shared container mid-test (which would risk leaving a connection pool
 * pointed at a stale port for other tests sharing that cached context).
 * Proves the @CircuitBreaker fallback -- verified empirically against a
 * real Redis outage before this was wired into the real service -- also
 * holds when driven through the real Spring context, not just the
 * throwaway smoke test used to validate the design.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.data.redis.host=localhost", "spring.data.redis.port=1"})
class MockBankLedgerCacheOutageIntegrationTest {

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
    private MockBankLedger bankLedger;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void getBalanceFallsBackToPostgresWhenRedisIsUnreachable() {
        UUID accountId = UUID.randomUUID();
        accountRepository.save(new Account(accountId, 77_000L, Instant.now()));

        long balance = bankLedger.getBalance(accountId);

        assertThat(balance).isEqualTo(77_000L);
    }
}
