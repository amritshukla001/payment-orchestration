package com.payflow.fundsauthservice.rules;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Mirrors ComplianceRuleEngine/FraudRuleEngine exactly: first rejection wins. */
@Component
public class FundsRuleEngine {

    private final List<FundsRule> rules;

    public FundsRuleEngine(List<FundsRule> rules) {
        this.rules = rules;
    }

    public Verdict evaluate(FundsAuthorizationContext context) {
        for (FundsRule rule : rules) {
            Optional<String> rejection = rule.checkForRejection(context);
            if (rejection.isPresent()) {
                return Verdict.reject(rejection.get());
            }
        }
        return Verdict.approve();
    }
}
