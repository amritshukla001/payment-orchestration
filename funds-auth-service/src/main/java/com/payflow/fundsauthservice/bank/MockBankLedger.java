package com.payflow.fundsauthservice.bank;

import com.payflow.fundsauthservice.domain.Account;
import com.payflow.fundsauthservice.domain.FundsReservation;
import com.payflow.fundsauthservice.repository.AccountRepository;
import com.payflow.fundsauthservice.repository.FundsReservationRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Stands in for a real bank's ledger. Accounts are provisioned on first
 * sight with a flat starting balance — there's no onboarding flow here,
 * the point is exercising the reserve/release mechanics, not realistic
 * account lifecycle.
 */
@Component
public class MockBankLedger {

    private static final long DEFAULT_STARTING_BALANCE_CENTS = 10_000_000L; // $100,000

    private final AccountRepository accountRepository;
    private final FundsReservationRepository reservationRepository;

    public MockBankLedger(AccountRepository accountRepository, FundsReservationRepository reservationRepository) {
        this.accountRepository = accountRepository;
        this.reservationRepository = reservationRepository;
    }

    public record Result(boolean authorized, String reason) {
        public static Result authorize() {
            return new Result(true, null);
        }
        public static Result deny(String reason) {
            return new Result(false, reason);
        }
    }

    public Result reserve(UUID paymentId, UUID accountId, long amountCents) {
        if (reservationRepository.existsById(paymentId)) {
            return Result.authorize(); // already reserved — safe to treat as success
        }

        Account account = accountRepository.findById(accountId)
                .orElseGet(() -> accountRepository.save(
                        new Account(accountId, DEFAULT_STARTING_BALANCE_CENTS, Instant.now())));

        if (!account.debit(amountCents)) {
            return Result.deny("Insufficient funds: balance " + account.getBalanceCents()
                    + " < requested " + amountCents);
        }

        accountRepository.save(account);
        reservationRepository.save(new FundsReservation(paymentId, accountId, amountCents, Instant.now()));
        return Result.authorize();
    }

    public void release(UUID paymentId) {
        reservationRepository.findById(paymentId).ifPresent(reservation -> {
            if (reservation.getStatus() == com.payflow.fundsauthservice.domain.ReservationStatus.RELEASED) {
                return; // already released — idempotent no-op
            }
            accountRepository.findById(reservation.getAccountId())
                    .ifPresent(account -> {
                        account.credit(reservation.getAmountCents());
                        accountRepository.save(account);
                    });
            reservation.markReleased();
            reservationRepository.save(reservation);
        });
    }
}
