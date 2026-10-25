package com.example.hotel.booking.config;

import com.example.hotel.booking.properties.AdminBootstrapProperties;
import com.example.hotel.booking.service.AdminBootstrapService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import lombok.SneakyThrows;

import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminBootstrapConfigTest {

    @Test
    void shouldSkipInitializationWhenBootstrapIsDisabled() {
        final var service = mock(AdminBootstrapService.class);
        final var runner = new ApplicationContextRunner()
            .withUserConfiguration(AdminBootstrapConfig.class)
            .withBean(AdminBootstrapService.class, () -> service)
            .withPropertyValues("security.bootstrap-admin.enabled=false");

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ApplicationRunner.class);
            verifyNoInteractions(service);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "true"})
    void shouldInitializeAdminWhenBootstrapIsEnabledOrUnspecified(String enabled) {
        final var service = mock(AdminBootstrapService.class);
        final var password = randomUUID().toString();
        var runner = new ApplicationContextRunner()
            .withUserConfiguration(AdminBootstrapConfig.class)
            .withBean(AdminBootstrapService.class, () -> service)
            .withPropertyValues(
                "security.bootstrap-admin.username=hotel_admin",
                "security.bootstrap-admin.password=" + password
            );
        if (!enabled.isEmpty()) {
            runner = runner.withPropertyValues("security.bootstrap-admin.enabled=" + enabled);
        }

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            bootstrap(context.getBean(ApplicationRunner.class));

            verify(service, times(1))
                .initializeAdmin(new AdminBootstrapProperties(true, "hotel_admin", password));
        });
    }

    @SneakyThrows
    private static void bootstrap(ApplicationRunner runner) {
        runner.run(new DefaultApplicationArguments());
    }
}
