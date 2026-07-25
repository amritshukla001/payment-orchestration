package com.paymentengine.complianceservice.repository;

import com.paymentengine.complianceservice.domain.KycRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface KycRecordRepository extends JpaRepository<KycRecord, UUID> {
}
