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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.RedisConnectionFailureException;

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

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    private MockBankLedger ledgerUnderTest() {
        return new MockBankLedger(accountRepository, reservationRepository, cacheManager);
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

    @Test
    void reserveEvictsTheCachedBalanceAfterASuccessfulDebit() {
        UUID paymentId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(reservationRepository.existsById(paymentId)).thenReturn(false);
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cacheManager.getCache("accountBalances")).thenReturn(cache);

        ledgerUnderTest().reserve(paymentId, accountId, 5_000L);

        verify(cache).evict(accountId);
    }

    @Test
    void releaseEvictsTheCachedBalanceAfterASuccessfulCredit() {
        UUID paymentId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        FundsReservation reservation = new FundsReservation(paymentId, accountId, 5_000L, Instant.now());
        Account account = new Account(accountId, 95_000L, Instant.now());
        when(reservationRepository.findById(paymentId)).thenReturn(Optional.of(reservation));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(cacheManager.getCache("accountBalances")).thenReturn(cache);

        ledgerUnderTest().release(paymentId);

        verify(cache).evict(accountId);
    }

    @Test
    void reserveSucceedsEvenWhenCacheEvictionFails() {
        // A down Redis must never break fund reservation -- the cache is a
        // best-effort side channel, not something reserve() depends on.
        UUID paymentId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(reservationRepository.existsById(paymentId)).thenReturn(false);
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cacheManager.getCache("accountBalances"))
                .thenThrow(new RedisConnectionFailureException("redis is down"));

        MockBankLedger.Result result = ledgerUnderTest().reserve(paymentId, accountId, 5_000L);

        assertThat(result.authorized()).isTrue();
        verify(reservationRepository).save(any(FundsReservation.class));
    }

    @Test
    void getBalanceReturnsTheAccountsCurrentBalanceWhenItExists() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(new Account(accountId, 42_000L, Instant.now())));

        long balance = ledgerUnderTest().getBalance(accountId);

        assertThat(balance).isEqualTo(42_000L);
    }

    @Test
    void getBalanceReturnsTheDefaultStartingBalanceWhenAccountDoesNotExist() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        long balance = ledgerUnderTest().getBalance(accountId);

        assertThat(balance).isEqualTo(10_000_000L);
    }
}
