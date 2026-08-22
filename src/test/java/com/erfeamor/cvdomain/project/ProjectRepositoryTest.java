package com.erfeamor.cvdomain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erfeamor.cvdomain.person.Person;
import com.erfeamor.cvdomain.person.PersonRepository;
import jakarta.validation.ConstraintViolationException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/**
 * Persistence coverage for the project aggregate (test-plan cases P1-P6, plus the contract's
 * NULL-aware ordering).
 */
@DataJpaTest
class ProjectRepositoryTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Person persistPerson(String email) {
        return personRepository.saveAndFlush(
                new Person("Jane Doe", "Engineer", email, "Remote", "Bio"));
    }

    private Project projectFor(Person person, String name, LocalDate startDate) {
        return new Project(person, name, "An interactive CV",
                "https://github.com/erfeamor/cv-project", startDate, null);
    }

    /**
     * P1: every field round-trips through the physical columns V1 created. {@code repoUrl} ->
     * {@code repo_url} is this aggregate's highest-risk mapping — a naming-strategy slip here is
     * invisible under H2 and fails MySQL's {@code ddl-auto: validate} at boot.
     */
    @Test
    void p1RoundTripsAllFieldsThroughTheContractColumns() {
        Person person = persistPerson("p1@example.com");
        Project saved = projectRepository.saveAndFlush(new Project(person, "cv-project",
                "An interactive CV", "https://github.com/erfeamor/cv-project",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31)));
        entityManager.clear();

        Project found = projectRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("cv-project");
        assertThat(found.getDescription()).isEqualTo("An interactive CV");
        assertThat(found.getRepoUrl()).isEqualTo("https://github.com/erfeamor/cv-project");
        assertThat(found.getStartDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(found.getEndDate()).isEqualTo(LocalDate.of(2026, 8, 31));

        // Read back through the physical column names, so the mapping is asserted rather than
        // assumed: this query fails if repoUrl stops mapping to repo_url.
        Object row = entityManager.getEntityManager()
                .createNativeQuery("SELECT repo_url FROM project WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();
        assertThat(row).isEqualTo("https://github.com/erfeamor/cv-project");
    }

    /**
     * P2 / DoR 6: a name-only project persists, and the other four columns reload as genuine SQL
     * NULLs rather than empty strings. {@code start_date} and {@code end_date} are nullable in
     * this table only — the sibling resources' {@code start_date} is NOT NULL — so this is the
     * case that fails first if the entity copies their {@code @NotNull}.
     */
    @Test
    void p2SavesWithOnlyTheNameAndReloadsTheOtherFieldsAsNull() {
        Person person = persistPerson("p2@example.com");
        Project saved = projectRepository.saveAndFlush(
                new Project(person, "name only", null, null, null, null));
        entityManager.clear();

        Project found = projectRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("name only");
        assertThat(found.getDescription()).isNull();
        assertThat(found.getRepoUrl()).isNull();
        assertThat(found.getStartDate()).isNull();
        assertThat(found.getEndDate()).isNull();

        // SQL NULL, not an empty string: COUNT(column) skips NULLs, so 0 here proves the column
        // really is null rather than ''.
        Number nonNulls = (Number) entityManager.getEntityManager()
                .createNativeQuery("SELECT COUNT(repo_url) + COUNT(description) "
                        + "+ COUNT(start_date) + COUNT(end_date) FROM project WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();
        assertThat(nonNulls.longValue()).isZero();
    }

    /** P3: the FK column carries the owning person's id. */
    @Test
    void p3PersistsTheForeignKeyToTheOwningPerson() {
        Person person = persistPerson("p3@example.com");
        Project saved = projectRepository.saveAndFlush(
                projectFor(person, "cv-project", LocalDate.of(2026, 7, 1)));
        entityManager.clear();

        Object personId = entityManager.getEntityManager()
                .createNativeQuery("SELECT person_id FROM project WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();
        assertThat(((Number) personId).longValue()).isEqualTo(person.getId());
    }

    /** P4: deleting the person cascades to their project rows. */
    @Test
    void p4CascadesDeleteFromThePerson() {
        Person person = persistPerson("p4@example.com");
        projectRepository.saveAndFlush(projectFor(person, "cv-project", LocalDate.of(2026, 7, 1)));

        personRepository.delete(person);
        personRepository.flush();
        entityManager.clear();

        assertThat(projectRepository.findByPersonIdOrdered(person.getId())).isEmpty();
    }

    /** P5: the scoped finders return only the requested person's rows. */
    @Test
    void p5ScopesTheCollectionToOnePerson() {
        Person jane = persistPerson("jane@example.com");
        Person john = persistPerson("john@example.com");
        projectRepository.saveAndFlush(projectFor(jane, "janes", LocalDate.of(2026, 7, 1)));
        Project johns = projectRepository.saveAndFlush(
                projectFor(john, "johns", LocalDate.of(2026, 7, 1)));
        entityManager.clear();

        List<Project> janes = projectRepository.findByPersonIdOrdered(jane.getId());
        assertThat(janes).hasSize(1);
        assertThat(janes.get(0).getName()).isEqualTo("janes");

        // DoR 2: the scoped single-row lookup must not find another person's row.
        assertThat(projectRepository.findByIdAndPersonId(johns.getId(), jane.getId())).isEmpty();
        assertThat(projectRepository.findByIdAndPersonId(johns.getId(), john.getId())).isPresent();
    }

    /**
     * P6: {@code name} is the only column that rejects a null. Bean validation catches it before
     * the INSERT is attempted, so the failure is a ConstraintViolationException rather than a SQL
     * error — that is the layer, and it is worth knowing which one answers.
     */
    @Test
    void p6RejectsANullName() {
        Person person = persistPerson("p6@example.com");

        assertThatThrownBy(() -> projectRepository.saveAndFlush(
                new Project(person, null, "desc", null, LocalDate.of(2026, 7, 1), null)))
                .isInstanceOf(ConstraintViolationException.class);
    }

    /**
     * P6, the other half and the one that matters most on this task: a null {@code startDate} is
     * NOT rejected. Separate test because a ConstraintViolationException leaves the persistence
     * context unusable, so a successful save cannot follow a rejection in the same transaction.
     */
    @Test
    void p6AcceptsANullStartDateUnlikeExperienceAndEducation() {
        Person person = persistPerson("p6b@example.com");

        assertThatCode(() -> projectRepository.saveAndFlush(
                new Project(person, "undated", null, null, null, null)))
                .doesNotThrowAnyException();
    }

    /**
     * V1 declares {@code name} as VARCHAR(150). Bean validation has to carry that limit, because
     * nothing else does: {@code ddl-auto: validate} checks names and types but not lengths, and H2
     * builds a {@code varchar(255)} from the unannotated mapping — so an over-long value passes
     * this whole suite and then fails on real MySQL in strict mode with error 1406, surfacing as a
     * 500 where contract design rule 4 requires a 400.
     */
    @Test
    void rejectsANameLongerThanTheVarchar150Column() {
        Person person = persistPerson("size@example.com");

        assertThatThrownBy(() -> projectRepository.saveAndFlush(
                new Project(person, "x".repeat(151), null, null, null, null)))
                .isInstanceOf(ConstraintViolationException.class);
    }

    /** The boundary itself must be accepted — 150 is legal, 151 is not. */
    @Test
    void acceptsANameExactlyAtTheVarchar150Boundary() {
        Person person = persistPerson("boundary@example.com");

        assertThat(projectRepository.saveAndFlush(
                new Project(person, "x".repeat(150), null, null, null, null)).getId()).isNotNull();
    }

    /**
     * Contract § Ordering for projects: {@code startDate} DESC with undated projects
     * <strong>last</strong>, tiebroken by {@code id} ASC.
     *
     * <p>The fixture is built to discriminate, because a weaker one would not: it carries a
     * NULL-{@code startDate} row (so a query relying on the engine default, which sorts NULL
     * lowest, returns it FIRST and fails here) and two rows sharing a {@code startDate} (so a
     * query without the {@code id} tiebreaker can only pass by luck). A test with only distinct
     * dated rows passes under either NULL convention and proves nothing.
     *
     * <p>Rows are inserted in an order that matches none of the expectations, so a repository
     * returning insertion order cannot pass either.
     */
    @Test
    void ordersByStartDateDescendingWithUndatedLastThenIdAscending() {
        Person person = persistPerson("order@example.com");
        Project undated = projectRepository.saveAndFlush(projectFor(person, "undated", null));
        Project oldest = projectRepository.saveAndFlush(
                projectFor(person, "oldest", LocalDate.of(2010, 1, 1)));
        Project tiedFirstInserted = projectRepository.saveAndFlush(
                projectFor(person, "tied-a", LocalDate.of(2020, 1, 1)));
        Project tiedSecondInserted = projectRepository.saveAndFlush(
                projectFor(person, "tied-b", LocalDate.of(2020, 1, 1)));
        entityManager.clear();

        List<Project> ordered = projectRepository.findByPersonIdOrdered(person.getId());

        assertThat(ordered).extracting(Project::getName)
                .containsExactly("tied-a", "tied-b", "oldest", "undated");
        assertThat(ordered).extracting(Project::getId).containsExactly(
                tiedFirstInserted.getId(), tiedSecondInserted.getId(), oldest.getId(),
                undated.getId());
    }

    /**
     * Two undated projects fall through to the {@code id} tiebreaker as well — the CASE branch
     * must not swallow the secondary keys for the NULL group.
     */
    @Test
    void ordersUndatedProjectsAmongThemselvesByIdAscending() {
        Person person = persistPerson("order2@example.com");
        Project first = projectRepository.saveAndFlush(projectFor(person, "undated-a", null));
        Project second = projectRepository.saveAndFlush(projectFor(person, "undated-b", null));
        entityManager.clear();

        assertThat(projectRepository.findByPersonIdOrdered(person.getId()))
                .extracting(Project::getId).containsExactly(first.getId(), second.getId());
    }
}
