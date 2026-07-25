package com.paymentengine.complianceservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentengine.common.commands.CheckComplianceCommand;
import com.paymentengine.common.events.ComplianceApprovedEvent;
import com.paymentengine.common.events.ComplianceRejectedEvent;
import com.paymentengine.common.events.EventEnvelope;
import com.paymentengine.common.events.PaymentEventType;
import com.paymentengine.complianceservice.domain.ProcessedEvent;
import com.paymentengine.complianceservice.domain.RegulatoryReport;
import com.paymentengine.complianceservice.repository.ProcessedEventRepository;
import com.paymentengine.complianceservice.repository.RegulatoryReportRepository;
import com.paymentengine.complianceservice.rules.ComplianceRuleEngine;
import com.paymentengine.complianceservice.rules.Verdict;
import io.github.resilience4j.retry.annotation.Retry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class ComplianceCommandListener {

    private static final Logger log = LoggerFactory.getLogger(ComplianceCommandListener.class);
    private static final String EVENTS_TOPIC = "payment.events";

    private final ProcessedEventRepository processedEventRepository;
    private final RegulatoryReportRepository regulatoryReportRepository;
    private final ComplianceRuleEngine ruleEngine;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final long amlThresholdCents;

    public ComplianceCommandListener(ProcessedEventRepository processedEventRepository,
                                      RegulatoryReportRepository regulatoryReportRepository,
                                      ComplianceRuleEngine ruleEngine,
                                      KafkaTemplate<String, String> kafkaTemplate,
                                      ObjectMapper objectMapper,
                                      @Value("${compliance.aml-threshold-cents}") long amlThresholdCents) {
        this.processedEventRepository = processedEventRepository;
        this.regulatoryReportRepository = regulatoryReportRepository;
        this.ruleEngine = ruleEngine;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.amlThresholdCents = amlThresholdCents;
    }

    @KafkaListener(topics = "payment.commands", containerFactory = "kafkaListenerContainerFactory")
    @Transactional(rollbackFor = Exception.class)
    @Retry(name = "kafka-consumer", fallbackMethod = "onCommandProcessingFailed")
    public void onCommand(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);

        if (!"CHECK_COMPLIANCE".equals(envelope.eventType())) {
            ack.acknowledge();
            return;
        }
        if (processedEventRepository.existsById(envelope.eventId())) {
            log.debug("Skipping already-processed command {}", envelope.eventId());
            ack.acknowledge();
            return;
        }

        CheckComplianceCommand command = objectMapper.treeToValue(envelope.payload(), CheckComplianceCommand.class);

        // Recorded regardless of the verdict below -- real CTR-style filing
        // happens on the attempted transaction, not just approved ones.
        if (command.amountCents() >= amlThresholdCents) {
            regulatoryReportRepository.save(new RegulatoryReport(
                    UUID.randomUUID(), command.paymentId(), command.payerAccount(), command.payeeAccount(),
                    command.amountCents(), command.currency(), Instant.now()));
            log.info("Payment {} recorded a regulatory report (amount {} >= threshold {})",
                    command.paymentId(), command.amountCents(), amlThresholdCents);
        }

        Verdict verdict = ruleEngine.evaluate(command);

        if (verdict.approved()) {
            publish(command.paymentId(), PaymentEventType.COMPLIANCE_APPROVED,
                    new ComplianceApprovedEvent(command.paymentId(), Instant.now()));
            log.info("Payment {} compliance check APPROVED", command.paymentId());
        } else {
            publish(command.paymentId(), PaymentEventType.COMPLIANCE_REJECTED,
                    new ComplianceRejectedEvent(command.paymentId(), verdict.reason(), Instant.now()));
            log.info("Payment {} compliance check REJECTED: {}", command.paymentId(), verdict.reason());
        }

        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), Instant.now()));
        ack.acknowledge();
    }

    private void onCommandProcessingFailed(ConsumerRecord<String, String> record, Acknowledgment ack, Throwable t) {
        log.error("Failed to process compliance command after retries exhausted, will redeliver", t);
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
