package com.yourapp.outbox.model;

import java.time.Instant;
import java.util.UUID;

public class OutboxEvent {

    private Long id;
    private String eventId;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String payload;
    private String status;
    private int retryCount;
    private int maxRetries;
    private String errorMessage;
    private Instant createdAt;
    private Instant sentAt;

    // ── Static Factory ──────────────────────────────────────────

    public static OutboxEvent create(String aggregateType,
                                     String aggregateId,
                                     String eventType,
                                     String payload) {
        OutboxEvent event = new OutboxEvent();
        event.eventId = UUID.randomUUID().toString();
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.payload = payload;
        event.status = "PENDING";
        event.retryCount = 0;
        event.maxRetries = 5;
        event.createdAt = Instant.now();
        return event;
    }

    // ── Kafka Message Key (ensures ordering per entity) ─────────

    public String kafkaKey() {
        return aggregateType + ":" + aggregateId;
    }

    // ── Getters & Setters ───────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getAggregateType() { return aggregateType; }
    public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }

    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
}
