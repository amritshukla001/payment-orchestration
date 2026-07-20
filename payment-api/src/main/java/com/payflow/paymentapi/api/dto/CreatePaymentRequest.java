package com.payflow.paymentapi.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreatePaymentRequest(
        @Schema(description = "Account being debited") @NotNull UUID payerAccount,
        @Schema(description = "Account being credited") @NotNull UUID payeeAccount,
        @Schema(description = "Amount in minor units, e.g. cents", example = "2500") @Positive long amountCents,
        @Schema(description = "ISO 4217 currency code", example = "USD") @NotNull @Size(min = 3, max = 3) String currency
) {
}
