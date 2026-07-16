package com.payflow.notificationservice.api;

import com.payflow.notificationservice.api.dto.NotificationResponse;
import com.payflow.notificationservice.repository.NotificationRecordRepository;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Read-only view onto sent notifications for the dashboard. */
@RestController
@RequestMapping("/api/notifications")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:4173"})
public class NotificationController {

    private final NotificationRecordRepository repository;

    public NotificationController(NotificationRecordRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{paymentId}")
    public List<NotificationResponse> byPayment(@PathVariable UUID paymentId) {
        return repository.findByPaymentId(paymentId).stream()
                .map(NotificationResponse::from)
                .toList();
    }
}
