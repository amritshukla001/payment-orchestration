package com.payflow.fundsauthservice.rules;

import com.payflow.common.enums.PaymentMethod;
import com.payflow.fundsauthservice.repository.BankOutageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NetBankingAvailabilityRuleTest {

    @Mock
    private BankOutageRepository bankOutageRepository;

    private NetBankingAvailabilityRule rule;

    @BeforeEach
    void setUp() {
        rule = new NetBankingAvailabilityRule(bankOutageRepository);
    }

    @Test
    void ignoresNonNetBankingPaymentsEntirely() {
        Optional<String> result = rule.checkForRejection(contextFor(PaymentMethod.CARD, "HDFC"));

        assertThat(result).isEmpty();
        verifyNoInteractions(bankOutageRepository);
    }

    @Test
    void rejectsNetBankingWhenTheResolvedBankIsMarkedDown() {
        when(bankOutageRepository.existsById("HDFC")).thenReturn(true);

        Optional<String> result = rule.checkForRejection(contextFor(PaymentMethod.NETBANKING, "HDFC"));

        assertThat(result).contains("NetBanking gateway for HDFC is currently down for maintenance");
    }

    @Test
    void approvesNetBankingWhenTheResolvedBankIsUp() {
        when(bankOutageRepository.existsById("HDFC")).thenReturn(false);

        Optional<String> result = rule.checkForRejection(contextFor(PaymentMethod.NETBANKING, "HDFC"));

        assertThat(result).isEmpty();
    }

    private FundsAuthorizationContext contextFor(PaymentMethod method, String bankCode) {
        return new FundsAuthorizationContext(UUID.randomUUID(), 5_000L, 100_000L, method, bankCode);
    }
}
