package com.thekarthikh.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thekarthikh.booking.client.InventoryItemDto;
import com.thekarthikh.booking.dto.CreateBookingRequest;
import com.thekarthikh.booking.entity.Booking;
import com.thekarthikh.booking.entity.SagaEvent;
import com.thekarthikh.booking.exception.DuplicateBookingException;
import com.thekarthikh.booking.repository.BookingRepository;
import com.thekarthikh.booking.repository.SagaEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingTransactionServiceTest {

    @Mock BookingRepository bookingRepository;
    @Mock SagaEventRepository sagaEventRepository;
    private BookingTransactionService service;
    private Validator validator;

    @BeforeEach
    void setUp() {
        service = new BookingTransactionService(bookingRepository, sagaEventRepository, new ObjectMapper());
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void createsBookingAndOutboxEntryWithPriceSnapshot() {
        UUID userId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        CreateBookingRequest request = request(itemId, 2, UUID.randomUUID().toString());
        InventoryItemDto item = InventoryItemDto.builder().id(itemId).price(new BigDecimal("12.50")).build();
        when(bookingRepository.findByIdempotencyKey(request.getIdempotencyKey())).thenReturn(Optional.empty());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> {
            Booking booking = invocation.getArgument(0);
            booking.setId(UUID.randomUUID());
            return booking;
        });

        var response = service.createPendingBooking(userId, request, item);

        assertThat(response.getTotalPrice()).isEqualByComparingTo("25.00");
        ArgumentCaptor<SagaEvent> event = ArgumentCaptor.forClass(SagaEvent.class);
        verify(sagaEventRepository).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("BOOKING_CREATED");
        assertThat(event.getValue().getPayload()).contains(response.getId().toString());
    }

    @Test
    void sameIdempotencyKeyReturnsExistingBookingWithoutCreatingAnotherOutboxEvent() {
        UUID userId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        CreateBookingRequest request = request(itemId, 1, key);
        Booking existing = Booking.builder().id(UUID.randomUUID()).idempotencyKey(key).userId(userId)
                .itemId(itemId).quantity(1).totalPrice(new BigDecimal("10.00"))
                .status("PENDING").sagaStatus("STARTED").build();
        when(bookingRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));

        var response = service.createPendingBooking(userId, request,
                InventoryItemDto.builder().id(itemId).price(new BigDecimal("10.00")).build());

        assertThat(response.getId()).isEqualTo(existing.getId());
        verify(bookingRepository, never()).save(any());
        verifyNoInteractions(sagaEventRepository);
    }

    @Test
    void rejectsReusingIdempotencyKeyForDifferentRequest() {
        UUID userId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        Booking existing = Booking.builder().id(UUID.randomUUID()).idempotencyKey(key).userId(userId)
                .itemId(itemId).quantity(1).totalPrice(new BigDecimal("10.00")).build();
        when(bookingRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createPendingBooking(userId,
                request(UUID.randomUUID(), 1, key),
                InventoryItemDto.builder().price(new BigDecimal("10.00")).build()))
                .isInstanceOf(DuplicateBookingException.class);
    }

    @Test
    void rejectsInvalidRequestFields() {
        CreateBookingRequest request = new CreateBookingRequest();
        request.setItemId(UUID.randomUUID());
        request.setQuantity(0);
        request.setIdempotencyKey("not-a-v4-key");

        assertThat(validator.validate(request)).hasSize(2);
    }

    private CreateBookingRequest request(UUID itemId, int quantity, String key) {
        CreateBookingRequest request = new CreateBookingRequest();
        request.setItemId(itemId);
        request.setQuantity(quantity);
        request.setIdempotencyKey(key);
        return request;
    }
}
