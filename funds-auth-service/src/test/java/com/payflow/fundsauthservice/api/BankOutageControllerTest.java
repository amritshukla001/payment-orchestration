package com.payflow.fundsauthservice.api;

import com.payflow.fundsauthservice.domain.BankOutage;
import com.payflow.fundsauthservice.repository.BankOutageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BankOutageController.class)
class BankOutageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BankOutageRepository bankOutageRepository;

    @Test
    void markDownCreatesAnOutage() throws Exception {
        when(bankOutageRepository.findById("HDFC")).thenReturn(Optional.empty());
        when(bankOutageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/funds-auth/banks/{bankCode}/outage", "HDFC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankCode").value("HDFC"))
                .andExpect(jsonPath("$.down").value(true));
    }

    @Test
    void restoreClearsAnOutage() throws Exception {
        mockMvc.perform(post("/api/funds-auth/banks/{bankCode}/restore", "HDFC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankCode").value("HDFC"))
                .andExpect(jsonPath("$.down").value(false));

        verify(bankOutageRepository).deleteById("HDFC");
    }

    @Test
    void getReportsUpWhenNoOutageIsRecorded() throws Exception {
        when(bankOutageRepository.findById("SBI")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/funds-auth/banks/{bankCode}", "SBI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.down").value(false));
    }

    @Test
    void getReportsDownWhenAnOutageIsRecorded() throws Exception {
        when(bankOutageRepository.findById("SBI")).thenReturn(Optional.of(new BankOutage("SBI", Instant.now())));

        mockMvc.perform(get("/api/funds-auth/banks/{bankCode}", "SBI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.down").value(true));
    }

    @Test
    void rejectsAnUnknownBankCode() throws Exception {
        mockMvc.perform(post("/api/funds-auth/banks/{bankCode}/outage", "NOTABANK"))
                .andExpect(status().isBadRequest());
    }
}
