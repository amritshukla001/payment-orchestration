package com.payflow.complianceservice.rules;

import com.payflow.common.commands.CheckComplianceCommand;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Consults each registered ComplianceRule in turn and rejects on the first
 * objection -- mirrors fraud-service's FraudRuleEngine exactly. Spring
 * autowires every ComplianceRule bean into the list below.
 */
@Component
public class ComplianceRuleEngine {

    private final List<ComplianceRule> rules;

    public ComplianceRuleEngine(List<ComplianceRule> rules) {
        this.rules = rules;
    }

    public Verdict evaluate(CheckComplianceCommand command) {
        for (ComplianceRule rule : rules) {
            Optional<String> rejection = rule.checkForRejection(command);
            if (rejection.isPresent()) {
                return Verdict.reject(rejection.get());
            }
        }
        return Verdict.approve();
    }
}
