package com.payflow.complianceservice.api;

import com.payflow.complianceservice.domain.KycRecord;
import com.payflow.complianceservice.domain.RegulatoryReport;
import com.payflow.complianceservice.repository.KycRecordRepository;
import com.payflow.complianceservice.repository.RegulatoryReportRepository;
import com.payflow.complianceservice.repository.UpiRegistrationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ComplianceController.class)
class ComplianceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private KycRecordRepository kycRecordRepository;
    @MockBean
    private UpiRegistrationRepository upiRegistrationRepository;
    @MockBean
    private RegulatoryReportRepository regulatoryReportRepository;

    @Test
    void flagMarksAnAccountUnverified() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(kycRecordRepository.findById(accountId)).thenReturn(Optional.empty());
        when(kycRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/compliance/accounts/{id}/flag", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.verified").value(false));
    }

    @Test
    void verifyMarksAnAccountVerified() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(kycRecordRepository.findById(accountId))
                .thenReturn(Optional.of(new KycRecord(accountId, false, Instant.now())));
        when(kycRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/compliance/accounts/{id}/verify", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));
    }

    @Test
    void getLazilyCreatesAnUnseenAccountAsVerified() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(kycRecordRepository.findById(accountId)).thenReturn(Optional.empty());
        when(kycRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(get("/api/compliance/accounts/{id}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));
    }

    @Test
    void registerUpiCreatesARegistration() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(upiRegistrationRepository.findById(accountId)).thenReturn(Optional.empty());
        when(upiRegistrationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/compliance/upi/{id}/register", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId.toString()));
    }

    @Test
    void reportsListsAllRecordedRegulatoryReports() throws Exception {
        UUID paymentId = UUID.randomUUID();
        RegulatoryReport report = new RegulatoryReport(UUID.randomUUID(), paymentId,
                UUID.randomUUID(), UUID.randomUUID(), 1_500_000L, "USD", Instant.now());
        when(regulatoryReportRepository.findAll()).thenReturn(List.of(report));

        mockMvc.perform(get("/api/compliance/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$[0].amountCents").value(1_500_000));
    }
}
