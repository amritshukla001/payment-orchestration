package com.payflow.paymentengine.summary;

import com.payflow.paymentengine.domain.PaymentEngineEvent;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Renders a payment's event log as a compact plain-text timeline --
 * the input handed to the LLM, and the raw material for the deterministic
 * fallback summary. Pure formatting, no I/O.
 */
@Component
public class PaymentEngineTimelineFormatter {

    public String format(List<PaymentEngineEvent> events) {
        PaymentEngineEvent first = events.getFirst();
        StringBuilder sb = new StringBuilder();
        sb.append("Payment ").append(first.getPaymentId()).append('\n');
        sb.append("Amount: ").append(formatAmount(first.getAmountCents(), first.getCurrency())).append('\n');
        sb.append("Payer account: ").append(first.getPayerAccount()).append('\n');
        sb.append("Payee account: ").append(first.getPayeeAccount()).append('\n');
        sb.append("Timeline:\n");
        for (PaymentEngineEvent event : events) {
            sb.append("  ").append(event.getSequenceNumber())
                    .append(". ").append(event.getEventType())
                    .append(" -> ").append(event.getToState())
                    .append(" at ").append(event.getOccurredAt())
                    .append('\n');
        }
        return sb.toString();
    }

    public String formatAmount(long amountCents, String currency) {
        return String.format("%s %,.2f", currency, amountCents / 100.0);
    }
}
