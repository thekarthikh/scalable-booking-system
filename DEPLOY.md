# Deployment Guide

This microservices system is designed for high-concurrency and reliability.

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
   ```bash
   docker-compose up --build
   ```

4. **Step 3: Access Infrastructure**
   - **PostgreSQL**: `localhost:5432` (bookingdb)
   - **Redis**: `localhost:6379`
   - **Kafka**: `localhost:9092`

5. **Step 4: Verify Services**
   - User Service: `http://localhost:8081/actuator/health`
   - Booking Service: `http://localhost:8082/actuator/health`
   - Inventory Service: `http://localhost:8083/actuator/health`
   - Notification Service: `http://localhost:8084/actuator/health`

## Railway Deployment

1. **Install Railway CLI**: `npm i -g @railway/cli`
2. **Login**: `railway login`
3. **Initialize Project**: `railway init`
4. **Link Database**: Add Postgres and Redis plugins via Railway Dashboard.
5. **Set Environment Variables**: Copy from `docker-compose.yml` properties.
6. **Deploy**: `railway up`

## Load Testing
Use `k6` or `JMeter` with the provided `postman-collection.json` as a base. Recommendation:
- Peak load: 10K concurrent users.
- Constant load: 2K requests/sec.
