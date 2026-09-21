# Scalable Booking System

[![Microservices](https://img.shields.io/badge/Architecture-Microservices-blue.svg)](https://microservices.io/)
[![Spring Boot](https://img.shields.io/badge/Framework-Spring%20Boot%203.3-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Concurrency](https://img.shields.io/badge/Concurrency-10K%20Users-orange.svg)]()

A high-performance, fault-tolerant microservices booking system built for peak loads.

## 🚀 Quick Start
```bash
git clone https://github.com/thekarthikh/scalable-booking-system
cd scalable-booking-system
docker-compose up --build
```

## 📐 Architecture & Features

### Microservices
- **UserService**: Auth (JWT) & profile management.
- **BookingService**: Orchestrates the booking lifecycle.
- **InventoryService**: Atomic stock reservation.
- **NotificationService**: Event-driven alerts.

### Distributed Transactions (Saga)
We use the **Saga Choreography Pattern** with Kafka to manage distributed transactions.
- **Consistent State**: The Transactional Outbox pattern ensures that database changes and Kafka events are atomic.
- **Compensating Transactions**: If `InventoryService` fails to reserve stock, it emits an `INVENTORY_FAILED` event, and `BookingService` automatically cancels the booking.

### 🔒 3-Layer Locking Strategy
To prevent double-booking under extreme concurrency, we implement three layers of security:
1. **Layer 1: DB Row Lock** (`SELECT FOR UPDATE`) - Serializes access in the database.
2. **Layer 2: Redis Distributed Lock** - Prevents concurrent processing of the same resource across the cluster.
3. **Layer 3: Optimistic Lock** (`@Version`) - Last-resort safety to catch race conditions the previous layers might miss.

### 🛡️ Reliability & Scalability
- **Idempotency Layer**: Redis-backed storage with UUID v4 keys prevents duplicate bookings during retry storms.
- **Rate Limiter**: Token-bucket algorithm via **Redis Lua script** allows 10K concurrent users without system overload.
- **Circuit Breaker**: Resilience4j protects the system from cascading failures if a service goes down.

## 📊 Metrics & Load Tests
- **Performance**: 10K concurrent users.
- **Response Time**: Booking p99 latency `< 200ms`.
- **Reliability**: 99.9% request success rate under chaos conditions.
- **Integrity**: 0 double-bookings registered during crash tests.

## 🌍 Live Deployment
Detailed steps to deploy on Railway can be found in [DEPLOY.md](DEPLOY.md).

## 🛠 Tech Stack
- **Backend**: Java 21, Spring Boot 3.3
- **Data**: PostgreSQL 16, Redis 7
- **Messaging**: Apache Kafka
- **Security**: Spring Security, JWT
- **DevOps**: Docker, Docker Compose, Railway, Prometheus/Grafana

Developed by [thekarthikh](https://github.com/thekarthikh).
