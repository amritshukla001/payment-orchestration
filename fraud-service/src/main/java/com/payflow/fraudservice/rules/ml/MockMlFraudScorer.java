package com.payflow.fraudservice.rules.ml;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Stands in for a real external ML fraud-scoring service: applies a
 * hand-trained logistic regression (see FraudModelTrainer) and simulates
 * the latency/occasional-outage behavior a real network call would have,
 * so the @CircuitBreaker guarding score() has something real to react to.
 * The circuit breaker lives here rather than on the caller (MlRiskScoreRule)
 * deliberately -- Spring's AOP proxy only intercepts calls that arrive from
 * outside the bean, so a @CircuitBreaker-annotated method can never trigger
 * its fallback via self-invocation (calling it through `this` from another
 * method on the same class). Putting it on this separate bean, called
 * externally by MlRiskScoreRule, is what makes the proxy actually apply.
 */
@Component
public class MockMlFraudScorer {

    private static final Logger log = LoggerFactory.getLogger(MockMlFraudScorer.class);

    private static final double AMOUNT_NORMALIZATION_CENTS = 500_000.0; // $5,000

    private final double amountWeight;
    private final double velocityWeight;
    private final double deviationWeight;
    private final double bias;
    private final long simulatedLatencyMs;
    private final double simulatedFailureRate;

    public MockMlFraudScorer(@Value("${fraud.ml-scorer.weights.amount}") double amountWeight,
                              @Value("${fraud.ml-scorer.weights.velocity}") double velocityWeight,
                              @Value("${fraud.ml-scorer.weights.deviation}") double deviationWeight,
                              @Value("${fraud.ml-scorer.weights.bias}") double bias,
                              @Value("${fraud.ml-scorer.simulated-latency-ms}") long simulatedLatencyMs,
                              @Value("${fraud.ml-scorer.simulated-failure-rate}") double simulatedFailureRate) {
        this.amountWeight = amountWeight;
        this.velocityWeight = velocityWeight;
        this.deviationWeight = deviationWeight;
        this.bias = bias;
        this.simulatedLatencyMs = simulatedLatencyMs;
        this.simulatedFailureRate = simulatedFailureRate;
    }

    /**
     * @return a risk score in [0, 1]; higher means more likely fraudulent.
     */
    @CircuitBreaker(name = "ml-fraud-scorer", fallbackMethod = "scoreFallback")
    public double score(long amountCents, int velocity, double deviation) {
        simulateNetworkBehavior();
        double normalizedAmount = amountCents / AMOUNT_NORMALIZATION_CENTS;
        double z = amountWeight * normalizedAmount + velocityWeight * velocity + deviationWeight * deviation + bias;
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /**
     * Never rejects on its own -- a below-threshold score is what makes "a
     * down/slow ML service degrades to the deterministic rules instead of
     * blocking the saga" literally true: MlRiskScoreRule sees this score,
     * finds it under its threshold, and reports no objection.
     */
    private double scoreFallback(long amountCents, int velocity, double deviation, Throwable t) {
        log.warn("ML scorer unavailable, deferring to deterministic rules only", t);
        return 0.0;
    }

    private void simulateNetworkBehavior() {
        if (simulatedLatencyMs > 0) {
            try {
                Thread.sleep(simulatedLatencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MlScorerUnavailableException("interrupted while calling the ML scorer");
            }
        }
        if (ThreadLocalRandom.current().nextDouble() < simulatedFailureRate) {
            throw new MlScorerUnavailableException("simulated ML scoring service outage");
        }
    }
}
