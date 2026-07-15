package com.payflow.settlementservice.risk;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementRiskCheckTest {

    private final SettlementRiskCheck check = new SettlementRiskCheck();

    @Test
    void allowsAnOrdinaryAmount() {
        assertThat(check.checkForDecline(6_500L)).isEmpty();
    }

    @Test
    void declinesAtExactlyTheThreshold() {
        Optional<String> result = check.checkForDecline(900_000L);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("settlement risk threshold");
    }

    @Test
    void allowsOneCentUnderTheThreshold() {
        assertThat(check.checkForDecline(899_999L)).isEmpty();
    }
}
