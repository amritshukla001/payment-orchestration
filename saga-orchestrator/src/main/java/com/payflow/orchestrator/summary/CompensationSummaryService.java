package com.payflow.orchestrator.summary;

import com.payflow.common.enums.PaymentState;
import com.payflow.orchestrator.domain.SagaEvent;
import com.payflow.orchestrator.domain.SagaSummary;
import com.payflow.orchestrator.repository.SagaEventRepository;
import com.payflow.orchestrator.repository.SagaSummaryRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * On-demand generation of a plain-English incident summary for a
 * COMPENSATED payment, from its own saga event log. Generated once per
 * payment and cached in payment_saga_summaries -- repeat requests (and
 * repeat dashboard clicks) never re-bill the LLM. The AI path degrades to a
 * deterministic template built from the same event log, so the endpoint
 * always answers.
 */
@Service
public class CompensationSummaryService {

    private final SagaEventRepository sagaEventRepository;
    private final SagaSummaryRepository sagaSummaryRepository;
    private final SagaTimelineFormatter timelineFormatter;
    private final ClaudeSummaryClient claudeSummaryClient;

    public CompensationSummaryService(SagaEventRepository sagaEventRepository,
                                       SagaSummaryRepository sagaSummaryRepository,
                                       SagaTimelineFormatter timelineFormatter,
                                       ClaudeSummaryClient claudeSummaryClient) {
        this.sagaEventRepository = sagaEventRepository;
        this.sagaSummaryRepository = sagaSummaryRepository;
        this.timelineFormatter = timelineFormatter;
        this.claudeSummaryClient = claudeSummaryClient;
    }

    public SagaSummary summarize(UUID paymentId) {
        SagaSummary cached = sagaSummaryRepository.findById(paymentId).orElse(null);
        if (cached != null) {
            return cached;
        }

        List<SagaEvent> events = sagaEventRepository.findByPaymentIdOrderBySequenceNumberAsc(paymentId);
        if (events.isEmpty()) {
            throw new SummaryUnavailableException(paymentId, "No saga found for payment " + paymentId, true);
        }

        SagaEvent last = events.get(events.size() - 1);
        if (!PaymentState.COMPENSATED.name().equals(last.getToState())) {
            throw new SummaryUnavailableException(paymentId,
                    "Payment " + paymentId + " is " + last.getToState()
                            + "; summaries are only generated for COMPENSATED payments", false);
        }

        String timeline = timelineFormatter.format(events);
        String aiSummary = claudeSummaryClient.summarize(timeline);

        SagaSummary summary = aiSummary != null
                ? new SagaSummary(paymentId, aiSummary, SagaSummary.Source.AI, Instant.now())
                : new SagaSummary(paymentId, deterministicSummary(events), SagaSummary.Source.DETERMINISTIC, Instant.now());
        return sagaSummaryRepository.save(summary);
    }

    private String deterministicSummary(List<SagaEvent> events) {
        SagaEvent first = events.get(0);
        SagaEvent trigger = events.stream()
                .filter(e -> PaymentState.COMPENSATING.name().equals(e.getToState()))
                .findFirst()
                .orElse(null);
        SagaEvent last = events.get(events.size() - 1);

        StringBuilder sb = new StringBuilder();
        sb.append("Payment ").append(first.getPaymentId())
                .append(" for ").append(timelineFormatter.formatAmount(first.getAmountCents(), first.getCurrency()))
                .append(" was reversed");
        if (trigger != null) {
            sb.append(" after ").append(trigger.getEventType().toLowerCase().replace('_', ' '));
        }
        sb.append(". Compensation completed at ").append(last.getOccurredAt())
                .append(" and the payer's funds were returned.");
        return sb.toString();
    }
}
