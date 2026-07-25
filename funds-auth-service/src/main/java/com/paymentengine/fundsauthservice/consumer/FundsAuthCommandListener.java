package com.payflow.fundsauthservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.commands.AuthorizeFundsCommand;
import com.payflow.common.commands.ReleaseFundsCommand;
import com.payflow.common.events.EventEnvelope;
import com.payflow.common.events.FundsAuthorizationFailedEvent;
import com.payflow.common.events.FundsAuthorizedEvent;
import com.payflow.common.events.FundsReleasedEvent;
import com.payflow.common.events.PaymentEventType;
import com.payflow.fundsauthservice.bank.MockBankLedger;
import com.payflow.fundsauthservice.domain.ProcessedEvent;
import com.payflow.fundsauthservice.repository.ProcessedEventRepository;
import io.github.resilience4j.retry.annotation.Retry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class FundsAuthCommandListener {

    private static final Logger log = LoggerFactory.getLogger(FundsAuthCommandListener.class);
    private static final String EVENTS_TOPIC = "payment.events";

    private final ProcessedEventRepository processedEventRepository;
    private final MockBankLedger bankLedger;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public FundsAuthCommandListener(ProcessedEventRepository processedEventRepository,
                                     MockBankLedger bankLedger,
                                     KafkaTemplate<String, String> kafkaTemplate,
                                     ObjectMapper objectMapper) {
        this.processedEventRepository = processedEventRepository;
        this.bankLedger = bankLedger;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment.commands", containerFactory = "kafkaListenerContainerFactory")
    @Transactional(rollbackFor = Exception.class)
    @Retry(name = "kafka-consumer", fallbackMethod = "onCommandProcessingFailed")
    public void onCommand(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);

        switch (envelope.eventType()) {
            case "AUTHORIZE_FUNDS" -> handleAuthorize(envelope);
            case "RELEASE_FUNDS" -> handleRelease(envelope);
            default -> { /* not for us */ }
        }
        ack.acknowledge();
    }

    private void onCommandProcessingFailed(ConsumerRecord<String, String> record, Acknowledgment ack, Throwable t) {
        log.error("Failed to process funds-auth command after retries exhausted, will redeliver", t);
    }

    private void handleAuthorize(EventEnvelope envelope) throws Exception {
        if (processedEventRepository.existsById(envelope.eventId())) {
            log.debug("Skipping already-processed command {}", envelope.eventId());
            return;
        }

        AuthorizeFundsCommand command = objectMapper.treeToValue(envelope.payload(), AuthorizeFundsCommand.class);
        MockBankLedger.Result result = bankLedger.reserve(command.paymentId(), command.payerAccount(), command.amountCents());

        if (result.authorized()) {
            publish(command.paymentId(), PaymentEventType.FUNDS_AUTHORIZED,
                    new FundsAuthorizedEvent(command.paymentId(), Instant.now()));
            log.info("Payment {} funds AUTHORIZED", command.paymentId());
        } else {
            publish(command.paymentId(), PaymentEventType.FUNDS_AUTHORIZATION_FAILED,
                    new FundsAuthorizationFailedEvent(command.paymentId(), result.reason(), Instant.now()));
            log.info("Payment {} funds authorization FAILED: {}", command.paymentId(), result.reason());
        }

        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), Instant.now()));
    }

    private void handleRelease(EventEnvelope envelope) throws Exception {
        if (processedEventRepository.existsById(envelope.eventId())) {
            log.debug("Skipping already-processed command {}", envelope.eventId());
            return;
        }

        ReleaseFundsCommand command = objectMapper.treeToValue(envelope.payload(), ReleaseFundsCommand.class);
        bankLedger.release(command.paymentId());
        publish(command.paymentId(), PaymentEventType.FUNDS_RELEASED,
                new FundsReleasedEvent(command.paymentId(), Instant.now()));
        log.info("Payment {} funds RELEASED (compensation)", command.paymentId());

        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), Instant.now()));
    }

    private void publish(UUID paymentId, PaymentEventType type, Object payload) throws Exception {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                paymentId,
                type.name(),
                Instant.now(),
                objectMapper.valueToTree(payload)
        );
        kafkaTemplate.send(EVENTS_TOPIC, paymentId.toString(), objectMapper.writeValueAsString(envelope)).get();
    }
}
