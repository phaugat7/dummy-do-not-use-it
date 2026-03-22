package com.yourapp.service;

import com.yourapp.outbox.service.OutboxService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * ─────────────────────────────────────────────────────────────
 * EXAMPLE: How to integrate OutboxService into your existing
 * service layer. This shows the pattern — adapt to your
 * real entities and DAO structure.
 * ─────────────────────────────────────────────────────────────
 */
@Service
public class OrderService {

    private final JdbcTemplate jdbcTemplate;
    private final OutboxService outboxService;

    public OrderService(JdbcTemplate jdbcTemplate, OutboxService outboxService) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxService = outboxService;
    }

    // ── CREATE ──────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> createOrder(String customerId, String product, double amount) {

        // 1. Your existing business logic — nothing changes here
        jdbcTemplate.update(
            "INSERT INTO orders (customer_id, product, amount, status) VALUES (?, ?, ?, ?)",
            customerId, product, amount, "CREATED"
        );

        // Get the inserted ID (adapt to your ID generation strategy)
        Long orderId = jdbcTemplate.queryForObject(
            "SELECT LAST_INSERT_ID()", Long.class
        );

        Map<String, Object> order = Map.of(
            "id", orderId,
            "customerId", customerId,
            "product", product,
            "amount", amount,
            "status", "CREATED"
        );

        // 2. Record the outbox event — ONE extra line.
        //    This INSERT happens in the SAME transaction.
        //    Kafka publish fires AFTER commit (non-blocking).
        outboxService.recordEvent("Order", String.valueOf(orderId), "INSERT", order);

        return order;
    }

    // ── UPDATE ──────────────────────────────────────────────────

    @Transactional
    public void updateOrderStatus(Long orderId, String newStatus) {

        // 1. Your existing update
        jdbcTemplate.update(
            "UPDATE orders SET status = ? WHERE id = ?",
            newStatus, orderId
        );

        Map<String, Object> payload = Map.of(
            "id", orderId,
            "status", newStatus
        );

        // 2. Record outbox event
        outboxService.recordEvent("Order", String.valueOf(orderId), "UPDATE", payload);
    }

    // ── DELETE ──────────────────────────────────────────────────

    @Transactional
    public void deleteOrder(Long orderId) {

        // 1. Your existing delete
        jdbcTemplate.update("DELETE FROM orders WHERE id = ?", orderId);

        Map<String, Object> payload = Map.of("id", orderId);

        // 2. Record outbox event
        outboxService.recordEvent("Order", String.valueOf(orderId), "DELETE", payload);
    }
}
