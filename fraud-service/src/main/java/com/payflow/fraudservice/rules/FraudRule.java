package com.payflow.fraudservice.rules;

import com.payflow.common.commands.CheckFraudCommand;
import java.util.Optional;

/**
 * A single, independent risk check. Each implementation is a strategy the
 * engine consults in turn — adding a new rule (velocity, blocklist,
 * device fingerprint) means adding a new implementation, not touching
 * the engine or any existing rule.
 */
public interface FraudRule {
    /**
     * @return empty if this rule has no objection, or a rejection reason if it does.
     */
    Optional<String> checkForRejection(CheckFraudCommand command);
}
