package com.payflow.fundsauthservice.rules;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** The original, only check MockBankLedger.reserve() used to do inline -- now the first rule in the engine. */
@Component
@Order(1)
public class SufficientBalanceRule implements FundsRule {

    @Override
    public Optional<String> checkForRejection(FundsAuthorizationContext context) {
        if (context.currentBalanceCents() < context.amountCents()) {
            return Optional.of("Insufficient funds: balance " + context.currentBalanceCents()
                    + " < requested " + context.amountCents());
        }
        return Optional.empty();
    }
}
