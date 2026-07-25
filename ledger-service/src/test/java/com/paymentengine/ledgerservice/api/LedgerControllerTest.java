package com.payflow.ledgerservice.api;

import com.payflow.ledgerservice.domain.LedgerEntry;
import com.payflow.ledgerservice.domain.PostingType;
import com.payflow.ledgerservice.repository.LedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LedgerController.class)
class LedgerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LedgerEntryRepository repository;

    @Test
    void returnsEntriesForAPaymentInPostedOrder() throws Exception {
        UUID paymentId = UUID.randomUUID();
        LedgerEntry hold = new LedgerEntry(UUID.randomUUID(), paymentId, UUID.randomUUID(), UUID.randomUUID(),
                5_000L, PostingType.HOLD, Instant.now());
        when(repository.findByPaymentIdOrderByPostedAtAsc(paymentId)).thenReturn(List.of(hold));

        mockMvc.perform(get("/api/ledger/{paymentId}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].postingType").value("HOLD"))
                .andExpect(jsonPath("$[0].amountCents").value(5_000));
    }

    @Test
    void returnsAnEmptyListForAPaymentWithNoEntries() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(repository.findByPaymentIdOrderByPostedAtAsc(paymentId)).thenReturn(List.of());

        mockMvc.perform(get("/api/ledger/{paymentId}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}
