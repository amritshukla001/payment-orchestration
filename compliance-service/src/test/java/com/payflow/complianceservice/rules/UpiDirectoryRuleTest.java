package com.payflow.complianceservice.rules;

import com.payflow.common.commands.CheckComplianceCommand;
import com.payflow.common.enums.PaymentMethod;
import com.payflow.complianceservice.domain.UpiRegistration;
import com.payflow.complianceservice.repository.UpiRegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpiDirectoryRuleTest {

    @Mock
    private UpiRegistrationRepository upiRegistrationRepository;

    private UpiDirectoryRule rule;

    @BeforeEach
    void setUp() {
        rule = new UpiDirectoryRule(upiRegistrationRepository);
    }

    @Test
    void ignoresNonUpiPaymentsEntirely() {
        CheckComplianceCommand command = commandFor(PaymentMethod.NETBANKING, UUID.randomUUID());

        Optional<String> result = rule.checkForRejection(command);

        assertThat(result).isEmpty();
        verifyNoInteractions(upiRegistrationRepository);
    }

    @Test
    void rejectsAUpiPaymentToAnUnregisteredPayee() {
        UUID payeeAccount = UUID.randomUUID();
        when(upiRegistrationRepository.findById(payeeAccount)).thenReturn(Optional.empty());

        Optional<String> result = rule.checkForRejection(commandFor(PaymentMethod.UPI, payeeAccount));

        assertThat(result).contains("Payee account is not a registered UPI recipient");
    }

    @Test
    void approvesAUpiPaymentToARegisteredPayee() {
        UUID payeeAccount = UUID.randomUUID();
        when(upiRegistrationRepository.findById(payeeAccount))
                .thenReturn(Optional.of(new UpiRegistration(payeeAccount, Instant.now())));

        Optional<String> result = rule.checkForRejection(commandFor(PaymentMethod.UPI, payeeAccount));

        assertThat(result).isEmpty();
    }

    private CheckComplianceCommand commandFor(PaymentMethod paymentMethod, UUID payeeAccount) {
        return new CheckComplianceCommand(UUID.randomUUID(), UUID.randomUUID(), payeeAccount,
                5_000L, "USD", paymentMethod, Instant.now());
    }
}
