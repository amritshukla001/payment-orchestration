package com.payflow.complianceservice.rules;

import com.payflow.common.commands.CheckComplianceCommand;
import com.payflow.common.enums.PaymentMethod;
import com.payflow.complianceservice.repository.UpiRegistrationRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Only applies to UPI-method payments -- mirrors "you can only pay a
 * registered VPA". Unlike KycVerificationRule, there's no lazy
 * auto-registration: absence means not registered, since a payment
 * method-specific directory shouldn't silently admit every account.
 */
@Component
@Order(2)
public class UpiDirectoryRule implements ComplianceRule {

    private final UpiRegistrationRepository upiRegistrationRepository;

    public UpiDirectoryRule(UpiRegistrationRepository upiRegistrationRepository) {
        this.upiRegistrationRepository = upiRegistrationRepository;
    }

    @Override
    public Optional<String> checkForRejection(CheckComplianceCommand command) {
        if (command.paymentMethod() != PaymentMethod.UPI) {
            return Optional.empty();
        }
        if (upiRegistrationRepository.findById(command.payeeAccount()).isEmpty()) {
            return Optional.of("Payee account is not a registered UPI recipient");
        }
        return Optional.empty();
    }
}
