package com.erfeamor.cvdomain.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erfeamor.cvdomain.person.PersonController;
import com.erfeamor.cvdomain.person.PersonRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises the security filter chain itself, which the controller tests
 * deliberately switch off with {@code addFilters = false}. Nothing covered the
 * matcher in {@link SecurityConfig} until T-106, and the matcher is what
 * decided that {@code /v3/api-docs} answered anonymously in the deployed
 * stack.
 *
 * <p>A note on the assertions: this is a {@code @WebMvcTest} slice, so the
 * actuator and springdoc endpoints are not registered. That is fine and is in
 * fact the point — Spring Security runs before the handler, so a protected
 * path returns 401 whether or not anything is behind it. Where a path is
 * *permitted* the request reaches dispatch and 404s in the slice, so those
 * cases assert "not 401" rather than 200. Asserting 200 here would test the
 * slice's wiring, not the security rule.
 *
 * <p>A JwtDecoder is mocked because {@code oauth2ResourceServer} needs one;
 * without it the context tries to fetch issuer metadata from Cognito at
 * startup and the test cannot run offline.
 */
class SecurityConfigTest {

    @Nested
    @WebMvcTest(controllers = PersonController.class)
    @Import(SecurityConfig.class)
    @TestPropertySource(properties = {
        "app.auth.enabled=true",
        "app.cors.allowed-origins=http://localhost:5173"
    })
    class WhenAuthIsEnabled {

        @Autowired
        private MockMvc mockMvc;

        @MockBean
        private PersonRepository personRepository;

        @MockBean
        private JwtDecoder jwtDecoder;

        @Test
        void doesNotServeTheOpenApiDocumentAnonymously() throws Exception {
            mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        }

        @Test
        void doesNotServeTheOpenApiDocumentAnonymouslyOnSubPaths() throws Exception {
            // springdoc serves grouped documents under /v3/api-docs/**; a
            // matcher covering only the exact path would leak these.
            mockMvc.perform(get("/v3/api-docs/swagger-config"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void doesNotServeSwaggerUiAnonymously() throws Exception {
            mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
        }

        @Test
        void doesNotServePrometheusMetricsAnonymously() throws Exception {
            // Nothing scrapes this in AWS. The local stack does, but it runs
            // AUTH_ENABLED=false, which is covered below.
            mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        }

        @Test
        void stillServesTheHealthProbeAnonymously() throws Exception {
            // The liveness probe must never require a token. 404 in this slice
            // (actuator is not registered) proves it got past security, which
            // is the only thing this test is about.
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().is(org.springframework.http.HttpStatus.NOT_FOUND.value()));
        }

        @Test
        void stillRequiresATokenForTheApi() throws Exception {
            mockMvc.perform(get("/api/v1/people")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @WebMvcTest(controllers = PersonController.class)
    @Import(SecurityConfig.class)
    @TestPropertySource(properties = {
        "app.auth.enabled=false",
        "app.cors.allowed-origins=http://localhost:5173"
    })
    class WhenAuthIsDisabled {

        @Autowired
        private MockMvc mockMvc;

        @MockBean
        private PersonRepository personRepository;

        @Test
        void permitsEverythingSoLocalStacksAndPrometheusKeepWorking() throws Exception {
            // docker-compose.dev.yml runs the domain service with
            // AUTH_ENABLED=false and cv-observability's Prometheus scrapes
            // /actuator/prometheus anonymously against it. Tightening the
            // matcher above must not reach that path, and this is the test
            // that says so.
            mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
            mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/people")).andExpect(status().isOk());
        }
    }
}
