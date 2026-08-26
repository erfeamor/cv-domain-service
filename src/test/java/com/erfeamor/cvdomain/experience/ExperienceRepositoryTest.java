package com.erfeamor.cvdomain.experience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erfeamor.cvdomain.person.Person;
import com.erfeamor.cvdomain.person.PersonRepository;
import jakarta.validation.ConstraintViolationException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Persistence coverage for the experience aggregate (test-plan cases P1-P6).
 */
@DataJpaTest
@Import(ExperienceRepositoryTest.CapturedSql.class)
class ExperienceRepositoryTest {

    /**
     * Records the SQL Hibernate actually issues, so the ordering tests can assert the sort keys the
     * database was asked for rather than only the row order it happened to return. See
     * {@link #declaresTheIdTiebreakerInTheGeneratedSql()} for why the behavioural assertions are not
     * sufficient on their own.
     *
     * <p>{@code STATEMENTS} is process-global mutable state: this class must not be run under
     * parallel test execution (no {@code junit-platform.properties} and no surefire {@code
     * parallel} setting enables it today), or unrelated statements would interleave into the
     * capture and the SQL assertion would flake as if Hibernate had changed.
     */
    @TestConfiguration
    static class CapturedSql implements HibernatePropertiesCustomizer, StatementInspector {

        static final List<String> STATEMENTS = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void customize(Map<String, Object> hibernateProperties) {
            hibernateProperties.put(AvailableSettings.STATEMENT_INSPECTOR, this);
        }

        @Override
        public String inspect(String sql) {
            STATEMENTS.add(sql);
            return sql;
        }
    }

    @Autowired
    private ExperienceRepository experienceRepository;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private TestEntityManager entityManager;

    /** Leaves no captured SQL behind for the next test in this (single-threaded) class to see. */
    @BeforeEach
    void clearCapturedSql() {
        CapturedSql.STATEMENTS.clear();
    }

    private Person persistPerson(String email) {
        return personRepository.saveAndFlush(
                new Person("Jane Doe", "Engineer", email, "Remote", "Bio"));
    }

    private Experience experienceFor(Person person, String company, LocalDate endDate) {
        return new Experience(person, company, "Backend Engineer", "Remote",
                LocalDate.of(2022, 1, 1), endDate, "Built things");
    }

    private Experience experienceStarting(Person person, String company, LocalDate startDate) {
        return new Experience(person, company, "Backend Engineer", "Remote",
                startDate, null, "Built things");
    }

    /**
     * Inserts a row with an explicitly chosen id, bypassing the IDENTITY generator. The tiebreaker
     * test needs ids that do <strong>not</strong> follow insertion order, and {@code persist} can
     * never produce that: {@code @GeneratedValue(IDENTITY)} overwrites any assigned id, so
     * generated ids are monotonic in insertion order by construction.
     */
    private void insertWithExplicitId(long id, Person person, String company, LocalDate startDate) {
        entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO experience"
                        + " (id, person_id, company, role, location, start_date, end_date,"
                        + " description) VALUES (?1, ?2, ?3, 'Backend Engineer', 'Remote', ?4,"
                        + " NULL, 'Built things')")
                .setParameter(1, id)
                .setParameter(2, person.getId())
                .setParameter(3, company)
                .setParameter(4, startDate)
                .executeUpdate();
    }

    /**
     * P1: every field round-trips, and the physical columns are the ones V1 created — the native
     * query fails if {@code startDate}/{@code endDate} stop mapping to {@code start_date}/{@code
     * end_date}, which is what {@code ddl-auto: validate} enforces against MySQL.
     */
    @Test
    void p1RoundTripsAllFieldsThroughTheContractColumns() {
        Person person = persistPerson("p1@example.com");
        Experience saved = experienceRepository.saveAndFlush(
                experienceFor(person, "ACME", LocalDate.of(2024, 3, 31)));
        entityManager.clear();

        Experience found = experienceRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getCompany()).isEqualTo("ACME");
        assertThat(found.getRole()).isEqualTo("Backend Engineer");
        assertThat(found.getLocation()).isEqualTo("Remote");
        assertThat(found.getStartDate()).isEqualTo(LocalDate.of(2022, 1, 1));
        assertThat(found.getEndDate()).isEqualTo(LocalDate.of(2024, 3, 31));
        assertThat(found.getDescription()).isEqualTo("Built things");

        Object[] row = (Object[]) entityManager.getEntityManager()
                .createNativeQuery(
                        "SELECT person_id, company, role, location, start_date, end_date, description"
                                + " FROM experience WHERE id = ?1")
                .setParameter(1, saved.getId())
                .getSingleResult();
        assertThat(row[4]).hasToString("2022-01-01");
        assertThat(row[5]).hasToString("2024-03-31");
    }

    /** P2: "current" is a plain SQL NULL, no sentinel date. */
    @Test
    void p2StoresANullEndDateAsNull() {
        Person person = persistPerson("p2@example.com");
        Experience saved = experienceRepository.saveAndFlush(experienceFor(person, "ACME", null));
        entityManager.clear();

        assertThat(experienceRepository.findById(saved.getId()).orElseThrow().getEndDate()).isNull();

        Object endDate = entityManager.getEntityManager()
                .createNativeQuery("SELECT end_date FROM experience WHERE id = ?1")
                .setParameter(1, saved.getId())
                .getSingleResult();
        assertThat(endDate).isNull();
    }

    /** P3: the FK column carries the owning person's id. */
    @Test
    void p3PopulatesThePersonForeignKey() {
        Person person = persistPerson("p3@example.com");
        Experience saved = experienceRepository.saveAndFlush(experienceFor(person, "ACME", null));
        entityManager.clear();

        Object personId = entityManager.getEntityManager()
                .createNativeQuery("SELECT person_id FROM experience WHERE id = ?1")
                .setParameter(1, saved.getId())
                .getSingleResult();
        assertThat(((Number) personId).longValue()).isEqualTo(person.getId());
    }

    /** P4: deleting the person cascades to its experiences (FK ON DELETE CASCADE). */
    @Test
    void p4CascadesDeleteFromThePerson() {
        Person person = persistPerson("p4@example.com");
        Long experienceId = experienceRepository.saveAndFlush(
                experienceFor(person, "ACME", null)).getId();
        entityManager.clear();

        personRepository.deleteById(person.getId());
        personRepository.flush();
        entityManager.clear();

        assertThat(experienceRepository.findById(experienceId)).isEmpty();
    }

    /** P5: the scoped lookups only ever see the requested person's rows. */
    @Test
    void p5ScopesLookupsToTheOwningPerson() {
        Person one = persistPerson("p5-one@example.com");
        Person two = persistPerson("p5-two@example.com");
        experienceRepository.saveAndFlush(experienceFor(one, "ACME", null));
        Experience othersRow = experienceRepository.saveAndFlush(experienceFor(two, "Globex", null));
        entityManager.clear();

        List<Experience> forOne = experienceRepository.findByPersonIdOrderByStartDateDescIdAsc(one.getId());
        assertThat(forOne).extracting(Experience::getCompany).containsExactly("ACME");

        assertThat(experienceRepository.findByIdAndPersonId(othersRow.getId(), one.getId())).isEmpty();
        assertThat(experienceRepository.findByIdAndPersonId(othersRow.getId(), two.getId()))
                .isPresent();
    }

    /**
     * P6: the required columns reject nulls. The bean-validation constraints on the entity fire
     * first (Hibernate's pre-insert validation), so the failing layer is jakarta validation, not the
     * database NOT NULL — the DB constraint is still there as the backstop.
     */
    @Test
    void p6RejectsNullsInRequiredColumns() {
        Person person = persistPerson("p6@example.com");

        assertThatThrownBy(() -> experienceRepository.saveAndFlush(
                new Experience(person, null, "Backend Engineer", "Remote",
                        LocalDate.of(2022, 1, 1), null, null)))
                .isInstanceOf(ConstraintViolationException.class);

        assertThatThrownBy(() -> experienceRepository.saveAndFlush(
                new Experience(person, "ACME", null, "Remote",
                        LocalDate.of(2022, 1, 1), null, null)))
                .isInstanceOf(ConstraintViolationException.class);

        assertThatThrownBy(() -> experienceRepository.saveAndFlush(
                new Experience(person, "ACME", "Backend Engineer", "Remote", null, null, null)))
                .isInstanceOf(ConstraintViolationException.class);
    }

    /**
     * T-105 / contract § Ordering: {@code startDate} DESC, tiebroken by {@code id} ASC.
     *
     * <p>The rows are inserted in an order that matches neither the expected sequence nor its
     * reverse, so a repository returning insertion (and therefore PK) order cannot pass by luck —
     * a fixture inserted in the expected order would pass against the unordered query this task
     * exists to fix, and would prove nothing. {@code start_date} is {@code NOT NULL} on
     * {@code experience}, so there is deliberately no undated row here: the NULL-placement rule
     * that {@code project} needs does not apply to this table.
     */
    @Test
    void ordersByStartDateDescendingThenIdAscending() {
        Person person = persistPerson("order@example.com");
        Experience gamma = experienceRepository.saveAndFlush(
                experienceStarting(person, "gamma", LocalDate.of(2025, 3, 1)));
        Experience epsilon = experienceRepository.saveAndFlush(
                experienceStarting(person, "epsilon", LocalDate.of(2020, 1, 1)));
        Experience beta = experienceRepository.saveAndFlush(
                experienceStarting(person, "beta", LocalDate.of(2026, 6, 1)));
        Experience alpha = experienceRepository.saveAndFlush(
                experienceStarting(person, "alpha", LocalDate.of(2024, 1, 15)));
        Experience delta = experienceRepository.saveAndFlush(
                experienceStarting(person, "delta", LocalDate.of(2025, 3, 1)));
        entityManager.clear();

        List<Experience> ordered =
                experienceRepository.findByPersonIdOrderByStartDateDescIdAsc(person.getId());

        assertThat(ordered).extracting(Experience::getCompany)
                .containsExactly("beta", "gamma", "delta", "alpha", "epsilon");
        assertThat(ordered).extracting(Experience::getId).containsExactly(
                beta.getId(), gamma.getId(), delta.getId(), alpha.getId(), epsilon.getId());
    }

    /**
     * Documents the tiebreaker's intent, and excludes a repository that returns insertion order.
     *
     * <p><strong>It cannot go red against a missing {@code id ASC}</strong> — measured, not
     * assumed (T-105, 2026-08-24). Assigning the ids out of insertion order, as this test does,
     * was expected to make the missing secondary key observable and does not: H2 walks a tie group
     * in primary-key order whatever the query says, so this test stayed green under a bare
     * {@code ORDER BY start_date DESC} and under no {@code ORDER BY} at all. What it still rules
     * out is an implementation that hands back rows in the order they were inserted, which is why
     * it is kept rather than deleted.
     *
     * <p>The load-bearing assertion is
     * {@link #declaresTheIdTiebreakerInTheGeneratedSql()} — it is the only one here that
     * distinguishes a declared tiebreak from an incidental one, and it must not be removed as
     * redundant on the strength of this test passing.
     */
    @Test
    void ordersRowsSharingAStartDateByIdAscendingEvenWhenIdsRunAgainstInsertionOrder() {
        Person person = persistPerson("tie@example.com");
        LocalDate tiedStart = LocalDate.of(2020, 1, 1);
        insertWithExplicitId(9002L, person, "inserted-first-higher-id", tiedStart);
        insertWithExplicitId(9001L, person, "inserted-second-lower-id", tiedStart);
        entityManager.flush();
        entityManager.clear();

        List<Experience> ordered =
                experienceRepository.findByPersonIdOrderByStartDateDescIdAsc(person.getId());

        assertThat(ordered).extracting(Experience::getId).containsExactly(9001L, 9002L);
        assertThat(ordered).extracting(Experience::getCompany)
                .containsExactly("inserted-second-lower-id", "inserted-first-higher-id");
    }

    /**
     * The tiebreaker asserted where it can actually fail: in the SQL.
     *
     * <p>Measured on this codebase (T-105, 2026-08-24), <em>no</em> row-order assertion in a
     * {@code @DataJpaTest} can go red against a missing {@code id ASC} secondary key — not even
     * with ids assigned out of insertion order, as
     * {@link #ordersRowsSharingAStartDateByIdAscendingEvenWhenIdsRunAgainstInsertionOrder()} does.
     * H2 stores rows in a primary-key B-tree, so a tie group comes back in {@code id} ascending
     * order under a bare {@code ORDER BY start_date DESC} — and under no {@code ORDER BY} at all —
     * for the same reason InnoDB usually does. Both of those spellings were run against the two
     * tests above and both passed. This assertion is the one that distinguishes a declared
     * tiebreak from an incidental one: it goes red the moment the second sort key leaves the query.
     */
    @Test
    void declaresTheIdTiebreakerInTheGeneratedSql() {
        Person person = persistPerson("sql@example.com");
        experienceRepository.saveAndFlush(
                experienceStarting(person, "any", LocalDate.of(2020, 1, 1)));
        entityManager.clear();
        CapturedSql.STATEMENTS.clear();

        experienceRepository.findByPersonIdOrderByStartDateDescIdAsc(person.getId());

        String select = CapturedSql.STATEMENTS.stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT))
                .filter(sql -> sql.startsWith("select") && sql.contains("from experience"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no select against experience was issued"));

        assertThat(select).contains("order by");
        String orderBy = select.substring(select.indexOf("order by"));
        assertThat(orderBy).contains("start_date desc");
        assertThat(orderBy.substring(orderBy.indexOf("start_date desc")))
                .as("an explicit id sort key must follow start_date, or tie order is unspecified")
                .containsPattern("\\bid\\b");
        assertThat(orderBy).doesNotContain("id desc");
    }
}
