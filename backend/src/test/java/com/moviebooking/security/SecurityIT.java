package com.moviebooking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;

import com.moviebooking.TestcontainersConfiguration;
import com.moviebooking.config.ResourceNotFoundException;

/** Authentication and authorization against the demo users seeded by V8. Requires Docker. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserDetailsService userDetailsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @CsvSource({
            "alice,         alice-demo-pass,    CUSTOMER,      Alice (demo customer)",
            "bob,           bob-demo-pass,      CUSTOMER,      Bob (demo customer)",
            "admin.dallas,  dallas-admin-demo,  THEATER_ADMIN, Dallas theater admin (demo)",
            "admin.houston, houston-admin-demo, THEATER_ADMIN, Houston theater admin (demo)"
    })
    void documentedDemoAccountsAuthenticateWithTheirRole(String username, String password, String role,
            String displayName) throws Exception {
        long id = jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);

        String body = mockMvc.perform(get("/api/auth/me").with(httpBasic(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.displayName").value(displayName))
                .andExpect(jsonPath("$.role").value(role))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContainIgnoringCase("password").doesNotContain("$2a$").doesNotContain(password);
    }

    @Test
    void missingCredentialsAreRejected() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void wrongPasswordAndUnknownUserAreRejected() throws Exception {
        mockMvc.perform(get("/api/auth/me").with(httpBasic("alice", "bob-demo-pass")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/auth/me").with(httpBasic("mallory", "alice-demo-pass")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void customerIsForbiddenFromAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/theaters").with(httpBasic("alice", "alice-demo-pass")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void authenticatedRequestForUnknownApiPathIsNotFoundRatherThanUnauthorized() throws Exception {
        mockMvc.perform(get("/api/does-not-exist").with(httpBasic("alice", "alice-demo-pass")))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminPrincipalCarriesOnlyItsSeededTheaterAssignment() {
        long starlight = theaterId("Starlight Cinemas Downtown");
        long bayou = theaterId("Bayou Picture House");

        AuthenticatedUser dallas = userDetailsService.loadUserByUsername("admin.dallas");
        AuthenticatedUser houston = userDetailsService.loadUserByUsername("admin.houston");

        assertThat(dallas.theaterIds()).containsExactly(starlight);
        assertThat(houston.theaterIds()).containsExactly(bayou);
        assertThatCode(() -> AccessPolicy.requireAssignedTheater(dallas, starlight)).doesNotThrowAnyException();
        assertThatThrownBy(() -> AccessPolicy.requireAssignedTheater(dallas, bayou))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> AccessPolicy.requireAssignedTheater(houston, starlight))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void customerCannotReachAnotherCustomersResource() {
        AuthenticatedUser alice = userDetailsService.loadUserByUsername("alice");
        AuthenticatedUser bob = userDetailsService.loadUserByUsername("bob");

        assertThat(alice.theaterIds()).isEmpty();
        assertThatCode(() -> AccessPolicy.requireOwner(alice, alice.id())).doesNotThrowAnyException();
        assertThatThrownBy(() -> AccessPolicy.requireOwner(bob, alice.id()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private long theaterId(String name) {
        return jdbcTemplate.queryForObject("SELECT id FROM theater WHERE name = ?", Long.class, name);
    }
}
