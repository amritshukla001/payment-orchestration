package com.payflow.paymentengine.repository;

import com.payflow.paymentengine.domain.PaymentEngineSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface PaymentEngineSummaryRepository extends JpaRepository<PaymentEngineSummary, UUID> {
}
