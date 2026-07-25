package com.paymentengine.orchestrator.repository;

import com.paymentengine.orchestrator.domain.SagaEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface SagaEventRepository extends JpaRepository<SagaEvent, UUID> {
    List<SagaEvent> findByPaymentIdOrderBySequenceNumberAsc(UUID paymentId);
    List<SagaEvent> findAllByOrderByPaymentIdAscSequenceNumberAsc();
}
