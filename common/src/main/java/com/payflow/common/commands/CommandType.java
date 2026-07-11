package com.payflow.common.commands;

/**
 * Commands issued by the saga orchestrator onto payment.commands.
 * Only CHECK_FRAUD has a consumer so far; the rest are the contract
 * later phases (funds-auth, ledger, settlement) will implement.
 */
public enum CommandType {
    CHECK_FRAUD,
    AUTHORIZE_FUNDS,
    POST_LEDGER,
    SETTLE,
    RELEASE_FUNDS
}
