Create a Spring Boot 3.2 service called "order-sync-consumer" that consumes Kafka messages and dynamically upserts data into a GCP PostgreSQL database.

## Context
- 40 pharmacy edge sites publish order sync messages to a Kafka topic "order-sync-events"
- Each message contains a JSON bundle with data from multiple tables for one order
- This consumer runs on GCP and upserts the data into a central Cloud SQL PostgreSQL database
- All tables have a composite primary key that includes site_id (tables are partitioned by site_id)
- The consumer must handle ANY table dynamically — no table-specific code

## Sample Kafka Message
```json
{
  "messageId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "orderId": "ORD-500",
  "siteId": "SITE_001",
  "eventType": "ORDER_PICKED",
  "bundleJson": "{\"orders\":[{\"id\":1,\"site_id\":\"SITE_001\",\"order_id\":\"ORD-500\",\"status\":\"PICKED\",\"priority\":1,\"created_at\":\"2026-02-22T10:00:00Z\",\"updated_at\":\"2026-02-22T10:30:00Z\"}],\"rx\":[{\"id\":100,\"site_id\":\"SITE_001\",\"order_id\":\"ORD-500\",\"rx_number\":\"RX-001\",\"drug_name\":\"Lisinopril\",\"quantity\":30,\"status\":\"ACTIVE\",\"created_at\":\"2026-02-22T10:00:00Z\"},{\"id\":101,\"site_id\":\"SITE_001\",\"order_id\":\"ORD-500\",\"rx_number\":\"RX-002\",\"drug_name\":\"Metformin\",\"quantity\":60,\"status\":\"ACTIVE\",\"created_at\":\"2026-02-22T10:00:00Z\"}],\"container\":[{\"id\":300,\"site_id\":\"SITE_001\",\"order_id\":\"ORD-500\",\"container_type\":\"VIAL\",\"barcode\":\"CTN-12345\",\"status\":\"PICKED\",\"created_at\":\"2026-02-22T10:30:00Z\"}],\"container_content\":[{\"id\":400,\"site_id\":\"SITE_001\",\"order_id\":\"ORD-500\",\"container_id\":300,\"rx_id\":100,\"quantity\":30,\"created_at\":\"2026-02-22T10:30:00Z\"},{\"id\":401,\"site_id\":\"SITE_001\",\"order_id\":\"ORD-500\",\"container_id\":300,\"rx_id\":101,\"quantity\":60,\"created_at\":\"2026-02-22T10:30:00Z\"}],\"order_history\":[{\"id\":50,\"site_id\":\"SITE_001\",\"order_id\":\"ORD-500\",\"action\":\"PICKED\",\"performed_by\":\"USER_01\",\"created_at\":\"2026-02-22T10:30:00Z\"}],\"rx_loc\":[],\"rx_staging\":[],\"rx_history\":[],\"container_history\":[],\"container_content_history\":[],\"tote\":[],\"tote_history\":[],\"order_details\":[]}",
  "createdAt": "2026-02-22T10:30:05Z",
  "outboxId": 42,
  "retryCount": 0
}
```

Note: bundleJson is a JSON string (escaped) containing table data. Each key is a table name, each value is a JSON array of row objects. Empty arrays mean no data for that table in this event.

## Project Structure
order-sync-consumer/
├── pom.xml
├── src/main/java/com/pharmacy/sync/consumer/
│   ├── OrderSyncConsumerApplication.java
│   ├── config/
│   │   └── KafkaConsumerConfig.java
│   ├── listener/
│   │   └── OrderSyncListener.java
│   ├── service/
│   │   ├── SyncProcessingService.java
│   │   └── DynamicUpsertService.java
│   └── model/
│       ├── SyncMessage.java
│       └── ColumnMeta.java
└── src/main/resources/
└── application.yml

## Component Details

### 1. KafkaConsumerConfig
- MANUAL_IMMEDIATE ack mode: offset committed only after successful database upsert
- ErrorHandlingDeserializer wrapping StringDeserializer: prevents poison pill messages from killing the consumer. If a message can't be deserialized, it is logged and skipped (acknowledged), not retried.
- DefaultErrorHandler with FixedBackOff: retry 3 times with 5-second delay on processing errors. After 3 retries, the message is skipped.
- Add JsonProcessingException as a not-retryable exception (no point retrying bad JSON)
- max.poll.records=10, enable.auto.commit=false, session.timeout.ms=30000
- Concurrency configurable via property (default 3)

### 2. OrderSyncListener (@KafkaListener)
- Listens to topic from config property: ${order-sync.consumer.topic:order-sync-events}
- Receives ConsumerRecord<String, String> and Acknowledgment
- Null/empty value check: log warning, acknowledge, return
- Deserializes value to SyncMessage using ObjectMapper with JavaTimeModule
- If deserialization fails: log error, acknowledge (skip poison pill), return
- Validates required fields: orderId, siteId, bundleJson must not be null/blank. If invalid, log error, acknowledge, return.
- Calls SyncProcessingService.processMessage(message)
- On success: acknowledgment.acknowledge() and log with partition/offset
- On failure: do NOT acknowledge. Throw RuntimeException so the error handler retries.

### 3. SyncProcessingService
- processMessage(SyncMessage) method annotated with @Transactional
- Validates message fields (null checks)
- Parses bundleJson string into JsonNode using ObjectMapper
- Iterates through each key (table name) in the bundle JsonNode
- For each table: skips if not an array, skips if empty array, skips if not in allowed table set
- Delegates to DynamicUpsertService.upsertTable(tableName, recordsJsonNode)
- Returns Map<String, Integer> of table name → rows upserted
- Logs: order, site, event, tables processed, total rows, elapsed time
- If any table fails, the entire transaction rolls back (all-or-nothing per message)

### 4. DynamicUpsertService (this is the core — most important component)

This service upserts data into ANY table without table-specific code. It reads metadata from the database catalog and builds SQL dynamically.

**Allowed tables** (security guard against SQL injection):
orders, patient, rx, order_details, rx_details, order_comment, order_history, order_hold_exclusion, tote, tote_history, rx_staging, rx_loc, rx_history, container, container_content, container_history, container_content_history

**upsertTable(String tableName, JsonNode records) method:**

Step 1: Validate table name is in allowed set
Step 2: Get column metadata from information_schema (cached in ConcurrentHashMap):
```sql
SELECT column_name, data_type FROM information_schema.columns
WHERE table_name = ? AND table_schema = 'public'
ORDER BY ordinal_position
```
Step 3: Get primary key columns from pg_index (cached in ConcurrentHashMap):
```sql
SELECT a.attname AS column_name
FROM pg_index i
JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
WHERE i.indrelid = ?::regclass AND i.indisprimary
ORDER BY array_position(i.indkey, a.attnum)
```
Step 4: Determine active columns = intersection of JSON keys and actual table columns
Step 5: Verify all PK columns are present in JSON (if not, log error and return 0)
Step 6: Build dynamic SQL:
```sql
INSERT INTO {table} ({columns}) VALUES (?, ?, ...)
ON CONFLICT ({pk_columns}) DO UPDATE SET
  col1 = EXCLUDED.col1, col2 = EXCLUDED.col2, ...
```
(exclude PK columns from the SET clause. If all columns are PK, use DO NOTHING)

Step 7: Build batch parameters — for each JSON record, convert values to correct Java types based on column data_type:
- integer/smallint → int
- bigint → long
- numeric/decimal → BigDecimal
- double precision/real → double
- boolean → boolean
- timestamp with time zone / timestamp without time zone → java.sql.Timestamp (parse from ISO-8601 using Instant.parse, fallback to Timestamp.valueOf)
- date → java.sql.Date
- uuid → UUID
- jsonb/json → PGobject with type="jsonb"
- everything else → String (text, varchar, char)
- null JSON values → null

Step 8: Execute jdbcTemplate.batchUpdate(sql, batchParams)
Step 9: Return total rows affected

**Cache management:**
- Column metadata and PK columns cached in ConcurrentHashMap<String, ...>
- Provide a clearCache() method for manual refresh after schema changes
- Cache is populated lazily on first access per table

### 5. SyncMessage model
Simple POJO with: messageId, orderId, siteId, eventType, bundleJson (String), createdAt (Instant), outboxId (Long), retryCount (int). Use @JsonIgnoreProperties(ignoreUnknown = true).

### 6. ColumnMeta model
Simple record or class with: columnName (String), dataType (String)

### 7. application.yml
```yaml
server:
  port: 8080
spring:
  application:
    name: order-sync-consumer
  datasource:
    url: jdbc:postgresql://${GCP_DB_HOST:localhost}:${GCP_DB_PORT:5432}/${GCP_DB_NAME:pharmacy_db}
    username: ${GCP_DB_USERNAME:postgres}
    password: ${GCP_DB_PASSWORD:}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: order-sync-consumer-group
order-sync:
  consumer:
    topic: order-sync-events
    concurrency: 3
logging:
  level:
    com.pharmacy.sync.consumer: DEBUG
    org.springframework.kafka: WARN
```

## Quality Requirements
- Production-grade: null checks on every input, validate before processing
- Exception handling: catch specific exceptions, log with context (orderId, siteId, tableName, partition, offset)
- SLF4J logging at appropriate levels (DEBUG for routine, INFO for milestones, WARN for recoverable issues, ERROR for failures)
- Thread-safe caching using ConcurrentHashMap
- SQL injection prevention: only allow tables in the hardcoded ALLOWED_TABLES set
- Type conversion: handle edge cases (null values, blank strings, unparseable timestamps with fallback)
- Javadoc on all public methods

Create all files with complete, production-ready code.

This prompt is self-contained — it includes the sample Kafka message, the exact SQL queries for metadata lookup, the type conversion rules, and every design decision. Claude on your other machine won't need any follow-up context from this conversation.
