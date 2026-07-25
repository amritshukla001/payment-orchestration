package com.paymentengine.readmodelservice.api.dto;

import java.util.List;

/**
 * One response bundling the payment view with its full ledger and
 * notification history -- the dashboard's detail drawer used to need two
 * separate calls (ledger + notifications) on top of the list; this is
 * the actual point of collapsing three services' read APIs into one.
 */
public record PaymentDetailResponse(
        PaymentViewResponse payment,
        List<LedgerEntryViewResponse> ledgerEntries,
        List<NotificationViewResponse> notifications
) {
}
