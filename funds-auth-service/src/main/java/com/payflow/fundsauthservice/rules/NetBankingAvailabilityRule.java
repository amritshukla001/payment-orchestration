package com.payflow.fundsauthservice.rules;

import com.payflow.common.enums.PaymentMethod;
import com.payflow.fundsauthservice.repository.BankOutageRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Only applies to NETBANKING-method payments -- mirrors compliance-service's
 * UpiDirectoryRule exactly: method-gated, presence-in-a-table means the
 * negative outcome (here, "this bank's gateway is down"), no lazy
 * auto-clearing.
 */
@Component
@Order(2)
public class NetBankingAvailabilityRule implements FundsRule {

    private final BankOutageRepository bankOutageRepository;

    public NetBankingAvailabilityRule(BankOutageRepository bankOutageRepository) {
        this.bankOutageRepository = bankOutageRepository;
    }

    @Override
    public Optional<String> checkForRejection(FundsAuthorizationContext context) {
        if (context.paymentMethod() != PaymentMethod.NETBANKING) {
            return Optional.empty();
        }
        if (bankOutageRepository.existsById(context.bankCode())) {
            return Optional.of("NetBanking gateway for " + context.bankCode() + " is currently down for maintenance");
        }
        return Optional.empty();
    }
}
