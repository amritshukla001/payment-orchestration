package com.payflow.fraudservice.rules.ml;

import com.payflow.common.commands.CheckFraudCommand;
import com.payflow.fraudservice.domain.FraudCheckHistory;
import com.payflow.fraudservice.repository.FraudCheckHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MlRiskScoreRuleTest {

    private static final double THRESHOLD = 0.75;

    @Mock
    private MockMlFraudScorer scorer;

    @Mock
    private FraudCheckHistoryRepository historyRepository;

    private MlRiskScoreRule ruleUnderTest() {
        return new MlRiskScoreRule(scorer, historyRepository, THRESHOLD);
    }

    @Test
    void rejectsWhenTheScoreMeetsOrExceedsTheThreshold() {
        UUID payerAccount = UUID.randomUUID();
        when(historyRepository.findByPayerAccountAndCheckedAtAfter(eq(payerAccount), any()))
                .thenReturn(List.of());
        when(scorer.score(anyLong(), anyInt(), anyDouble())).thenReturn(0.9);

        Optional<String> result = ruleUnderTest().checkForRejection(commandFor(payerAccount, 5_000L));

        assertThat(result).isPresent();
        assertThat(result.get()).contains("ML risk score");
    }

    @Test
    void approvesWhenTheScoreIsBelowTheThreshold() {
        UUID payerAccount = UUID.randomUUID();
        when(historyRepository.findByPayerAccountAndCheckedAtAfter(eq(payerAccount), any()))
                .thenReturn(List.of());
        when(scorer.score(anyLong(), anyInt(), anyDouble())).thenReturn(0.1);

        Optional<String> result = ruleUnderTest().checkForRejection(commandFor(payerAccount, 5_000L));

        assertThat(result).isEmpty();
    }

    @Test
    void computesVelocityAndDeviationFromPriorHistoryBeforeScoring() {
        UUID payerAccount = UUID.randomUUID();
        List<FraudCheckHistory> priorChecks = List.of(
                new FraudCheckHistory(UUID.randomUUID(), UUID.randomUUID(), payerAccount, 5_000L, Instant.now()),
                new FraudCheckHistory(UUID.randomUUID(), UUID.randomUUID(), payerAccount, 5_000L, Instant.now()));
        when(historyRepository.findByPayerAccountAndCheckedAtAfter(eq(payerAccount), any()))
                .thenReturn(priorChecks);
        when(scorer.score(anyLong(), anyInt(), anyDouble())).thenReturn(0.1);

        ruleUnderTest().checkForRejection(commandFor(payerAccount, 400_000L));

        // velocity = 2 prior checks; deviation = |400_000 - 5_000| / 5_000 = 79.0
        verify(scorer).score(400_000L, 2, 79.0);
    }

    @Test
    void treatsABrandNewPayerWithNoHistoryAsZeroDeviation() {
        UUID payerAccount = UUID.randomUUID();
        when(historyRepository.findByPayerAccountAndCheckedAtAfter(eq(payerAccount), any()))
                .thenReturn(List.of());
        when(scorer.score(anyLong(), anyInt(), anyDouble())).thenReturn(0.1);

        ruleUnderTest().checkForRejection(commandFor(payerAccount, 900_000L));

        verify(scorer).score(900_000L, 0, 0.0);
    }

    private CheckFraudCommand commandFor(UUID payerAccount, long amountCents) {
        return new CheckFraudCommand(UUID.randomUUID(), payerAccount, amountCents, "USD", Instant.now());
    }
}
