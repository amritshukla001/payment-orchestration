package com.payflow.paymentapi.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreatePaymentRequest(
        @NotNull UUID payerAccount,
        @NotNull UUID payeeAccount,
        @Positive long amountCents,
        @NotNull @Size(min = 3, max = 3) String currency
) {
}
