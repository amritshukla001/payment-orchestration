package com.payflow.orchestrator.summary;

import com.payflow.orchestrator.domain.SagaEvent;
import com.payflow.orchestrator.domain.SagaSummary;
import com.payflow.orchestrator.repository.SagaEventRepository;
import com.payflow.orchestrator.repository.SagaSummaryRepository;
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
    private SagaEventRepository sagaEventRepository;
    @Mock
    private SagaSummaryRepository sagaSummaryRepository;
    @Mock
    private GeminiSummaryClient geminiSummaryClient;

    private CompensationSummaryService service;

    @BeforeEach
    void setUp() {
        service = new CompensationSummaryService(sagaEventRepository, sagaSummaryRepository,
                new SagaTimelineFormatter(), geminiSummaryClient);
        lenient().when(sagaSummaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void aiSummaryIsPersistedWithSourceAi() {
        UUID paymentId = UUID.randomUUID();
        when(sagaSummaryRepository.findById(paymentId)).thenReturn(Optional.empty());
        when(sagaEventRepository.findByPaymentIdOrderBySequenceNumberAsc(paymentId))
                .thenReturn(compensatedLog(paymentId));
        when(geminiSummaryClient.summarize(anyString()))
                .thenReturn("A $9,500 payment was reversed after settlement declined.");

        SagaSummary summary = service.summarize(paymentId);

        assertThat(summary.getSource()).isEqualTo(SagaSummary.Source.AI);
        assertThat(summary.getSummary()).contains("reversed after settlement declined");
        verify(sagaSummaryRepository).save(any());
    }

    @Test
    void geminiUnavailableFallsBackToDeterministicSummary() {
        UUID paymentId = UUID.randomUUID();
        when(sagaSummaryRepository.findById(paymentId)).thenReturn(Optional.empty());
        when(sagaEventRepository.findByPaymentIdOrderBySequenceNumberAsc(paymentId))
                .thenReturn(compensatedLog(paymentId));
        when(geminiSummaryClient.summarize(anyString())).thenReturn(null);

        SagaSummary summary = service.summarize(paymentId);

        assertThat(summary.getSource()).isEqualTo(SagaSummary.Source.DETERMINISTIC);
        assertThat(summary.getSummary())
                .contains("USD 9,500.00")
                .contains("reversed")
                .contains("after settlement declined")
                .contains("funds were returned");
    }

    @Test
    void cachedSummaryIsReturnedWithoutCallingGeminiAgain() {
        UUID paymentId = UUID.randomUUID();
        SagaSummary cached = new SagaSummary(paymentId, "cached text", SagaSummary.Source.AI, Instant.now());
        when(sagaSummaryRepository.findById(paymentId)).thenReturn(Optional.of(cached));

        SagaSummary summary = service.summarize(paymentId);

        assertThat(summary).isSameAs(cached);
        verifyNoInteractions(geminiSummaryClient);
        verify(sagaSummaryRepository, never()).save(any());
    }

    @Test
    void unknownPaymentThrowsNotFound() {
        UUID paymentId = UUID.randomUUID();
        when(sagaSummaryRepository.findById(paymentId)).thenReturn(Optional.empty());
        when(sagaEventRepository.findByPaymentIdOrderBySequenceNumberAsc(paymentId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.summarize(paymentId))
                .isInstanceOf(SummaryUnavailableException.class)
                .matches(e -> ((SummaryUnavailableException) e).isNotFound());
        verifyNoInteractions(geminiSummaryClient);
    }

    @Test
    void nonCompensatedPaymentThrowsConflict() {
        UUID paymentId = UUID.randomUUID();
        when(sagaSummaryRepository.findById(paymentId)).thenReturn(Optional.empty());
        when(sagaEventRepository.findByPaymentIdOrderBySequenceNumberAsc(paymentId)).thenReturn(List.of(
                new SagaEvent(UUID.randomUUID(), paymentId, 0, "PAYMENT_INITIATED", "INITIATED",
                        UUID.randomUUID(), UUID.randomUUID(), 2_500L, "USD", Instant.now()),
                new SagaEvent(UUID.randomUUID(), paymentId, 1, "PAYMENT_SETTLED", "SETTLED",
                        null, null, null, null, Instant.now())
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
        when(sagaSummaryRepository.findById(paymentId)).thenReturn(Optional.empty());
        when(sagaEventRepository.findByPaymentIdOrderBySequenceNumberAsc(paymentId))
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

    private List<SagaEvent> compensatedLog(UUID paymentId) {
        Instant t0 = Instant.now();
        return List.of(
                new SagaEvent(UUID.randomUUID(), paymentId, 0, "PAYMENT_INITIATED", "INITIATED",
                        UUID.randomUUID(), UUID.randomUUID(), 950_000L, "USD", t0),
                new SagaEvent(UUID.randomUUID(), paymentId, 1, "FRAUD_APPROVED", "FRAUD_CHECKED",
                        null, null, null, null, t0.plusSeconds(1)),
                new SagaEvent(UUID.randomUUID(), paymentId, 2, "FUNDS_AUTHORIZED", "AUTHORIZED",
                        null, null, null, null, t0.plusSeconds(2)),
                new SagaEvent(UUID.randomUUID(), paymentId, 3, "LEDGER_POSTED", "LEDGER_POSTED",
                        null, null, null, null, t0.plusSeconds(3)),
                new SagaEvent(UUID.randomUUID(), paymentId, 4, "SETTLEMENT_DECLINED", "COMPENSATING",
                        null, null, null, null, t0.plusSeconds(4)),
                new SagaEvent(UUID.randomUUID(), paymentId, 5, "FUNDS_RELEASED", "COMPENSATED",
                        null, null, null, null, t0.plusSeconds(5))
        );
    }
}
