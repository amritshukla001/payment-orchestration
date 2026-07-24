package com.payflow.notificationservice.api;

import com.payflow.notificationservice.api.dto.NotificationResponse;
import com.payflow.notificationservice.repository.NotificationRecordRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Read-only view onto sent notifications for the dashboard. */
@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications", description = "Notifications recorded for terminal payment outcomes")
public class NotificationController {

    private final NotificationRecordRepository repository;

    public NotificationController(NotificationRecordRepository repository) {
        this.repository = repository;
    }

    @Operation(summary = "List a payment's notifications",
            description = "Both payer and payee on success; payer only on failure or compensation. "
                    + "The message includes the failure/compensation reason.")
    @GetMapping("/{paymentId}")
    public List<NotificationResponse> byPayment(@PathVariable UUID paymentId) {
        return repository.findByPaymentId(paymentId).stream()
                .map(NotificationResponse::from)
                .toList();
    }
}
