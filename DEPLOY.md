# Deployment Guide

This guide describes the local development deployment. It does not certify production capacity or availability.

## Local Deployment

1. **Prerequisites**:
   - Docker and Docker Compose installed.
   - Java 21+ (if running locally outside Docker).
   - Maven.

2. **Step 1: Clone the repository**
   ```bash
   git clone https://github.com/thekarthikh/scalable-booking-system
   cd scalable-booking-system
   ```

3. **Step 2: Start all services**
   Export `POSTGRES_USER`, `POSTGRES_PASSWORD`, `JWT_PRIVATE_KEY`, and
   `JWT_PUBLIC_KEY` first. Compose intentionally fails when required values
   are missing.
   ```bash
   docker compose up --build
   ```

4. **Step 3: Access Infrastructure**
   - **PostgreSQL**: `localhost:5432` (bookingdb)
   - **Redis**: `localhost:6379`
   - **Kafka (Docker network)**: `kafka:9092`
   - **Kafka (host-launched JARs)**: `localhost:29092`

5. **Step 4: Verify Services**
   - User Service: `http://localhost:8081/actuator/health`
   - Booking Service: `http://localhost:8082/actuator/health`
   - Inventory Service: `http://localhost:8083/actuator/health`
   - Notification Service: `http://localhost:8084/actuator/health`

## Load Testing

The repository includes `scripts/booking-load-test.ps1`. It is a command-line
harness, not a published benchmark. Record the machine, Java version, warmup,
concurrency, duration, request mix, success rate, throughput, p50/p95/p99
latency, and final inventory correctness for each real run.
