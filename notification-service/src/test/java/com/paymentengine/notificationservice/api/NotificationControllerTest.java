package com.payflow.notificationservice.api;

import com.payflow.notificationservice.domain.NotificationRecord;
import com.payflow.notificationservice.domain.Outcome;
import com.payflow.notificationservice.domain.Recipient;
import com.payflow.notificationservice.repository.NotificationRecordRepository;
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

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationRecordRepository repository;

    @Test
    void returnsNotificationsForAPayment() throws Exception {
        UUID paymentId = UUID.randomUUID();
        NotificationRecord record = new NotificationRecord(UUID.randomUUID(), paymentId, UUID.randomUUID(),
                Recipient.PAYER, Outcome.SUCCESS, "Your payment has settled.", Instant.now());
        when(repository.findByPaymentId(paymentId)).thenReturn(List.of(record));

        mockMvc.perform(get("/api/notifications/{paymentId}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recipient").value("PAYER"))
                .andExpect(jsonPath("$[0].outcome").value("SUCCESS"));
    }
}
