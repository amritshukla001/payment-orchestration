package com.payflow.complianceservice.rules;

import com.payflow.common.commands.CheckComplianceCommand;
import java.util.Optional;

/**
 * A single, independent compliance check. Each implementation is a
 * strategy the engine consults in turn -- mirrors fraud-service's
 * FraudRule exactly.
 */
public interface ComplianceRule {
    /**
     * @return empty if this rule has no objection, or a rejection reason if it does.
     */
    Optional<String> checkForRejection(CheckComplianceCommand command);
}
