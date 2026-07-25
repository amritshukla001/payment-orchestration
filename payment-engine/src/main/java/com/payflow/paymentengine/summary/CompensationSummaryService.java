package com.payflow.paymentengine.summary;

import com.payflow.common.enums.PaymentState;
import com.payflow.paymentengine.domain.PaymentEngineEvent;
import com.payflow.paymentengine.domain.PaymentEngineSummary;
import com.payflow.paymentengine.repository.PaymentEngineEventRepository;
import com.payflow.paymentengine.repository.PaymentEngineSummaryRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * On-demand generation of a plain-English incident summary for a
 * COMPENSATED payment, from its own event log. Generated once per
 * payment and cached in payment_saga_summaries -- repeat requests (and
 * repeat dashboard clicks) never re-bill the LLM. The AI path degrades to a
 * deterministic template built from the same event log, so the endpoint
 * always answers.
 */
@Service
public class CompensationSummaryService {

    private final PaymentEngineEventRepository paymentEngineEventRepository;
    private final PaymentEngineSummaryRepository paymentEngineSummaryRepository;
    private final PaymentEngineTimelineFormatter timelineFormatter;
    private final GeminiSummaryClient geminiSummaryClient;

    public CompensationSummaryService(PaymentEngineEventRepository paymentEngineEventRepository,
                                       PaymentEngineSummaryRepository paymentEngineSummaryRepository,
                                       PaymentEngineTimelineFormatter timelineFormatter,
                                       GeminiSummaryClient geminiSummaryClient) {
        this.paymentEngineEventRepository = paymentEngineEventRepository;
        this.paymentEngineSummaryRepository = paymentEngineSummaryRepository;
        this.timelineFormatter = timelineFormatter;
        this.geminiSummaryClient = geminiSummaryClient;
    }

    public PaymentEngineSummary summarize(UUID paymentId) {
        PaymentEngineSummary cached = paymentEngineSummaryRepository.findById(paymentId).orElse(null);
        if (cached != null) {
            return cached;
        }

        List<PaymentEngineEvent> events = paymentEngineEventRepository.findByPaymentIdOrderBySequenceNumberAsc(paymentId);
        if (events.isEmpty()) {
            throw new SummaryUnavailableException(paymentId, "No payment found for " + paymentId, true);
        }

        PaymentEngineEvent last = events.getLast();
        if (!PaymentState.COMPENSATED.name().equals(last.getToState())) {
            throw new SummaryUnavailableException(paymentId,
                    "Payment " + paymentId + " is " + last.getToState()
                            + "; summaries are only generated for COMPENSATED payments", false);
        }

        String timeline = timelineFormatter.format(events);
        String aiSummary = geminiSummaryClient.summarize(timeline);

        PaymentEngineSummary summary = aiSummary != null
                ? new PaymentEngineSummary(paymentId, aiSummary, PaymentEngineSummary.Source.AI, Instant.now())
                : new PaymentEngineSummary(paymentId, deterministicSummary(events), PaymentEngineSummary.Source.DETERMINISTIC, Instant.now());
        return paymentEngineSummaryRepository.save(summary);
    }

    private String deterministicSummary(List<PaymentEngineEvent> events) {
        PaymentEngineEvent first = events.getFirst();
        PaymentEngineEvent trigger = events.stream()
                .filter(e -> PaymentState.COMPENSATING.name().equals(e.getToState()))
                .findFirst()
                .orElse(null);
        PaymentEngineEvent last = events.getLast();

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
