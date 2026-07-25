package com.payflow.complianceservice.rules;

import com.payflow.common.commands.CheckComplianceCommand;
import com.payflow.complianceservice.domain.KycRecord;
import com.payflow.complianceservice.repository.KycRecordRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Both payer and payee must be KYC-verified. Accounts are lazily
 * provisioned as verified on first sight (mirrors funds-auth-service's
 * lazy account provisioning), so a brand-new account can transact
 * immediately -- a specific account is routed to rejection by explicitly
 * flagging it via the REST API, not by a restrictive default.
 */
@Component
@Order(1)
public class KycVerificationRule implements ComplianceRule {

    private final KycRecordRepository kycRecordRepository;

    public KycVerificationRule(KycRecordRepository kycRecordRepository) {
        this.kycRecordRepository = kycRecordRepository;
    }

    @Override
    public Optional<String> checkForRejection(CheckComplianceCommand command) {
        KycRecord payer = recordFor(command.payerAccount());
        if (!payer.isVerified()) {
            return Optional.of("Payer account is not KYC-verified");
        }
        KycRecord payee = recordFor(command.payeeAccount());
        if (!payee.isVerified()) {
            return Optional.of("Payee account is not KYC-verified");
        }
        return Optional.empty();
    }

    private KycRecord recordFor(UUID accountId) {
        return kycRecordRepository.findById(accountId)
                .orElseGet(() -> kycRecordRepository.save(new KycRecord(accountId, true, Instant.now())));
    }
}
