package com.payflow.fraudservice.rules;

import com.payflow.common.commands.CheckFraudCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the engine's own contract (consult rules in order, stop at the
 * first rejection) using fake rules — not HighValueThresholdRule or
 * PositiveAmountRule, which have their own dedicated tests. This is what
 * proves the Strategy pattern actually works: swapping in different
 * FraudRule implementations changes behavior without touching the engine.
 */
class FraudRuleEngineTest {

    private final CheckFraudCommand anyCommand =
            new CheckFraudCommand(UUID.randomUUID(), UUID.randomUUID(), 100L, "USD", Instant.now());

    @Test
    void approvesWhenNoRuleObjects() {
        FraudRuleEngine engine = new FraudRuleEngine(List.of(alwaysApprove(), alwaysApprove()));

        Verdict verdict = engine.evaluate(anyCommand);

        assertThat(verdict.approved()).isTrue();
    }

    @Test
    void rejectsOnTheFirstRuleThatObjects() {
        FraudRuleEngine engine = new FraudRuleEngine(List.of(
                alwaysApprove(),
                alwaysReject("first objection"),
                alwaysReject("never reached")
        ));

        Verdict verdict = engine.evaluate(anyCommand);

        assertThat(verdict.approved()).isFalse();
        assertThat(verdict.reason()).isEqualTo("first objection");
    }

    @Test
    void approvesWhenThereAreNoRulesAtAll() {
        FraudRuleEngine engine = new FraudRuleEngine(List.of());

        assertThat(engine.evaluate(anyCommand).approved()).isTrue();
    }

    private FraudRule alwaysApprove() {
        return command -> Optional.empty();
    }

    private FraudRule alwaysReject(String reason) {
        return command -> Optional.of(reason);
    }
}
