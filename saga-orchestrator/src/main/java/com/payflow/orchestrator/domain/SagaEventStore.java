package com.payflow.orchestrator.domain;

import com.payflow.common.enums.PaymentState;
import com.payflow.orchestrator.repository.SagaEventRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The only component that talks to SagaEventRepository -- everything else
 * (the listener, the controller) goes through load/loadAll/append instead
 * of touching rows directly, so the fold logic lives in exactly one place.
 */
@Component
public class SagaEventStore {

    private final SagaEventRepository repository;

    public SagaEventStore(SagaEventRepository repository) {
        this.repository = repository;
    }

    public Optional<PaymentSagaAggregate> load(UUID paymentId) {
        List<SagaEvent> events = repository.findByPaymentIdOrderBySequenceNumberAsc(paymentId);
        return events.isEmpty() ? Optional.empty() : Optional.of(PaymentSagaAggregate.replay(events));
    }

    /**
     * Full-table fold, not a DISTINCT-ON-style query -- this is the
     * aggregate's own correctness-first read path. read-model-service is
     * the actual scalable/denormalized read path the dashboard uses;
     * duplicating that optimization here would be premature.
     */
    public List<PaymentSagaAggregate> loadAll() {
        List<SagaEvent> all = repository.findAllByOrderByPaymentIdAscSequenceNumberAsc();
        Map<UUID, List<SagaEvent>> byPayment = new LinkedHashMap<>();
        for (SagaEvent event : all) {
            byPayment.computeIfAbsent(event.getPaymentId(), id -> new ArrayList<>()).add(event);
        }
        return byPayment.values().stream()
                .map(PaymentSagaAggregate::replay)
                .sorted(Comparator.comparing(PaymentSagaAggregate::getUpdatedAt).reversed())
                .toList();
    }

    public void appendInitiated(UUID paymentId, UUID payerAccount, UUID payeeAccount, long amountCents,
                                 String currency, Instant occurredAt) {
        repository.save(new SagaEvent(UUID.randomUUID(), paymentId, 0, "PAYMENT_INITIATED",
                PaymentState.INITIATED.name(), payerAccount, payeeAccount, amountCents, currency, occurredAt));
    }

    public void append(PaymentSagaAggregate aggregate, String triggeringEventType, PaymentState toState,
                        Instant occurredAt) {
        repository.save(new SagaEvent(UUID.randomUUID(), aggregate.getPaymentId(), aggregate.nextSequenceNumber(),
                triggeringEventType, toState.name(), null, null, null, null, occurredAt));
    }
}
