package com.payflow.fraudservice.rules.ml;

import com.payflow.common.commands.CheckFraudCommand;
import com.payflow.fraudservice.domain.FraudCheckHistory;
import com.payflow.fraudservice.repository.FraudCheckHistoryRepository;
import com.payflow.fraudservice.rules.FraudRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * The one non-deterministic rule in the engine -- calls a (mock) ML risk
 * scorer whose score() method is itself circuit-breaker-guarded (see
 * MockMlFraudScorer). When the scorer is unavailable, its fallback reports
 * a below-threshold score rather than propagating the failure, so a
 * down/slow ML service degrades to PositiveAmountRule/HighValueThresholdRule
 * alone instead of blocking the saga. Ordered last: it's the only rule that
 * does I/O, so cheaper deterministic rejections never pay for it.
 */
@Component
@Order(3)
public class MlRiskScoreRule implements FraudRule {

    private static final int VELOCITY_WINDOW_HOURS = 24;

    private final MockMlFraudScorer scorer;
    private final FraudCheckHistoryRepository historyRepository;
    private final double threshold;

    public MlRiskScoreRule(MockMlFraudScorer scorer, FraudCheckHistoryRepository historyRepository,
                            @Value("${fraud.ml-scorer.threshold}") double threshold) {
        this.scorer = scorer;
        this.historyRepository = historyRepository;
        this.threshold = threshold;
    }

    @Override
    public Optional<String> checkForRejection(CheckFraudCommand command) {
        List<FraudCheckHistory> priorChecks = historyRepository.findByPayerAccountAndCheckedAtAfter(
                command.payerAccount(), Instant.now().minus(VELOCITY_WINDOW_HOURS, ChronoUnit.HOURS));

        int velocity = priorChecks.size();
        double deviation = deviationFrom(priorChecks, command.amountCents());

        double score = scorer.score(command.amountCents(), velocity, deviation);
        if (score >= threshold) {
            return Optional.of("ML risk score %.2f exceeds threshold %.2f".formatted(score, threshold));
        }
        return Optional.empty();
    }

    private double deviationFrom(List<FraudCheckHistory> priorChecks, long amountCents) {
        if (priorChecks.isEmpty()) {
            return 0.0;
        }
        double average = priorChecks.stream().mapToLong(FraudCheckHistory::getAmountCents).average().orElse(0);
        if (average == 0) {
            return 0.0;
        }
        return Math.abs(amountCents - average) / average;
    }
}
