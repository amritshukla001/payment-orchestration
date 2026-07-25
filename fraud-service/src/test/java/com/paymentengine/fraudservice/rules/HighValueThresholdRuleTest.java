package com.payflow.fraudservice.rules;

import com.payflow.common.commands.CheckFraudCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HighValueThresholdRuleTest {

    private final HighValueThresholdRule rule = new HighValueThresholdRule();

    @Test
    void allowsAmountAtExactlyTheThreshold() {
        Optional<String> result = rule.checkForRejection(commandFor(1_000_000L));

        assertThat(result).isEmpty();
    }

    @Test
    void rejectsAmountOneCentOverTheThreshold() {
        Optional<String> result = rule.checkForRejection(commandFor(1_000_001L));

        assertThat(result).isPresent();
        assertThat(result.get()).contains("high-value threshold");
    }

    @Test
    void allowsAnOrdinaryAmount() {
        Optional<String> result = rule.checkForRejection(commandFor(6_000L));

        assertThat(result).isEmpty();
    }

    private CheckFraudCommand commandFor(long amountCents) {
        return new CheckFraudCommand(UUID.randomUUID(), UUID.randomUUID(), amountCents, "USD", Instant.now());
    }
}
