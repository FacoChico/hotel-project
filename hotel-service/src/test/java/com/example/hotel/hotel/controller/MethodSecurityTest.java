package com.example.hotel.hotel.controller;

import com.example.hotel.hotel.testutil.JwtTestKeys;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"eureka.client.enabled=false", "security.bootstrap-admin.enabled=false"})
class MethodSecurityTest {
    @Autowired
    private RoomController controller;

    @DynamicPropertySource
    private static void registerProperties(DynamicPropertyRegistry registry) {
        JwtTestKeys.registerProperties(registry);
    }

    @Test
    @WithMockUser(roles = "USER")
    void shouldRejectAdminOperationWhenMethodIsCalledByUser() {
        assertThatThrownBy(() -> controller.getOccupancyStatistics(java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(1), null, 0, 20))
            .isInstanceOf(AccessDeniedException.class);
    }
}
