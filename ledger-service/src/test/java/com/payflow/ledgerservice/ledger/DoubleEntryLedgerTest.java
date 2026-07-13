package com.payflow.ledgerservice.ledger;

import com.payflow.ledgerservice.domain.LedgerEntry;
import com.payflow.ledgerservice.domain.PostingType;
import com.payflow.ledgerservice.repository.LedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoubleEntryLedgerTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    private DoubleEntryLedger ledger() {
        return new DoubleEntryLedger(ledgerEntryRepository);
    }

    @Test
    void postsAHoldEntryDebitingThePayerAndCreditingTheSuspenseAccount() {
        UUID paymentId = UUID.randomUUID();
        UUID payerAccount = UUID.randomUUID();
        when(ledgerEntryRepository.existsByPaymentIdAndPostingType(paymentId, PostingType.HOLD)).thenReturn(false);

        ledger().postHold(paymentId, payerAccount, 4_500L);

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(captor.capture());
        LedgerEntry saved = captor.getValue();
        assertThat(saved.getPaymentId()).isEqualTo(paymentId);
        assertThat(saved.getDebitAccount()).isEqualTo(payerAccount);
        assertThat(saved.getCreditAccount()).isEqualTo(DoubleEntryLedger.SUSPENSE_ACCOUNT);
        assertThat(saved.getAmountCents()).isEqualTo(4_500L);
        assertThat(saved.getPostingType()).isEqualTo(PostingType.HOLD);
    }

    @Test
    void isIdempotentAgainstARedeliveredCommand() {
        UUID paymentId = UUID.randomUUID();
        when(ledgerEntryRepository.existsByPaymentIdAndPostingType(paymentId, PostingType.HOLD)).thenReturn(true);

        ledger().postHold(paymentId, UUID.randomUUID(), 4_500L);

        verify(ledgerEntryRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void postsAFinalEntryDebitingTheSuspenseAccountAndCreditingThePayee() {
        UUID paymentId = UUID.randomUUID();
        UUID payeeAccount = UUID.randomUUID();
        when(ledgerEntryRepository.existsByPaymentIdAndPostingType(paymentId, PostingType.FINAL)).thenReturn(false);

        ledger().postFinal(paymentId, payeeAccount, 4_500L);

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(captor.capture());
        LedgerEntry saved = captor.getValue();
        assertThat(saved.getDebitAccount()).isEqualTo(DoubleEntryLedger.SUSPENSE_ACCOUNT);
        assertThat(saved.getCreditAccount()).isEqualTo(payeeAccount);
        assertThat(saved.getPostingType()).isEqualTo(PostingType.FINAL);
    }

    @Test
    void postFinalIsIdempotentAgainstARedeliveredCommand() {
        UUID paymentId = UUID.randomUUID();
        when(ledgerEntryRepository.existsByPaymentIdAndPostingType(paymentId, PostingType.FINAL)).thenReturn(true);

        ledger().postFinal(paymentId, UUID.randomUUID(), 4_500L);

        verify(ledgerEntryRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
