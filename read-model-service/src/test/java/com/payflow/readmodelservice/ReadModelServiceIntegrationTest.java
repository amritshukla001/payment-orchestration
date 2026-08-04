package com.payflow.readmodelservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.payflow.common.events.EventEnvelope;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Postgres + real Kafka via Testcontainers, mirroring payment-api's
 * canonical IT: publishes a realistic envelope sequence for one payment
 * directly onto payment.events with a real producer (bypassing every
 * write-side service), then polls the REST API until the projection
 * catches up -- proving the whole consume-and-project pipeline works
 * end to end, not just each handler method in isolation.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class ReadModelServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    private static final String API_KEY = "it-test-api-key";

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("payflow.security.api-key", () -> API_KEY);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void projectsAFullHappyPathSequenceIntoOnePaymentDetailResponse() throws InterruptedException {
        UUID paymentId = UUID.randomUUID();
        UUID payerAccount = UUID.randomUUID();
        UUID payeeAccount = UUID.randomUUID();

        try (KafkaProducer<String, String> producer = producer()) {
            publish(producer, paymentId, "PAYMENT_INITIATED", Map.of(
                    "paymentId", paymentId, "payerAccount", payerAccount, "payeeAccount", payeeAccount,
                    "amountCents", 950_000L, "currency", "USD", "occurredAt", Instant.now()));
            publish(producer, paymentId, "FRAUD_APPROVED", Map.of("paymentId", paymentId, "occurredAt", Instant.now()));
            publish(producer, paymentId, "FUNDS_AUTHORIZED", Map.of("paymentId", paymentId, "occurredAt", Instant.now()));

            UUID holdId = UUID.randomUUID();
            UUID suspenseAccount = UUID.fromString("00000000-0000-0000-0000-000000000001");
            publish(producer, paymentId, "LEDGER_POSTED", Map.of(
                    "id", holdId, "paymentId", paymentId, "debitAccount", payerAccount,
                    "creditAccount", suspenseAccount, "amountCents", 950_000L,
                    "postingType", "HOLD", "occurredAt", Instant.now()));
            publish(producer, paymentId, "PAYMENT_SETTLED", Map.of(
                    "paymentId", paymentId, "payerAccount", payerAccount, "payeeAccount", payeeAccount,
                    "occurredAt", Instant.now()));

            UUID finalId = UUID.randomUUID();
            publish(producer, paymentId, "LEDGER_FINALIZED", Map.of(
                    "id", finalId, "paymentId", paymentId, "debitAccount", suspenseAccount,
                    "creditAccount", payeeAccount, "amountCents", 950_000L,
                    "postingType", "FINAL", "occurredAt", Instant.now()));

            publish(producer, paymentId, "NOTIFICATION_SENT", Map.of(
                    "id", UUID.randomUUID(), "paymentId", paymentId, "accountId", payerAccount,
                    "recipient", "PAYER", "outcome", "SUCCESS", "message", "Your payment has settled.",
                    "sentAt", Instant.now()));
        }

        Map<?, ?> body = awaitSettled(paymentId);
        Map<?, ?> payment = (Map<?, ?>) body.get("payment");
        assertThat(payment.get("state")).isEqualTo("SETTLED");
        List<?> ledgerEntries = (List<?>) body.get("ledgerEntries");
        assertThat(ledgerEntries).hasSize(2);
        List<?> notifications = (List<?>) body.get("notifications");
        assertThat(notifications).hasSize(1);
    }

    // Uses >= assertions rather than exact counts -- this shares the same
    // Testcontainers Postgres with the happy-path test above, so the
    // read model may already contain that test's payment by the time this
    // one runs (JUnit doesn't guarantee ordering across @Test methods).
    @Test
    void analyticsSummaryAggregatesAcrossPaymentsAndMethods() throws InterruptedException {
        UUID settledPaymentId = UUID.randomUUID();
        UUID failedPaymentId = UUID.randomUUID();
        UUID payerAccount = UUID.randomUUID();
        UUID payeeAccount = UUID.randomUUID();
        UUID suspenseAccount = UUID.fromString("00000000-0000-0000-0000-000000000001");

        try (KafkaProducer<String, String> producer = producer()) {
            publish(producer, settledPaymentId, "PAYMENT_INITIATED", Map.of(
                    "paymentId", settledPaymentId, "payerAccount", payerAccount, "payeeAccount", payeeAccount,
                    "amountCents", 5_000L, "currency", "USD", "paymentMethod", "CARD", "occurredAt", Instant.now()));
            publish(producer, settledPaymentId, "FRAUD_APPROVED", Map.of("paymentId", settledPaymentId, "occurredAt", Instant.now()));
            publish(producer, settledPaymentId, "FUNDS_AUTHORIZED", Map.of("paymentId", settledPaymentId, "occurredAt", Instant.now()));
            publish(producer, settledPaymentId, "LEDGER_POSTED", Map.of(
                    "id", UUID.randomUUID(), "paymentId", settledPaymentId, "debitAccount", payerAccount,
                    "creditAccount", suspenseAccount, "amountCents", 5_000L,
                    "postingType", "HOLD", "occurredAt", Instant.now()));
            publish(producer, settledPaymentId, "PAYMENT_SETTLED", Map.of(
                    "paymentId", settledPaymentId, "payerAccount", payerAccount, "payeeAccount", payeeAccount,
                    "occurredAt", Instant.now()));

            publish(producer, failedPaymentId, "PAYMENT_INITIATED", Map.of(
                    "paymentId", failedPaymentId, "payerAccount", payerAccount, "payeeAccount", payeeAccount,
                    "amountCents", 2_000L, "currency", "USD", "paymentMethod", "UPI", "occurredAt", Instant.now()));
            publish(producer, failedPaymentId, "PAYMENT_FAILED", Map.of(
                    "paymentId", failedPaymentId, "payerAccount", payerAccount, "reason", "test failure",
                    "occurredAt", Instant.now()));
        }

        awaitState(settledPaymentId, "SETTLED");
        awaitState(failedPaymentId, "FAILED");

        Map<?, ?> summary = getAnalyticsSummary();
        assertThat(((Number) summary.get("settledCount")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) summary.get("failedCount")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) summary.get("settledAmountCents")).longValue()).isGreaterThanOrEqualTo(5_000L);

        List<?> byMethod = (List<?>) summary.get("byMethod");
        assertThat(byMethod).isNotEmpty();
        List<?> dailyVolume = (List<?>) summary.get("dailyVolume");
        assertThat(dailyVolume).isNotEmpty();
    }

    private Map<?, ?> getAnalyticsSummary() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", API_KEY);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/payments/analytics/summary", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        return response.getBody();
    }

    private Map<?, ?> awaitSettled(UUID paymentId) throws InterruptedException {
        return awaitState(paymentId, "SETTLED");
    }

    private Map<?, ?> awaitState(UUID paymentId, String expectedState) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<Map> response = getDetail(paymentId);
            if (response.getStatusCode().is2xxSuccessful()) {
                Map<?, ?> payment = (Map<?, ?>) response.getBody().get("payment");
                if (payment != null && expectedState.equals(payment.get("state"))) {
                    return response.getBody();
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Payment " + paymentId + " never reached " + expectedState + " in the read model");
    }

    private ResponseEntity<Map> getDetail(UUID paymentId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", API_KEY);
        return restTemplate.exchange("/api/payments/" + paymentId, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private KafkaProducer<String, String> producer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaProducer<>(props);
    }

    private void publish(KafkaProducer<String, String> producer, UUID paymentId, String eventType, Map<String, Object> payload) {
        try {
            ObjectNode payloadNode = objectMapper.valueToTree(payload);
            EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), paymentId, eventType, Instant.now(), payloadNode);
            String json = objectMapper.writeValueAsString(envelope);
            producer.send(new ProducerRecord<>("payment.events", paymentId.toString(), json)).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
