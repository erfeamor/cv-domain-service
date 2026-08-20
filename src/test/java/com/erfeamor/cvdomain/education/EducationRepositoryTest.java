package com.erfeamor.cvdomain.education;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Persistence coverage for the education aggregate (test-plan cases P1-P6).
 */
@DataJpaTest
class EducationRepositoryTest {

    @Autowired
    private EducationRepository educationRepository;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Person persistPerson(String email) {
        return personRepository.saveAndFlush(
                new Person("Jane Doe", "Engineer", email, "Remote", "Bio"));
    }

    private Education educationFor(Person person, String institution, LocalDate startDate,
            LocalDate endDate) {
        return new Education(person, institution, "BSc", "Computer Science", startDate, endDate);
    }

    /**
     * P1: every field round-trips through the physical columns V1 created. {@code fieldOfStudy} ->
     * {@code field_of_study} is this aggregate's highest-risk mapping — a naming-strategy slip
     * here is invisible under H2 and fails MySQL's {@code ddl-auto: validate} at boot.
     */
    @Test
    void p1RoundTripsAllFieldsThroughTheContractColumns() {
        Person person = persistPerson("p1@example.com");
        Education saved = educationRepository.saveAndFlush(educationFor(person, "UNED",
                LocalDate.of(2015, 9, 1), LocalDate.of(2019, 6, 30)));
        entityManager.clear();

        Education found = educationRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getInstitution()).isEqualTo("UNED");
        assertThat(found.getDegree()).isEqualTo("BSc");
        assertThat(found.getFieldOfStudy()).isEqualTo("Computer Science");
        assertThat(found.getStartDate()).isEqualTo(LocalDate.of(2015, 9, 1));
        assertThat(found.getEndDate()).isEqualTo(LocalDate.of(2019, 6, 30));

        // Read back through the physical column names, so the mapping is asserted rather than
        // assumed: this query fails if fieldOfStudy stops mapping to field_of_study.
        Object row = entityManager.getEntityManager()
                .createNativeQuery("SELECT field_of_study FROM education WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();
        assertThat(row).isEqualTo("Computer Science");
    }

    /** P2: a null endDate is a plain SQL NULL, not a sentinel date. */
    @Test
    void p2NullEndDateReloadsAsNull() {
        Person person = persistPerson("p2@example.com");
        Education saved = educationRepository.saveAndFlush(
                educationFor(person, "MIT", LocalDate.of(2020, 1, 1), null));
        entityManager.clear();

        assertThat(educationRepository.findById(saved.getId()).orElseThrow().getEndDate()).isNull();
    }

    /** P3: the FK column carries the owning person's id. */
    @Test
    void p3PersistsTheForeignKeyToTheOwningPerson() {
        Person person = persistPerson("p3@example.com");
        Education saved = educationRepository.saveAndFlush(
                educationFor(person, "UNED", LocalDate.of(2015, 9, 1), null));
        entityManager.clear();

        Object personId = entityManager.getEntityManager()
                .createNativeQuery("SELECT person_id FROM education WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();
        assertThat(((Number) personId).longValue()).isEqualTo(person.getId());
    }

    /** P4: deleting the person cascades to their education rows. */
    @Test
    void p4CascadesDeleteFromThePerson() {
        Person person = persistPerson("p4@example.com");
        educationRepository.saveAndFlush(
                educationFor(person, "UNED", LocalDate.of(2015, 9, 1), null));

        personRepository.delete(person);
        personRepository.flush();
        entityManager.clear();

        assertThat(educationRepository.findByPersonIdOrderByStartDateDescIdAsc(person.getId()))
                .isEmpty();
    }

    /** P5: the scoped finder returns only the requested person's rows. */
    @Test
    void p5ScopesTheCollectionToOnePerson() {
        Person jane = persistPerson("jane@example.com");
        Person john = persistPerson("john@example.com");
        educationRepository.saveAndFlush(
                educationFor(jane, "UNED", LocalDate.of(2015, 9, 1), null));
        Education johns = educationRepository.saveAndFlush(
                educationFor(john, "MIT", LocalDate.of(2016, 9, 1), null));
        entityManager.clear();

        List<Education> janes =
                educationRepository.findByPersonIdOrderByStartDateDescIdAsc(jane.getId());
        assertThat(janes).hasSize(1);
        assertThat(janes.get(0).getInstitution()).isEqualTo("UNED");

        // DoR 2: the scoped single-row lookup must not find another person's row.
        assertThat(educationRepository.findByIdAndPersonId(johns.getId(), jane.getId())).isEmpty();
        assertThat(educationRepository.findByIdAndPersonId(johns.getId(), john.getId()))
                .isPresent();
    }

    /**
     * P6: the required columns reject nulls. Bean validation catches these before the INSERT is
     * attempted, so the failure is a ConstraintViolationException rather than a SQL error — that
     * is the layer, and it is worth knowing which one answers.
     */
    @Test
    void p6RejectsNullsInTheRequiredColumns() {
        Person person = persistPerson("p6@example.com");

        assertThatThrownBy(() -> educationRepository.saveAndFlush(
                new Education(person, null, "BSc", null, LocalDate.of(2015, 9, 1), null)))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> educationRepository.saveAndFlush(
                new Education(person, "UNED", null, null, LocalDate.of(2015, 9, 1), null)))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> educationRepository.saveAndFlush(
                new Education(person, "UNED", "BSc", null, null, null)))
                .isInstanceOf(ConstraintViolationException.class);
    }

    /**
     * V1 declares institution, degree and field_of_study as VARCHAR(150). Bean validation has to
     * carry that limit, because nothing else does: {@code ddl-auto: validate} checks names and
     * types but not lengths, and H2 builds a {@code varchar(255)} from the unannotated mapping —
     * so an over-long value passes this whole suite and then fails on real MySQL in strict mode
     * with error 1406, surfacing as a 500 where contract design rule 4 requires a 400.
     */
    @Test
    void rejectsValuesLongerThanTheVarchar150Columns() {
        Person person = persistPerson("size@example.com");
        String tooLong = "x".repeat(151);

        assertThatThrownBy(() -> educationRepository.saveAndFlush(new Education(person, tooLong,
                "BSc", null, LocalDate.of(2015, 9, 1), null)))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> educationRepository.saveAndFlush(new Education(person, "UNED",
                tooLong, null, LocalDate.of(2015, 9, 1), null)))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> educationRepository.saveAndFlush(new Education(person, "UNED",
                "BSc", tooLong, LocalDate.of(2015, 9, 1), null)))
                .isInstanceOf(ConstraintViolationException.class);
    }

    /**
     * The boundary itself must be accepted — 150 is legal, 151 is not. Separate test on purpose:
     * a ConstraintViolationException leaves the persistence context unusable, so a successful
     * save cannot follow the rejections above in the same transaction.
     */
    @Test
    void acceptsValuesExactlyAtTheVarchar150Boundary() {
        Person person = persistPerson("boundary@example.com");

        assertThat(educationRepository.saveAndFlush(new Education(person, "x".repeat(150), "BSc",
                null, LocalDate.of(2015, 9, 1), null)).getId()).isNotNull();
    }

    /**
     * Contract § Ordering: startDate DESC, tiebroken by id ASC. The tiebreaker is asserted with
     * two rows sharing a startDate — asserting only the date order passes on unordered data by
     * luck, which is exactly what T-006 filed this rule against.
     */
    @Test
    void ordersByStartDateDescendingThenIdAscending() {
        Person person = persistPerson("order@example.com");
        Education oldest = educationRepository.saveAndFlush(
                educationFor(person, "oldest", LocalDate.of(2010, 1, 1), null));
        Education tiedFirstInserted = educationRepository.saveAndFlush(
                educationFor(person, "tied-a", LocalDate.of(2020, 1, 1), null));
        Education tiedSecondInserted = educationRepository.saveAndFlush(
                educationFor(person, "tied-b", LocalDate.of(2020, 1, 1), null));
        entityManager.clear();

        List<Education> ordered =
                educationRepository.findByPersonIdOrderByStartDateDescIdAsc(person.getId());

        assertThat(ordered).extracting(Education::getId).containsExactly(
                tiedFirstInserted.getId(), tiedSecondInserted.getId(), oldest.getId());
        assertThat(ordered).extracting(Education::getInstitution)
                .containsExactly("tied-a", "tied-b", "oldest");
    }
}
