package com.payflow.paymentengine.api;

import com.payflow.common.enums.PaymentState;
import com.payflow.paymentengine.domain.PaymentEngineAggregate;
import com.payflow.paymentengine.domain.PaymentEngineEventStore;
import com.payflow.paymentengine.domain.PaymentEngineTransitions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The customer-facing resolution of a CARD payment's step-up pause
 * (see PaymentEventListener.onFraudApproved / PaymentEngineTransitions).
 * A write path onto the saga, unlike the read-only PaymentEngineController --
 * kept as a separate class for that reason.
 */
@RestController
@RequestMapping("/api/payment-engine")
@Tag(name = "Step-up", description = "Confirm or decline a CARD payment's step-up authentication")
public class StepUpController {

    private final PaymentEngineEventStore paymentEngineEventStore;
    private final PaymentEngineTransitions transitions;

    public StepUpController(PaymentEngineEventStore paymentEngineEventStore, PaymentEngineTransitions transitions) {
        this.paymentEngineEventStore = paymentEngineEventStore;
        this.transitions = transitions;
    }

    @Operation(summary = "Confirm a card step-up",
            description = "The customer approved the step-up (e.g. tapped 'approve' in their banking app) -- "
                    + "resumes the saga by issuing AUTHORIZE_FUNDS. 409 if the payment isn't currently awaiting one.")
    @PostMapping("/{paymentId}/step-up/confirm")
    public ResponseEntity<Void> confirm(@PathVariable UUID paymentId) throws Exception {
        transitions.resumeAfterStepUp(pendingStepUp(paymentId));
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Decline a card step-up",
            description = "The customer declined the step-up -- fails the payment immediately rather than "
                    + "waiting for the timeout sweep. 409 if the payment isn't currently awaiting one.")
    @PostMapping("/{paymentId}/step-up/decline")
    public ResponseEntity<Void> decline(@PathVariable UUID paymentId) throws Exception {
        pendingStepUp(paymentId);
        transitions.failPayment(paymentId, "STEP_UP_DECLINED", "Card step-up declined by customer", "card step-up declined");
        return ResponseEntity.accepted().build();
    }

    private PaymentEngineAggregate pendingStepUp(UUID paymentId) {
        PaymentEngineAggregate aggregate = paymentEngineEventStore.load(paymentId)
                .orElseThrow(() -> new PaymentEngineNotFoundException(paymentId));
        if (aggregate.getState() != PaymentState.AWAITING_STEP_UP) {
            throw new StepUpNotPendingException(paymentId, aggregate.getState());
        }
        return aggregate;
    }

    @ExceptionHandler(PaymentEngineNotFoundException.class)
    public ResponseEntity<String> handleNotFound(PaymentEngineNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    @ExceptionHandler(StepUpNotPendingException.class)
    public ResponseEntity<String> handleNotPending(StepUpNotPendingException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
