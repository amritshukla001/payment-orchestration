package com.payflow.complianceservice.repository;

import com.payflow.complianceservice.domain.RegulatoryReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface RegulatoryReportRepository extends JpaRepository<RegulatoryReport, UUID> {
}
