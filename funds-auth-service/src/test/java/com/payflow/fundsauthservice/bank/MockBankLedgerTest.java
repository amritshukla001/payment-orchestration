package com.payflow.fundsauthservice.bank;

import com.payflow.fundsauthservice.domain.Account;
import com.payflow.fundsauthservice.domain.FundsReservation;
import com.payflow.fundsauthservice.domain.ReservationStatus;
import com.payflow.fundsauthservice.repository.AccountRepository;
import com.payflow.fundsauthservice.repository.FundsReservationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MockBankLedgerTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private FundsReservationRepository reservationRepository;

    private MockBankLedger ledgerUnderTest() {
        return new MockBankLedger(accountRepository, reservationRepository);
    }

    @Test
    void provisionsANewAccountOnFirstSightAndDebitsIt() {
        UUID paymentId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(reservationRepository.existsById(paymentId)).thenReturn(false);
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        MockBankLedger.Result result = ledgerUnderTest().reserve(paymentId, accountId, 5_000L);

        assertThat(result.authorized()).isTrue();
        verify(reservationRepository).save(any(FundsReservation.class));
    }

    @Test
    void deniesWhenBalanceIsInsufficient() {
        UUID paymentId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Account lowBalanceAccount = new Account(accountId, 100L, Instant.now());
        when(reservationRepository.existsById(paymentId)).thenReturn(false);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(lowBalanceAccount));

        MockBankLedger.Result result = ledgerUnderTest().reserve(paymentId, accountId, 5_000L);

        assertThat(result.authorized()).isFalse();
        assertThat(result.reason()).contains("Insufficient funds");
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void treatsAnAlreadyReservedPaymentAsAnIdempotentSuccess() {
        UUID paymentId = UUID.randomUUID();
        when(reservationRepository.existsById(paymentId)).thenReturn(true);

        MockBankLedger.Result result = ledgerUnderTest().reserve(paymentId, UUID.randomUUID(), 5_000L);

        assertThat(result.authorized()).isTrue();
        verifyNoInteractions(accountRepository);
    }

    @Test
    void releaseCreditsTheAccountBackAndMarksTheReservationReleased() {
        UUID paymentId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        FundsReservation reservation = new FundsReservation(paymentId, accountId, 5_000L, Instant.now());
        Account account = new Account(accountId, 95_000L, Instant.now());
        when(reservationRepository.findById(paymentId)).thenReturn(Optional.of(reservation));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        ledgerUnderTest().release(paymentId);

        assertThat(account.getBalanceCents()).isEqualTo(100_000L);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        verify(reservationRepository).save(reservation);
    }

    @Test
    void releaseIsANoOpForAnAlreadyReleasedReservation() {
        UUID paymentId = UUID.randomUUID();
        FundsReservation reservation = new FundsReservation(paymentId, UUID.randomUUID(), 5_000L, Instant.now());
        reservation.markReleased();
        when(reservationRepository.findById(paymentId)).thenReturn(Optional.of(reservation));

        ledgerUnderTest().release(paymentId);

        verifyNoInteractions(accountRepository);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void releaseIsANoOpForAnUnknownPayment() {
        when(reservationRepository.findById(any())).thenReturn(Optional.empty());

        ledgerUnderTest().release(UUID.randomUUID());

        verifyNoInteractions(accountRepository);
    }
}
