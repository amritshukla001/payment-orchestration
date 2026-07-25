package com.payflow.complianceservice.repository;

import com.payflow.complianceservice.domain.KycRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface KycRecordRepository extends JpaRepository<KycRecord, UUID> {
}
