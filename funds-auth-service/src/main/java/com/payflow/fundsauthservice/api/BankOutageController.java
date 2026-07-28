package com.payflow.fundsauthservice.api;

import com.payflow.fundsauthservice.api.dto.BankOutageResponse;
import com.payflow.fundsauthservice.bank.BankCodeResolver;
import com.payflow.fundsauthservice.domain.BankOutage;
import com.payflow.fundsauthservice.repository.BankOutageRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * The demo lever for NetBankingAvailabilityRule -- there's no dashboard UI
 * that isn't the ops console's "Bank outages" panel, matching how the
 * compliance/UPI demo levers are API-first and dashboard-surfaced.
 */
@RestController
@RequestMapping("/api/funds-auth/banks")
@Tag(name = "Bank outages", description = "Mark a mock bank's NetBanking gateway down for maintenance, or restore it")
public class BankOutageController {

    private final BankOutageRepository bankOutageRepository;

    public BankOutageController(BankOutageRepository bankOutageRepository) {
        this.bankOutageRepository = bankOutageRepository;
    }

    @Operation(summary = "Mark a bank down for maintenance",
            description = "The demo lever for a NetBanking funds-authorization decline -- NetBanking "
                    + "payments from accounts that resolve to this bank (see BankCodeResolver) will fail.")
    @PostMapping("/{bankCode}/outage")
    public BankOutageResponse markDown(@PathVariable String bankCode) {
        String bank = validated(bankCode);
        BankOutage outage = bankOutageRepository.findById(bank)
                .orElseGet(() -> bankOutageRepository.save(new BankOutage(bank, Instant.now())));
        return BankOutageResponse.down(outage);
    }

    @Operation(summary = "Restore a bank", description = "Clears a prior outage -- undoes markDown.")
    @PostMapping("/{bankCode}/restore")
    public BankOutageResponse restore(@PathVariable String bankCode) {
        String bank = validated(bankCode);
        bankOutageRepository.deleteById(bank);
        return BankOutageResponse.up(bank);
    }

    @Operation(summary = "Get a bank's status")
    @GetMapping("/{bankCode}")
    public BankOutageResponse get(@PathVariable String bankCode) {
        String bank = validated(bankCode);
        return bankOutageRepository.findById(bank)
                .map(BankOutageResponse::down)
                .orElseGet(() -> BankOutageResponse.up(bank));
    }

    private String validated(String bankCode) {
        String bank = bankCode.toUpperCase();
        if (!BankCodeResolver.allBanks().contains(bank)) {
            throw new UnknownBankException(bankCode);
        }
        return bank;
    }

    @ExceptionHandler(UnknownBankException.class)
    public ResponseEntity<String> handleUnknownBank(UnknownBankException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }
}
