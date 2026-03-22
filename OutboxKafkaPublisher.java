package com.yourapp.outbox.publisher;

import com.yourapp.outbox.model.OutboxEvent;
import com.yourapp.outbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class OutboxKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxKafkaPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxEventRepository outboxRepository;

    public OutboxKafkaPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                 OutboxEventRepository outboxRepository) {
        this.kafkaTemplate = kafkaTemplate;
        this.outboxRepository = outboxRepository;
    }

    /**
     * Fire-and-forget async publish.
     *
     * Call this AFTER the transaction has committed.
     * It does NOT block the calling thread.
     * On success → marks the event as SENT.
     * On failure → logs a warning; the scheduler will retry later.
     */
    public void publishAsync(OutboxEvent event) {
        String topic = resolveTopic(event);
        String key = event.kafkaKey();
        String value = event.getPayload();

        try {
            CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(topic, key, value);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    // ✅ Success — mark as SENT
                    try {
                        outboxRepository.markSent(event.getEventId());
                        log.debug("Outbox event {} published to Kafka topic {} successfully",
                                  event.getEventId(), topic);
                    } catch (Exception dbEx) {
                        // Kafka got the message but DB update failed.
                        // Scheduler will see it as PENDING and may re-send — that's OK,
                        // because the GCP consumer deduplicates on eventId.
                        log.warn("Outbox event {} published to Kafka but failed to update status: {}",
                                 event.getEventId(), dbEx.getMessage());
                    }
                } else {
                    // ❌ Kafka publish failed — do NOT propagate to caller.
                    // The scheduler will pick this up and retry.
                    log.warn("Async Kafka publish failed for outbox event {}: {}",
                             event.getEventId(), ex.getMessage());
                }
            });
        } catch (Exception e) {
            // Catch any synchronous exception from kafkaTemplate.send() itself
            // (e.g., serialization error, producer not initialized)
            log.warn("Failed to initiate async Kafka publish for outbox event {}: {}",
                     event.getEventId(), e.getMessage());
        }
    }

    /**
     * Synchronous publish used by the scheduler.
     * Returns true if published successfully, false otherwise.
     */
    public boolean publishSync(OutboxEvent event) {
        String topic = resolveTopic(event);
        String key = event.kafkaKey();
        String value = event.getPayload();

        try {
            kafkaTemplate.send(topic, key, value).get();  // blocking
            outboxRepository.markSent(event.getEventId());
            log.debug("Scheduler: outbox event {} published to Kafka topic {}",
                      event.getEventId(), topic);
            return true;
        } catch (Exception ex) {
            log.error("Scheduler: failed to publish outbox event {}: {}",
                      event.getEventId(), ex.getMessage());
            outboxRepository.markFailed(event.getEventId(), ex.getMessage());
            return false;
        }
    }

    // ── Topic Resolution ────────────────────────────────────────

    private String resolveTopic(OutboxEvent event) {
        // Option A: per-aggregate topic  → edge.outbox.Order
        // Option B: single unified topic → edge.outbox.events
        // Choose one and stick with it:

        return "edge.outbox." + event.getAggregateType();

        // For a single unified topic, use:
        // return "edge.outbox.events";
    }
}
