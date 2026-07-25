package com.payflow.paymentengine.repository;

import com.payflow.paymentengine.domain.PaymentEngineEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PaymentEngineEventRepository extends JpaRepository<PaymentEngineEvent, UUID> {
    List<PaymentEngineEvent> findByPaymentIdOrderBySequenceNumberAsc(UUID paymentId);
    List<PaymentEngineEvent> findAllByOrderByPaymentIdAscSequenceNumberAsc();
}
