package com.erfeamor.cvdomain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erfeamor.cvdomain.education.EducationRepository;
import com.erfeamor.cvdomain.experience.ExperienceRepository;
import com.erfeamor.cvdomain.person.Person;
import com.erfeamor.cvdomain.person.PersonRepository;
import com.erfeamor.cvdomain.project.ProjectRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * T-113, contract design rule 8: optimistic concurrency on person, experience, education and
 * project, end to end through MockMvc against the real JPA stack (H2).
 *
 * <p>Same deliberate setup as {@link ConcurrentDeleteDuringUpdateTest}: no {@code @Transactional}
 * on the class (a per-test transaction would fold every request into one transaction and no
 * concurrent write could commit in between), {@code open-in-view=false} as in production, rows
 * cleaned up by hand. The in-flight races use the same test-only interceptor on the real
 * repository proxy: right after the PUT's single-row read returns, a statement is committed on
 * another thread and connection, before the controller writes.
 *
 * <p>Every case runs for all four versioned resources.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase
@TestPropertySource(properties = {
    "app.auth.enabled=false",
    "spring.jpa.open-in-view=false",
    "app.cors.allowed-origins=http://localhost:5173"
})
class OptimisticConcurrencyTest {

    /**
     * One versioned resource. Bodies are templates: {@code %1$s} is a unique email, so the person
     * resource never trips {@code person.email}'s UNIQUE constraint; section bodies ignore it.
     *
     * @param table the table, for direct SQL assertions
     * @param column a column the A and B bodies set to different values
     * @param repository the repository whose {@code lookup} method the PUT path reads through
     * @param lookup the single-row read the PUT path makes, hooked for the race cases
     * @param sections true for a person-scoped section, false for person itself
     */
    record Resource(String name, String table, String column, String createBody,
            String bodyA, String valueA, String bodyB, String valueB,
            Class<?> repository, String lookup, boolean sections) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Resource> resources() {
        return Stream.of(
                new Resource("person", "person", "full_name",
                        """
                        {"fullName":"Original","headline":null,"email":"%1$s",
                         "location":null,"summary":null}""",
                        """
                        {"fullName":"Client A","headline":null,"email":"%1$s",
                         "location":null,"summary":null}""", "Client A",
                        """
                        {"fullName":"Client B","headline":null,"email":"%1$s",
                         "location":null,"summary":null}""", "Client B",
                        PersonRepository.class, "findById", false),
                new Resource("experiences", "experience", "company",
                        """
                        {"company":"Original","role":"Engineer","location":"Remote",
                         "startDate":"2019-01-01","endDate":null,"description":"d"}""",
                        """
                        {"company":"Client A","role":"Engineer","location":"Remote",
                         "startDate":"2019-01-01","endDate":null,"description":"d"}""", "Client A",
                        """
                        {"company":"Client B","role":"Engineer","location":"Remote",
                         "startDate":"2019-01-01","endDate":null,"description":"d"}""", "Client B",
                        ExperienceRepository.class, "findByIdAndPersonId", true),
                new Resource("educations", "education", "institution",
                        """
                        {"institution":"Original","degree":"BSc","fieldOfStudy":"CS",
                         "startDate":"2012-09-01","endDate":"2016-06-30"}""",
                        """
                        {"institution":"Client A","degree":"BSc","fieldOfStudy":"CS",
                         "startDate":"2012-09-01","endDate":"2016-06-30"}""", "Client A",
                        """
                        {"institution":"Client B","degree":"BSc","fieldOfStudy":"CS",
                         "startDate":"2012-09-01","endDate":"2016-06-30"}""", "Client B",
                        EducationRepository.class, "findByIdAndPersonId", true),
                new Resource("projects", "project", "name",
                        """
                        {"name":"Original","description":"d","repoUrl":"https://example.com",
                         "startDate":null,"endDate":null}""",
                        """
                        {"name":"Client A","description":"d","repoUrl":"https://example.com",
                         "startDate":null,"endDate":null}""", "Client A",
                        """
                        {"name":"Client B","description":"d","repoUrl":"https://example.com",
                         "startDate":null,"endDate":null}""", "Client B",
                        ProjectRepository.class, "findByIdAndPersonId", true));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PersonRepository personRepository;

    /** Keeps the context offline: nothing may try to reach a Cognito issuer. */
    @MockBean
    private JwtDecoder jwtDecoder;

    /** Owner of the section rows. */
    private Person owner;

    /** Every person row this test created, owner included, for clean-up. */
    private final List<Long> people = new ArrayList<>();

    private String email;

    private Advised hooked;
    private MethodInterceptor hook;

    @BeforeEach
    void createOwner() {
        owner = personRepository.save(new Person("Version Tester", null,
                "owner-" + UUID.randomUUID() + "@example.com", null, null));
        people.add(owner.getId());
        email = "v-" + UUID.randomUUID() + "@example.com";
    }

    @AfterEach
    void cleanUp() {
        if (hooked != null) {
            hooked.removeAdvice(hook);
            hooked = null;
        }
        for (Long pid : people) {
            jdbc.update("DELETE FROM experience WHERE person_id = ?", pid);
            jdbc.update("DELETE FROM education WHERE person_id = ?", pid);
            jdbc.update("DELETE FROM project WHERE person_id = ?", pid);
            jdbc.update("DELETE FROM person WHERE id = ?", pid);
        }
    }

    // ------------------------------------------------------------------ helpers

    private String collection(Resource r) {
        return r.sections()
                ? "/api/v1/people/" + owner.getId() + "/" + r.name()
                : "/api/v1/people";
    }

    private String item(Resource r, long id) {
        return collection(r) + "/" + id;
    }

    private String body(String template) {
        return template.formatted(email);
    }

    /** Prepends {@code "version": v} to a JSON object body. */
    private static String withVersion(String json, long version) {
        return "{\"version\":" + version + "," + json.strip().substring(1);
    }

    /**
     * Creates a row through the API and returns its id. The 201's version is asserted by
     * {@link #postReturnsVersionZeroAndIgnoresAClientSuppliedVersion}, not here, so that the
     * lost-update case fails on its own assertion on code that predates versions.
     */
    private long create(Resource r) throws Exception {
        String json = mockMvc.perform(post(collection(r))
                        .contentType(MediaType.APPLICATION_JSON).content(body(r.createBody())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(json, "$.id")).longValue();
        if (!r.sections()) {
            people.add(id);
        }
        return id;
    }

    private ResultActions put(Resource r, long id, String json) throws Exception {
        return mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(item(r, id))
                        .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String storedValue(Resource r, long id) {
        return jdbc.queryForObject("SELECT " + r.column() + " FROM " + r.table()
                + " WHERE id = ?", String.class, id);
    }

    private long storedVersion(Resource r, long id) {
        return jdbc.queryForObject("SELECT version FROM " + r.table() + " WHERE id = ?",
                Long.class, id);
    }

    private long rowsWithId(Resource r, long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + r.table() + " WHERE id = ?",
                Long.class, id);
    }

    /**
     * After the first {@code r.lookup()} call on the resource's repository returns, commits
     * {@code sql} on another thread and connection, then hands the read back. Fires once.
     */
    private void commitAfterRead(Resource r, String sql, Object... args) {
        AtomicBoolean fired = new AtomicBoolean();
        hook = invocation -> {
            Object read = invocation.proceed();
            if (invocation.getMethod().getName().equals(r.lookup())
                    && fired.compareAndSet(false, true)) {
                CompletableFuture.runAsync(() -> jdbc.update(sql, args)).join();
            }
            return read;
        };
        hooked = (Advised) context.getBean(r.repository());
        hooked.addAdvice(0, hook);
    }

    private static void isVersionConflictProblem(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"));
    }

    // ------------------------------------------------------------------ responses

    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    void postReturnsVersionZeroAndIgnoresAClientSuppliedVersion(Resource r) throws Exception {
        String json = mockMvc.perform(post(collection(r))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withVersion(body(r.createBody()), 7)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(json, "$.id")).longValue();
        if (!r.sections()) {
            people.add(id);
        }

        assertThat(storedVersion(r, id)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    void getCarriesTheStoredVersion(Resource r) throws Exception {
        long id = create(r);
        put(r, id, withVersion(body(r.bodyA()), 0)).andExpect(status().isOk());

        if (r.sections()) {
            mockMvc.perform(get(collection(r)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(id))
                    .andExpect(jsonPath("$[0].version").value(1));
        } else {
            mockMvc.perform(get(item(r, id)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(1));
        }
    }

    // ------------------------------------------------------------------ the lost update

    /**
     * The lost update: A and B both read version 0. A writes first and wins; B's write carries
     * the version it read, which is now stale, so it is refused and A's values survive. Before
     * T-113 B got a 200 and silently overwrote A.
     *
     * <p>Nothing is in flight when B arrives — the stored row changed in an earlier, committed
     * request — so this is only caught by the explicit comparison in the update path, not by
     * Hibernate's flush-time check (which compares against the version it just read, 1).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    void staleVersionIsRejectedWith409AndTheFirstWriteSurvives(Resource r) throws Exception {
        long id = create(r);

        put(r, id, withVersion(body(r.bodyA()), 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        isVersionConflictProblem(put(r, id, withVersion(body(r.bodyB()), 0)));

        assertThat(storedValue(r, id)).isEqualTo(r.valueA());
        assertThat(storedVersion(r, id)).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    void aVersionAheadOfTheStoredOneIsAlsoStale(Resource r) throws Exception {
        long id = create(r);

        isVersionConflictProblem(put(r, id, withVersion(body(r.bodyA()), 5)));

        assertThat(storedValue(r, id)).isEqualTo("Original");
        assertThat(storedVersion(r, id)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    void matchingVersionAppliesAndTheResponseCarriesTheNextVersion(Resource r)
            throws Exception {
        long id = create(r);

        put(r, id, withVersion(body(r.bodyA()), 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        put(r, id, withVersion(body(r.bodyB()), 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        assertThat(storedValue(r, id)).isEqualTo(r.valueB());
        assertThat(storedVersion(r, id)).isEqualTo(2);
    }

    /** Rule 8: an omitted version is the v1 behaviour, last write wins. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    void omittedVersionAppliesUnconditionally(Resource r) throws Exception {
        long id = create(r);
        put(r, id, withVersion(body(r.bodyA()), 0)).andExpect(status().isOk());

        put(r, id, body(r.bodyB()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        assertThat(storedValue(r, id)).isEqualTo(r.valueB());
    }

    // ------------------------------------------------------------------ no-op PUT

    /**
     * Decision: every successful PUT increments the version, including one whose body equals the
     * stored values (for which Hibernate's dirty check alone would issue no UPDATE). The forced
     * UPDATE is what makes a no-op PUT detect a row deleted under it (next test).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    void aNoOpPutStillIncrementsTheVersion(Resource r) throws Exception {
        long id = create(r);

        put(r, id, withVersion(body(r.createBody()), 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(storedVersion(r, id)).isEqualTo(1);

        put(r, id, body(r.createBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(storedVersion(r, id)).isEqualTo(2);

        isVersionConflictProblem(put(r, id, withVersion(body(r.createBody()), 0)));
    }

    /**
     * T-108's leftover gap: a no-op PUT racing a committed DELETE used to answer 200 echoing the
     * deleted row, because no UPDATE was issued to notice it was gone.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    void aNoOpPutRacingACommittedDeleteIs404(Resource r) throws Exception {
        long id = create(r);
        commitAfterRead(r, "DELETE FROM " + r.table() + " WHERE id = ?", id);

        put(r, id, withVersion(body(r.createBody()), 0)).andExpect(status().isNotFound());

        assertThat(rowsWithId(r, id)).isZero();
    }

    // ------------------------------------------------------------------ in-flight race

    /**
     * A genuine race: the client's version matches what this request read, but another
     * transaction commits an update between the read and the write. Hibernate's conditional
     * UPDATE matches 0 rows; the row still exists, so that is a 409, not T-108's 404.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    void aWriteCommittedBetweenReadAndWriteIs409(Resource r) throws Exception {
        long id = create(r);
        commitAfterRead(r, "UPDATE " + r.table() + " SET " + r.column()
                + " = 'Elsewhere', version = version + 1 WHERE id = ?", id);

        isVersionConflictProblem(put(r, id, withVersion(body(r.bodyA()), 0)));

        assertThat(storedValue(r, id)).isEqualTo("Elsewhere");
        assertThat(storedVersion(r, id)).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    void aDeleteCommittedBetweenReadAndWriteIsStill404(Resource r) throws Exception {
        long id = create(r);
        commitAfterRead(r, "DELETE FROM " + r.table() + " WHERE id = ?", id);

        put(r, id, withVersion(body(r.bodyA()), 0)).andExpect(status().isNotFound());

        assertThat(rowsWithId(r, id)).isZero();
    }
}
