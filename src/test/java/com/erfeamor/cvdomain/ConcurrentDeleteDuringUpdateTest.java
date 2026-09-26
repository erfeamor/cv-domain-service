package com.erfeamor.cvdomain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erfeamor.cvdomain.education.Education;
import com.erfeamor.cvdomain.education.EducationRepository;
import com.erfeamor.cvdomain.experience.Experience;
import com.erfeamor.cvdomain.experience.ExperienceRepository;
import com.erfeamor.cvdomain.person.Person;
import com.erfeamor.cvdomain.person.PersonRepository;
import com.erfeamor.cvdomain.personskill.PersonSkill;
import com.erfeamor.cvdomain.personskill.PersonSkillRepository;
import com.erfeamor.cvdomain.personskill.Proficiency;
import com.erfeamor.cvdomain.project.Project;
import com.erfeamor.cvdomain.project.ProjectRepository;
import com.erfeamor.cvdomain.skill.Skill;
import com.erfeamor.cvdomain.skill.SkillRepository;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.repository.Repository;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T-108: a section PUT racing a DELETE must not resurrect the deleted row under a new id.
 *
 * <p><strong>How the race is made deterministic.</strong> A test-only interceptor is added to
 * the real Spring Data repository proxy (it is {@link Advised}) and removed after each test. It
 * wraps the resource's single-row lookup so that, right after the real read returns and before
 * the controller writes, a DELETE of that row runs on <em>another thread</em> through
 * {@link JdbcTemplate} on its own pooled connection in auto-commit mode — so it is really
 * committed, in a separate transaction, exactly between update()'s read and its write. No sleeps.
 *
 * <p><strong>Why this class is not transactional, on purpose.</strong> It is a plain
 * {@code @SpringBootTest} with no {@code @Transactional}: Spring's per-test rollback transaction
 * would fold every repository call of the request into one transaction that the test thread owns,
 * and the "concurrent" delete could never commit between the read and the write. That setup
 * false-passes on the unfixed code. Rows are cleaned up by hand in {@link #cleanUp()} instead.
 *
 * <p><strong>Why {@code open-in-view=false} is pinned here.</strong> It is production's setting,
 * but the test {@code application.yml} replaces the main one, so tests otherwise run with Spring
 * Boot's default ({@code true}). With open-in-view on, one EntityManager spans the request, the
 * entity read by the unfixed code stays managed, and the race surfaces as a 500 at flush instead
 * of the real production failure — an INSERT under a new id, answered 200. The resurrection
 * assertions below can only fail, as they must on unfixed code, with this pinned.
 *
 * <p><strong>H2 vs InnoDB.</strong> H2 defaults to READ COMMITTED; InnoDB defaults to REPEATABLE
 * READ. The difference does not weaken this repro: the defect is about <em>transaction
 * boundaries</em>, not snapshot visibility. Unfixed, the read and the write are separate
 * transactions, so on either engine the write's {@code merge()} re-selects in a fresh transaction
 * and finds nothing, so Hibernate persists the detached entity as new: an INSERT under a fresh
 * IDENTITY id, answered 200 (verified with {@code open-in-view=false}, see below). Fixed, the
 * write is an UPDATE, and an UPDATE is a current (locking) read on InnoDB even
 * under REPEATABLE READ — it matches 0 rows against the committed DELETE exactly as it does on
 * H2. What H2 proves nothing about is lock waits (e.g. InnoDB blocking the DELETE on a row the
 * PUT has already updated); this test deliberately commits the DELETE before the PUT writes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase
@TestPropertySource(properties = {
    "app.auth.enabled=false",
    // Production's setting (main application.yml). src/test/resources/application.yml replaces
    // that file rather than overlaying it, so without this line the test would run with Spring
    // Boot's default open-in-view=true: one EntityManager per request, the read entity stays
    // managed, and the unfixed code fails as a 500 instead of re-inserting — hiding the defect.
    "spring.jpa.open-in-view=false",
    "app.cors.allowed-origins=http://localhost:5173"
})
class ConcurrentDeleteDuringUpdateTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private SkillRepository skillRepository;

    @Autowired
    private ExperienceRepository experienceRepository;

    @Autowired
    private EducationRepository educationRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private PersonSkillRepository personSkillRepository;

    /** Keeps the context offline: nothing may try to reach a Cognito issuer. */
    @MockBean
    private JwtDecoder jwtDecoder;

    private Person person;

    private Advised hooked;
    private MethodInterceptor hook;

    /**
     * After the first call of {@code method} on {@code repository} returns, runs {@code sql} as a
     * separately committed statement, then hands the read back to the controller. Fires once.
     */
    private void deleteAfterRead(Repository<?, ?> repository, String method, String sql,
            Object... args) {
        AtomicBoolean fired = new AtomicBoolean();
        hook = invocation -> {
            Object read = invocation.proceed();
            if (invocation.getMethod().getName().equals(method)
                    && fired.compareAndSet(false, true)) {
                commitElsewhere(sql, args);
            }
            return read;
        };
        hooked = (Advised) repository;
        hooked.addAdvice(0, hook);
    }

    @BeforeEach
    void createPerson() {
        person = personRepository.save(new Person("Race Tester", null,
                "race-" + UUID.randomUUID() + "@example.com", null, null));
    }

    @AfterEach
    void cleanUp() {
        if (hooked != null) {
            hooked.removeAdvice(hook);
            hooked = null;
        }
        Long pid = person.getId();
        jdbc.update("DELETE FROM experience WHERE person_id = ?", pid);
        jdbc.update("DELETE FROM education WHERE person_id = ?", pid);
        jdbc.update("DELETE FROM project WHERE person_id = ?", pid);
        jdbc.update("DELETE FROM person_skill WHERE person_id = ?", pid);
        jdbc.update("DELETE FROM person WHERE id = ?", pid);
    }

    /** Runs the SQL on another thread and connection, auto-committed, and waits for it. */
    private void commitElsewhere(String sql, Object... args) {
        CompletableFuture.runAsync(() -> jdbc.update(sql, args)).join();
    }

    private long rows(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE person_id = ?", Long.class,
                person.getId());
    }

    private long rowsWithId(String table, Long id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?", Long.class, id);
    }

    // ---------------------------------------------------------------- Experience

    private static final String EXPERIENCE_BODY = """
            {"company":"Globex","role":"Staff Engineer","location":"Paris",
             "startDate":"2020-01-01","endDate":null,"description":"Changed"}
            """;

    private Long seedExperience() {
        return experienceRepository.save(new Experience(person, "Acme", "Engineer", "Remote",
                LocalDate.of(2019, 1, 1), null, "Original")).getId();
    }

    @Test
    void experiencePutRacingACommittedDeleteIs404AndDoesNotResurrectTheRow() throws Exception {
        Long id = seedExperience();
        Long pid = person.getId();
        deleteAfterRead(experienceRepository, "findByIdAndPersonId",
                "DELETE FROM experience WHERE id = ?", id);

        mockMvc.perform(put("/api/v1/people/{p}/experiences/{id}", pid, id)
                        .contentType(MediaType.APPLICATION_JSON).content(EXPERIENCE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Experience not found"));

        assertThat(rowsWithId("experience", id)).as("deleted row stays deleted").isZero();
        assertThat(rows("experience")).as("no row inserted under a new id").isZero();
    }

    @Test
    void experiencePutStillUpdatesInPlaceUnderTheSameId() throws Exception {
        Long id = seedExperience();

        mockMvc.perform(put("/api/v1/people/{p}/experiences/{id}", person.getId(), id)
                        .contentType(MediaType.APPLICATION_JSON).content(EXPERIENCE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.company").value("Globex"));

        assertThat(rows("experience")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT company FROM experience WHERE id = ?",
                String.class, id)).isEqualTo("Globex");
    }

    // ---------------------------------------------------------------- Education

    private static final String EDUCATION_BODY = """
            {"institution":"MIT","degree":"MSc","fieldOfStudy":"Physics",
             "startDate":"2016-09-01","endDate":"2018-06-30"}
            """;

    private Long seedEducation() {
        return educationRepository.save(new Education(person, "UNED", "BSc", "CS",
                LocalDate.of(2012, 9, 1), LocalDate.of(2016, 6, 30))).getId();
    }

    @Test
    void educationPutRacingACommittedDeleteIs404AndDoesNotResurrectTheRow() throws Exception {
        Long id = seedEducation();
        Long pid = person.getId();
        deleteAfterRead(educationRepository, "findByIdAndPersonId",
                "DELETE FROM education WHERE id = ?", id);

        mockMvc.perform(put("/api/v1/people/{p}/educations/{id}", pid, id)
                        .contentType(MediaType.APPLICATION_JSON).content(EDUCATION_BODY))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Education not found"));

        assertThat(rowsWithId("education", id)).as("deleted row stays deleted").isZero();
        assertThat(rows("education")).as("no row inserted under a new id").isZero();
    }

    @Test
    void educationPutStillUpdatesInPlaceUnderTheSameId() throws Exception {
        Long id = seedEducation();

        mockMvc.perform(put("/api/v1/people/{p}/educations/{id}", person.getId(), id)
                        .contentType(MediaType.APPLICATION_JSON).content(EDUCATION_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.institution").value("MIT"));

        assertThat(rows("education")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT institution FROM education WHERE id = ?",
                String.class, id)).isEqualTo("MIT");
    }

    // ---------------------------------------------------------------- Project

    private static final String PROJECT_BODY = """
            {"name":"cv-project v2","description":"Changed",
             "repoUrl":"https://example.com/v2","startDate":null,"endDate":null}
            """;

    private Long seedProject() {
        return projectRepository.save(new Project(person, "cv-project", "Original",
                "https://example.com/v1", LocalDate.of(2024, 1, 1), null)).getId();
    }

    @Test
    void projectPutRacingACommittedDeleteIs404AndDoesNotResurrectTheRow() throws Exception {
        Long id = seedProject();
        Long pid = person.getId();
        deleteAfterRead(projectRepository, "findByIdAndPersonId",
                "DELETE FROM project WHERE id = ?", id);

        mockMvc.perform(put("/api/v1/people/{p}/projects/{id}", pid, id)
                        .contentType(MediaType.APPLICATION_JSON).content(PROJECT_BODY))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Project not found"));

        assertThat(rowsWithId("project", id)).as("deleted row stays deleted").isZero();
        assertThat(rows("project")).as("no row inserted under a new id").isZero();
    }

    @Test
    void projectPutStillUpdatesInPlaceUnderTheSameId() throws Exception {
        Long id = seedProject();

        mockMvc.perform(put("/api/v1/people/{p}/projects/{id}", person.getId(), id)
                        .contentType(MediaType.APPLICATION_JSON).content(PROJECT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("cv-project v2"));

        assertThat(rows("project")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM project WHERE id = ?",
                String.class, id)).isEqualTo("cv-project v2");
    }

    // ---------------------------------------------------------------- PersonSkill (confirmatory)

    /**
     * The upsert already held read and write in one {@code TransactionTemplate}, so it could
     * never resurrect anything — but the 0-row UPDATE surfaced at that template's commit as an
     * uncaught optimistic-lock failure, i.e. a 500. It now takes the existing single retry and
     * answers as the upsert contract says a PUT after a DELETE should: 200, link re-created.
     */

    @Test
    void personSkillUpsertRacingACommittedDelete() throws Exception {
        Skill skill = skillRepository.save(new Skill("race-" + UUID.randomUUID(), "Backend"));
        Long pid = person.getId();
        Long sid = skill.getId();
        try {
            personSkillRepository.save(new PersonSkill(person, skill, Proficiency.BEGINNER));
            deleteAfterRead(personSkillRepository, "findById",
                    "DELETE FROM person_skill WHERE person_id = ? AND skill_id = ?", pid, sid);

            mockMvc.perform(put("/api/v1/people/{p}/skills/{s}", pid, sid)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"proficiency\":\"ADVANCED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.proficiency").value("ADVANCED"));

            // Linearised as DELETE-then-PUT: the upsert re-creates the link (contract § Skills).
            assertThat(jdbc.queryForObject(
                    "SELECT proficiency FROM person_skill WHERE person_id = ? AND skill_id = ?",
                    String.class, pid, sid)).isEqualTo("ADVANCED");
        } finally {
            jdbc.update("DELETE FROM person_skill WHERE skill_id = ?", sid);
            jdbc.update("DELETE FROM skill WHERE id = ?", sid);
        }
    }
}
