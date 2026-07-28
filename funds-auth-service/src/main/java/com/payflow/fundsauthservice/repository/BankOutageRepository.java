package com.payflow.fundsauthservice.repository;

import com.payflow.fundsauthservice.domain.BankOutage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankOutageRepository extends JpaRepository<BankOutage, String> {
}
