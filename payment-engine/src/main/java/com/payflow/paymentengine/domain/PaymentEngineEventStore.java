package com.payflow.paymentengine.domain;

import com.payflow.common.enums.PaymentState;
import com.payflow.paymentengine.repository.PaymentEngineEventRepository;
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
 * The only component that talks to PaymentEngineEventRepository -- everything else
 * (the listener, the controller) goes through load/loadAll/append instead
 * of touching rows directly, so the fold logic lives in exactly one place.
 */
@Component
public class PaymentEngineEventStore {

    private final PaymentEngineEventRepository repository;

    public PaymentEngineEventStore(PaymentEngineEventRepository repository) {
        this.repository = repository;
    }

    public Optional<PaymentEngineAggregate> load(UUID paymentId) {
        List<PaymentEngineEvent> events = repository.findByPaymentIdOrderBySequenceNumberAsc(paymentId);
        return events.isEmpty() ? Optional.empty() : Optional.of(PaymentEngineAggregate.replay(events));
    }

    /**
     * Full-table fold, not a DISTINCT-ON-style query -- this is the
     * aggregate's own correctness-first read path. read-model-service is
     * the actual scalable/denormalized read path the dashboard uses;
     * duplicating that optimization here would be premature.
     */
    public List<PaymentEngineAggregate> loadAll() {
        List<PaymentEngineEvent> all = repository.findAllByOrderByPaymentIdAscSequenceNumberAsc();
        Map<UUID, List<PaymentEngineEvent>> byPayment = new LinkedHashMap<>();
        for (PaymentEngineEvent event : all) {
            byPayment.computeIfAbsent(event.getPaymentId(), id -> new ArrayList<>()).add(event);
        }
        return byPayment.values().stream()
                .map(PaymentEngineAggregate::replay)
                .sorted(Comparator.comparing(PaymentEngineAggregate::getUpdatedAt).reversed())
                .toList();
    }

    public void appendInitiated(UUID paymentId, UUID payerAccount, UUID payeeAccount, long amountCents,
                                 String currency, String paymentMethod, Instant occurredAt) {
        repository.save(new PaymentEngineEvent(UUID.randomUUID(), paymentId, 0, "PAYMENT_INITIATED",
                PaymentState.INITIATED.name(), payerAccount, payeeAccount, amountCents, currency,
                paymentMethod, occurredAt));
    }

    public void append(PaymentEngineAggregate aggregate, String triggeringEventType, PaymentState toState,
                        Instant occurredAt) {
        repository.save(new PaymentEngineEvent(UUID.randomUUID(), aggregate.getPaymentId(), aggregate.nextSequenceNumber(),
                triggeringEventType, toState.name(), null, null, null, null, null, occurredAt));
    }
}
