package com.paymentengine.complianceservice.api;

import com.paymentengine.complianceservice.api.dto.KycRecordResponse;
import com.paymentengine.complianceservice.api.dto.RegulatoryReportResponse;
import com.paymentengine.complianceservice.api.dto.UpiRegistrationResponse;
import com.paymentengine.complianceservice.domain.KycRecord;
import com.paymentengine.complianceservice.domain.UpiRegistration;
import com.paymentengine.complianceservice.repository.KycRecordRepository;
import com.paymentengine.complianceservice.repository.RegulatoryReportRepository;
import com.paymentengine.complianceservice.repository.UpiRegistrationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The demo levers for compliance-service's two gates, plus audit
 * visibility into recorded AML reports. There's no dashboard UI for these
 * -- API-only, matching how e.g. the ML fraud scorer's weights are
 * config-only rather than dashboard-editable.
 */
@RestController
@RequestMapping("/api/compliance")
@Tag(name = "Compliance", description = "KYC verification, UPI directory registration, and regulatory reports")
public class ComplianceController {

    private final KycRecordRepository kycRecordRepository;
    private final UpiRegistrationRepository upiRegistrationRepository;
    private final RegulatoryReportRepository regulatoryReportRepository;

    public ComplianceController(KycRecordRepository kycRecordRepository,
                                 UpiRegistrationRepository upiRegistrationRepository,
                                 RegulatoryReportRepository regulatoryReportRepository) {
        this.kycRecordRepository = kycRecordRepository;
        this.upiRegistrationRepository = upiRegistrationRepository;
        this.regulatoryReportRepository = regulatoryReportRepository;
    }

    @Operation(summary = "Flag an account for KYC review",
            description = "Marks the account unverified -- the demo lever for a COMPLIANCE_REJECTED outcome. "
                    + "Accounts default to verified on first sight, so this is the deliberate trigger.")
    @PostMapping("/accounts/{accountId}/flag")
    public KycRecordResponse flag(@PathVariable UUID accountId) {
        return KycRecordResponse.from(setVerified(accountId, false));
    }

    @Operation(summary = "Verify an account's KYC status",
            description = "Marks the account verified -- undoes a prior flag, or confirms a new account explicitly.")
    @PostMapping("/accounts/{accountId}/verify")
    public KycRecordResponse verify(@PathVariable UUID accountId) {
        return KycRecordResponse.from(setVerified(accountId, true));
    }

    @Operation(summary = "Get an account's KYC status")
    @GetMapping("/accounts/{accountId}")
    public KycRecordResponse get(@PathVariable UUID accountId) {
        KycRecord record = kycRecordRepository.findById(accountId)
                .orElseGet(() -> kycRecordRepository.save(new KycRecord(accountId, true, Instant.now())));
        return KycRecordResponse.from(record);
    }

    @Operation(summary = "Register an account as a UPI recipient",
            description = "Required before a UPI-method payment to this account can pass compliance -- "
                    + "mirrors \"you can only pay a registered VPA\".")
    @PostMapping("/upi/{accountId}/register")
    public UpiRegistrationResponse registerUpi(@PathVariable UUID accountId) {
        UpiRegistration registration = upiRegistrationRepository.findById(accountId)
                .orElseGet(() -> upiRegistrationRepository.save(new UpiRegistration(accountId, Instant.now())));
        return UpiRegistrationResponse.from(registration);
    }

    @Operation(summary = "List regulatory reports",
            description = "Every payment whose amount crossed the AML reporting threshold, regardless of "
                    + "the eventual compliance verdict.")
    @GetMapping("/reports")
    public List<RegulatoryReportResponse> reports() {
        return regulatoryReportRepository.findAll().stream()
                .map(RegulatoryReportResponse::from)
                .toList();
    }

    private KycRecord setVerified(UUID accountId, boolean verified) {
        Instant now = Instant.now();
        KycRecord record = kycRecordRepository.findById(accountId)
                .orElseGet(() -> new KycRecord(accountId, true, now));
        record.setVerified(verified, now);
        return kycRecordRepository.save(record);
    }
}
