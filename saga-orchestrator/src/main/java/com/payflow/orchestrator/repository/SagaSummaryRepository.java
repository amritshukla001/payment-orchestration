package com.payflow.orchestrator.repository;

import com.payflow.orchestrator.domain.SagaSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface SagaSummaryRepository extends JpaRepository<SagaSummary, UUID> {
}
