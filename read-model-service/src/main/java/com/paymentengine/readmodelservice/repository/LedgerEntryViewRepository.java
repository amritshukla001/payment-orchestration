package com.paymentengine.readmodelservice.repository;

import com.paymentengine.readmodelservice.domain.LedgerEntryView;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryViewRepository extends JpaRepository<LedgerEntryView, UUID> {
    List<LedgerEntryView> findByPaymentIdOrderByPostedAtAsc(UUID paymentId);
}
