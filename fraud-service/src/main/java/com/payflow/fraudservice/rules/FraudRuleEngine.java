package com.payflow.fraudservice.rules;

import com.payflow.common.commands.CheckFraudCommand;
import org.springframework.stereotype.Component;

/**
 * Deliberately simple rule set — a real fraud engine would score velocity,
 * device fingerprint, and blocklists. The point here is the pipeline
 * (command in, verdict out), not sophisticated fraud modeling.
 */
@Component
public class FraudRuleEngine {

    private static final long HIGH_VALUE_THRESHOLD_CENTS = 1_000_000L; // $10,000

    public Verdict evaluate(CheckFraudCommand command) {
        if (command.amountCents() > HIGH_VALUE_THRESHOLD_CENTS) {
            return Verdict.reject("Amount exceeds high-value threshold ($10,000)");
        }
        if (command.amountCents() <= 0) {
            return Verdict.reject("Non-positive amount");
        }
        return Verdict.approve();
    }

    public record Verdict(boolean approved, String reason) {
        public static Verdict approve() {
            return new Verdict(true, null);
        }
        public static Verdict reject(String reason) {
            return new Verdict(false, reason);
        }
    }
}
