package com.payflow.fundsauthservice.repository;

import com.payflow.fundsauthservice.domain.FundsReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface FundsReservationRepository extends JpaRepository<FundsReservation, UUID> {
}
