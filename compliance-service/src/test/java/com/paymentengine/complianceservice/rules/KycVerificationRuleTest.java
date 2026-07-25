package com.paymentengine.complianceservice.rules;

import com.paymentengine.common.commands.CheckComplianceCommand;
import com.paymentengine.common.enums.PaymentMethod;
import com.paymentengine.complianceservice.domain.KycRecord;
import com.paymentengine.complianceservice.repository.KycRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KycVerificationRuleTest {

    @Mock
    private KycRecordRepository kycRecordRepository;

    private KycVerificationRule rule;

    @BeforeEach
    void setUp() {
        rule = new KycVerificationRule(kycRecordRepository);
    }

    @Test
    void approvesWhenBothAccountsAreAlreadyVerified() {
        UUID payerAccount = UUID.randomUUID();
        UUID payeeAccount = UUID.randomUUID();
        when(kycRecordRepository.findById(payerAccount))
                .thenReturn(Optional.of(new KycRecord(payerAccount, true, Instant.now())));
        when(kycRecordRepository.findById(payeeAccount))
                .thenReturn(Optional.of(new KycRecord(payeeAccount, true, Instant.now())));

        Optional<String> result = rule.checkForRejection(commandFor(payerAccount, payeeAccount));

        assertThat(result).isEmpty();
    }

    @Test
    void lazilyProvisionsAnUnseenAccountAsVerifiedAndApproves() {
        UUID payerAccount = UUID.randomUUID();
        UUID payeeAccount = UUID.randomUUID();
        when(kycRecordRepository.findById(payerAccount)).thenReturn(Optional.empty());
        when(kycRecordRepository.findById(payeeAccount)).thenReturn(Optional.empty());
        when(kycRecordRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        Optional<String> result = rule.checkForRejection(commandFor(payerAccount, payeeAccount));

        assertThat(result).isEmpty();
        ArgumentCaptor<KycRecord> captor = ArgumentCaptor.forClass(KycRecord.class);
        verify(kycRecordRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allMatch(KycRecord::isVerified);
    }

    @Test
    void rejectsWhenThePayerIsFlaggedUnverified() {
        UUID payerAccount = UUID.randomUUID();
        UUID payeeAccount = UUID.randomUUID();
        when(kycRecordRepository.findById(payerAccount))
                .thenReturn(Optional.of(new KycRecord(payerAccount, false, Instant.now())));

        Optional<String> result = rule.checkForRejection(commandFor(payerAccount, payeeAccount));

        assertThat(result).contains("Payer account is not KYC-verified");
    }

    @Test
    void rejectsWhenThePayeeIsFlaggedUnverified() {
        UUID payerAccount = UUID.randomUUID();
        UUID payeeAccount = UUID.randomUUID();
        when(kycRecordRepository.findById(payerAccount))
                .thenReturn(Optional.of(new KycRecord(payerAccount, true, Instant.now())));
        when(kycRecordRepository.findById(payeeAccount))
                .thenReturn(Optional.of(new KycRecord(payeeAccount, false, Instant.now())));

        Optional<String> result = rule.checkForRejection(commandFor(payerAccount, payeeAccount));

        assertThat(result).contains("Payee account is not KYC-verified");
    }

    private CheckComplianceCommand commandFor(UUID payerAccount, UUID payeeAccount) {
        return new CheckComplianceCommand(UUID.randomUUID(), payerAccount, payeeAccount,
                5_000L, "USD", PaymentMethod.NETBANKING, Instant.now());
    }
}
