package com.payflow.fundsauthservice.rules;

import java.util.Optional;

public interface FundsRule {
    /** @return empty if this rule has no objection, or a denial reason if it does. */
    Optional<String> checkForRejection(FundsAuthorizationContext context);
}
