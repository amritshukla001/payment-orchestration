package com.payflow.paymentengine.scheduler;

import com.payflow.common.enums.PaymentState;
import com.payflow.paymentengine.domain.PaymentEngineAggregate;
import com.payflow.paymentengine.domain.PaymentEngineEventStore;
import com.payflow.paymentengine.domain.PaymentEngineTransitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * A CARD payment left AWAITING_STEP_UP with no customer response
 * eventually needs to resolve one way or the other -- this is that
 * resolution. Reuses PaymentEngineEventStore.loadAll()'s full-table fold
 * (already self-documented as "correctness-first, not the scalable read
 * path") since there's no dedicated query for "payments stuck in state X."
 */
@Component
public class StepUpTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(StepUpTimeoutScheduler.class);

    private final PaymentEngineEventStore paymentEngineEventStore;
    private final PaymentEngineTransitions transitions;
    private final long timeoutSeconds;

    public StepUpTimeoutScheduler(PaymentEngineEventStore paymentEngineEventStore,
                                   PaymentEngineTransitions transitions,
                                   @Value("${payment-engine.step-up-timeout-seconds:60}") long timeoutSeconds) {
        this.paymentEngineEventStore = paymentEngineEventStore;
        this.transitions = transitions;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Scheduled(fixedDelay = 5000)
    public void sweep() throws Exception {
        Instant deadline = Instant.now().minusSeconds(timeoutSeconds);
        for (PaymentEngineAggregate aggregate : paymentEngineEventStore.loadAll()) {
            if (aggregate.getState() == PaymentState.AWAITING_STEP_UP && aggregate.getUpdatedAt().isBefore(deadline)) {
                log.info("Payment {} step-up confirmation timed out after {}s", aggregate.getPaymentId(), timeoutSeconds);
                transitions.failPayment(aggregate.getPaymentId(), "STEP_UP_TIMEOUT",
                        "Card step-up confirmation timed out", "card step-up timed out");
            }
        }
    }
}
