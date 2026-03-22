package com.yourapp.outbox.repository;

import com.yourapp.outbox.model.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class OutboxEventRepository {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public OutboxEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── INSERT (called within the same transaction as the business write) ──

    public void insert(OutboxEvent event) {
        jdbcTemplate.update(
            """
            INSERT INTO outbox_events
                (event_id, aggregate_type, aggregate_id, event_type, payload, status, retry_count, max_retries, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            event.getEventId(),
            event.getAggregateType(),
            event.getAggregateId(),
            event.getEventType(),
            event.getPayload(),
            event.getStatus(),
            event.getRetryCount(),
            event.getMaxRetries(),
            Timestamp.from(event.getCreatedAt())
        );
    }

    // ── Mark as SENT ────────────────────────────────────────────────────────

    public void markSent(String eventId) {
        jdbcTemplate.update(
            "UPDATE outbox_events SET status = 'SENT', sent_at = ? WHERE event_id = ?",
            Timestamp.from(Instant.now()),
            eventId
        );
    }

    // ── Mark as FAILED ──────────────────────────────────────────────────────

    public void markFailed(String eventId, String errorMessage) {
        jdbcTemplate.update(
            """
            UPDATE outbox_events
            SET retry_count = retry_count + 1,
                error_message = ?,
                status = CASE
                    WHEN retry_count + 1 >= max_retries THEN 'FAILED'
                    ELSE status
                END
            WHERE event_id = ?
            """,
            errorMessage,
            eventId
        );
    }

    // ── Fetch PENDING events (for scheduler — with 10s buffer) ──────────────

    public List<OutboxEvent> findPendingEvents(int limit) {
        return jdbcTemplate.query(
            """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
              AND created_at < ?
              AND retry_count < max_retries
            ORDER BY created_at ASC
            LIMIT ?
            """,
            OUTBOX_ROW_MAPPER,
            Timestamp.from(Instant.now().minusSeconds(10)),  // 10-second buffer
            limit
        );
    }

    // ── Cleanup: delete old SENT events ─────────────────────────────────────

    public int deleteOldSentEvents(int hoursOld) {
        int deleted = jdbcTemplate.update(
            "DELETE FROM outbox_events WHERE status = 'SENT' AND sent_at < ?",
            Timestamp.from(Instant.now().minusSeconds(hoursOld * 3600L))
        );
        log.info("Cleanup: deleted {} old SENT outbox events", deleted);
        return deleted;
    }

    // ── Row Mapper ──────────────────────────────────────────────────────────

    private static final RowMapper<OutboxEvent> OUTBOX_ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        OutboxEvent e = new OutboxEvent();
        e.setId(rs.getLong("id"));
        e.setEventId(rs.getString("event_id"));
        e.setAggregateType(rs.getString("aggregate_type"));
        e.setAggregateId(rs.getString("aggregate_id"));
        e.setEventType(rs.getString("event_type"));
        e.setPayload(rs.getString("payload"));
        e.setStatus(rs.getString("status"));
        e.setRetryCount(rs.getInt("retry_count"));
        e.setMaxRetries(rs.getInt("max_retries"));
        e.setErrorMessage(rs.getString("error_message"));
        e.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        Timestamp sentAt = rs.getTimestamp("sent_at");
        e.setSentAt(sentAt != null ? sentAt.toInstant() : null);
        return e;
    };
}
