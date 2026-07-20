package com.payflow.fraudservice.rules;

import com.payflow.common.commands.CheckFraudCommand;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
@Order(2)
public class HighValueThresholdRule implements FraudRule {

    private static final long HIGH_VALUE_THRESHOLD_CENTS = 1_000_000L; // $10,000

    @Override
    public Optional<String> checkForRejection(CheckFraudCommand command) {
        if (command.amountCents() > HIGH_VALUE_THRESHOLD_CENTS) {
            return Optional.of("Amount exceeds high-value threshold ($10,000)");
        }
        return Optional.empty();
    }
}
