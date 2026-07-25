package com.payflow.ledgerservice.repository;

import com.payflow.ledgerservice.domain.LedgerEntry;
import com.payflow.ledgerservice.domain.PostingType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    Optional<LedgerEntry> findByPaymentIdAndPostingType(UUID paymentId, PostingType postingType);
    List<LedgerEntry> findByPaymentIdOrderByPostedAtAsc(UUID paymentId);
}
