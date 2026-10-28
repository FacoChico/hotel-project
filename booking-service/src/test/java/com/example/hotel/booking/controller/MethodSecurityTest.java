package com.example.hotel.booking.controller;

import com.example.hotel.booking.testutil.JwtTestKeys;

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
    private UserController controller;

    @DynamicPropertySource
    private static void registerProperties(DynamicPropertyRegistry registry) {
        JwtTestKeys.registerProperties(registry);
    }

    @Test
    @WithMockUser(roles = "USER")
    void shouldRejectAdminOperationWhenMethodIsCalledByUser() {
        assertThatThrownBy(() -> controller.listUsers(0, 20))
            .isInstanceOf(AccessDeniedException.class);
    }
}
