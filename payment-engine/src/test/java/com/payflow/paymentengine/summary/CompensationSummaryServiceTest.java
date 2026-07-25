package com.payflow.paymentengine.summary;

import com.payflow.paymentengine.domain.PaymentEngineEvent;
import com.payflow.paymentengine.domain.PaymentEngineSummary;
import com.payflow.paymentengine.repository.PaymentEngineEventRepository;
import com.payflow.paymentengine.repository.PaymentEngineSummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompensationSummaryServiceTest {

    @Mock
    private PaymentEngineEventRepository paymentEngineEventRepository;
    @Mock
    private PaymentEngineSummaryRepository paymentEngineSummaryRepository;
    @Mock
    private GeminiSummaryClient geminiSummaryClient;

    private CompensationSummaryService service;

    @BeforeEach
    void setUp() {
        service = new CompensationSummaryService(paymentEngineEventRepository, paymentEngineSummaryRepository,
                new PaymentEngineTimelineFormatter(), geminiSummaryClient);
        lenient().when(paymentEngineSummaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void aiSummaryIsPersistedWithSourceAi() {
        UUID paymentId = UUID.randomUUID();
        when(paymentEngineSummaryRepository.findById(paymentId)).thenReturn(Optional.empty());
        when(paymentEngineEventRepository.findByPaymentIdOrderBySequenceNumberAsc(paymentId))
                .thenReturn(compensatedLog(paymentId));
        when(geminiSummaryClient.summarize(anyString()))
                .thenReturn("A $9,500 payment was reversed after settlement declined.");

        PaymentEngineSummary summary = service.summarize(paymentId);

        assertThat(summary.getSource()).isEqualTo(PaymentEngineSummary.Source.AI);
        assertThat(summary.getSummary()).contains("reversed after settlement declined");
        verify(paymentEngineSummaryRepository).save(any());
    }

    @Test
    void geminiUnavailableFallsBackToDeterministicSummary() {
        UUID paymentId = UUID.randomUUID();
        when(paymentEngineSummaryRepository.findById(paymentId)).thenReturn(Optional.empty());
        when(paymentEngineEventRepository.findByPaymentIdOrderBySequenceNumberAsc(paymentId))
                .thenReturn(compensatedLog(paymentId));
        when(geminiSummaryClient.summarize(anyString())).thenReturn(null);

        PaymentEngineSummary summary = service.summarize(paymentId);

        assertThat(summary.getSource()).isEqualTo(PaymentEngineSummary.Source.DETERMINISTIC);
        assertThat(summary.getSummary())
                .contains("USD 9,500.00")
                .contains("reversed")
                .contains("after settlement declined")
                .contains("funds were returned");
    }

    @Test
    void cachedSummaryIsReturnedWithoutCallingGeminiAgain() {
        UUID paymentId = UUID.randomUUID();
        PaymentEngineSummary cached = new PaymentEngineSummary(paymentId, "cached text", PaymentEngineSummary.Source.AI, Instant.now());
        when(paymentEngineSummaryRepository.findById(paymentId)).thenReturn(Optional.of(cached));

        PaymentEngineSummary summary = service.summarize(paymentId);

        assertThat(summary).isSameAs(cached);
        verifyNoInteractions(geminiSummaryClient);
        verify(paymentEngineSummaryRepository, never()).save(any());
    }

    @Test
    void unknownPaymentThrowsNotFound() {
        UUID paymentId = UUID.randomUUID();
        when(paymentEngineSummaryRepository.findById(paymentId)).thenReturn(Optional.empty());
        when(paymentEngineEventRepository.findByPaymentIdOrderBySequenceNumberAsc(paymentId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.summarize(paymentId))
                .isInstanceOf(SummaryUnavailableException.class)
                .matches(e -> ((SummaryUnavailableException) e).isNotFound());
        verifyNoInteractions(geminiSummaryClient);
    }

    @Test
    void nonCompensatedPaymentThrowsConflict() {
        UUID paymentId = UUID.randomUUID();
        when(paymentEngineSummaryRepository.findById(paymentId)).thenReturn(Optional.empty());
        when(paymentEngineEventRepository.findByPaymentIdOrderBySequenceNumberAsc(paymentId)).thenReturn(List.of(
                new PaymentEngineEvent(UUID.randomUUID(), paymentId, 0, "PAYMENT_INITIATED", "INITIATED",
                        UUID.randomUUID(), UUID.randomUUID(), 2_500L, "USD", "NETBANKING", Instant.now()),
                new PaymentEngineEvent(UUID.randomUUID(), paymentId, 1, "PAYMENT_SETTLED", "SETTLED",
                        null, null, null, null, null, Instant.now())
        ));

        assertThatThrownBy(() -> service.summarize(paymentId))
                .isInstanceOf(SummaryUnavailableException.class)
                .matches(e -> !((SummaryUnavailableException) e).isNotFound())
                .hasMessageContaining("SETTLED");
        verifyNoInteractions(geminiSummaryClient);
    }

    @Test
    void timelineHandedToGeminiContainsTheFullTransitionLog() {
        UUID paymentId = UUID.randomUUID();
        when(paymentEngineSummaryRepository.findById(paymentId)).thenReturn(Optional.empty());
        when(paymentEngineEventRepository.findByPaymentIdOrderBySequenceNumberAsc(paymentId))
                .thenReturn(compensatedLog(paymentId));
        when(geminiSummaryClient.summarize(anyString())).thenReturn("summary");

        service.summarize(paymentId);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(geminiSummaryClient).summarize(captor.capture());
        assertThat(captor.getValue())
                .contains("PAYMENT_INITIATED")
                .contains("SETTLEMENT_DECLINED -> COMPENSATING")
                .contains("FUNDS_RELEASED -> COMPENSATED");
    }

    private List<PaymentEngineEvent> compensatedLog(UUID paymentId) {
        Instant t0 = Instant.now();
        return List.of(
                new PaymentEngineEvent(UUID.randomUUID(), paymentId, 0, "PAYMENT_INITIATED", "INITIATED",
                        UUID.randomUUID(), UUID.randomUUID(), 950_000L, "USD", "NETBANKING", t0),
                new PaymentEngineEvent(UUID.randomUUID(), paymentId, 1, "FRAUD_APPROVED", "FRAUD_CHECKED",
                        null, null, null, null, null, t0.plusSeconds(1)),
                new PaymentEngineEvent(UUID.randomUUID(), paymentId, 2, "FUNDS_AUTHORIZED", "AUTHORIZED",
                        null, null, null, null, null, t0.plusSeconds(2)),
                new PaymentEngineEvent(UUID.randomUUID(), paymentId, 3, "LEDGER_POSTED", "LEDGER_POSTED",
                        null, null, null, null, null, t0.plusSeconds(3)),
                new PaymentEngineEvent(UUID.randomUUID(), paymentId, 4, "SETTLEMENT_DECLINED", "COMPENSATING",
                        null, null, null, null, null, t0.plusSeconds(4)),
                new PaymentEngineEvent(UUID.randomUUID(), paymentId, 5, "FUNDS_RELEASED", "COMPENSATED",
                        null, null, null, null, null, t0.plusSeconds(5))
        );
    }
}
