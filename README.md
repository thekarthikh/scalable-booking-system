# Scalable Booking System

A backend-only Spring Boot microservices booking system demonstrating reliable booking workflows with PostgreSQL, Redis, Kafka, Saga choreography, a transactional outbox, JWT/RS256 security, and Resilience4j.

## Architecture

    Client (curl / PowerShell / Postman)
                    |
       +------------+------------+
       |                         |
    User Service            Booking Service
       |                         |
       +---------- PostgreSQL --+
                    |
          Redis  <-> Kafka
                    |
       +------------+------------+
       |                         |
    Inventory Service      Notification Service

The services are independently deployable. The local Compose topology uses one PostgreSQL instance with service-owned tables, one Redis instance, and a single Kafka broker. This is intentionally a small, explainable development topology rather than a production high-availability deployment.

## Services

| Service | Port | Responsibility |
| --- | ---: | --- |
| User Service | 8081 | Registration, login, BCrypt password hashing, and RS256 JWT issuance |
| Booking Service | 8082 | Authenticated booking API, idempotency, booking state, cancellation, and booking outbox |
| Inventory Service | 8083 | Inventory reads, admin-protected mutation, row-locked reservation/release, and inventory outbox |
| Notification Service | 8084 | Durable confirmation/cancellation notification records with duplicate-event protection |

## Technology stack

- Java 21 and Spring Boot
- Maven
- PostgreSQL 16 with Flyway and JPA/Hibernate
- Redis 7 for idempotency, rate limiting, and short-lived coordination
- Apache Kafka in KRaft mode
- Spring Security with RS256 JWTs
- Resilience4j retry, timeout, and circuit breaker
- Docker Compose
- PowerShell/REST/JSON command-line operation

## Booking lifecycle

1. The client authenticates with User Service and receives an RS256 JWT.
2. The client sends an authenticated booking request to Booking Service with a UUID v4 idempotency key.
3. Booking Service reads the inventory price and transactionally persists a PENDING booking plus a BOOKING_CREATED outbox event.
4. The outbox relay publishes to Kafka after acknowledgement.
5. Inventory Service consumes the event, locks the inventory row with SELECT FOR UPDATE, checks availability, and records a unique reservation ledger entry.
6. Inventory Service publishes INVENTORY_RESERVED or INVENTORY_FAILED.
7. Booking Service transitions the booking to CONFIRMED or CANCELLED and emits the notification event.
8. DELETE on a confirmed booking emits cancellation. Inventory releases only the matching reservation; an idempotent tombstone prevents a stale create event from reserving after cancellation.

The valid booking state transitions are PENDING -> CONFIRMED, PENDING -> CANCELLED, and CONFIRMED -> CANCELLED. Duplicate and stale terminal events do not reopen or reprocess a booking.

## Saga and Kafka

Kafka topics used by the local system:

- booking-events: BOOKING_CREATED and BOOKING_CANCELLED
- inventory-events: INVENTORY_RESERVED and INVENTORY_FAILED

Messages include the booking, user, item, quantity, and event metadata needed by consumers. Kafka delivery is at least once. Consumers and reservation/notification ledgers are idempotent so a duplicate event does not create a second business effect.

Kafka uses two advertised listeners in Compose:

- Docker-network clients: kafka:9092
- Host-launched JVM clients: localhost:29092

The booking and inventory workflows are Saga choreography. There is no distributed database transaction and exactly-once processing is not claimed.

## Transactional Outbox

Booking and inventory business state changes and their outgoing event rows are written in the same database transaction. A relay claims pending rows, publishes to Kafka, and marks them published only after Kafka acknowledgement. A crash after publish but before the published flag can produce a duplicate; idempotent consumers are therefore part of the correctness design.

## Consistency and concurrency

- PostgreSQL row locking is the correctness authority for inventory allocation.
- A unique reservation ledger prevents duplicate reservation for one booking.
- Database constraints protect identity and event deduplication.
- JPA optimistic locking detects conflicting entity updates.
- Redis locks are short-lived coordination aids and use ownership-safe release; Redis is not treated as the source of truth.
- Booking idempotency binds a key to the authenticated user and request fingerprint. Same key plus the same request replays the result; same key plus a different request returns a conflict.
- Redis rate limiting has an explicit fail-open setting for local availability-first operation. Set REDIS_RATE_LIMITER_FAIL_OPEN=false in a stricter environment to reject requests when Redis is unavailable.

## Security

- Registration and login are exposed by User Service.
- Other business endpoints require a valid RS256 bearer token.
- Inventory mutation requires ROLE_ADMIN; inventory reads are public.
- Booking lookup and cancellation enforce ownership.
- JWT keys are supplied as base64-encoded PKCS#8 private and X.509 public key material through environment variables.
- No credentials or private keys belong in the repository.

## API

User Service:

- POST /api/v1/auth/register
- POST /api/v1/auth/login

Inventory Service:

- GET /api/v1/inventory
- GET /api/v1/inventory/{id}
- POST /api/v1/inventory (ROLE_ADMIN)

Booking Service:

- POST /api/v1/bookings (authenticated; UUID v4 idempotencyKey required)
- GET /api/v1/bookings (authenticated user bookings)
- GET /api/v1/bookings/{bookingId} (owner only)
- DELETE /api/v1/bookings/{bookingId} (owner only)

Health endpoints:

- http://localhost:8081/actuator/health
- http://localhost:8082/actuator/health
- http://localhost:8083/actuator/health
- http://localhost:8084/actuator/health

## Quick start

Prerequisites: Git, Docker Desktop with Compose, Java 21, and Maven. Java/Maven are needed for local builds; Docker runs the complete topology.

Clone the repository:

    git clone https://github.com/thekarthikh/scalable-booking-system.git
    cd scalable-booking-system

Set local-only environment values. The following PowerShell example generates an ephemeral RSA key pair in memory and does not write key files:

    $rsa = [System.Security.Cryptography.RSA]::Create(2048)
    $env:JWT_PRIVATE_KEY = [Convert]::ToBase64String($rsa.ExportPkcs8PrivateKey())
    $env:JWT_PUBLIC_KEY = [Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo())
    $env:POSTGRES_USER = 'bookinguser'
    $env:POSTGRES_PASSWORD = 'change-this-local-password'
    $env:POSTGRES_DB = 'bookingdb'

Start the existing Compose topology:

    docker compose up --build -d
    docker compose ps

Compose starts PostgreSQL, Redis, Kafka, and the four application services. It waits for healthy infrastructure dependencies before starting application containers. The database password is applied when the PostgreSQL volume is initialized; changing POSTGRES_PASSWORD later does not change an existing database role.

Required Compose variables:

| Variable | Purpose |
| --- | --- |
| POSTGRES_USER | PostgreSQL role used by the services |
| POSTGRES_PASSWORD | PostgreSQL role password |
| POSTGRES_DB | Database name; defaults to bookingdb |
| JWT_PRIVATE_KEY | Base64 PKCS#8 RSA private key for User Service |
| JWT_PUBLIC_KEY | Base64 X.509 RSA public key for all JWT consumers |

Optional booking configuration includes INVENTORY_SERVICE_URL, USER_SERVICE_URL, and REDIS_RATE_LIMITER_FAIL_OPEN. Compose supplies the service URLs for the Docker network. Do not commit a .env file containing real credentials.

For host-launched packaged JARs, use localhost:5432, localhost:6379, and localhost:29092. For containers, use postgres:5432, redis:6379, and kafka:9092.

## Command-line demo

After health endpoints report UP:

    $register = @{ username = 'alice'; email = 'alice@example.test'; password = 'StrongPassword123!' } | ConvertTo-Json
    Invoke-RestMethod http://localhost:8081/api/v1/auth/register -Method Post -ContentType 'application/json' -Body $register

    $login = @{ username = 'alice'; password = 'StrongPassword123!' } | ConvertTo-Json
    $token = (Invoke-RestMethod http://localhost:8081/api/v1/auth/login -Method Post -ContentType 'application/json' -Body $login).token

    $request = @{ itemId = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890'; quantity = 1; idempotencyKey = [guid]::NewGuid().ToString() } | ConvertTo-Json
    $created = Invoke-RestMethod http://localhost:8082/api/v1/bookings -Method Post -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json' -Body $request
    $created

    Invoke-RestMethod "http://localhost:8082/api/v1/bookings/$($created.id)" -Headers @{ Authorization = "Bearer $token" }

For the local admin demonstration, promote a test user directly in the local database, then log in again. This is for local development only:

    docker exec -e PGPASSWORD=$env:POSTGRES_PASSWORD booking-postgres psql -U $env:POSTGRES_USER -d $env:POSTGRES_DB -c "UPDATE users SET role='ADMIN' WHERE username='alice';"

The same APIs work with curl. The checked-in postman-collection.json uses variables for credentials, JWT, item, booking, and idempotency values.

## Postman demo flow

1. Set the collection variables for username, email, password, and item_id.
2. Run Register and Login; the Login test script stores the JWT.
3. Run Get All Items and Get Item to confirm the inventory item.
4. Run Create Booking; the test script stores booking_id and idempotency_key.
5. Run Get Booking or Get My Bookings until the Saga reaches CONFIRMED.
6. Replay Create Booking with the same idempotency_key and request body; the same booking is returned and inventory is not reserved twice.
7. Change the request while keeping the same key; the API returns an idempotency conflict.
8. Run Cancel Booking with DELETE /api/v1/bookings/{{booking_id}}.
9. Run Get Booking and Get Item again to verify CANCELLED and released inventory.

## Testing and verification

Run all backend modules from PowerShell:

    $services = 'user-service','booking-service','inventory-service','notification-service'
    foreach ($service in $services) {
        Push-Location $service
        mvn clean test
        mvn package
        Pop-Location
    }

The checked-in tests focus on core behavior, including transactional booking/outbox writes, request-bound idempotency, state transitions, duplicate inventory events, cancellation without a reservation, JWT validation, and security rules. They are not a substitute for a full Testcontainers suite.

A live local verification on 2026-09-21 used packaged JARs against the running PostgreSQL 16.15, Redis 7.4.11, and Kafka 7.6.1 Compose infrastructure. It verified registration/login, JWT authorization and ownership checks, admin inventory protection, booking confirmation, Saga compensation, cancellation/release, notification persistence, Flyway version 3, Redis outage fallback/recovery, rate limiting, bounded Resilience4j timeout/circuit recovery, duplicate Kafka replay, stale-event handling, and concurrent contention.

Recorded concurrency observation: 10 requests against capacity 5 resulted in 5 confirmed and 5 cancelled bookings, 5 reserved ledger rows, and no over-allocation.

Recorded load observation (environment-specific, not a capacity claim): JDK 24.0.2, Docker-mounted packaged JARs, PostgreSQL 16.15, Redis 7.4.11, Kafka 7.6.1, fresh capacity-25 item, 20 requests, configured concurrency 5, no warmup: 20 successes, 0 failures, 15.32 requests/second, p50 53.49 ms, p95 199.97 ms, p99 211.89 ms, 20 unique booking IDs, 0 duplicates, and inventory 25 to 5.

The final bounded Docker Compose build completed successfully using the existing BuildKit cache, and the resulting application images started healthy. An earlier uncached attempt stalled during containerized Maven dependency resolution; no Maven, TLS, repository, or security workaround was used. The packaged JARs were also live-verified against the same healthy infrastructure.

## Project structure

    booking-service/       Booking API, Saga coordinator, outbox relay, tests
    inventory-service/     Inventory API, reservation ledger, Saga listener, tests
    notification-service/  Notification consumer and persistence
    user-service/          Registration, login, JWT issuance, tests
    scripts/               Command-line load harness
    docker-compose.yml     Local PostgreSQL, Redis, Kafka, and service topology
    init.sql               Local database bootstrap
    postman-collection.json API demo collection
    DEPLOY.md              Local deployment notes
    BookingSystemSimulator.java
                             In-memory demonstration only; not a production benchmark

## Known limitations

- Kafka delivery is at least once; exactly-once processing is not claimed.
- Compose is a single-node local topology and is not a production HA deployment.
- Services share one PostgreSQL instance locally; separate service databases are a future deployment concern.
- Notification delivery is persisted; no external email/SMS provider is configured.
- The load result is one environment-specific observation and is not a throughput, SLA, availability, or capacity guarantee.
- A clean uncached Docker build may still depend on Docker's ability to reach Maven Central; the final build recorded here used existing BuildKit cache layers.
