package com.payflow.readmodelservice.api.dto;

import com.payflow.common.enums.PaymentState;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Backs the merchant analytics view (`/?view=merchant` in the dashboard) --
 * aggregate facts only, no derived rates/percentages baked in here. The
 * dashboard computes settled/failed/reversed rates itself from the raw
 * counts, same as every other response DTO in this service stays close to
 * the underlying data rather than pre-computing display values server-side.
 */
public record AnalyticsSummaryResponse(
        long totalPayments,
        long settledCount,
        long failedCount,
        long compensatedCount,
        long settledAmountCents,
        List<MethodBreakdown> byMethod,
        List<DailyVolume> dailyVolume
) {
    public record MethodBreakdown(String method, long count, long amountCents) {
    }

    public record DailyVolume(String date, long count, long amountCents) {
    }

    public static AnalyticsSummaryResponse from(List<Object[]> stateRows, List<Object[]> methodRows,
                                                 List<Object[]> dailyRows) {
        long total = 0;
        long settledCount = 0;
        long failedCount = 0;
        long compensatedCount = 0;
        long settledAmountCents = 0;

        for (Object[] row : stateRows) {
            PaymentState state = (PaymentState) row[0];
            long count = toLong(row[1]);
            long amountCents = toLong(row[2]);
            total += count;
            switch (state) {
                case SETTLED -> {
                    settledCount = count;
                    settledAmountCents = amountCents;
                }
                case FAILED -> failedCount = count;
                case COMPENSATED -> compensatedCount = count;
                default -> {
                    // In-flight states (INITIATED, ..., AWAITING_STEP_UP, LEDGER_POSTED,
                    // COMPENSATING) count toward the total but not any rate --
                    // they haven't reached an outcome yet.
                }
            }
        }

        List<MethodBreakdown> byMethod = methodRows.stream()
                .map(row -> new MethodBreakdown((String) row[0], toLong(row[1]), toLong(row[2])))
                .toList();

        List<DailyVolume> dailyVolume = dailyRows.stream()
                .map(row -> new DailyVolume(formatDay(row[0]), toLong(row[1]), toLong(row[2])))
                .toList();

        return new AnalyticsSummaryResponse(
                total, settledCount, failedCount, compensatedCount, settledAmountCents, byMethod, dailyVolume);
    }

    // COUNT/SUM come back as Long from JPQL's typed attribute conversion,
    // but as BigDecimal from the native date_trunc query -- Hibernate has
    // no declared Java type to coerce to for an unmapped native scalar
    // column, so Postgres's numeric/bigint aggregate results surface as
    // whatever java.sql.Types mapping the driver picked (confirmed live:
    // a ClassCastException here on a real Postgres COUNT(*)/SUM(...)
    // before this fix). Number.longValue() handles both.
    private static long toLong(Object raw) {
        return ((Number) raw).longValue();
    }

    // date_trunc('day', ...) comes back through the native-query/JDBC path
    // rather than JPQL's enum/entity type conversion, so its exact Java
    // type depends on the driver's timestamptz mapping -- handled
    // defensively rather than assuming one.
    private static String formatDay(Object raw) {
        LocalDate date = switch (raw) {
            case java.sql.Timestamp ts -> ts.toLocalDateTime().toLocalDate();
            case Instant instant -> instant.atZone(ZoneOffset.UTC).toLocalDate();
            case OffsetDateTime odt -> odt.toLocalDate();
            case java.time.LocalDateTime ldt -> ldt.toLocalDate();
            case null, default -> null;
        };
        return date != null ? date.toString() : String.valueOf(raw);
    }
}
