package com.payflow.fundsauthservice.rules;

import com.payflow.common.enums.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FundsRuleEngineTest {

    private final FundsAuthorizationContext context =
            new FundsAuthorizationContext(UUID.randomUUID(), 5_000L, 100_000L, PaymentMethod.CARD, "HDFC");

    @Test
    void approvesWhenNoRuleObjects() {
        FundsRuleEngine engine = new FundsRuleEngine(List.of(ctx -> Optional.empty(), ctx -> Optional.empty()));

        Verdict verdict = engine.evaluate(context);

        assertThat(verdict.approved()).isTrue();
    }

    @Test
    void returnsTheFirstRejectionAndSkipsLaterRules() {
        FundsRule alwaysRejects = ctx -> Optional.of("first reason");
        FundsRule neverEvaluated = ctx -> {
            throw new AssertionError("should not be reached once an earlier rule rejects");
        };
        FundsRuleEngine engine = new FundsRuleEngine(List.of(alwaysRejects, neverEvaluated));

        Verdict verdict = engine.evaluate(context);

        assertThat(verdict.approved()).isFalse();
        assertThat(verdict.reason()).isEqualTo("first reason");
    }
}
