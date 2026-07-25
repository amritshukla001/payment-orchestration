package com.paymentengine.settlementservice.repository;

import com.paymentengine.settlementservice.domain.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface SettlementRepository extends JpaRepository<Settlement, UUID> {
}
