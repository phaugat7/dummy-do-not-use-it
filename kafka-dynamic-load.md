# Reactive Kafka Consumer Lifecycle Design

## Problem Statement

We have multiple **edge environments** (one per site/store) and one centralized **GCP environment**. An `order-consumer` service runs in both locations using `ReactiveKafkaConsumerTemplate`. We need:

1. On app startup, **don't start** the Kafka consumer if the site is disabled in the database.
2. A **scheduler** (every 5 minutes) that starts or stops the Kafka consumer based on the `site_env_config` table.
3. When edge is disabled, GCP picks up the orders using the **same consumer group ID**.
4. Edge is always disabled **before** GCP is enabled (operational guarantee).

---

## Database Schema

```sql
CREATE TABLE site_env_config (
    site_name    VARCHAR(100) PRIMARY KEY,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMP DEFAULT now()
);
```

---

## Implementation

### 1. Reactive Kafka Configuration

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

### 2. Order Consumer Service

The core service that owns the reactive subscription lifecycle. `start()` subscribes to the Kafka topic, `stop()` disposes the subscription (causing the consumer to leave the group and trigger a Kafka rebalance).

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderConsumerService {

    private final ReactiveKafkaConsumerTemplate<String, String> consumerTemplate;
    private Disposable consumerSubscription;

    /**
     * Start consuming — subscribes to the reactive stream.
     * Safe to call multiple times; won't double-subscribe.
     */
    public synchronized void start() {
        if (consumerSubscription != null && !consumerSubscription.isDisposed()) {
            log.info("Consumer already running, skipping start.");
            return;
        }

        consumerSubscription = consumerTemplate
            .receiveAutoAck()
            .doOnNext(record -> {
                log.info("Processing order: key={}, partition={}",
                         record.key(), record.partition());
                processOrder(record);
            })
            .doOnError(e -> log.error("Consumer error", e))
            .onErrorResume(e -> {
                log.warn("Consumer resubscribing after error...");
                return Mono.empty();
            })
            .repeat()
            .subscribe();

        log.info("Reactive Kafka consumer STARTED.");
    }

    /**
     * Stop consuming — disposes the subscription.
     * Consumer leaves the group, triggering Kafka rebalance.
     */
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

> **Note:** The `synchronized` keyword on `start()`, `stop()`, and `isRunning()` is important — the scheduler runs on a different thread than the startup hook, and we don't want them racing to create duplicate subscriptions.

### 3. Startup Hook — DB Check Before Consuming

Uses `ApplicationReadyEvent` (a Spring Boot event, not tied to imperative Kafka) to check the database **before** starting the consumer. If the site is disabled, the consumer never starts.

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsumerStartupHook {

    private final OrderConsumerService orderConsumerService;
    private final SiteEnvConfigRepository siteEnvConfigRepo;

    @Value("${site.name}")
    private String siteName;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        boolean active = siteEnvConfigRepo
            .findBySiteName(siteName)
            .map(SiteEnvConfig::isActive)
            .orElse(false);

        if (active) {
            orderConsumerService.start();
            log.info("Startup — site {} is active, consumer started.", siteName);
        } else {
            log.info("Startup — site {} is inactive, consumer remains stopped.", siteName);
        }
    }
}
```

### 4. Scheduler — 5 Minute Reconciliation

Periodically checks the database and starts/stops the consumer accordingly. Acts as the mechanism for planned switchovers and as a safety net.

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

> **Important:** The `try-catch` ensures that if the DB is temporarily unreachable, the consumer stays in whatever state it was in (safe default behavior).

### 5. Entity and Repository

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

### 6. Status Endpoint

Optional but recommended — lets you verify consumer state before enabling GCP.

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

## Application Configuration

```yaml
site:
  name: "store-dallas-01"        # Unique per edge deployment
  environment: "edge"             # or "gcp"

kafka:
  topic:
    orders: "orders"
  consumer:
    group-id: "order-consumer-group"  # SAME everywhere (edge + GCP)

spring:
  kafka:
    bootstrap-servers: "kafka-broker:9092"
    consumer:
      properties:
        session.timeout.ms: 6000
        heartbeat.interval.ms: 2000
        partition.assignment.strategy: org.apache.kafka.clients.consumer.CooperativeStickyAssignor
```

---

## Lifecycle Flow

```
APP STARTUP (edge)
    │
    ├── ApplicationReadyEvent fires
    │     ├── Check DB: is site active?
    │     │     ├── YES → orderConsumerService.start() → joins group → consumes orders
    │     │     └── NO  → consumer never starts → GCP handles this site
    │
    └── Scheduled reconciler begins (every 5 min)


PLANNED SWITCHOVER (edge → GCP)
    │
    ├── Set active=false in edge DB
    ├── Wait for scheduler cycle (up to 5 min)
    │     └── Scheduler detects change → stop() → consumer leaves group → Kafka rebalances
    ├── (Optional) Hit GET /consumer/status to confirm stopped
    └── Enable GCP consumer → GCP picks up the partitions


APP RESTART WHILE DISABLED
    │
    └── Startup hook reads DB → active=false → consumer never starts → GCP keeps consuming
```

---

## Key Design Decisions

- **Same `group.id` everywhere:** Kafka's consumer group protocol handles failover automatically. When edge stops, its partitions rebalance to GCP.
- **`autoStartup = false` equivalent:** We never auto-subscribe. The startup hook and scheduler are the only paths to `start()`.
- **`synchronized` on lifecycle methods:** Prevents race conditions between the startup hook and the scheduler thread.
- **`CooperativeStickyAssignor`:** During rebalance, only affected partitions move — GCP continues consuming its existing partitions uninterrupted.
- **DB as single source of truth:** No control topics or external signals. Simple, auditable, easy to reason about.
- **Operational guarantee:** Edge is always disabled before GCP is enabled, so there's no risk of two consumers fighting over the same partitions.
