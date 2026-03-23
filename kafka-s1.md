# Reactive Kafka Consumer Lifecycle Management

## Overview

A Spring Boot design pattern for dynamically controlling Kafka consumer lifecycle across **edge** and **GCP** environments using a database-driven approach. This enables seamless order processing failover between edge sites and a centralized GCP environment — without losing messages.

---

## Problem Statement

We operate multiple **edge environments** (one per store/site) and one centralized **GCP environment**. An `order-consumer` service runs in both locations. The requirements are:

- When a site is **active at the edge**, the edge consumer processes orders.
- When a site is **switched to GCP**, the GCP consumer takes over — with no message loss or duplication.
- Both environments use the **same Kafka consumer group ID**, so Kafka's built-in rebalancing handles partition assignment.
- The switch is always **manual and sequential**: edge is disabled first, then GCP is enabled.

### Why Not Just Filter Messages?

If the edge consumer reads a message and discards it (commits the offset), that message is gone from GCP's perspective — same group means same offset tracking. We must prevent the edge consumer from **reading at all** when the site is disabled.

---

## Solution

Control the Kafka consumer's subscription lifecycle (start/stop) rather than filtering messages after consumption.

### Two Control Layers

| Layer | Trigger | Purpose |
|---|---|---|
| **Startup check** | `CommandLineRunner.run()` | Prevents consumer from joining the group on app boot if site is disabled |
| **Scheduled reconciler** | `@Scheduled` (every 5 min) | Starts or stops the consumer based on DB changes at runtime |

Both layers call the same `start()` / `stop()` methods on a single Spring bean. The `synchronized` keyword prevents race conditions.

---

## Architecture

```
┌─────────────────────────────────────────────────┐
│                   KAFKA CLUSTER                  │
│              Topic: "orders"                     │
│         Consumer Group: "order-consumer-group"   │
├─────────────────────────────────────────────────┤
│                                                  │
│   Partitions assigned based on active consumers  │
│                                                  │
└──────────┬──────────────────────┬────────────────┘
           │                      │
    ┌──────▼──────┐        ┌──────▼──────┐
    │  EDGE ENV   │        │   GCP ENV   │
    │             │        │             │
    │  Consumer   │        │  Consumer   │
    │  (dynamic)  │        │ (always on) │
    │             │        │             │
    │  ┌───────┐  │        │             │
    │  │  DB   │  │        │             │
    │  │ check │  │        │             │
    │  └───────┘  │        │             │
    └─────────────┘        └─────────────┘
```

### Switchover Flow

```
PLANNED SWITCHOVER (edge → GCP)

  1. Set active=false in edge DB
  2. Wait for scheduler cycle (up to 5 min)
       └── Scheduler detects change
           └── stop() called
               └── Subscription disposed
                   └── Consumer leaves group
                       └── Kafka rebalances
                           └── GCP picks up partitions
  3. (Optional) Hit GET /consumer/status to confirm stopped
  4. Enable GCP consumer
```

```
APP RESTART WHILE DISABLED

  1. App starts, CommandLineRunner.run() fires
  2. DB check: active=false
  3. Consumer never subscribes
  4. GCP continues consuming uninterrupted
```

---

## Components

### 1. OrderConsumerService

The core service that owns the reactive Kafka subscription lifecycle.

**File:** `OrderConsumerService.java`

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderConsumerService implements CommandLineRunner {

    private final ReactiveKafkaConsumerTemplate<String, String> consumerTemplate;
    private final SiteEnvConfigRepository siteEnvConfigRepo;

    @Value("${site.name}")
    private String siteName;

    private Disposable consumerSubscription;

    @Override
    public void run(String... args) {
        boolean active = siteEnvConfigRepo
            .findBySiteName(siteName)
            .map(SiteEnvConfig::isActive)
            .orElse(false);

        if (active) {
            start();
            log.info("Startup — site {} is active, consumer started.", siteName);
        } else {
            log.info("Startup — site {} is inactive, consumer remains stopped.", siteName);
        }
    }

    public synchronized void start() {
        if (consumerSubscription != null && !consumerSubscription.isDisposed()) {
            log.info("Consumer already running, skipping start.");
            return;
        }

        consumerSubscription = consumerTemplate
            .receiveAutoAck()
            .doOnNext(this::processOrder)
            .doOnError(e -> log.error("Consumer error", e))
            .onErrorResume(e -> Mono.empty())
            .repeat()
            .subscribe();

        log.info("Reactive Kafka consumer STARTED.");
    }

    public synchronized void stop() {
        if (consumerSubscription != null && !consumerSubscription.isDisposed()) {
            consumerSubscription.dispose();
            consumerSubscription = null;
            log.info("Reactive Kafka consumer STOPPED.");
        }
    }

    public synchronized boolean isRunning() {
        return consumerSubscription != null && !consumerSubscription.isDisposed();
    }

    private void processOrder(ReceiverRecord<String, String> record) {
        // Your order processing logic
    }
}
```

**Key details:**

- `CommandLineRunner.run()` fires after the full application context is ready (including DB), making the `siteEnvConfigRepo` call safe.
- `synchronized` on `start()`, `stop()`, and `isRunning()` prevents race conditions between the startup thread and the scheduler thread.
- `start()` is idempotent — calling it when already running is a no-op.
- `stop()` disposes the reactive subscription, which causes the Kafka consumer to leave the consumer group and triggers a rebalance.

---

### 2. ConsumerLifecycleScheduler

Periodically reconciles the consumer state with the database.

**File:** `ConsumerLifecycleScheduler.java`

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsumerLifecycleScheduler {

    private final OrderConsumerService orderConsumerService;
    private final SiteEnvConfigRepository siteEnvConfigRepo;

    @Value("${site.name}")
    private String siteName;

    @Scheduled(fixedDelay = 300_000) // 5 minutes
    public void manageConsumerLifecycle() {
        try {
            boolean active = siteEnvConfigRepo
                .findBySiteName(siteName)
                .map(SiteEnvConfig::isActive)
                .orElse(false);

            if (active && !orderConsumerService.isRunning()) {
                orderConsumerService.start();
                log.info("Scheduler — STARTED consumer for site: {}", siteName);
            } else if (!active && orderConsumerService.isRunning()) {
                orderConsumerService.stop();
                log.info("Scheduler — STOPPED consumer for site: {}", siteName);
            }
        } catch (Exception e) {
            log.error("Scheduler — DB check failed, consumer state unchanged.", e);
        }
    }
}
```

**Key details:**

- The `try-catch` ensures that if the DB is temporarily unreachable, the consumer stays in its current state (safe default).
- If the consumer is already in the desired state, the method does nothing.
- The 5-minute interval is configurable. During switchover, the maximum delay before the consumer stops is 5 minutes — orders are not lost during this window, they remain in Kafka.

---

### 3. ReactiveKafkaConfig

Configures the `ReactiveKafkaConsumerTemplate` bean with optimal settings for failover.

**File:** `ReactiveKafkaConfig.java`

```java
@Configuration
public class ReactiveKafkaConfig {

    @Bean
    public ReactiveKafkaConsumerTemplate<String, String> consumerTemplate(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${kafka.consumer.group-id}") String groupId,
            @Value("${kafka.topic.orders}") String topic) {

        ReceiverOptions<String, String> options = ReceiverOptions
            .<String, String>create(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 6000,
                ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 2000,
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                    CooperativeStickyAssignor.class.getName()
            ))
            .subscription(Collections.singletonList(topic));

        return new ReactiveKafkaConsumerTemplate<>(options);
    }
}
```

**Key settings:**

| Property | Value | Why |
|---|---|---|
| `session.timeout.ms` | `6000` | Minimum allowed. Kafka detects a dead consumer in 6 seconds. |
| `heartbeat.interval.ms` | `2000` | 1/3 of session timeout (recommended ratio). |
| `partition.assignment.strategy` | `CooperativeStickyAssignor` | During rebalance, only affected partitions move. GCP continues consuming its existing partitions uninterrupted. |

---

### 4. Entity and Repository

**File:** `SiteEnvConfig.java`

```java
@Entity
@Table(name = "site_env_config")
@Data
public class SiteEnvConfig {
    @Id
    @Column(name = "site_name")
    private String siteName;

    @Column(name = "active")
    private boolean active;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

**File:** `SiteEnvConfigRepository.java`

```java
@Repository
public interface SiteEnvConfigRepository extends JpaRepository<SiteEnvConfig, String> {

    Optional<SiteEnvConfig> findBySiteName(String siteName);

    @Modifying
    @Transactional
    @Query("UPDATE SiteEnvConfig s SET s.active = :active, " +
           "s.updatedAt = CURRENT_TIMESTAMP WHERE s.siteName = :siteName")
    void updateActive(@Param("siteName") String siteName,
                      @Param("active") boolean active);
}
```

---

### 5. Status Endpoint (Optional)

Lets you verify consumer state before enabling GCP during a switchover.

**File:** `ConsumerStatusController.java`

```java
@RestController
@RequiredArgsConstructor
public class ConsumerStatusController {

    private final OrderConsumerService orderConsumerService;

    @GetMapping("/consumer/status")
    public Map<String, Object> status() {
        return Map.of("running", orderConsumerService.isRunning());
    }
}
```

---

## Configuration

### application.yml (Edge)

```yaml
site:
  name: "store-dallas-01"        # Unique per edge deployment
  environment: "edge"

kafka:
  topic:
    orders: "orders"
  consumer:
    group-id: "order-consumer-group"  # SAME across all edge + GCP

spring:
  kafka:
    bootstrap-servers: "kafka-broker:9092"
```

### application.yml (GCP)

```yaml
site:
  name: "gcp-central"
  environment: "gcp"

kafka:
  topic:
    orders: "orders"
  consumer:
    group-id: "order-consumer-group"  # SAME as edge
```

> **The GCP consumer runs unconditionally** — it does not check the DB or use the scheduler. It is always part of the consumer group. When edge consumers leave, Kafka rebalances their partitions to GCP.

---

## Database Schema

```sql
CREATE TABLE site_env_config (
    site_name    VARCHAR(100) PRIMARY KEY,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMP DEFAULT now()
);

-- Example data
INSERT INTO site_env_config (site_name, active) VALUES
    ('store-dallas-01', true),
    ('store-chicago-02', true),
    ('store-nyc-03', false);    -- This site is handled by GCP
```

---

## Operational Runbook

### Switching a Site from Edge to GCP

1. Update the database:
   ```sql
   UPDATE site_env_config SET active = false WHERE site_name = 'store-dallas-01';
   ```
2. Wait up to 5 minutes for the scheduler to pick up the change.
3. (Optional) Confirm the consumer is stopped:
   ```bash
   curl http://edge-host:8080/consumer/status
   # Expected: {"running": false}
   ```
4. Enable the GCP consumer for this site (if not already running).

### Switching a Site Back to Edge

1. Disable the GCP consumer for this site (if applicable).
2. Update the database:
   ```sql
   UPDATE site_env_config SET active = true WHERE site_name = 'store-dallas-01';
   ```
3. Wait up to 5 minutes, or restart the edge app for immediate effect.

### Emergency: Edge App Restarts While Disabled

No action needed. The `CommandLineRunner.run()` method checks the DB on startup. If `active = false`, the consumer never subscribes. GCP continues handling the site's orders.

---

## Design Decisions

| Decision | Rationale |
|---|---|
| **Same `group.id` everywhere** | Kafka's consumer group protocol handles failover automatically. When edge stops, its partitions rebalance to GCP. |
| **DB as single source of truth** | No control topics or external signals. Simple, auditable, easy to reason about. |
| **`synchronized` on lifecycle methods** | Prevents race conditions between the startup thread and the scheduler thread. |
| **`CooperativeStickyAssignor`** | Minimizes disruption during rebalance — only affected partitions move. |
| **5-minute scheduler interval** | Balances responsiveness with DB load. Orders are never lost during the window; they remain in Kafka until a consumer picks them up. |
| **`orElse(false)` default** | If the site is not found in the DB, the consumer stays stopped — fail-safe behavior. |
| **Operational guarantee (disable edge before enabling GCP)** | Eliminates the risk of two consumers processing the same partition simultaneously. Simplifies the design significantly. |

---

## Edge Cases

| Scenario | Behavior |
|---|---|
| DB unreachable during scheduler run | `try-catch` keeps consumer in current state. Logs the error. |
| DB unreachable during app startup | `run()` throws exception; Spring Boot fails to start. Consumer never joins group. GCP is unaffected. |
| `start()` called when already running | Idempotent — logs and returns without creating a duplicate subscription. |
| `stop()` called when already stopped | No-op — safe to call multiple times. |
| Edge and GCP both active for same site | Both share partitions via Kafka rebalancing. Not recommended but not catastrophic — messages are processed once (by whichever consumer owns the partition). Avoid by following the operational runbook. |
| Edge app killed (not graceful shutdown) | Kafka detects the dead consumer via `session.timeout.ms` (6s) and rebalances to GCP. DB flag is irrelevant here. |
