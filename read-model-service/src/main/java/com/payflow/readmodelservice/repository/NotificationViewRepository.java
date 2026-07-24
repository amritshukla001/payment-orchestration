package com.payflow.readmodelservice.repository;

import com.payflow.readmodelservice.domain.NotificationView;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface NotificationViewRepository extends JpaRepository<NotificationView, UUID> {
    List<NotificationView> findByPaymentIdOrderBySentAtAsc(UUID paymentId);
}
