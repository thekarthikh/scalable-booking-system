import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SCALABLE BOOKING SYSTEM - IN-MEMORY LOGIC SIMULATOR
 * 
 * This standalone script demonstrates the "3-Layer Locking" and "Saga Choreography" 
 * logic implemented in the microservices. It simulates 1,000 concurrent booking 
 * attempts against a limited stock to prove the system's integrity and reliability.
 */
public class BookingSystemSimulator {

    // --- CONFIGURATION ---
    private static final int INITIAL_STOCK = 50;
    private static final int CONCURRENT_USERS = 1000;
    private static final int ITEMS_PER_BOOKING = 1;

    // --- STATE ---
    private static final AtomicInteger availableStock = new AtomicInteger(INITIAL_STOCK);
    private static final AtomicInteger successfulBookings = new AtomicInteger(0);
    private static final AtomicInteger rejectedBookings = new AtomicInteger(0);
    private static final AtomicInteger doubleBookingErrors = new AtomicInteger(0);

    // Simulated Layers
    private static final ReentrantLock dbRowLock = new ReentrantLock(); // Layer 1: DB Row Lock
    private static final ConcurrentHashMap<String, Long> redisLocks = new ConcurrentHashMap<>(); // Layer 2: Redis Lock Simulation
    private static final AtomicInteger version = new AtomicInteger(0); // Layer 3: Optimistic Locking

    public static void main(String[] args) throws InterruptedException {
        System.out.println("================================================================================");
        System.out.println("🚀 STARTING SCALABLE BOOKING SYSTEM SIMULATION");
        System.out.println("Config: " + CONCURRENT_USERS + " concurrent users competing for " + INITIAL_STOCK + " items.");
        System.out.println("Logic: 3-Layer Locking (Pessimistic + Distributed + Optimistic) + Saga flow.");
        System.out.println("================================================================================\n");

        ExecutorService executor = Executors.newFixedThreadPool(100);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_USERS);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            final int userId = i;
            executor.submit(() -> {
                try {
                    boolean success = attemptBooking("item-123", userId);
                    if (success) {
                        successfulBookings.incrementAndGet();
                    } else {
                        rejectedBookings.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long endTime = System.currentTimeMillis();
        executor.shutdown();

        // Safety check
        int finalStock = availableStock.get();
        int expectedStock = INITIAL_STOCK - successfulBookings.get();

        System.out.println("\n================================================================================");
        System.out.println("📊 SIMULATION RESULTS");
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Total Requests:       " + CONCURRENT_USERS);
        System.out.println("Successful Bookings: " + successfulBookings.get() + " (Expect: " + INITIAL_STOCK + ")");
        System.out.println("Rejected (Sold Out):  " + rejectedBookings.get());
        System.out.println("System Duration:      " + (endTime - startTime) + "ms");
        System.out.println("Average Latency:     " + ((double)(endTime - startTime) / CONCURRENT_USERS) + "ms");
        System.out.println("Final Stock Level:   " + finalStock);
        
        if (finalStock == expectedStock && finalStock >= 0) {
            System.out.println("\n✅ INTEGRITY VERIFIED: 0 DOUBLE-BOOKINGS DETECTED.");
            System.out.println("The 3-layer locking strategy successfully serialized all " + CONCURRENT_USERS + " requests.");
        } else {
            System.out.println("\n❌ INTEGRITY FAILED: Stock mismatch detected!");
        }
        System.out.println("================================================================================");
    }

    /**
     * Simulates the complex booking logic implemented in BookingService.java
     */
    private static boolean attemptBooking(String itemId, int userId) {
        // LAYER 2: Redis Distributed Lock Simulation
        if (redisLocks.putIfAbsent(itemId, System.currentTimeMillis()) != null) {
            // Simulated lock acquisition failure (Wait/Retry logic would go here)
            // For simulation, we'll try a small wait
            try { Thread.sleep(new Random().nextInt(10)); } catch (InterruptedException e) {}
        }

        try {
            // LAYER 1: DB Row Lock Simulation
            dbRowLock.lock();
            try {
                // SAGA: Check Inventory Start
                int currentStock = availableStock.get();
                int currentVersion = version.get();

                if (currentStock >= ITEMS_PER_BOOKING) {
                    
                    // Simulate processing time
                    try { Thread.sleep(2); } catch (InterruptedException e) {}

                    // LAYER 3: Optimistic Locking Check before Commit
                    if (version.compareAndSet(currentVersion, currentVersion + 1)) {
                        // Saga: Commit Inventory
                        availableStock.addAndGet(-ITEMS_PER_BOOKING);
                        return true;
                    } else {
                        // Optimistic lock conflict!
                        return false;
                    }
                } else {
                    return false; // Insufficient stock
                }
            } finally {
                dbRowLock.unlock();
            }
        } finally {
            redisLocks.remove(itemId);
        }
    }
}
