package com.payflow.paymentapi;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The regression pack for payment-api: real Postgres + real Kafka via
 * Testcontainers, not mocks. This is exactly the flow that's been
 * manually curl-tested by hand at every prior phase -- now it runs on
 * every build instead of relying on someone remembering to do it again.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentApiIntegrationTest {

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

    @Test
    void postingAPaymentPersistsItPublishesToKafkaAndIsIdempotent() {
        String idempotencyKey = "it-" + UUID.randomUUID();
        String body = """
                {"payerAccount":"%s","payeeAccount":"%s","amountCents":2500,"currency":"USD"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-API-Key", API_KEY);
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> firstResponse = restTemplate.postForEntity("/payments", request, Map.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String paymentId = (String) firstResponse.getBody().get("id");
        assertThat(firstResponse.getBody().get("state")).isEqualTo("INITIATED");

        // Retrying with the same Idempotency-Key must return the same payment, not create a duplicate.
        ResponseEntity<Map> retryResponse = restTemplate.postForEntity("/payments", request, Map.class);
        assertThat(retryResponse.getBody().get("id")).isEqualTo(paymentId);

        // Confirm the outbox publisher actually got the event onto the real topic.
        assertPaymentInitiatedEventPublished(paymentId);

        // Confirm it's queryable back through the API too.
        HttpEntity<Void> getRequest = new HttpEntity<>(authHeaders());
        ResponseEntity<Map> getResponse = restTemplate.exchange(
                "/payments/" + paymentId, HttpMethod.GET, getRequest, Map.class);
        assertThat(getResponse.getBody().get("state")).isEqualTo("INITIATED");
    }

    @Test
    void aRequestWithoutTheApiKeyIsRejected() {
        // A GET, not a POST: the JDK's default HttpURLConnection-based client
        // can't cleanly surface a 401 for a request with a body already
        // streamed ("cannot retry due to server authentication, in streaming
        // mode") -- an artifact of the test client, not the filter under
        // test, which runs identically regardless of HTTP method.
        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/payments/" + UUID.randomUUID(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void prometheusScrapeEndpointIsExposedAndUnauthenticated() {
        // Same /actuator exemption as health/info -- Prometheus scrapes without
        // an API key, same as the health check does today.
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", API_KEY);
        return headers;
    }

    private void assertPaymentInitiatedEventPublished(String paymentId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("payment.events"));
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.value().contains(paymentId)) {
                        return;
                    }
                }
            }
            fail("Never saw a PAYMENT_INITIATED event for " + paymentId + " on payment.events");
        }
    }
}
