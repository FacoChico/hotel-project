package com.example.hotel.booking.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.CountDownLatch;

import lombok.SneakyThrows;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class BookingRecoverySchedulerTest {

    @Test
    @SneakyThrows
    void shouldRunRecoveryWhenSchedulingIsEnabled() {
        final var service = mock(BookingRecoveryService.class);
        final var invoked = new CountDownLatch(1);
        doAnswer(invocation -> {
            invoked.countDown();
            return null;
        }).when(service).recoverBookings();

        new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class)
            .withBean(BookingRecoveryService.class, () -> service)
            .withPropertyValues("booking.recovery.enabled=true", "booking.recovery.poll-interval-ms=10")
            .run(context -> {
                assertThat(context).hasSingleBean(BookingRecoveryScheduler.class);
                assertThat(invoked.await(2, SECONDS)).isTrue();
            });
    }

    @Test
    void shouldOmitSchedulerWhenSchedulingIsDisabled() {
        final var service = mock(BookingRecoveryService.class);

        new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class)
            .withBean(BookingRecoveryService.class, () -> service)
            .withPropertyValues("booking.recovery.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(BookingRecoveryScheduler.class));

        verifyNoInteractions(service);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(BookingRecoveryScheduler.class)
    static class SchedulingConfig {
    }
}
