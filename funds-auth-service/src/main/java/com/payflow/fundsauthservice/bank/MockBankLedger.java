package com.payflow.fundsauthservice.bank;

import com.payflow.fundsauthservice.domain.Account;
import com.payflow.fundsauthservice.domain.FundsReservation;
import com.payflow.fundsauthservice.repository.AccountRepository;
import com.payflow.fundsauthservice.repository.FundsReservationRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
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

    private static final Logger log = LoggerFactory.getLogger(MockBankLedger.class);
    private static final String BALANCE_CACHE_NAME = "accountBalances";
    private static final long DEFAULT_STARTING_BALANCE_CENTS = 10_000_000L; // $100,000

    private final AccountRepository accountRepository;
    private final FundsReservationRepository reservationRepository;
    private final CacheManager cacheManager;

    public MockBankLedger(AccountRepository accountRepository, FundsReservationRepository reservationRepository,
                           CacheManager cacheManager) {
        this.accountRepository = accountRepository;
        this.reservationRepository = reservationRepository;
        this.cacheManager = cacheManager;
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
        evictBalanceCache(accountId);
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
            evictBalanceCache(reservation.getAccountId());
        });
    }

    /**
     * A read-only, cache-aside view of an account's balance -- not used by
     * reserve()/release() themselves, which always read Postgres directly
     * since they're the read-modify-write that actually moves money. This
     * is for callers that tolerate a briefly stale value (e.g. a fraud
     * velocity check) in exchange for not hitting Postgres on every read.
     * @CircuitBreaker wraps outside @Cacheable (Resilience4j's default
     * aspect order is more outer than Spring's cache aspect, and Spring's
     * default CacheErrorHandler rethrows rather than swallows) -- verified
     * empirically before wiring this up: a Redis outage makes the cache
     * lookup throw, which the circuit breaker catches and redirects to the
     * fallback below, instead of propagating out to the caller.
     */
    @Cacheable(cacheNames = BALANCE_CACHE_NAME, key = "#accountId")
    @CircuitBreaker(name = "account-balance-cache", fallbackMethod = "getBalanceFallback")
    public long getBalance(UUID accountId) {
        return readBalanceFromDb(accountId);
    }

    private long getBalanceFallback(UUID accountId, Throwable t) {
        log.warn("Redis unavailable, reading account {} balance directly from Postgres", accountId, t);
        return readBalanceFromDb(accountId);
    }

    private long readBalanceFromDb(UUID accountId) {
        return accountRepository.findById(accountId)
                .map(Account::getBalanceCents)
                .orElse(DEFAULT_STARTING_BALANCE_CENTS);
    }

    /**
     * Best-effort, deliberately not @CacheEvict: reserve()/release() carry
     * the correctness-critical DB write inside the caller's @Transactional
     * scope, and an annotation-based evict failure would propagate into
     * that scope -- meaning a Redis outage could break fund reservation
     * itself, the exact single point of failure a cache is supposed to
     * avoid. A Redis outage here just means getBalance() may serve a
     * stale value until the next successful write's eviction succeeds or
     * Redis recovers -- acceptable since reserve()/release() never read
     * from the cache themselves.
     */
    private void evictBalanceCache(UUID accountId) {
        try {
            Cache cache = cacheManager.getCache(BALANCE_CACHE_NAME);
            if (cache != null) {
                cache.evict(accountId);
            }
        } catch (DataAccessException e) {
            log.warn("Failed to evict cached balance for account {} -- cache may serve a stale value "
                    + "until the next successful write", accountId, e);
        }
    }
}
