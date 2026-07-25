package com.payflow.ledgerservice.ledger;

import com.payflow.ledgerservice.domain.LedgerEntry;
import com.payflow.ledgerservice.domain.PostingType;
import com.payflow.ledgerservice.repository.LedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        when(ledgerEntryRepository.findByPaymentIdAndPostingType(paymentId, PostingType.HOLD))
                .thenReturn(Optional.empty());
        when(ledgerEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LedgerEntry result = ledger().postHold(paymentId, payerAccount, 4_500L);

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(captor.capture());
        LedgerEntry saved = captor.getValue();
        assertThat(saved.getPaymentId()).isEqualTo(paymentId);
        assertThat(saved.getDebitAccount()).isEqualTo(payerAccount);
        assertThat(saved.getCreditAccount()).isEqualTo(DoubleEntryLedger.SUSPENSE_ACCOUNT);
        assertThat(saved.getAmountCents()).isEqualTo(4_500L);
        assertThat(saved.getPostingType()).isEqualTo(PostingType.HOLD);
        assertThat(result).isSameAs(saved);
    }

    @Test
    void isIdempotentAgainstARedeliveredCommandAndReturnsTheExistingEntry() {
        UUID paymentId = UUID.randomUUID();
        LedgerEntry existing = new LedgerEntry(UUID.randomUUID(), paymentId, UUID.randomUUID(),
                DoubleEntryLedger.SUSPENSE_ACCOUNT, 4_500L, PostingType.HOLD, Instant.now());
        when(ledgerEntryRepository.findByPaymentIdAndPostingType(paymentId, PostingType.HOLD))
                .thenReturn(Optional.of(existing));

        LedgerEntry result = ledger().postHold(paymentId, UUID.randomUUID(), 4_500L);

        verify(ledgerEntryRepository, never()).save(any());
        assertThat(result).isSameAs(existing);
    }

    @Test
    void postsAFinalEntryDebitingTheSuspenseAccountAndCreditingThePayee() {
        UUID paymentId = UUID.randomUUID();
        UUID payeeAccount = UUID.randomUUID();
        when(ledgerEntryRepository.findByPaymentIdAndPostingType(paymentId, PostingType.FINAL))
                .thenReturn(Optional.empty());
        when(ledgerEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

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
        LedgerEntry existing = new LedgerEntry(UUID.randomUUID(), paymentId, DoubleEntryLedger.SUSPENSE_ACCOUNT,
                UUID.randomUUID(), 4_500L, PostingType.FINAL, Instant.now());
        when(ledgerEntryRepository.findByPaymentIdAndPostingType(paymentId, PostingType.FINAL))
                .thenReturn(Optional.of(existing));

        ledger().postFinal(paymentId, UUID.randomUUID(), 4_500L);

        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void reverseHoldPostsAnOffsettingEntryDebitingSuspenseAndCreditingThePayer() {
        UUID paymentId = UUID.randomUUID();
        UUID payerAccount = UUID.randomUUID();
        when(ledgerEntryRepository.findByPaymentIdAndPostingType(paymentId, PostingType.REVERSAL))
                .thenReturn(Optional.empty());
        when(ledgerEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ledger().reverseHold(paymentId, payerAccount, 950_000L);

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(captor.capture());
        LedgerEntry saved = captor.getValue();
        assertThat(saved.getDebitAccount()).isEqualTo(DoubleEntryLedger.SUSPENSE_ACCOUNT);
        assertThat(saved.getCreditAccount()).isEqualTo(payerAccount);
        assertThat(saved.getAmountCents()).isEqualTo(950_000L);
        assertThat(saved.getPostingType()).isEqualTo(PostingType.REVERSAL);
    }

    @Test
    void reverseHoldIsIdempotentAgainstARedeliveredCommand() {
        UUID paymentId = UUID.randomUUID();
        LedgerEntry existing = new LedgerEntry(UUID.randomUUID(), paymentId, DoubleEntryLedger.SUSPENSE_ACCOUNT,
                UUID.randomUUID(), 950_000L, PostingType.REVERSAL, Instant.now());
        when(ledgerEntryRepository.findByPaymentIdAndPostingType(paymentId, PostingType.REVERSAL))
                .thenReturn(Optional.of(existing));

        ledger().reverseHold(paymentId, UUID.randomUUID(), 950_000L);

        verify(ledgerEntryRepository, never()).save(any());
    }
}
