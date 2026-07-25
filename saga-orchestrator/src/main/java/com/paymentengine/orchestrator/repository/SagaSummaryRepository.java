package com.paymentengine.orchestrator.repository;

import com.paymentengine.orchestrator.domain.SagaSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface SagaSummaryRepository extends JpaRepository<SagaSummary, UUID> {
}
