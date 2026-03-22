package com.yourapp.outbox.scheduler;

import com.yourapp.outbox.model.OutboxEvent;
import com.yourapp.outbox.publisher.OutboxKafkaPublisher;
import com.yourapp.outbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxEventRepository outboxRepository;
    private final OutboxKafkaPublisher kafkaPublisher;

    @Value("${outbox.scheduler.batch-size:100}")
    private int batchSize;

    @Value("${outbox.cleanup.hours-old:48}")
    private int cleanupHoursOld;

    public OutboxScheduler(OutboxEventRepository outboxRepository,
                           OutboxKafkaPublisher kafkaPublisher) {
        this.outboxRepository = outboxRepository;
        this.kafkaPublisher = kafkaPublisher;
    }

    // ── Retry PENDING events every 30 seconds ──────────────────

    @Scheduled(fixedDelayString = "${outbox.scheduler.interval-ms:30000}")
    public void processPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxRepository.findPendingEvents(batchSize);

        if (pendingEvents.isEmpty()) {
            return;  // nothing to do — stay quiet
        }

        log.info("Scheduler: found {} pending outbox events to process", pendingEvents.size());

        int successCount = 0;
        int failCount = 0;

        for (OutboxEvent event : pendingEvents) {
            boolean success = kafkaPublisher.publishSync(event);
            if (success) {
                successCount++;
            } else {
                failCount++;
            }
        }

        log.info("Scheduler: processed {} events — {} sent, {} failed",
                 pendingEvents.size(), successCount, failCount);
    }

    // ── Cleanup old SENT events daily at 2 AM ──────────────────

    @Scheduled(cron = "${outbox.cleanup.cron:0 0 2 * * *}")
    public void cleanupOldEvents() {
        log.info("Cleanup: removing SENT outbox events older than {} hours", cleanupHoursOld);
        outboxRepository.deleteOldSentEvents(cleanupHoursOld);
    }
}
