package com.erfeamor.cvdomain.experience;

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
 * Persistence coverage for the experience aggregate (test-plan cases P1-P6).
 */
@DataJpaTest
class ExperienceRepositoryTest {

    @Autowired
    private ExperienceRepository experienceRepository;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Person persistPerson(String email) {
        return personRepository.saveAndFlush(
                new Person("Jane Doe", "Engineer", email, "Remote", "Bio"));
    }

    private Experience experienceFor(Person person, String company, LocalDate endDate) {
        return new Experience(person, company, "Backend Engineer", "Remote",
                LocalDate.of(2022, 1, 1), endDate, "Built things");
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

        List<Experience> forOne = experienceRepository.findByPersonId(one.getId());
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
}
