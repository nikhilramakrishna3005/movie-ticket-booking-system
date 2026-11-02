package com.moviebooking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Loads the application context with the real security configuration but without database
 * auto-configuration, so it runs where Docker is unavailable. Database wiring is covered by
 * {@link ApplicationIT}, which requires Docker.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
@AutoConfigureMockMvc
class ApplicationContextWithoutDatabaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Clock clock;

    @Test
    void clockBeanIsUtc() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void healthEndpointIsPublicAndUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void otherRequestsAreDeniedByDefault() throws Exception {
        mockMvc.perform(get("/api/showtimes/search"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isForbidden());
    }
}
