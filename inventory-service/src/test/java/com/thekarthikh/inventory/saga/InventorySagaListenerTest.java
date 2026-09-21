package com.thekarthikh.inventory.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thekarthikh.inventory.entity.InventoryItem;
import com.thekarthikh.inventory.entity.InventoryReservation;
import com.thekarthikh.inventory.repository.InventoryRepository;
import com.thekarthikh.inventory.repository.InventoryReservationRepository;
import com.thekarthikh.inventory.repository.InventorySagaEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventorySagaListenerTest {

    @Mock InventoryRepository inventoryRepository;
    @Mock InventoryReservationRepository reservationRepository;
    @Mock InventorySagaEventRepository eventRepository;
    private InventorySagaListener listener;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        listener = new InventorySagaListener(inventoryRepository, reservationRepository, eventRepository, objectMapper);
    }

    @Test
    void duplicateBookingCreatedDoesNotDeductInventoryTwice() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        InventoryReservation reservation = InventoryReservation.builder().bookingId(bookingId)
                .itemId(itemId).quantity(2).status("RESERVED").build();
        when(reservationRepository.findByBookingIdWithLock(bookingId)).thenReturn(Optional.of(reservation));

        String payload = objectMapper.writeValueAsString(SagaMessage.builder()
                .eventType("BOOKING_CREATED").bookingId(bookingId).itemId(itemId).quantity(2).build());
        listener.handleBookingEvent(new ConsumerRecord<>("booking-events", 0, 0L, bookingId.toString(), payload));

        verifyNoInteractions(inventoryRepository);
        verify(eventRepository).insertIfAbsent(eq(bookingId), eq("INVENTORY_RESERVED"), anyString());
    }

    @Test
    void cancellationWithoutReservationCreatesTombstoneWithoutIncreasingInventory() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        when(reservationRepository.findByBookingIdWithLock(bookingId)).thenReturn(Optional.empty());

        String payload = objectMapper.writeValueAsString(SagaMessage.builder()
                .eventType("BOOKING_CANCELLED").bookingId(bookingId).itemId(itemId).quantity(2).build());
        listener.handleBookingEvent(new ConsumerRecord<>("booking-events", 0, 0L, bookingId.toString(), payload));

        verify(reservationRepository).save(argThat(saved ->
                saved.getBookingId().equals(bookingId)
                        && saved.getItemId().equals(itemId)
                        && saved.getQuantity().equals(2)
                        && "CANCELLED".equals(saved.getStatus())));
        verifyNoInteractions(inventoryRepository, eventRepository);
    }
}
