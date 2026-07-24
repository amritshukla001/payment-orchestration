package com.payflow.orchestrator.summary;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Message;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place saga-orchestrator talks to the Claude API. Deliberately a
 * separate bean from CompensationSummaryService so the circuit-breaker
 * annotation is intercepted by Spring's AOP proxy -- a self-invocation
 * inside the service would bypass it (same reasoning as fraud-service's
 * MockMlFraudScorer). The LLM only ever *explains* an already-final saga
 * history; it never participates in a payment decision, and it is never
 * called from a Kafka listener -- only from the on-demand summary endpoint.
 *
 * Degrades to null on any failure (no API key configured, API down, circuit
 * open, empty response) -- the caller then falls back to a deterministic
 * template summary instead of surfacing an error.
 */
@Component
public class ClaudeSummaryClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeSummaryClient.class);

    private static final String SYSTEM_PROMPT = """
            You write brief incident summaries for a payments operations dashboard.
            Given a payment's saga timeline, write a 2-4 sentence plain-English summary of
            what happened: what the payment was, how far it got, what triggered the
            compensation, and that the payer's funds were returned. Write for an operations
            analyst. State only facts present in the timeline; do not speculate about causes
            the timeline doesn't show. Respond with the summary text only, no preamble.""";

    private final AnthropicClient client;
    private final String model;

    public ClaudeSummaryClient(@Value("${payflow.ai.api-key}") String apiKey,
                                @Value("${payflow.ai.model}") String model) {
        // No key configured is a normal local-dev state, not an error -- the
        // endpoint still works via the deterministic fallback.
        this.client = apiKey == null || apiKey.isBlank()
                ? null
                : AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        this.model = model;
    }

    @CircuitBreaker(name = "ai-summarizer", fallbackMethod = "summarizeFallback")
    public String summarize(String timeline) {
        if (client == null) {
            log.debug("No Claude API key configured, deferring to deterministic summary");
            return null;
        }

        Message response = client.messages().create(MessageCreateParams.builder()
                .model(model)
                .maxTokens(1024L)
                .system(SYSTEM_PROMPT)
                .addUserMessage(timeline)
                .build());

        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .reduce("", String::concat)
                .strip();
        return text.isEmpty() ? null : text;
    }

    private String summarizeFallback(String timeline, Throwable t) {
        log.warn("Claude API unavailable, deferring to deterministic summary", t);
        return null;
    }
}
