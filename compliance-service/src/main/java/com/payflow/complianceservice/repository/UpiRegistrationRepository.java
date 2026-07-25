package com.payflow.complianceservice.repository;

import com.payflow.complianceservice.domain.UpiRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface UpiRegistrationRepository extends JpaRepository<UpiRegistration, UUID> {
}
