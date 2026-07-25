package com.payflow.paymentapi.repository;

import com.payflow.paymentapi.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findTop50ByPublishedFalseOrderByCreatedAtAsc();
    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);
}
