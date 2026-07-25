package com.payflow.notificationservice.repository;

import com.payflow.notificationservice.domain.NotificationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface NotificationRecordRepository extends JpaRepository<NotificationRecord, UUID> {
    List<NotificationRecord> findByPaymentId(UUID paymentId);
}
