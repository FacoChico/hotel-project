package com.example.hotel.booking.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@ConditionalOnProperty(name = "booking.recovery.enabled", matchIfMissing = true)
@RequiredArgsConstructor
class BookingRecoveryScheduler {
    private final BookingRecoveryService recoveryService;

    @Scheduled(fixedDelayString = "${booking.recovery.poll-interval-ms}",
        initialDelayString = "${booking.recovery.poll-interval-ms}")
    void recoverBookings() {
        recoveryService.recoverBookings();
    }
}
