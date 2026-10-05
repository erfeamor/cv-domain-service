package com.erfeamor.cvdomain.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erfeamor.cvdomain.person.Person;
import com.erfeamor.cvdomain.person.PersonRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T-116: writes require a user token (scope {@code openid}); machine tokens are read-only.
 *
 * <p>Runs the whole application against H2 with auth on. Tokens are sent as real
 * {@code Authorization: Bearer} headers and only the {@link JwtDecoder} is mocked, so the
 * production authentication converter (scope claim to {@code SCOPE_*} authorities) is the one
 * that decides -- the test would catch a converter or claim-name mismatch, which a
 * {@code jwt()} post-processor with hand-set authorities would not.
 *
 * <p>The two token shapes are Cognito's: a client-credentials access token carries only
 * resource-server scopes ({@code cv-domain/read}, the BFF's), a hosted-UI user access token
 * carries {@code openid email profile} (the admin's). Both are space-delimited strings.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase
@TestPropertySource(properties = {
    "app.auth.enabled=true",
    "spring.jpa.open-in-view=false",
    "app.cors.allowed-origins=http://localhost:5173"
})
class WriteScopeEnforcementTest {

    private static final String MACHINE = "machine-token";
    private static final String ADMIN = "admin-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PersonRepository personRepository;

    @MockBean
    private JwtDecoder jwtDecoder;

    private Person person;
    private long experienceId;

    @BeforeEach
    void setUp() {
        given(jwtDecoder.decode(anyString())).willAnswer(call -> switch (
                (String) call.getArgument(0)) {
            case MACHINE -> token(MACHINE, "cv-domain/read");
            case ADMIN -> token(ADMIN, "openid email profile");
            default -> throw new BadJwtException("unknown token");
        });

        person = personRepository.save(new Person("Scope Tester", null,
                "scope-" + UUID.randomUUID() + "@example.com", null, null));
        jdbc.update("INSERT INTO experience (person_id, company, role, location, start_date, "
                + "description, version) VALUES (?, 'Original', 'Engineer', 'Remote', "
                + "DATE '2019-01-01', 'd', 0)", person.getId());
        experienceId = jdbc.queryForObject(
                "SELECT id FROM experience WHERE person_id = ?", Long.class, person.getId());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM experience WHERE person_id = ?", person.getId());
        jdbc.update("DELETE FROM person WHERE id = ?", person.getId());
    }

    private static Jwt token(String value, String scope) {
        return Jwt.withTokenValue(value)
                .header("alg", "RS256")
                .subject("sub-" + value)
                .claim("scope", scope)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    private static MockHttpServletRequestBuilder as(String bearer,
            MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request,
            String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String personUrl() {
        return "/api/v1/people/" + person.getId();
    }

    private String experiencesUrl() {
        return personUrl() + "/experiences";
    }

    private String experienceUrl() {
        return experiencesUrl() + "/" + experienceId;
    }

    private String personBody(String fullName, long version) {
        return "{\"version\":" + version + ",\"fullName\":\"" + fullName + "\",\"headline\":null,"
                + "\"email\":\"" + person.getEmail() + "\",\"location\":null,\"summary\":null}";
    }

    private static String experienceBody(String company, long version) {
        return "{\"version\":" + version + ",\"company\":\"" + company + "\",\"role\":\"Engineer\","
                + "\"location\":\"Remote\",\"startDate\":\"2019-01-01\",\"endDate\":null,"
                + "\"description\":\"d\"}";
    }

    private String personName() {
        return jdbc.queryForObject("SELECT full_name FROM person WHERE id = ?", String.class,
                person.getId());
    }

    private String experienceCompany() {
        return jdbc.queryForObject("SELECT company FROM experience WHERE id = ?", String.class,
                experienceId);
    }

    private int count(String table, String where, Object arg) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where,
                Integer.class, arg);
    }

    // ------------------------------------------------------- machine token: read-only

    @Test
    void machineTokenCanRead() throws Exception {
        mockMvc.perform(as(MACHINE, get(personUrl()))).andExpect(status().isOk());
        mockMvc.perform(as(MACHINE, get(experiencesUrl()))).andExpect(status().isOk());
    }

    @Test
    void machineTokenCannotCreate() throws Exception {
        String email = "new-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(as(MACHINE, json(post("/api/v1/people"),
                "{\"fullName\":\"Intruder\",\"email\":\"" + email + "\"}")))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(MACHINE, json(post(experiencesUrl()), experienceBody("Intruder", 0))))
                .andExpect(status().isForbidden());

        assertThat(count("person", "email = ?", email)).isZero();
        assertThat(count("experience", "person_id = ?", person.getId())).isEqualTo(1);
    }

    @Test
    void machineTokenCannotUpdate() throws Exception {
        mockMvc.perform(as(MACHINE, json(put(personUrl()), personBody("Intruder", 0))))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(MACHINE, json(put(experienceUrl()), experienceBody("Intruder", 0))))
                .andExpect(status().isForbidden());

        assertThat(personName()).isEqualTo("Scope Tester");
        assertThat(experienceCompany()).isEqualTo("Original");
    }

    @Test
    void machineTokenCannotPatch() throws Exception {
        // No PATCH endpoint exists today; the rule must still cover the method so one added
        // later is not writable by a machine token.
        mockMvc.perform(as(MACHINE, json(patch(personUrl()), "{\"fullName\":\"Intruder\"}")))
                .andExpect(status().isForbidden());
        assertThat(personName()).isEqualTo("Scope Tester");
    }

    @Test
    void machineTokenCannotDelete() throws Exception {
        mockMvc.perform(as(MACHINE, delete(experienceUrl()))).andExpect(status().isForbidden());
        mockMvc.perform(as(MACHINE, delete(personUrl()))).andExpect(status().isForbidden());

        assertThat(count("experience", "id = ?", experienceId)).isEqualTo(1);
        assertThat(count("person", "id = ?", person.getId())).isEqualTo(1);
    }

    // ------------------------------------------------------- user token: full access

    @Test
    void userTokenCanReadAndWrite() throws Exception {
        mockMvc.perform(as(ADMIN, get(personUrl()))).andExpect(status().isOk());
        mockMvc.perform(as(ADMIN, get(experiencesUrl()))).andExpect(status().isOk());

        mockMvc.perform(as(ADMIN, json(put(personUrl()), personBody("Edited", 0))))
                .andExpect(status().isOk());
        assertThat(personName()).isEqualTo("Edited");
        mockMvc.perform(as(ADMIN, json(put(experienceUrl()), experienceBody("Edited", 0))))
                .andExpect(status().isOk());
        assertThat(experienceCompany()).isEqualTo("Edited");

        String created = mockMvc.perform(as(ADMIN,
                        json(post(experiencesUrl()), experienceBody("Second", 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long secondId = ((Number) JsonPath.read(created, "$.id")).longValue();

        mockMvc.perform(as(ADMIN, delete(experiencesUrl() + "/" + secondId)))
                .andExpect(status().isNoContent());
        assertThat(count("experience", "id = ?", secondId)).isZero();
    }

    @Test
    void userTokenCanCreateAndDeleteAPerson() throws Exception {
        String email = "new-" + UUID.randomUUID() + "@example.com";
        String created = mockMvc.perform(as(ADMIN, json(post("/api/v1/people"),
                        "{\"fullName\":\"New\",\"email\":\"" + email + "\"}")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();

        mockMvc.perform(as(ADMIN, delete("/api/v1/people/" + id)))
                .andExpect(status().isNoContent());
        assertThat(count("person", "id = ?", id)).isZero();
    }

    // ------------------------------------------------------- unchanged behaviour

    @Test
    void noTokenIsUnauthorizedForReadsAndWrites() throws Exception {
        mockMvc.perform(get(personUrl())).andExpect(status().isUnauthorized());
        mockMvc.perform(json(put(personUrl()), personBody("Intruder", 0)))
                .andExpect(status().isUnauthorized());
        assertThat(personName()).isEqualTo("Scope Tester");
    }

    @Test
    void healthProbeStaysAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void corsPreflightForAWriteStillWorks() throws Exception {
        // Browsers send the preflight without credentials; it must not be refused by the
        // write rule, or the admin can never send the PUT that carries its token.
        mockMvc.perform(options(personUrl())
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "http://localhost:5173"));
    }
}
