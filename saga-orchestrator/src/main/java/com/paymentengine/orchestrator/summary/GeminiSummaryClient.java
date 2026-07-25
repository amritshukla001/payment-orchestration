package com.payflow.orchestrator.summary;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * The one place saga-orchestrator talks to an external LLM API -- Google's
 * Gemini, chosen because its free tier needs no billing setup (unlike the
 * Claude API, which is pay-as-you-go even for a Claude Pro subscriber).
 * Deliberately a separate bean from CompensationSummaryService so the
 * circuit-breaker annotation is intercepted by Spring's AOP proxy -- a
 * self-invocation inside the service would bypass it (same reasoning as
 * fraud-service's MockMlFraudScorer). The LLM only ever *explains* an
 * already-final saga history; it never participates in a payment decision,
 * and it is never called from a Kafka listener -- only from the on-demand
 * summary endpoint.
 *
 * Degrades to null on any failure (no API key configured, API down, circuit
 * open, empty response) -- the caller then falls back to a deterministic
 * template summary instead of surfacing an error.
 */
@Component
public class GeminiSummaryClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiSummaryClient.class);

    private static final String ENDPOINT_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    private static final String SYSTEM_PROMPT = """
            You write brief incident summaries for a payments operations dashboard.
            Given a payment's saga timeline, write a 2-4 sentence plain-English summary of
            what happened: what the payment was, how far it got, what triggered the
            compensation, and that the payer's funds were returned. Write for an operations
            analyst. State only facts present in the timeline; do not speculate about causes
            the timeline doesn't show. Respond with the summary text only, no preamble.""";

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public GeminiSummaryClient(@Value("${payflow.ai.api-key}") String apiKey,
                                @Value("${payflow.ai.model}") String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = RestClient.create();
    }

    @CircuitBreaker(name = "ai-summarizer", fallbackMethod = "summarizeFallback")
    public String summarize(String timeline) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("No Gemini API key configured, deferring to deterministic summary");
            return null;
        }

        GeminiRequest request = new GeminiRequest(
                List.of(new Content(List.of(new Part(timeline)))),
                new Content(List.of(new Part(SYSTEM_PROMPT))),
                new GenerationConfig(1024));

        GeminiResponse response = restClient.post()
                .uri(ENDPOINT_TEMPLATE.formatted(model))
                .header("x-goog-api-key", apiKey)
                .body(request)
                .retrieve()
                .body(GeminiResponse.class);

        String text = extractText(response);
        return text.isEmpty() ? null : text;
    }

    private String extractText(GeminiResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            return "";
        }
        Content content = response.candidates().get(0).content();
        if (content == null || content.parts() == null) {
            return "";
        }
        return content.parts().stream()
                .map(Part::text)
                .reduce("", String::concat)
                .strip();
    }

    private String summarizeFallback(String timeline, Throwable t) {
        log.warn("Gemini API unavailable, deferring to deterministic summary", t);
        return null;
    }

    record GeminiRequest(List<Content> contents, Content systemInstruction, GenerationConfig generationConfig) {
    }

    record Content(List<Part> parts) {
    }

    record Part(String text) {
    }

    record GenerationConfig(int maxOutputTokens) {
    }

    record GeminiResponse(List<Candidate> candidates) {
    }

    record Candidate(Content content) {
    }
}
