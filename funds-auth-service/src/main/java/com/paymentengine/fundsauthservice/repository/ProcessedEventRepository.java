package com.payflow.fundsauthservice.repository;

import com.payflow.fundsauthservice.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
}
