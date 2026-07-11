package com.payflow.orchestrator.repository;

import com.payflow.orchestrator.domain.PaymentSagaState;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface PaymentSagaStateRepository extends JpaRepository<PaymentSagaState, UUID> {
}
