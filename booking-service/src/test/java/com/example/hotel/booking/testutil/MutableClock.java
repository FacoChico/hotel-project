package com.example.hotel.booking.testutil;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import lombok.RequiredArgsConstructor;

import static java.time.ZoneOffset.UTC;

@RequiredArgsConstructor
public final class MutableClock extends Clock {
    private final AtomicReference<Instant> currentTime;
    private final ZoneId zone;

    public MutableClock(Instant initialTime) {
        this(new AtomicReference<>(initialTime), UTC);
    }

    public void set(Instant instant) {
        currentTime.set(instant);
    }

    public void advance(Duration duration) {
        currentTime.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(currentTime, zone);
    }

    @Override
    public Instant instant() {
        return currentTime.get();
    }
}
