package com.payflow.paymentengine.domain;

import com.payflow.common.commands.AuthorizeFundsCommand;
import com.payflow.common.enums.PaymentMethod;
import com.payflow.common.enums.PaymentState;
import com.payflow.common.events.PaymentEventType;
import com.payflow.common.events.PaymentFailedEvent;
import com.payflow.common.events.StepUpRequiredEvent;
import com.payflow.paymentengine.kafka.PaymentEnginePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Saga transitions that more than one call site needs to trigger --
 * originally private methods on PaymentEventListener (the Kafka-driven
 * happy/unhappy path), now also invoked from StepUpController (a REST
 * callback) and StepUpTimeoutScheduler (a timer), neither of which has an
 * incoming EventEnvelope to react to.
 */
@Component
public class PaymentEngineTransitions {

    private static final Logger log = LoggerFactory.getLogger(PaymentEngineTransitions.class);

    private final PaymentEngineEventStore paymentEngineEventStore;
    private final PaymentEnginePublisher publisher;

    public PaymentEngineTransitions(PaymentEngineEventStore paymentEngineEventStore, PaymentEnginePublisher publisher) {
        this.paymentEngineEventStore = paymentEngineEventStore;
        this.publisher = publisher;
    }

    public void failPayment(UUID paymentId, String triggerEventType, String reason, String logLabel) throws Exception {
        PaymentEngineAggregate aggregate = paymentEngineEventStore.load(paymentId).orElse(null);
        if (aggregate == null) {
            log.warn("Received a failure signal for unknown payment {}", paymentId);
            return;
        }

        paymentEngineEventStore.append(aggregate, triggerEventType, PaymentState.FAILED, Instant.now());
        log.info("Payment {} FAILED — {}: {}", paymentId, logLabel, reason);

        publisher.publishEvent(paymentId, PaymentEventType.PAYMENT_FAILED,
                new PaymentFailedEvent(paymentId, aggregate.getPayerAccount(), reason, Instant.now()));
    }

    /** CARD-only pause after fraud approval: park the saga in AWAITING_STEP_UP instead of authorizing funds. */
    public void requireStepUp(PaymentEngineAggregate aggregate) throws Exception {
        UUID paymentId = aggregate.getPaymentId();
        paymentEngineEventStore.append(aggregate, "STEP_UP_REQUIRED", PaymentState.AWAITING_STEP_UP, Instant.now());
        publisher.publishEvent(paymentId, PaymentEventType.STEP_UP_REQUIRED,
                new StepUpRequiredEvent(paymentId, Instant.now()));
        log.info("Payment {} FRAUD_CHECKED -> AWAITING_STEP_UP (card requires step-up confirmation)", paymentId);
    }

    /**
     * Resumes a CARD payment after the customer approves the step-up.
     * No new PaymentEngineEvent row here -- the next real transition to
     * AUTHORIZED still comes from FUNDS_AUTHORIZED via Kafka, same as any
     * other payment method.
     */
    public void resumeAfterStepUp(PaymentEngineAggregate aggregate) throws Exception {
        UUID paymentId = aggregate.getPaymentId();
        AuthorizeFundsCommand command = new AuthorizeFundsCommand(
                paymentId, aggregate.getPayerAccount(), aggregate.getAmountCents(), aggregate.getCurrency(),
                PaymentMethod.CARD, Instant.now());
        publisher.publishCommand(paymentId, "AUTHORIZE_FUNDS", command);
        log.info("Payment {} step-up confirmed -> issued AUTHORIZE_FUNDS", paymentId);
    }
}
