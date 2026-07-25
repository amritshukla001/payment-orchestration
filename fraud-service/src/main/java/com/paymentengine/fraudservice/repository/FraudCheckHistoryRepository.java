package com.payflow.fraudservice.repository;

import com.payflow.fraudservice.domain.FraudCheckHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface FraudCheckHistoryRepository extends JpaRepository<FraudCheckHistory, UUID> {
    List<FraudCheckHistory> findByPayerAccountAndCheckedAtAfter(UUID payerAccount, Instant since);
}
