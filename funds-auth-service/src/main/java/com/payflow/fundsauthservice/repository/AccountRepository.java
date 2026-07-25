package com.payflow.fundsauthservice.repository;

import com.payflow.fundsauthservice.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
}
