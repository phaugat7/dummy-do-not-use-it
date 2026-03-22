package com.yourapp.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourapp.outbox.model.OutboxEvent;
import com.yourapp.outbox.publisher.OutboxKafkaPublisher;
import com.yourapp.outbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxEventRepository outboxRepository;
    private final OutboxKafkaPublisher kafkaPublisher;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxRepository,
                         OutboxKafkaPublisher kafkaPublisher,
                         ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.kafkaPublisher = kafkaPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * Call this from your service/DAO methods INSIDE the existing transaction.
     *
     * It does two things:
     *   1. INSERTs the outbox event (same transaction — atomic with your business write)
     *   2. Registers an AFTER_COMMIT hook to fire-and-forget publish to Kafka
     *
     * Usage example:
     *   outboxService.recordEvent("Order", orderId, "INSERT", orderEntity);
     */
    public void recordEvent(String aggregateType,
                            String aggregateId,
                            String eventType,
                            Object entityPayload) {

        // ── Step 1: Serialize the payload ───────────────────────
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(entityPayload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox payload for {}/{}: {}",
                      aggregateType, aggregateId, e.getMessage());
            throw new RuntimeException("Outbox serialization failed", e);
        }

        // ── Step 2: Create and INSERT outbox event (same transaction) ──
        OutboxEvent event = OutboxEvent.create(aggregateType, aggregateId, eventType, payloadJson);
        outboxRepository.insert(event);

        log.debug("Outbox event {} inserted for {}/{} [{}]",
                  event.getEventId(), aggregateType, aggregateId, eventType);

        // ── Step 3: Register AFTER_COMMIT hook for async Kafka publish ──
        //
        // This is the critical part:
        //   - The Kafka publish happens ONLY after the transaction commits.
        //   - If the transaction rolls back, this hook never fires → no phantom events.
        //   - The hook itself is non-blocking (fire-and-forget).

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        kafkaPublisher.publishAsync(event);
                    }
                }
            );
        } else {
            // No active transaction (shouldn't happen in normal flow, but be safe)
            log.warn("No active transaction for outbox event {}. Publishing async immediately.",
                     event.getEventId());
            kafkaPublisher.publishAsync(event);
        }
    }
}
