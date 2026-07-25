package com.payflow.fraudservice.rules;

import com.payflow.common.commands.CheckFraudCommand;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Consults each registered FraudRule in turn and rejects on the first
 * objection. Spring autowires every FraudRule bean into the list below —
 * adding a rule to the strategy set means writing a new @Component, not
 * editing this class.
 */
@Component
public class FraudRuleEngine {

    private final List<FraudRule> rules;

    public FraudRuleEngine(List<FraudRule> rules) {
        this.rules = rules;
    }

    public Verdict evaluate(CheckFraudCommand command) {
        for (FraudRule rule : rules) {
            Optional<String> rejection = rule.checkForRejection(command);
            if (rejection.isPresent()) {
                return Verdict.reject(rejection.get());
            }
        }
        return Verdict.approve();
    }
}
