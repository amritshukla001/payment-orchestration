package com.payflow.settlementservice.risk;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * A single rule, not a Strategy-pattern rule set like fraud-service's --
 * that pattern doesn't fit here since there's only one thing to check.
 * Models a real payments scenario: the issuer clears authorization but
 * declines at capture (large-transaction risk hold, account flagged
 * between auth and settlement, etc.). Deliberately overlaps the top of
 * fraud-service's range so a payment can clear fraud and funds
 * authorization, then still get declined here -- the only way
 * compensation actually has something to undo.
 */
@Component
public class SettlementRiskCheck {

    private static final long RISK_HOLD_THRESHOLD_CENTS = 900_000L; // $9,000

    public Optional<String> checkForDecline(long amountCents) {
        if (amountCents >= RISK_HOLD_THRESHOLD_CENTS) {
            return Optional.of("Issuer declined capture: amount exceeds settlement risk threshold ($9,000)");
        }
        return Optional.empty();
    }
}
