package com.payflow.fundsauthservice.bank;

import java.util.List;
import java.util.UUID;

/**
 * Accounts in this system have no real bank-issuer field -- this stands
 * in for "which bank issued this account," deterministically and stably
 * for a given accountId, so NetBankingAvailabilityRule's demo lever
 * (mark a bank down) has something consistent to key off of.
 */
public final class BankCodeResolver {

    private static final List<String> BANKS = List.of("HDFC", "SBI", "ICICI", "AXIS", "KOTAK");

    private BankCodeResolver() {
    }

    public static String resolve(UUID accountId) {
        return BANKS.get(Math.floorMod(accountId.hashCode(), BANKS.size()));
    }

    public static List<String> allBanks() {
        return BANKS;
    }
}
