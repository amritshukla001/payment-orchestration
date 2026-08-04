package com.payflow.readmodelservice.api;

import com.payflow.common.enums.PaymentState;
import com.payflow.readmodelservice.repository.LedgerEntryViewRepository;
import com.payflow.readmodelservice.repository.NotificationViewRepository;
import com.payflow.readmodelservice.repository.PaymentViewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentViewController.class)
class PaymentViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentViewRepository paymentViewRepository;
    @MockBean
    private LedgerEntryViewRepository ledgerEntryViewRepository;
    @MockBean
    private NotificationViewRepository notificationViewRepository;

    @Test
    void analyticsSummaryAssemblesTheThreeAggregationsIntoOneResponse() throws Exception {
        when(paymentViewRepository.countByState()).thenReturn(List.of(
                new Object[]{PaymentState.SETTLED, 3L, 15_000L},
                new Object[]{PaymentState.FAILED, 1L, 2_000L},
                new Object[]{PaymentState.COMPENSATED, 1L, 9_500L},
                new Object[]{PaymentState.FRAUD_CHECKED, 2L, 4_000L}
        ));
        when(paymentViewRepository.aggregateByMethod()).thenReturn(List.of(
                new Object[]{"CARD", 4L, 20_000L},
                new Object[]{"UPI", 3L, 10_500L}
        ));
        when(paymentViewRepository.dailyVolumeSince(org.mockito.ArgumentMatchers.any())).thenReturn(List.<Object[]>of(
                new Object[]{java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), 2L, 5_000L}
        ));

        mockMvc.perform(get("/api/payments/analytics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPayments").value(7))
                .andExpect(jsonPath("$.settledCount").value(3))
                .andExpect(jsonPath("$.failedCount").value(1))
                .andExpect(jsonPath("$.compensatedCount").value(1))
                .andExpect(jsonPath("$.settledAmountCents").value(15_000))
                .andExpect(jsonPath("$.byMethod[0].method").value("CARD"))
                .andExpect(jsonPath("$.byMethod[0].count").value(4))
                .andExpect(jsonPath("$.dailyVolume[0].date").value("2026-01-01"))
                .andExpect(jsonPath("$.dailyVolume[0].count").value(2));
    }

    @Test
    void analyticsSummaryHandlesBigDecimalAggregatesFromNativeQueries() throws Exception {
        // Regression test: found live against real Postgres -- COUNT(*)/SUM(...)
        // from the native date_trunc query come back as BigDecimal, not Long,
        // since there's no declared Java type for Hibernate to coerce an
        // unmapped native scalar column to. A hard (Long) cast threw
        // ClassCastException here before AnalyticsSummaryResponse.toLong(...).
        when(paymentViewRepository.countByState()).thenReturn(List.<Object[]>of(
                new Object[]{PaymentState.SETTLED, BigDecimal.valueOf(2), BigDecimal.valueOf(5_000)}
        ));
        when(paymentViewRepository.aggregateByMethod()).thenReturn(List.<Object[]>of(
                new Object[]{"CARD", BigDecimal.valueOf(2), BigDecimal.valueOf(5_000)}
        ));
        when(paymentViewRepository.dailyVolumeSince(org.mockito.ArgumentMatchers.any())).thenReturn(List.<Object[]>of(
                new Object[]{java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), BigDecimal.valueOf(2), BigDecimal.valueOf(5_000)}
        ));

        mockMvc.perform(get("/api/payments/analytics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settledCount").value(2))
                .andExpect(jsonPath("$.settledAmountCents").value(5_000))
                .andExpect(jsonPath("$.byMethod[0].count").value(2))
                .andExpect(jsonPath("$.dailyVolume[0].count").value(2));
    }

    @Test
    void analyticsSummaryHandlesNoPaymentsYet() throws Exception {
        when(paymentViewRepository.countByState()).thenReturn(List.of());
        when(paymentViewRepository.aggregateByMethod()).thenReturn(List.of());
        when(paymentViewRepository.dailyVolumeSince(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        mockMvc.perform(get("/api/payments/analytics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPayments").value(0))
                .andExpect(jsonPath("$.settledAmountCents").value(0))
                .andExpect(jsonPath("$.byMethod").isEmpty())
                .andExpect(jsonPath("$.dailyVolume").isEmpty());
    }
}
