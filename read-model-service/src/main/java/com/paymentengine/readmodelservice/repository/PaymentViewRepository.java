package com.paymentengine.readmodelservice.repository;

import com.paymentengine.readmodelservice.domain.PaymentView;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PaymentViewRepository extends JpaRepository<PaymentView, UUID> {
    List<PaymentView> findAllByOrderByUpdatedAtDesc();
}
