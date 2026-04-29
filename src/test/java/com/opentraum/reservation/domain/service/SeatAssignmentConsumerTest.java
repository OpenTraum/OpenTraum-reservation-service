package com.opentraum.reservation.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeatAssignmentConsumerTest {

    private final LotteryTrackService lotteryTrackService = mock(LotteryTrackService.class);
    private final SeatAssignmentConsumer consumer = new SeatAssignmentConsumer(new ObjectMapper(), lotteryTrackService);

    @Test
    void handlesCamelCaseSeatAssignmentPayload() {
        when(lotteryTrackService.onPaymentCompleted(10L, 20L, 30L)).thenReturn(Mono.empty());
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.handleSeatAssignment(record("{\"reservationId\":10,\"userId\":20,\"scheduleId\":30}"), ack);

        verify(lotteryTrackService).onPaymentCompleted(10L, 20L, 30L);
        verify(ack).acknowledge();
    }

    @Test
    void handlesSnakeCaseSeatAssignmentPayload() {
        when(lotteryTrackService.onPaymentCompleted(10L, 20L, 30L)).thenReturn(Mono.empty());
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.handleSeatAssignment(record("{\"reservation_id\":10,\"user_id\":20,\"schedule_id\":30}"), ack);

        verify(lotteryTrackService).onPaymentCompleted(10L, 20L, 30L);
        verify(ack).acknowledge();
    }

    @Test
    void acknowledgesAndSkipsMalformedPayload() {
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.handleSeatAssignment(record("not-json"), ack);

        verify(lotteryTrackService, never()).onPaymentCompleted(anyLong(), anyLong(), anyLong());
        verify(ack).acknowledge();
    }

    @Test
    void doesNotAcknowledgeWhenProcessingFails() {
        when(lotteryTrackService.onPaymentCompleted(10L, 20L, 30L))
                .thenReturn(Mono.error(new IllegalStateException("redis-unavailable")));
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.handleSeatAssignment(record("{\"reservationId\":10,\"userId\":20,\"scheduleId\":30}"), ack);

        verify(lotteryTrackService).onPaymentCompleted(10L, 20L, 30L);
        verify(ack, never()).acknowledge();
    }

    @Test
    void acknowledgesAndSkipsNonNumericIds() {
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.handleSeatAssignment(record("{\"reservationId\":\"abc\",\"userId\":20,\"scheduleId\":30}"), ack);

        verify(lotteryTrackService, never()).onPaymentCompleted(anyLong(), anyLong(), anyLong());
        verify(ack).acknowledge();
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("seat-assignment", 0, 7L, "10", value);
    }
}
