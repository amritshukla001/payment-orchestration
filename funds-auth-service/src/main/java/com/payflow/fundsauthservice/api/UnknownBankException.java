package com.payflow.fundsauthservice.api;

import com.payflow.fundsauthservice.bank.BankCodeResolver;

public class UnknownBankException extends RuntimeException {
    public UnknownBankException(String bankCode) {
        super("Unknown bank code '" + bankCode + "' -- expected one of " + BankCodeResolver.allBanks());
    }
}
